package com.atlas.virtualspace.core.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Process
import com.atlas.virtualspace.core.fs.VirtualFileSystem
import com.atlas.virtualspace.core.hook.HookManager
import com.atlas.virtualspace.core.hook.SystemServiceHooks
import com.atlas.virtualspace.core.pm.InstallType
import com.atlas.virtualspace.core.pm.VirtualAppInfo
import com.atlas.virtualspace.core.pm.VirtualPackageManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Central singleton engine that orchestrates the entire virtual environment.
 *
 * Responsibilities:
 * - Initialising the Pine hooking framework via [HookManager]
 * - Installing / uninstalling virtual apps (delegating to [VirtualPackageManager])
 * - Launching / stopping virtual processes
 * - Managing the virtual file-system root via [VirtualFileSystem]
 * - Coordinating IPC between host and virtual processes
 *
 * All public methods return [Result] objects so callers can handle
 * failures explicitly.  Internally the engine uses [CoroutineScope]
 * with a supervisor job so that one failed child does not cancel siblings.
 */
object VirtualEngine {

    // ─── Reactive State ──────────────────────────────────────────

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _installedApps = MutableStateFlow<List<VirtualAppInfo>>(emptyList())
    val installedApps: StateFlow<List<VirtualAppInfo>> = _installedApps.asStateFlow()

    // ─── Internal State ──────────────────────────────────────────

    @Volatile
    private var engineConfig: EngineConfig = EngineConfig(
        virtualRootPath = "/data/data/unknown/virtual_root"
    )

    @Volatile
    private var applicationContext: Context? = null

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Map of package name → mutable [InternalProcessRecord].
     * Access must be synchronized on [processLock] for compound operations.
     */
    private val processRecords = ConcurrentHashMap<String, InternalProcessRecord>()
    private val processLock = Any()

    /** Monotonically increasing virtual UID counter (starting from 100_000). */
    private val virtualUidCounter = AtomicInteger(FIRST_VIRTUAL_UID)

    /** Virtual activity manager – created once during [initialize]. */
    private val activityManager = VirtualActivityManager()

    /** Gson instance for persisting app registry. */
    private val gson = Gson()

    /** Registry file that stores installed app metadata across restarts. */
    @Volatile
    private var registryFile: File? = null

    // ─── Lifecycle ───────────────────────────────────────────────

    init {
        Timber.d("VirtualEngine singleton created")
    }

    /**
     * Initialises the virtual engine.
     *
     * Steps:
     * 1. Stores the application [Context].
     * 2. Configures and initialises the Pine hooking framework via [HookManager].
     * 3. Sets up the virtual file-system root directory tree via [VirtualFileSystem].
     * 4. Loads the persisted app registry.
     * 5. Starts the virtual IPC bridge.
     * 6. Installs activity manager hooks.
     *
     * Must be called exactly once before any other method.
     *
     * @return [Result.success] if initialisation completed, [Result.failure] on error.
     */
    fun initialize(context: Context): Result<Unit> {
        return try {
            if (_isRunning.value) {
                Timber.w("VirtualEngine already running – skipping initialise")
                return Result.success(Unit)
            }

            applicationContext = context.applicationContext
            engineConfig = EngineConfig.default(context)

            // 1. Initialise Pine hooking framework.
            initPine(context)

            // 2. Ensure virtual file-system root exists (idempotent).
            val vfsResult = VirtualFileSystem.initialize(context)
            if (vfsResult.isFailure) {
                Timber.e(vfsResult.exceptionOrNull(), "VirtualFileSystem initialisation failed")
                throw vfsResult.exceptionOrNull() ?: RuntimeException("VFS init failed")
            }

            // 3. Load persisted app registry.
            loadAppRegistry()

            Timber.i("Virtual IPC bridge ready")

            // 4. Install activity hooks.
            activityManager.installHooks()

            // 5. Install system service hooks (PackageManager, ActivityManager, etc.)
            //     These intercept system service calls from virtual apps and redirect
            //     them to Atlas' virtual implementations.
            val sysHooksResult = SystemServiceHooks.hookAll()
            if (sysHooksResult.isFailure) {
                Timber.w(sysHooksResult.exceptionOrNull(), "Some system service hooks failed — virtual apps may see real system data")
            } else {
                Timber.i("All system service hooks installed successfully")
            }

            _isRunning.value = true

            Timber.i(
                "VirtualEngine initialised – root=%s, apps=%d",
                engineConfig.virtualRootPath,
                _installedApps.value.size
            )

            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "VirtualEngine initialisation failed")
            Result.failure(e)
        }
    }

    /**
     * Installs an APK or XAPK file into the virtual space.
     *
     * Delegates to [VirtualPackageManager] for the actual installation,
     * which handles APK parsing, native lib extraction, and database
     * persistence. The installed apps flow is updated upon success.
     *
     * @param source The source APK / XAPK file on disk.
     * @param type   Whether this is an [InstallType.APK] or [InstallType.XAPK].
     * @return [Result.success] containing the new [VirtualAppInfo], or [Result.failure].
     */
    fun installApp(source: File, type: InstallType): Result<VirtualAppInfo> {
        return try {
            if (!source.exists()) {
                return Result.failure(IOException("Source file does not exist: ${source.absolutePath}"))
            }
            if (!source.canRead()) {
                return Result.failure(IOException("Cannot read source file: ${source.absolutePath}"))
            }

            Timber.i("Installing %s from %s", type.displayName, source.name)

            val result = when (type) {
                InstallType.APK -> VirtualPackageManager.installApp(source, type)
                InstallType.XAPK -> VirtualPackageManager.installXapk(source)
                InstallType.CLONE -> VirtualPackageManager.installApp(source, type)
                InstallType.IMPORT -> VirtualPackageManager.installApp(source, type)
            }

            val appInfo = result.getOrElse { error ->
                Timber.e(error, "VirtualPackageManager failed to install %s", source.name)
                return Result.failure(error)
            }

            // Update reactive state.
            val updatedList = _installedApps.value
                .filterNot { it.packageName == appInfo.packageName } + appInfo
            _installedApps.value = updatedList
            persistAppRegistry()

            Timber.i(
                "Installed %s v%s",
                appInfo.packageName,
                appInfo.versionName
            )

            Result.success(appInfo)
        } catch (e: Exception) {
            Timber.e(e, "Failed to install %s from %s", type.displayName, source.absolutePath)
            Result.failure(e)
        }
    }

    /**
     * Launches a virtual application.
     *
     * FIXES applied:
     * 1. Engine-not-ready guard: if the engine hasn't initialised yet (because
     *    the async DB init in AtlasApplication hasn't completed), we attempt
     *    a synchronous init with a clear error rather than silently failing.
     * 2. Null launchActivity: now caught with a clear user-facing error.
     * 3. Already-running path: correctly uses VirtualStubActivity intent
     *    instead of trying getLaunchIntentForPackage on an uninstalled package.
     *
     * @param packageName The package name of the app to launch.
     * @return [Result.success] if the launch was dispatched, [Result.failure] otherwise.
     */
    fun launchApp(packageName: String): Result<Unit> {
        return try {
            val appInfo = _installedApps.value.firstOrNull { it.packageName == packageName }
                ?: return Result.failure(
                    IllegalArgumentException("App not installed in virtual space: $packageName")
                )

            if (!appInfo.isEnabled) {
                return Result.failure(IllegalStateException("App is disabled: $packageName"))
            }

            // Ensure the engine is initialized before we try to launch.
            // The async init in AtlasApplication may not have completed yet
            // if the user taps "Launch" very quickly after first install.
            if (!_isRunning.value) {
                val ctx = applicationContext
                    ?: return Result.failure(
                        IllegalStateException(
                            "Virtual engine is still starting up. Please wait a moment and try again."
                        )
                    )
                Timber.w("VirtualEngine not running — attempting synchronous init before launch")
                val initResult = initialize(ctx)
                if (initResult.isFailure) {
                    return Result.failure(
                        IllegalStateException(
                            "Virtual engine failed to start: ${initResult.exceptionOrNull()?.message}",
                            initResult.exceptionOrNull()
                        )
                    )
                }
            }

            val launchActivity = appInfo.launchActivity
            if (launchActivity.isNullOrBlank()) {
                return Result.failure(
                    IllegalStateException(
                        "Cannot launch $packageName: no launcher activity found in APK manifest. " +
                        "Try re-installing the app."
                    )
                )
            }

            val ctx = applicationContext
                ?: return Result.failure(IllegalStateException("Application context not available"))

            // If already running, bring to foreground via VirtualStubActivity
            // (NOT via PackageManager.getLaunchIntentForPackage — the app is
            //  NOT installed on the real device in the import/file case).
            val existing = processRecords[packageName]
            if (existing != null && existing.isAlive()) {
                Timber.i("App %s already running (pid %d) — bringing to foreground", packageName, existing.pid.get())
                val bringForwardIntent = android.content.Intent(ctx, VirtualStubActivity::class.java).apply {
                    putExtra(VirtualStubActivity.EXTRA_PACKAGE_NAME, packageName)
                    putExtra(VirtualStubActivity.EXTRA_ACTIVITY_CLASS, launchActivity)
                    putExtra(VirtualStubActivity.EXTRA_VIRTUAL_LAUNCH, true)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
                ctx.startActivity(bringForwardIntent)
                return Result.success(Unit)
            }

            // Create a new virtual process record.
            val virtualUid = virtualUidCounter.getAndIncrement()
            val is64Bit = appInfo.is64Bit && engineConfig.enable64BitSupport

            val record = InternalProcessRecord(
                packageName = packageName,
                processName = packageName,
                uid = virtualUid,
                is64Bit = is64Bit
            )

            synchronized(processLock) {
                processRecords[packageName] = record
            }

            startVirtualProcess(record, appInfo)

            // Launch via VirtualStubActivity — it handles three strategies:
            //  1. Direct launch (app already on device)
            //  2. Shizuku install + am start (elevated, no dialog)
            //  3. System package installer dialog
            val stubIntent = android.content.Intent(ctx, VirtualStubActivity::class.java).apply {
                putExtra(VirtualStubActivity.EXTRA_PACKAGE_NAME, packageName)
                putExtra(VirtualStubActivity.EXTRA_ACTIVITY_CLASS, launchActivity)
                putExtra(VirtualStubActivity.EXTRA_VIRTUAL_LAUNCH, true)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
            ctx.startActivity(stubIntent)

            // Update last-launch time reactively.
            val updatedAppInfo = appInfo.copy(lastLaunchTime = System.currentTimeMillis())
            val updatedList = _installedApps.value
                .filterNot { it.packageName == packageName } + updatedAppInfo
            _installedApps.value = updatedList
            persistAppRegistry()

            Timber.i("Launched %s via VirtualStubActivity → %s (uid=%d, 64bit=%b)",
                packageName, launchActivity, virtualUid, is64Bit)
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to launch %s", packageName)
            Result.failure(e)
        }
    }

    /**
     * Uninstalls a virtual application.
     *
     * Force-stops the app if running, then delegates to
     * [VirtualPackageManager] for data cleanup and database removal.
     *
     * @param packageName The package name to uninstall.
     * @return [Result.success] if the uninstall completed, [Result.failure] otherwise.
     */
    fun uninstallApp(packageName: String): Result<Unit> {
        return try {
            val appInfo = _installedApps.value.firstOrNull { it.packageName == packageName }
                ?: return Result.failure(
                    IllegalArgumentException("App not installed: $packageName")
                )

            // Force stop if running.
            if (processRecords.containsKey(packageName)) {
                forceStopApp(packageName)
            }

            // Delegate to VirtualPackageManager for cleanup.
            VirtualPackageManager.uninstallApp(packageName).getOrElse { error ->
                Timber.w(error, "VirtualPackageManager uninstall failed for %s", packageName)
            }

            // Remove from registry.
            val updatedList = _installedApps.value.filterNot { it.packageName == packageName }
            _installedApps.value = updatedList
            persistAppRegistry()

            Timber.i("Uninstalled %s", packageName)
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to uninstall %s", packageName)
            Result.failure(e)
        }
    }

    /**
     * Force-stops a running virtual application.
     *
     * Sends a termination signal via the IPC bridge, removes the process
     * record, and cleans up associated activity records.
     *
     * @param packageName The package name to force-stop.
     * @return [Result.success] if the app was stopped, [Result.failure] otherwise.
     */
    fun forceStopApp(packageName: String): Result<Unit> {
        return try {
            val record = synchronized(processLock) {
                processRecords.remove(packageName)
            }

            if (record == null) {
                Timber.w("App %s is not running – nothing to stop", packageName)
                return Result.success(Unit)
            }

            // Update process state.
            record.state.set(ProcessState.STOPPING)

            // Terminate the virtual process.
            terminateVirtualProcess(record)

            // Remove associated activities.
            activityManager.removeActivitiesForProcess(packageName)

            // Final state update and cleanup.
            record.state.set(ProcessState.STOPPED)
            synchronized(processLock) {
                processRecords.remove(packageName)
            }

            Timber.i("Force-stopped %s (pid was %d)", packageName, record.pid.get())
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to force-stop %s", packageName)
            Result.failure(e)
        }
    }

    /**
     * Gracefully shuts down the entire virtual engine.
     *
     * Stops all running processes, unhooks Pine, and releases resources.
     */
    fun shutdown() {
        if (!_isRunning.value) return

        Timber.i("VirtualEngine shutting down…")

        // 1. Stop all running processes.
        val packagesToStop = synchronized(processLock) {
            processRecords.keys.toList()
        }
        packagesToStop.forEach { pkg ->
            try {
                forceStopApp(pkg)
            } catch (e: Exception) {
                Timber.w(e, "Error stopping %s during shutdown", pkg)
            }
        }

        // 2. Remove activity hooks.
        activityManager.removeHooks()

        // 3. Unhook all framework hooks via HookManager.
        HookManager.unhookAll()

        // 4. Cancel coroutine scope.
        engineScope.cancel()

        // 5. Clear state.
        _isRunning.value = false
        applicationContext = null

        Timber.i("VirtualEngine shutdown complete")
    }

    /**
     * Returns a snapshot of currently running virtual processes
     * as immutable [ProcessRecord] instances.
     */
    fun getRunningProcesses(): List<ProcessRecord> {
        synchronized(processLock) {
            return processRecords.values.map { it.toProcessRecord() }
        }
    }

    /**
     * Returns the [VirtualActivityManager] for activity-level operations.
     */
    fun getActivityManager(): VirtualActivityManager = activityManager

    /**
     * Returns the current engine configuration.
     */
    fun getConfig(): EngineConfig = engineConfig

    /**
     * Refreshes runtime statistics (memory, thread count) for all running
     * processes.  Should be called periodically by the service.
     */
    fun refreshProcessStats() {
        val ctx = applicationContext ?: return
        synchronized(processLock) {
            processRecords.values.forEach { record ->
                val pid = record.pid.get()
                if (pid > 0) {
                    record.memoryInfo.set(MemoryInfo.fromProcess(ctx, pid))
                    readThreadCountFromProcFs(pid)?.let { record.threadCount.set(it) }
                }
            }
        }
    }

    // ─── Methods required by existing IPC layer ──────────────────

    /**
     * Registers a new app in the virtual engine and returns its
     * [InternalProcessRecord].  Called by [VirtualIPC] transaction handlers.
     *
     * @param packageName The package name to register.
     * @param uid         The virtual UID to assign.
     * @return The newly created [InternalProcessRecord].
     */
    fun registerApp(packageName: String, uid: Int): InternalProcessRecord {
        val record = InternalProcessRecord(
            packageName = packageName,
            processName = packageName,
            uid = uid,
            is64Bit = engineConfig.enable64BitSupport
        )
        synchronized(processLock) {
            processRecords[packageName] = record
        }
        return record
    }

    /**
     * Returns the mutable [InternalProcessRecord] for a running package,
     * or `null` if the package is not currently running.
     *
     * Used by [VirtualIPC] and [ProcessConnector] for state updates.
     */
    fun getProcessRecord(packageName: String): InternalProcessRecord? {
        return processRecords[packageName]
    }

    /**
     * Called by [ProcessConnector] when a virtual process dies unexpectedly.
     *
     * Updates the process state to [ProcessState.CRASHED] and removes
     * associated activity records.
     */
    fun notifyProcessDeath(packageName: String) {
        val record = processRecords[packageName] ?: return
        record.state.set(ProcessState.CRASHED)
        activityManager.removeActivitiesForProcess(packageName)
        Timber.w("Process death notified for %s (pid=%d)", packageName, record.pid.get())
    }

    /**
     * Called by [VirtualStubActivity] when a virtual app has been successfully
     * launched. Updates the process record from STARTING to RUNNING.
     *
     * Also attempts to determine the actual PID of the launched app process
     * by querying the ActivityManager.
     */
    fun notifyActivityLaunched(packageName: String) {
        val record = processRecords[packageName] ?: return
        record.state.set(ProcessState.RUNNING)

        // Try to find the actual PID of the running app process
        try {
            val ctx = applicationContext ?: return
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val processes = am?.runningAppProcesses
            val appProcess = processes?.firstOrNull { it.processName == packageName }
            if (appProcess != null) {
                record.pid.set(appProcess.pid)
                Timber.i("Activity launched: %s (pid=%d)", packageName, appProcess.pid)
            } else {
                Timber.i("Activity launched: %s (pid unknown — app runs in its own process)", packageName)
            }
        } catch (e: Exception) {
            Timber.i("Activity launched: %s (pid lookup failed: %s)", packageName, e.message)
        }
    }

    // ─── Pine Initialisation ─────────────────────────────────────

    private fun initPine(context: Context) {
        val result = HookManager.initialize(engineConfig)
        if (result.isFailure) {
            throw result.exceptionOrNull() ?: RuntimeException("HookManager initialisation failed")
        }
        Timber.i("Pine hook framework initialised via HookManager")
    }

    // ─── App Registry Persistence ────────────────────────────────

    private fun loadAppRegistry() {
        val root = File(engineConfig.virtualRootPath)
        registryFile = File(root, "app_registry.json")

        val file = registryFile ?: return
        if (!file.exists()) {
            Timber.d("No existing app registry – starting fresh")
            // Also try loading from the database via VirtualPackageManager.
            loadFromPackageManager()
            return
        }

        try {
            val json = file.readText(Charsets.UTF_8)
            val type = object : TypeToken<List<VirtualAppInfo>>() {}.type
            val apps: List<VirtualAppInfo>? = gson.fromJson<List<VirtualAppInfo>>(json, type)
            if (apps != null) {
                _installedApps.value = apps
                Timber.d("Loaded %d apps from registry", apps.size)
            } else {
                loadFromPackageManager()
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to load app registry – trying database")
            loadFromPackageManager()
        }
    }

    private fun loadFromPackageManager() {
        try {
            val apps = VirtualPackageManager.getInstalledApps()
            _installedApps.value = apps
            Timber.d("Loaded %d apps from VirtualPackageManager", apps.size)
        } catch (e: Exception) {
            Timber.w(e, "Failed to load apps from VirtualPackageManager")
            _installedApps.value = emptyList()
        }
    }

    private fun persistAppRegistry() {
        val file = registryFile ?: return
        try {
            val json = gson.toJson(_installedApps.value)
            file.writeText(json, Charsets.UTF_8)
        } catch (e: Exception) {
            Timber.e(e, "Failed to persist app registry")
        }
    }

    // ─── Virtual Process Management ──────────────────────────────

    private fun startVirtualProcess(record: InternalProcessRecord, appInfo: VirtualAppInfo) {
        // Atlas uses a single-process model where the virtual app is installed
        // on the real device and launched normally. The "virtual process" record
        // tracks the app's virtual-space lifecycle, not an actual forked process.
        //
        // The app will run in its own process managed by Android's ActivityManager.
        // We track it as STARTING until VirtualStubActivity confirms the launch.

        record.pid.set(0) // Will be updated by notifyActivityLaunched if we can determine the PID
        record.state.set(ProcessState.STARTING)
        record.startTime.set(System.currentTimeMillis())

        Timber.d("Virtual process record created: %s (state=STARTING, awaiting launch confirmation)", record.packageName)
    }

    private fun terminateVirtualProcess(record: InternalProcessRecord) {
        val pid = record.pid.get()

        try {
            // Try to force-stop the app via Shizuku (most reliable)
            val shizuku = com.atlas.virtualspace.core.hook.ShizukuIntegration
            if (shizuku.isShizukuAvailable() && shizuku.isShizukuPermissionGranted()) {
                shizuku.forceStopWithShizuku(record.packageName)
            } else if (pid > 0 && pid != Process.myPid()) {
                // Only try to kill if PID is valid and NOT our own process
                Process.killProcess(pid)
            }
        } catch (e: Exception) {
            Timber.w(e, "Error terminating virtual process for %s", record.packageName)
        }

        record.state.set(ProcessState.STOPPED)
    }

    // ─── Utility Methods ─────────────────────────────────────────

    private fun readThreadCountFromProcFs(pid: Int): Int? {
        return try {
            val statusLines = java.io.File("/proc/$pid/status").readLines()
            statusLines
                .firstOrNull { it.startsWith("Threads:") }
                ?.substringAfter(':')
                ?.trim()
                ?.toIntOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private const val FIRST_VIRTUAL_UID = 100_000

    // ─── Internal Mutable Process Record ─────────────────────────

    /**
     * Mutable process record used internally by the engine and referenced
     * by the IPC layer ([VirtualIPC], [ProcessConnector]).
     *
     * State and PID are backed by atomic types for lock-free concurrent
     * access from multiple threads (engine thread, Binder threads, etc.).
     *
     * Use [toProcessRecord] to obtain an immutable snapshot.
     */
    class InternalProcessRecord(
        val packageName: String,
        val processName: String,
        val uid: Int,
        val is64Bit: Boolean
    ) {
        val pid: AtomicInteger = AtomicInteger(0)
        val state: AtomicReference<ProcessState> = AtomicReference(ProcessState.STARTING)
        val startTime: AtomicReference<Long> = AtomicReference(System.currentTimeMillis())
        val memoryInfo: AtomicReference<MemoryInfo> =
            AtomicReference(MemoryInfo(totalMb = 0L, usedMb = 0L, availableMb = 0L))
        val threadCount: AtomicInteger = AtomicInteger(1)

        fun isAlive(): Boolean {
            val s = state.get()
            return s == ProcessState.STARTING || s == ProcessState.RUNNING
        }

        /**
         * Creates an immutable [ProcessRecord] snapshot from this mutable record.
         */
        fun toProcessRecord(): ProcessRecord = ProcessRecord(
            pid = pid.get(),
            packageName = packageName,
            processName = processName,
            uid = uid,
            startTime = startTime.get(),
            is64Bit = is64Bit,
            state = state.get(),
            memoryInfo = memoryInfo.get(),
            threadCount = threadCount.get()
        )
    }
}
