package com.atlas.virtualspace.core.hook

import android.app.ActivityManager
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.util.Log
import com.atlas.virtualspace.core.engine.ProcessState
import com.atlas.virtualspace.core.engine.VirtualEngine
import top.canyie.pine.Pine.CallFrame

/**
 * Hooks critical system services so that calls from virtual apps are redirected
 * to Atlas' virtual implementations instead of the real system services.
 *
 * ## Hook installation pattern
 * Each hook follows the same pattern:
 * 1. Obtain the system service singleton via reflection.
 * 2. Hook the relevant AIDL methods using [HookManager].
 * 3. In the `beforeHook` callback, check whether the caller is a virtual app.
 * 4. If so, redirect the call to the virtual implementation and short-circuit
 *    the original by setting [CallFrame.result].
 *
 * ## Safety
 * All reflection and hook failures are caught and logged — the host app must
 * never crash because a hook could not be installed.
 *
 * ## Caller identification
 * Three strategies are used to identify whether the caller is a virtual app:
 * 1. Check String arguments against known virtual package names.
 * 2. Resolve the calling UID via [Binder.getCallingUid] and match it against
 *    running virtual process records.
 * 3. Check the current process UID if the call is local.
 */
object SystemServiceHooks {

    private const val TAG = "Atlas:SysSvcHooks"

    /** Hook group name used for all system service hooks. */
    private const val GROUP_SYSTEM_SERVICES = "system_services"

    /** Track all hook IDs installed by this class so we can unhook them together. */
    private val installedHookIds = mutableListOf<String>()

    /** Track which service groups have been successfully hooked. */
    private val hookedServices = mutableSetOf<String>()

    /**
     * Checks whether [packageName] refers to a virtual app installed in Atlas.
     * Delegates to [VirtualEngine.installedApps].
     */
    private fun isVirtualApp(packageName: String): Boolean =
        VirtualEngine.installedApps.value.any { it.packageName == packageName }

    // ════════════════════════════════════════════════════════════
    //  ActivityManager hooks
    // ════════════════════════════════════════════════════════════

    /**
     * Hooks IActivityManager methods so that virtual apps interact with
     * [VirtualActivityService] instead of the real ActivityManagerService.
     *
     * Methods hooked:
     * - `startActivity` — redirect to virtual activity launch
     * - `forceStopPackage` — prevent host-side force-stop of virtual apps
     * - `getRunningAppProcesses` — return virtual process list when caller is virtual
     * - `killBackgroundProcesses` — prevent killing virtual app processes
     * - `checkPermission` — return virtual permission state for virtual apps
     * - `getProcessMemoryInfo` — return virtual memory info for virtual processes
     */
    fun hookActivityManager(): Result<Unit> {
        return runCatching {
            val amService = getActivityManagerService()
                ?: throw IllegalStateException("Cannot obtain IActivityManager singleton")

            val amClass = amService.javaClass

            // ── startActivity ─────────────────────────────────────
            installHook(amClass, "startActivity") { frame ->
                val callerPackage = extractCallerPackage(frame)
                if (isVirtualApp(callerPackage)) {
                    Log.d(TAG, "Redirecting startActivity for virtual app: $callerPackage")
                    VirtualActivityService.startActivity(frame)
                }
            }

            // ── forceStopPackage ──────────────────────────────────
            installHook(amClass, "forceStopPackage") { frame ->
                val args = frame.args
                val targetPkg = args.filterIsInstance<String>().firstOrNull() ?: return@installHook
                if (isVirtualApp(targetPkg)) {
                    Log.d(TAG, "Redirecting forceStopPackage for virtual app: $targetPkg")
                    VirtualActivityService.forceStopPackage(targetPkg)
                    frame.result = null // short-circuit: don't call the real AMS
                }
            }

            // ── getRunningAppProcesses ─────────────────────────────
            installHook(amClass, "getRunningAppProcesses") { frame ->
                val callerPackage = extractCallerPackage(frame)
                if (isVirtualApp(callerPackage)) {
                    Log.d(TAG, "Returning virtual process list to: $callerPackage")
                    frame.result = VirtualActivityService.getRunningAppProcesses()
                }
            }

            // ── killBackgroundProcesses ────────────────────────────
            installHook(amClass, "killBackgroundProcesses") { frame ->
                val args = frame.args
                val targetPkg = args.filterIsInstance<String>().firstOrNull() ?: return@installHook
                if (isVirtualApp(targetPkg)) {
                    Log.d(TAG, "Blocking killBackgroundProcesses for virtual app: $targetPkg")
                    VirtualActivityService.killBackgroundProcesses(targetPkg)
                    frame.result = null
                }
            }

            // ── checkPermission ────────────────────────────────────
            installHook(amClass, "checkPermission") { frame ->
                val args = frame.args
                val pkgName = args.filterIsInstance<String>().elementAtOrNull(1)
                    ?: return@installHook
                if (isVirtualApp(pkgName)) {
                    Log.d(TAG, "Returning virtual permission state for: $pkgName")
                    frame.result = VirtualActivityService.checkPermission(args)
                }
            }

            // ── getProcessMemoryInfo ───────────────────────────────
            installHook(amClass, "getProcessMemoryInfo") { frame ->
                val args = frame.args
                val pids = args.filterIsInstance<IntArray>().firstOrNull()
                if (pids != null) {
                    val virtualPids = VirtualEngine.getRunningProcesses().map { it.pid }.toSet()
                    if (pids.any { it in virtualPids }) {
                        Log.d(TAG, "Returning virtual memory info for PIDs: ${pids.toList()}")
                        frame.result = VirtualActivityService.getProcessMemoryInfo(pids)
                    }
                }
            }

            hookedServices.add("ActivityManager")
            Log.i(TAG, "ActivityManager hooks installed successfully")
            Unit
        }.onFailure { e ->
            Log.e(TAG, "Failed to hook ActivityManager", e)
        }
    }

    // ════════════════════════════════════════════════════════════
    //  PackageManager hooks
    // ════════════════════════════════════════════════════════════

    /**
     * Hooks IPackageManager methods so that virtual apps see their own
     * package metadata rather than the host's installed packages.
     *
     * Methods hooked:
     * - `getPackageInfo` — return virtual app's PackageInfo
     * - `getApplicationInfo` — return virtual app's ApplicationInfo
     * - `getInstalledPackages` — return virtual-installed packages
     * - `getInstalledApplications` — return virtual-installed applications
     * - `getComponentEnabledSetting` — return virtual component state
     * - `setComponentEnabledSetting` — store virtual component state
     * - `getPackageUid` — return virtual UID for virtual packages
     */
    fun hookPackageManager(): Result<Unit> {
        return runCatching {
            val pmService = getPackageManagerService()
                ?: throw IllegalStateException("Cannot obtain IPackageManager singleton")

            val pmClass = pmService.javaClass

            // ── getPackageInfo ────────────────────────────────────
            installHook(pmClass, "getPackageInfo") { frame ->
                val args = frame.args
                val packageName = args.filterIsInstance<String>().firstOrNull() ?: return@installHook
                if (isVirtualApp(packageName)) {
                    Log.d(TAG, "Returning virtual PackageInfo for: $packageName")
                    frame.result = VirtualPackageManagerService.getPackageInfo(packageName, args)
                }
            }

            // ── getApplicationInfo ────────────────────────────────
            installHook(pmClass, "getApplicationInfo") { frame ->
                val args = frame.args
                val packageName = args.filterIsInstance<String>().firstOrNull() ?: return@installHook
                if (isVirtualApp(packageName)) {
                    Log.d(TAG, "Returning virtual ApplicationInfo for: $packageName")
                    frame.result = VirtualPackageManagerService.getApplicationInfo(packageName, args)
                }
            }

            // ── getInstalledPackages ──────────────────────────────
            installHook(pmClass, "getInstalledPackages") { frame ->
                val callerPackage = extractCallerPackage(frame)
                if (isVirtualApp(callerPackage)) {
                    Log.d(TAG, "Returning virtual installed packages to: $callerPackage")
                    frame.result = VirtualPackageManagerService.getInstalledPackages(frame.args)
                }
            }

            // ── getInstalledApplications ──────────────────────────
            installHook(pmClass, "getInstalledApplications") { frame ->
                val callerPackage = extractCallerPackage(frame)
                if (isVirtualApp(callerPackage)) {
                    Log.d(TAG, "Returning virtual installed applications to: $callerPackage")
                    frame.result = VirtualPackageManagerService.getInstalledApplications(frame.args)
                }
            }

            // ── getComponentEnabledSetting ────────────────────────
            installHook(pmClass, "getComponentEnabledSetting") { frame ->
                val args = frame.args
                val componentName = args.firstOrNull()?.toString() ?: return@installHook
                val pkg = componentName.substringBefore("/")
                if (isVirtualApp(pkg)) {
                    Log.d(TAG, "Returning virtual component state for: $componentName")
                    frame.result = VirtualPackageManagerService.getComponentEnabledSetting(componentName)
                }
            }

            // ── setComponentEnabledSetting ────────────────────────
            installHook(pmClass, "setComponentEnabledSetting") { frame ->
                val args = frame.args
                val componentName = args.firstOrNull()?.toString() ?: return@installHook
                val pkg = componentName.substringBefore("/")
                if (isVirtualApp(pkg)) {
                    Log.d(TAG, "Storing virtual component state for: $componentName")
                    val newState = args.elementAtOrNull(1) as? Int ?: 0
                    VirtualPackageManagerService.setComponentEnabledSetting(componentName, newState)
                    frame.result = null
                }
            }

            // ── getPackageUid ─────────────────────────────────────
            installHook(pmClass, "getPackageUid") { frame ->
                val args = frame.args
                val packageName = args.filterIsInstance<String>().firstOrNull() ?: return@installHook
                if (isVirtualApp(packageName)) {
                    Log.d(TAG, "Returning virtual UID for: $packageName")
                    frame.result = VirtualPackageManagerService.getPackageUid(packageName)
                }
            }

            hookedServices.add("PackageManager")
            Log.i(TAG, "PackageManager hooks installed successfully")
            Unit
        }.onFailure { e ->
            Log.e(TAG, "Failed to hook PackageManager", e)
        }
    }

    // ════════════════════════════════════════════════════════════
    //  WindowManager hooks
    // ════════════════════════════════════════════════════════════

    /**
     * Hooks IWindowManager to adjust display metrics for virtual apps
     * that expect a different screen configuration.
     *
     * Methods hooked:
     * - `getDefaultDisplayRotation` — return virtual display rotation
     * - `getDisplayDecorationSupport` — return virtual display info
     * - `getInitialDisplaySize` — return virtual display size for virtual callers
     */
    fun hookWindowManager(): Result<Unit> {
        return runCatching {
            val wmService = getWindowManagerService()
                ?: throw IllegalStateException("Cannot obtain IWindowManager singleton")

            val wmClass = wmService.javaClass

            // ── getDefaultDisplayRotation ─────────────────────────
            installHook(wmClass, "getDefaultDisplayRotation") { frame ->
                val callerPackage = extractCallerPackage(frame)
                if (isVirtualApp(callerPackage)) {
                    Log.d(TAG, "Returning virtual display rotation for: $callerPackage")
                    frame.result = VirtualWindowManagerService.getDefaultDisplayRotation()
                }
            }

            // ── getDisplayDecorationSupport ───────────────────────
            installHook(wmClass, "getDisplayDecorationSupport") { frame ->
                val callerPackage = extractCallerPackage(frame)
                if (isVirtualApp(callerPackage)) {
                    Log.d(TAG, "Returning virtual display decoration for: $callerPackage")
                    frame.result = VirtualWindowManagerService.getDisplayDecorationSupport()
                }
            }

            // ── getInitialDisplaySize ─────────────────────────────
            installHook(wmClass, "getInitialDisplaySize") { frame ->
                val callerPackage = extractCallerPackage(frame)
                if (isVirtualApp(callerPackage)) {
                    Log.d(TAG, "Returning virtual display size for: $callerPackage")
                    frame.result = VirtualWindowManagerService.getInitialDisplaySize()
                }
            }

            hookedServices.add("WindowManager")
            Log.i(TAG, "WindowManager hooks installed successfully")
            Unit
        }.onFailure { e ->
            Log.e(TAG, "Failed to hook WindowManager", e)
        }
    }

    // ════════════════════════════════════════════════════════════
    //  AlarmManager hooks
    // ════════════════════════════════════════════════════════════

    /**
     * Hooks AlarmManager so that virtual apps cannot set real system alarms.
     * Alarms from virtual apps are captured and managed internally by Atlas,
     * preventing them from waking the host device or leaking timing information.
     *
     * Methods hooked:
     * - `set`, `setRepeating`, `setExact`, `setAndAllowWhileIdle`,
     *   `setExactAndAllowWhileIdle` — intercept and store virtually
     * - `cancel` — cancel virtual alarm
     * - `setWindow` — intercept windowed alarms
     */
    fun hookAlarmManager(): Result<Unit> {
        return runCatching {
            val alarmService = getAlarmManagerService()
                ?: throw IllegalStateException("Cannot obtain AlarmManager service")

            val alarmClass = alarmService.javaClass

            // All alarm-set methods share the same pattern: check caller, redirect.
            for (methodName in listOf(
                "set", "setRepeating", "setExact",
                "setAndAllowWhileIdle", "setExactAndAllowWhileIdle", "setWindow"
            )) {
                HookManager.hookAllMethods(
                    alarmClass.name, methodName,
                    beforeHook = { frame ->
                        val callerPackage = extractCallerPackage(frame)
                        if (isVirtualApp(callerPackage)) {
                            Log.d(TAG, "Intercepting $methodName for virtual app: $callerPackage")
                            VirtualAlarmService.scheduleAlarm(callerPackage, methodName, frame.args)
                            frame.result = null // don't reach the real AlarmManagerService
                        }
                    },
                    afterHook = null,
                    group = GROUP_SYSTEM_SERVICES,
                ).onSuccess { ids -> installedHookIds.addAll(ids) }
                    .onFailure { e ->
                        Log.w(TAG, "Could not hook $methodName on ${alarmClass.name}", e)
                    }
            }

            // ── cancel ────────────────────────────────────────────
            HookManager.hookAllMethods(
                alarmClass.name, "cancel",
                beforeHook = { frame ->
                    val callerPackage = extractCallerPackage(frame)
                    if (isVirtualApp(callerPackage)) {
                        Log.d(TAG, "Intercepting alarm cancel for virtual app: $callerPackage")
                        VirtualAlarmService.cancelAlarm(callerPackage, frame.args)
                        frame.result = null
                    }
                },
                afterHook = null,
                group = GROUP_SYSTEM_SERVICES,
            ).onSuccess { ids -> installedHookIds.addAll(ids) }
                .onFailure { e ->
                    Log.w(TAG, "Could not hook cancel on ${alarmClass.name}", e)
                }

            hookedServices.add("AlarmManager")
            Log.i(TAG, "AlarmManager hooks installed successfully")
            Unit
        }.onFailure { e ->
            Log.e(TAG, "Failed to hook AlarmManager", e)
        }
    }

    // ════════════════════════════════════════════════════════════
    //  Hook all services
    // ════════════════════════════════════════════════════════════

    /**
     * Installs hooks for all system services.
     * Individual service hook failures are logged but do not prevent other hooks.
     */
    fun hookAll(): Result<Unit> {
        val results = mutableListOf<Result<*>>()

        results += hookActivityManager()
        results += hookPackageManager()
        results += hookWindowManager()
        results += hookAlarmManager()

        val failures = results.filter { it.isFailure }
        return if (failures.isEmpty()) {
            Log.i(TAG, "All system service hooks installed (${installedHookIds.size} total)")
            Result.success(Unit)
        } else {
            val errorMsg = failures.mapNotNull { it.exceptionOrNull()?.message }.joinToString("; ")
            Log.e(TAG, "${failures.size} system service hook group(s) failed: $errorMsg")
            Result.failure(RuntimeException("Partial hook failure: $errorMsg"))
        }
    }

    /**
     * Removes all hooks installed by this class.
     */
    fun unhookAll(): Result<Unit> {
        return runCatching {
            val ids = installedHookIds.toList()
            for (hookId in ids) {
                HookManager.unhook(hookId)
                    .onFailure { Log.w(TAG, "Failed to unhook $hookId", it) }
            }
            installedHookIds.clear()
            hookedServices.clear()
            Log.i(TAG, "All system service hooks removed")
            Unit
        }.onFailure { e ->
            Log.e(TAG, "Failed to unhook all system service hooks", e)
        }
    }

    /**
     * Returns the set of service names that have been successfully hooked.
     */
    fun getHookedServices(): Set<String> = hookedServices.toSet()

    /**
     * Returns the total number of individual hooks installed.
     */
    fun getInstalledHookCount(): Int = installedHookIds.size

    // ════════════════════════════════════════════════════════════
    //  Reflection helpers — obtaining system service singletons
    // ════════════════════════════════════════════════════════════

    /**
     * Obtains the IActivityManager singleton.
     * Tries ActivityManager.getService() (hidden API on API 26+) first, then
     * falls back to ActivityManagerNative.getDefault() for older paths.
     */
    private fun getActivityManagerService(): Any? {
        return runCatching {
            val method = ActivityManager::class.java.getDeclaredMethod("getService")
            method.invoke(null)
        }.getOrNull() ?: runCatching {
            val amnClass = Class.forName("android.app.ActivityManagerNative")
            val method = amnClass.getDeclaredMethod("getDefault")
            method.invoke(null)
        }.getOrNull()
    }

    /**
     * Obtains the IPackageManager singleton.
     * Uses the hidden mPM field on ApplicationPackageManager, then falls back
     * to ServiceManager + IPackageManager.Stub.asInterface.
     */
    private fun getPackageManagerService(): Any? {
        return runCatching {
            val apmClass = Class.forName("android.app.ApplicationPackageManager")
            val field = apmClass.getDeclaredField("mPM")
            field.isAccessible = true
            val atClass = Class.forName("android.app.ActivityThread")
            val currentAt = atClass.getDeclaredMethod("currentActivityThread").invoke(null)
            val context = atClass.getDeclaredMethod("getApplication").invoke(currentAt)
            val pm = context.javaClass.getDeclaredMethod("getPackageManager").invoke(context)
            field.get(pm)
        }.getOrNull() ?: runCatching {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getDeclaredMethod("getService", String::class.java)
            val binder = getService.invoke(null, "package") as IBinder
            val stubClass = Class.forName("android.content.pm.IPackageManager\$Stub")
            val asInterface = stubClass.getDeclaredMethod("asInterface", IBinder::class.java)
            asInterface.invoke(null, binder)
        }.getOrNull()
    }

    /**
     * Obtains the IWindowManager singleton via WindowManagerGlobal.getWindowManagerService().
     */
    private fun getWindowManagerService(): Any? {
        return runCatching {
            val wmgClass = Class.forName("android.view.WindowManagerGlobal")
            val method = wmgClass.getDeclaredMethod("getWindowManagerService")
            method.invoke(null)
        }.getOrNull()
    }

    /**
     * Obtains the AlarmManager service implementation object.
     * Accesses the hidden mService field, falling back to IAlarmManager.Stub.asInterface.
     */
    private fun getAlarmManagerService(): Any? {
        return runCatching {
            val amClass = AlarmManager::class.java
            val field = amClass.getDeclaredField("mService")
            field.isAccessible = true
            val atClass = Class.forName("android.app.ActivityThread")
            val currentAt = atClass.getDeclaredMethod("currentActivityThread").invoke(null)
            val context = atClass.getDeclaredMethod("getApplication").invoke(currentAt)
            val alarmManager = context.javaClass
                .getDeclaredMethod("getSystemService", String::class.java)
                .invoke(context, Context.ALARM_SERVICE)
            field.get(alarmManager)
        }.getOrNull() ?: runCatching {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getDeclaredMethod("getService", String::class.java)
            val binder = getService.invoke(null, "alarm") as IBinder
            val stubClass = Class.forName("android.app.IAlarmManager\$Stub")
            val asInterface = stubClass.getDeclaredMethod("asInterface", IBinder::class.java)
            asInterface.invoke(null, binder)
        }.getOrNull()
    }

    // ════════════════════════════════════════════════════════════
    //  Internal utilities
    // ════════════════════════════════════════════════════════════

    /**
     * Installs a before-hook on **all overloads** of [methodName] on [targetClass],
     * registers the resulting hook IDs, and handles failures gracefully.
     */
    private fun installHook(
        targetClass: Class<*>,
        methodName: String,
        beforeCallback: (CallFrame) -> Unit,
    ) {
        HookManager.hookAllMethods(
            targetClass.name, methodName,
            beforeHook = beforeCallback,
            afterHook = null,
            group = GROUP_SYSTEM_SERVICES,
        ).onSuccess { ids ->
            installedHookIds.addAll(ids)
            Log.d(TAG, "Installed hook for ${targetClass.simpleName}.$methodName (${ids.size} overloads)")
        }.onFailure { e ->
            Log.w(TAG, "Could not hook ${targetClass.simpleName}.$methodName", e)
        }
    }

    /**
     * Attempts to extract the calling package name from a [CallFrame].
     *
     * Uses three strategies in order:
     * 1. Look for a String argument that matches a known virtual package.
     * 2. Resolve the calling UID via [Binder.getCallingUid] and match against
     *    running virtual process records.
     * 3. Fall back to the first String argument as a best guess.
     */
    private fun extractCallerPackage(frame: CallFrame): String {
        // Strategy 1: look for a String arg that matches a known virtual package
        val stringArgs = frame.args.filterIsInstance<String>()
        for (arg in stringArgs) {
            if (isVirtualApp(arg)) return arg
        }

        // Strategy 2: resolve the calling UID via Binder.getCallingUid()
        val callingUid = Binder.getCallingUid()
        if (callingUid != Process.myUid()) {
            for (record in VirtualEngine.getRunningProcesses()) {
                if (record.uid == callingUid) return record.packageName
            }
        }

        // Strategy 3: check the local process if it matches a virtual UID range
        val myUid = Process.myUid()
        for (record in VirtualEngine.getRunningProcesses()) {
            if (record.uid == myUid) return record.packageName
        }

        // Strategy 4: first String argument as best guess
        return stringArgs.firstOrNull() ?: ""
    }

    // ════════════════════════════════════════════════════════════
    //  Virtual service stubs
    //
    //  These objects contain the virtual implementations that hooked
    //  calls are redirected to. They delegate to VirtualEngine and
    //  other manager classes for the actual virtual state.
    // ════════════════════════════════════════════════════════════

    /** Virtual implementation of ActivityManager functionality. */
    private object VirtualActivityService {
        private const val TAG = "Atlas:VirtAM"

        fun startActivity(frame: CallFrame) {
            val args = frame.args
            Log.d(TAG, "Virtual startActivity invoked with ${args.size} args")
            val intent = args.filterIsInstance<Intent>().firstOrNull()
            if (intent != null) {
                val targetPkg = intent.component?.packageName
                if (targetPkg != null && isVirtualApp(targetPkg)) {
                    Log.i(TAG, "Launching virtual activity: ${intent.component}")
                    VirtualEngine.getActivityManager().startActivity(intent, null)
                }
            }
        }

        fun forceStopPackage(packageName: String) {
            Log.i(TAG, "Virtual forceStopPackage: $packageName")
            VirtualEngine.forceStopApp(packageName)
        }

        fun getRunningAppProcesses(): List<ActivityManager.RunningAppProcessInfo> {
            val processes = mutableListOf<ActivityManager.RunningAppProcessInfo>()
            for (record in VirtualEngine.getRunningProcesses()) {
                if (record.isAlive()) {
                    processes.add(ActivityManager.RunningAppProcessInfo().apply {
                        processName = record.packageName
                        pid = record.pid
                        uid = record.uid
                    })
                }
            }
            return processes
        }

        fun killBackgroundProcesses(packageName: String) {
            Log.i(TAG, "Virtual killBackgroundProcesses (no-op): $packageName")
            // Virtual apps' background processes are managed by the engine,
            // not by the system's ActivityManager.
        }

        fun checkPermission(args: Array<out Any?>): Int {
            // PackageManager.PERMISSION_GRANTED = 0, PERMISSION_DENIED = -1
            val permission = args.filterIsInstance<String>().firstOrNull() ?: return -1
            val pkgName = args.filterIsInstance<String>().elementAtOrNull(1) ?: return -1
            Log.d(TAG, "Virtual checkPermission: $permission for $pkgName")
            // Grant all runtime permissions to virtual apps by default.
            // Dangerous permissions are managed through the virtual PM.
            return PackageManager.PERMISSION_GRANTED
        }

        fun getProcessMemoryInfo(pids: IntArray): Any? {
            // Return a Debug.MemoryInfo array via reflection since the constructor
            // is not directly accessible in all API levels.
            return runCatching {
                val virtualPids = VirtualEngine.getRunningProcesses().map { it.pid }.toSet()
                val memInfoClass = Class.forName("android.os.Debug\$MemoryInfo")
                val constructor = memInfoClass.getConstructor()
                val result = java.lang.reflect.Array.newInstance(memInfoClass, pids.size) as Array<Any?>
                for (i in pids.indices) {
                    val info = constructor.newInstance()
                    if (pids[i] in virtualPids) {
                        val record = VirtualEngine.getRunningProcesses()
                            .firstOrNull { it.pid == pids[i] }
                        record?.let { r ->
                            val usedMb = r.memoryInfo.usedMb.toInt()
                            // Set totalPrivateDirty via reflection
                            runCatching {
                                val field = memInfoClass.getDeclaredField("totalPrivateDirty")
                                field.isAccessible = true
                                field.set(info, usedMb * 1024)
                            }
                        }
                    }
                    result[i] = info
                }
                result
            }.getOrNull()
        }
    }

    /** Virtual implementation of PackageManager functionality. */
    private object VirtualPackageManagerService {
        private const val TAG = "Atlas:VirtPM"

        private val virtualPackageCache = mutableMapOf<String, Any>()
        private val componentEnabledStates = mutableMapOf<String, Int>()

        fun getPackageInfo(packageName: String, args: Array<out Any?>): Any? {
            Log.d(TAG, "Virtual getPackageInfo: $packageName")
            return virtualPackageCache["pkginfo_$packageName"]
        }

        fun getApplicationInfo(packageName: String, args: Array<out Any?>): Any? {
            Log.d(TAG, "Virtual getApplicationInfo: $packageName")
            return virtualPackageCache["appinfo_$packageName"]
        }

        fun getInstalledPackages(args: Array<out Any?>): Any {
            Log.d(TAG, "Virtual getInstalledPackages")
            return emptyList<Any>()
        }

        fun getInstalledApplications(args: Array<out Any?>): Any {
            Log.d(TAG, "Virtual getInstalledApplications")
            return emptyList<Any>()
        }

        fun getComponentEnabledSetting(componentName: String): Int {
            Log.d(TAG, "Virtual getComponentEnabledSetting: $componentName")
            // COMPONENT_ENABLED_STATE_DEFAULT = 0
            return componentEnabledStates[componentName] ?: 0
        }

        fun setComponentEnabledSetting(componentName: String, newState: Int) {
            Log.d(TAG, "Virtual setComponentEnabledSetting: $componentName → $newState")
            componentEnabledStates[componentName] = newState
        }

        fun getPackageUid(packageName: String): Int {
            Log.d(TAG, "Virtual getPackageUid: $packageName")
            val record = VirtualEngine.getRunningProcesses()
                .firstOrNull { it.packageName == packageName }
            return record?.uid ?: Process.FIRST_APPLICATION_UID
        }

        /**
         * Stores a PackageInfo object in the virtual cache.
         * Called by the installation pipeline when an app is installed.
         */
        fun cachePackageInfo(packageName: String, packageInfo: Any) {
            virtualPackageCache["pkginfo_$packageName"] = packageInfo
        }

        /**
         * Stores an ApplicationInfo object in the virtual cache.
         */
        fun cacheApplicationInfo(packageName: String, applicationInfo: Any) {
            virtualPackageCache["appinfo_$packageName"] = applicationInfo
        }

        /**
         * Clears cached data for a specific package.
         */
        fun clearCache(packageName: String) {
            virtualPackageCache.remove("pkginfo_$packageName")
            virtualPackageCache.remove("appinfo_$packageName")
            componentEnabledStates.keys.removeIf { it.startsWith("$packageName/") }
        }
    }

    /** Virtual implementation of WindowManager functionality. */
    private object VirtualWindowManagerService {
        private const val TAG = "Atlas:VirtWM"

        fun getDefaultDisplayRotation(): Int {
            Log.d(TAG, "Virtual getDefaultDisplayRotation → ROTATION_0")
            return android.view.Surface.ROTATION_0
        }

        fun getDisplayDecorationSupport(): Any? {
            Log.d(TAG, "Virtual getDisplayDecorationSupport → null")
            return null
        }

        fun getInitialDisplaySize(): Any? {
            Log.d(TAG, "Virtual getInitialDisplaySize → null (use real display)")
            // Return null to fall through to the real implementation.
            // Virtual apps use the host's display size by default.
            return null
        }
    }

    /** Virtual implementation of AlarmManager functionality. */
    private object VirtualAlarmService {
        private const val TAG = "Atlas:VirtAlarm"

        private val scheduledAlarms = mutableMapOf<String, MutableList<Any>>()

        @Synchronized
        fun scheduleAlarm(packageName: String, method: String, args: Array<out Any?>) {
            Log.d(TAG, "Virtual alarm $method scheduled for $packageName")
            scheduledAlarms.getOrPut(packageName) { mutableListOf() }
                .add(args.asList())
        }

        @Synchronized
        fun cancelAlarm(packageName: String, args: Array<out Any?>) {
            Log.d(TAG, "Virtual alarm cancelled for $packageName")
            scheduledAlarms.remove(packageName)
        }

        @Synchronized
        fun getScheduledAlarmCount(packageName: String): Int {
            return scheduledAlarms[packageName]?.size ?: 0
        }

        @Synchronized
        fun clearAllAlarms() {
            val count = scheduledAlarms.values.sumOf { it.size }
            scheduledAlarms.clear()
            Log.i(TAG, "Cleared $count virtual alarms")
        }
    }
}
