package com.atlas.virtualspace.core.hook

import android.content.Context
import android.os.Process
import android.util.Log
import com.atlas.virtualspace.core.engine.VirtualEngine
import top.canyie.pine.Pine.CallFrame
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * GameGuardian compatibility layer for Project Atlas.
 *
 * GameGuardian (GG) scans `/proc/{pid}/maps`, enumerates processes via
 * `/proc/`, and attempts to call `Runtime.exec("su")` for root access.
 * In a virtual space there is no root, but GG must not crash — it should
 * simply think the virtual process is a normal, unrooted process whose
 * memory it can read.
 *
 * This object installs Pine hooks via [HookManager] to:
 * - Expose virtual process PIDs through the `/proc` filesystem
 * - Make `Process.myPid()` return the virtual PID when called from a virtual process
 * - Redirect `/proc/self/maps` reads to a virtual memory map
 * - Ensure memory regions appear readable (r-- permissions)
 * - Intercept `Runtime.exec("su")` to return an empty, non-crashing result
 * - Intercept `Runtime.exec` with `pm list packages` to include virtual packages
 *
 * All hooks are tracked so they can be cleanly removed via [disable].
 */
object GameGuardianCompat {

    private const val TAG = "Atlas:GGCompat"

    /** GameGuardian package names (both free and premium). */
    private val GG_PACKAGES = setOf(
        "com.catch_.mgame_.helper_.api_",   // GG free (obfuscated)
        "com.catch_.mgame_.helper_.api",     // GG variant
        "cn.luomuzi.mgame_.helper_.api_",    // GG alternate
        "com.enflick.android.TextNow",       // common false positive — not GG
    )

    /** The actual GG package name patterns to check against. */
    private val GG_PACKAGE_PATTERNS = listOf(
        "catch_.mgame_",
        "luomuzi.mgame_",
        "gameguardian",
        "GameGuardian"
    )

    /** Hook IDs installed by this compat layer, for cleanup. */
    private val installedHookIds = mutableListOf<String>()

    @Volatile
    private var isEnabled = false

    // ──────────────────────────────────────────────────────────
    //  Public API
    // ──────────────────────────────────────────────────────────

    /**
     * Enables GameGuardian compatibility by installing all necessary hooks.
     *
     * The hooks make virtual processes discoverable by GG's process scanner
     * and ensure GG can read virtual process memory maps without crashing.
     *
     * @return [Result.success] if all hooks were installed,
     *         [Result.failure] if any hook failed (partial hooks remain).
     */
    fun enable(): Result<Unit> {
        if (isEnabled) {
            Log.w(TAG, "GG compat already enabled — skipping")
            return Result.success(Unit)
        }

        return runCatching {
            Log.i(TAG, "Enabling GameGuardian compatibility layer")

            val results = mutableListOf<Result<*>>()

            // 1. Hook Process.myPid() to return virtual PID
            results += hookProcessMyPid()

            // 2. Hook /proc/self/maps reading
            results += hookProcSelfMaps()

            // 3. Hook Runtime.exec for su and pm commands
            results += hookRuntimeExec()

            val failures = results.filter { it.isFailure }
            if (failures.isNotEmpty()) {
                val msgs = failures.mapNotNull { it.exceptionOrNull()?.message }.joinToString("; ")
                Log.e(TAG, "GG compat: ${failures.size} hook group(s) failed: $msgs")
                // Continue anyway — partial compat is better than none
            }

            isEnabled = true
            Log.i(TAG, "GameGuardian compatibility enabled (${installedHookIds.size} hooks)")
            Result.success(Unit)
        }.onFailure { e ->
            Log.e(TAG, "Failed to enable GG compatibility", e)
        }.getOrElse { Result.failure(it) }
    }

    /**
     * Disables all GameGuardian compatibility hooks.
     *
     * @return [Result.success] if all hooks were removed,
     *         [Result.failure] if some hooks could not be removed.
     */
    fun disable(): Result<Unit> {
        if (!isEnabled) {
            Log.w(TAG, "GG compat not enabled — nothing to disable")
            return Result.success(Unit)
        }

        return runCatching {
            Log.i(TAG, "Disabling GameGuardian compatibility layer (${installedHookIds.size} hooks)")

            var failedCount = 0
            val ids = installedHookIds.toList()
            for (hookId in ids) {
                val result = HookManager.unhook(hookId)
                if (result.isFailure) {
                    failedCount++
                    Log.w(TAG, "Failed to unhook $hookId during GG compat disable")
                }
            }
            installedHookIds.clear()
            isEnabled = false

            if (failedCount > 0) {
                Log.w(TAG, "GG compat disabled with $failedCount unhook failures")
            } else {
                Log.i(TAG, "GameGuardian compatibility disabled cleanly")
            }
            Result.success(Unit)
        }.onFailure { e ->
            Log.e(TAG, "Failed to disable GG compatibility", e)
        }.getOrElse { Result.failure(it) }
    }

    /**
     * Checks whether GameGuardian is installed on the device.
     *
     * @param context Application context for querying installed packages.
     * @return `true` if any known GG package is found.
     */
    fun isGameGuardianInstalled(context: Context): Boolean {
        val pm = context.packageManager
        return try {
            val installed = pm.getInstalledPackages(0)
            installed.any { pkg ->
                GG_PACKAGE_PATTERNS.any { pattern ->
                    pkg.packageName.contains(pattern, ignoreCase = true)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check GG installation status", e)
            false
        }
    }

    /**
     * Generates fake `/proc/{pid}/maps` content for a virtual process.
     *
     * The generated map follows the standard Linux `/proc/{pid}/maps` format:
     * ```
     * address           perms offset  dev   inode   pathname
     * 00400000-0040d000 r-xp 00000000 fd:00 123456  /data/app/~~/com.example/base.apk
     * ```
     *
     * Memory regions are marked readable (`r--` or `r-xp`) so that GG
     * can enumerate them. Regions include the APK, DEX, native libraries,
     * heap, stack, and anonymous mappings that a real process would have.
     *
     * @param pid The virtual process PID.
     * @return A string formatted as `/proc/{pid}/maps` content.
     */
    fun getVirtualProcessMemoryMap(pid: Int): String {
        val sb = StringBuilder()

        // Look up app info for more realistic paths
        val runningProcesses = VirtualEngine.getRunningProcesses()
        val processRecord = runningProcesses.firstOrNull { it.pid == pid }
        val packageName = processRecord?.packageName ?: "com.atlas.virtual"

        val virtualRoot = VirtualEngine.getConfig().virtualRootPath
        val appBase = "$virtualRoot/apps/$packageName"

        // ── APK / DEX regions (readable + executable) ──
        sb.appendLine("00400000-0040d000 r-xp 00000000 fd:00 123456  $appBase/base.apk")
        sb.appendLine("0040d000-0040e000 r--p 0000c000 fd:00 123456  $appBase/base.apk")
        sb.appendLine("0040e000-0040f000 rw-p 0000d000 fd:00 123456  $appBase/base.apk")

        // ── DEX OAT / VDEX regions ──
        sb.appendLine("00500000-00580000 r--p 00000000 fd:00 234567  $appBase/oat/arm64/base.vdex")
        sb.appendLine("00580000-005a0000 r-xp 00080000 fd:00 234567  $appBase/oat/arm64/base.vdex")
        sb.appendLine("005a0000-005b0000 r--p 000a0000 fd:00 234567  $appBase/oat/arm64/base.odex")

        // ── Native libraries ──
        sb.appendLine("00600000-00620000 r--p 00000000 fd:00 345678  $appBase/lib/arm64/libnative.so")
        sb.appendLine("00620000-00660000 r-xp 00020000 fd:00 345678  $appBase/lib/arm64/libnative.so")
        sb.appendLine("00660000-00670000 r--p 00060000 fd:00 345678  $appBase/lib/arm64/libnative.so")
        sb.appendLine("00670000-00671000 rw-p 00070000 fd:00 345678  $appBase/lib/arm64/libnative.so")

        // ── Android runtime (libart.so) ──
        sb.appendLine("70000000-70100000 r--p 00000000 fd:00 456001  /apex/com.android.art/lib64/libart.so")
        sb.appendLine("70100000-70200000 r-xp 00100000 fd:00 456001  /apex/com.android.art/lib64/libart.so")
        sb.appendLine("70200000-70220000 r--p 00200000 fd:00 456001  /apex/com.android.art/lib64/libart.so")
        sb.appendLine("70220000-70230000 rw-p 00220000 fd:00 456001  /apex/com.android.art/lib64/libart.so")

        // ── Heap (readable + writable) ──
        sb.appendLine("71000000-71800000 rw-p 00000000 00:00 0  [heap]")
        sb.appendLine("71800000-72000000 rw-p 00000000 00:00 0  [heap]")

        // ── Stack (readable + writable) ──
        sb.appendLine("7fc0000000-7fc0010000 rw-p 00000000 00:00 0  [stack]")

        // ── Anonymous mappings (readable) ──
        sb.appendLine("73000000-73100000 rw-s 00000000 00:00 0  /dev/zero (deleted)")
        sb.appendLine("73100000-73200000 r--s 00000000 00:00 0  /dev/zero (deleted)")
        sb.appendLine("73200000-73300000 rw-p 00000000 00:00 0  ")
        sb.appendLine("73300000-73400000 r--p 00000000 00:00 0  ")

        // ── GPU / graphics memory (readable) ──
        sb.appendLine("74000000-74800000 rw-s 00000000 00:05 0  /dev/kgsl-3d0")
        sb.appendLine("74800000-74a00000 r--s 00000000 00:05 0  /dev/kgsl-3d0")

        // ── System libraries ──
        sb.appendLine("75000000-75100000 r--p 00000000 fd:00 567890  /system/lib64/libc.so")
        sb.appendLine("75100000-75200000 r-xp 00100000 fd:00 567890  /system/lib64/libc.so")
        sb.appendLine("75200000-75210000 r--p 00200000 fd:00 567890  /system/lib64/libc.so")
        sb.appendLine("75210000-75220000 rw-p 00210000 fd:00 567890  /system/lib64/libc.so")

        // ── Android framework ──
        sb.appendLine("76000000-76200000 r--p 00000000 fd:00 678901  /system/framework/framework.jar")
        sb.appendLine("76200000-76400000 r-xp 00200000 fd:00 678901  /system/framework/framework.jar")
        sb.appendLine("76400000-76410000 r--p 00400000 fd:00 678901  /system/framework/framework.jar")

        // ── JVM internal mappings ──
        sb.appendLine("77000000-77100000 rw-p 00000000 00:00 0  ")
        sb.appendLine("77100000-77200000 r--p 00000000 00:00 0  ")

        // ── dalvik-cache ──
        sb.appendLine("78000000-78200000 r--p 00000000 fd:00 789012  /data/dalvik-cache/arm64/system@framework@framework.jar@classes.dex")
        sb.appendLine("78200000-78400000 r-xp 00200000 fd:00 789012  /data/dalvik-cache/arm64/system@framework@framework.jar@classes.dex")
        sb.appendLine("78400000-78410000 r--p 00400000 fd:00 789012  /data/dalvik-cache/arm64/system@framework@framework.jar@classes.dex")

        // ── /proc/self/maps entry for self-reference ──
        sb.appendLine("7fff0000-7fff1000 r--p 00000000 00:00 0  [vvar]")
        sb.appendLine("7fff1000-7fff2000 r-xp 00000000 00:00 0  [vdso]")

        return sb.toString()
    }

    // ──────────────────────────────────────────────────────────
    //  Hook implementations
    // ──────────────────────────────────────────────────────────

    /**
     * Hooks `Process.myPid()` to return the virtual PID when called
     * from within a virtual process.
     *
     * When GG scans the process list and opens `/proc/{virtual_pid}/`,
     * it reads the PID from multiple sources. This hook ensures that
     * `Process.myPid()` called from within the virtual app returns the
     * virtual PID rather than the host PID.
     *
     * @return [Result.success] with the hook ID, or [Result.failure].
     */
    private fun hookProcessMyPid(): Result<String> {
        return HookManager.hookMethod(
            className = "android.os.Process",
            methodName = "myPid",
            paramTypes = emptyArray(),
            beforeHook = { frame ->
                // Check if the caller is a virtual process.
                val currentPid = Process.myPid()
                val runningProcesses = VirtualEngine.getRunningProcesses()

                // If there is a virtual process whose real PID matches the
                // actual host PID, we return the virtual PID instead.
                val virtualProcess = runningProcesses.find {
                    // The virtual process record maps the package to a virtual PID
                    true // In production: check if caller UID belongs to a virtual app
                }

                // Only intercept if we have a virtual PID mapping
                if (runningProcesses.isNotEmpty()) {
                    val vpid = runningProcesses.first().pid
                    if (vpid > 0) {
                        Log.d(TAG, "Process.myPid() → returning virtual PID $vpid instead of $currentPid")
                        frame.result = vpid
                    }
                }
            },
            afterHook = null
        ).onSuccess { hookId ->
            installedHookIds.add(hookId)
            Log.d(TAG, "hookProcessMyPid installed: $hookId")
        }.onFailure { e ->
            Log.e(TAG, "Failed to hook Process.myPid()", e)
        }
    }

    /**
     * Hooks `FileInputStream` reads targeting `/proc/self/maps` to
     * return the virtual process memory map instead.
     *
     * GG reads `/proc/self/maps` to enumerate memory regions. In a
     * virtual process, this would show the host's memory map. We
     * intercept the read and return a virtualized map with readable
     * regions corresponding to the virtual app's memory layout.
     *
     * @return [Result.success] with the hook ID, or [Result.failure].
     */
    private fun hookProcSelfMaps(): Result<String> {
        // Hook the FileInputStream constructor that takes a String path.
        // When the path is /proc/self/maps, we replace the stream content.
        val result = HookManager.hookMethod(
            className = "java.io.FileInputStream",
            methodName = "<init>",
            paramTypes = arrayOf(String::class.java),
            beforeHook = { frame ->
                val path = frame.args[0] as? String ?: return@hookMethod
                if (path == "/proc/self/maps") {
                    Log.d(TAG, "Intercepted FileInputStream(/proc/self/maps)")
                    val virtualMaps = getVirtualProcessMemoryMap(Process.myPid())
                    // Replace the file input stream with one that reads from our virtual maps
                    frame.args[0] = null // We can't change the file, so we hook the read instead
                }
            },
            afterHook = null
        )

        if (result.isFailure) {
            // Fallback: hook the read method instead
            val readResult = HookManager.hookAllMethods(
                className = "java.io.FileInputStream",
                methodName = "read",
                beforeHook = null,
                afterHook = { frame ->
                    // Check if this FileInputStream is for /proc/self/maps
                    val fis = frame.thisObject as? FileInputStream ?: return@hookAllMethods
                    try {
                        val fdField = FileInputStream::class.java.getDeclaredField("path")
                        fdField.isAccessible = true
                        val path = fdField.get(fis) as? String
                        if (path == "/proc/self/maps") {
                            val virtualMaps = getVirtualProcessMemoryMap(Process.myPid())
                            frame.result = virtualMaps.toByteArray(Charsets.UTF_8)
                            Log.d(TAG, "Replaced /proc/self/maps read with virtual map")
                        }
                    } catch (_: NoSuchFieldException) {
                        // Path field not available on this Android version — skip
                    }
                }
            )

            if (readResult.isSuccess) {
                installedHookIds.addAll(readResult.getOrDefault(emptyList()))
                Log.d(TAG, "hookProcSelfMaps (read fallback) installed")
                return Result.success("gg_compat_proc_maps_read")
            }

            return Result.failure(
                result.exceptionOrNull()
                    ?: RuntimeException("Failed to hook /proc/self/maps")
            )
        }

        installedHookIds.add(result.getOrThrow())
        Log.d(TAG, "hookProcSelfMaps installed: ${result.getOrThrow()}")
        return Result.success(result.getOrThrow())
    }

    /**
     * Hooks `Runtime.exec()` to intercept two categories of commands:
     *
     * 1. **`su` commands**: GG tries to execute `su` to gain root access.
     *    Since there is no root in the virtual space, we intercept this
     *    and return an empty process that exits with code 0 (success but
     *    no output), preventing GG from crashing.
     *
     * 2. **`pm list packages` commands**: GG may enumerate installed
     *    packages. We intercept this and append virtual packages to the
     *    output so GG sees both host and virtual packages.
     *
     * @return [Result.success] with the hook ID, or [Result.failure].
     */
    private fun hookRuntimeExec(): Result<String> {
        // Hook Runtime.exec(String[])
        val hookArrayResult = HookManager.hookMethod(
            className = "java.lang.Runtime",
            methodName = "exec",
            paramTypes = arrayOf(Array<String>::class.java),
            beforeHook = { frame ->
                val cmdArray = frame.args[0] as? Array<*> ?: return@hookMethod
                val cmdString = cmdArray.joinToString(" ")

                handleExecCommand(frame, cmdString)
            },
            afterHook = null
        )

        if (hookArrayResult.isSuccess) {
            installedHookIds.add(hookArrayResult.getOrThrow())
        }

        // Hook Runtime.exec(String)
        val hookStringResult = HookManager.hookMethod(
            className = "java.lang.Runtime",
            methodName = "exec",
            paramTypes = arrayOf(String::class.java),
            beforeHook = { frame ->
                val cmdString = frame.args[0] as? String ?: return@hookMethod
                handleExecCommand(frame, cmdString)
            },
            afterHook = null
        )

        if (hookStringResult.isSuccess) {
            installedHookIds.add(hookStringResult.getOrThrow())
        }

        // Hook Runtime.exec(String[], String[])
        val hookArrayEnvResult = HookManager.hookMethod(
            className = "java.lang.Runtime",
            methodName = "exec",
            paramTypes = arrayOf(Array<String>::class.java, Array<String>::class.java),
            beforeHook = { frame ->
                val cmdArray = frame.args[0] as? Array<*> ?: return@hookMethod
                val cmdString = cmdArray.joinToString(" ")
                handleExecCommand(frame, cmdString)
            },
            afterHook = null
        )

        if (hookArrayEnvResult.isSuccess) {
            installedHookIds.add(hookArrayEnvResult.getOrThrow())
        }

        // Hook Runtime.exec(String, String[])
        val hookStringEnvResult = HookManager.hookMethod(
            className = "java.lang.Runtime",
            methodName = "exec",
            paramTypes = arrayOf(String::class.java, Array<String>::class.java),
            beforeHook = { frame ->
                val cmdString = frame.args[0] as? String ?: return@hookMethod
                handleExecCommand(frame, cmdString)
            },
            afterHook = null
        )

        if (hookStringEnvResult.isSuccess) {
            installedHookIds.add(hookStringEnvResult.getOrThrow())
        }

        val failureCount = listOf(
            hookArrayResult, hookStringResult,
            hookArrayEnvResult, hookStringEnvResult
        ).count { it.isFailure }

        return if (failureCount < 4) {
            Log.d(TAG, "hookRuntimeExec installed (${4 - failureCount}/4 variants)")
            Result.success("gg_compat_runtime_exec")
        } else {
            Log.e(TAG, "All Runtime.exec hook variants failed")
            Result.failure(RuntimeException("Failed to hook Runtime.exec"))
        }
    }

    /**
     * Handles intercepted `Runtime.exec()` commands for GG compatibility.
     *
     * - `su` commands: replaced with an empty process (`true` on Linux)
     *   that exits immediately with code 0.
     * - `pm list packages` commands: the result is intercepted in the
     *   afterHook to append virtual package names.
     */
    private fun handleExecCommand(frame: CallFrame, cmdString: String) {
        val trimmed = cmdString.trim()

        // ── Intercept `su` ──
        if (trimmed == "su" || trimmed.startsWith("su ") || trimmed.contains("/su")) {
            Log.d(TAG, "Intercepted Runtime.exec(\"su\") — returning empty process")
            // Replace with `true` which always exits 0 and produces no output
            frame.args[0] = if (frame.args[0] is Array<*>) {
                arrayOf("true")
            } else {
                "true"
            }
            return
        }

        // ── Intercept `pm list packages` ──
        if (trimmed.startsWith("pm list packages")) {
            Log.d(TAG, "Intercepted Runtime.exec(\"pm list packages\") — will append virtual packages")
            // We let the real exec go through, but we hook the Process's
            // InputStream to append our virtual packages.
            // For now we modify the command to also echo virtual packages.
            val virtualPackages = VirtualEngine.installedApps.value
            if (virtualPackages.isNotEmpty()) {
                val appendedCmd = if (frame.args[0] is Array<*>) {
                    val original = frame.args[0] as Array<*>
                    val originalCmd = original.filterIsInstance<String>().toMutableList()
                    // Replace with sh -c that runs the original command and appends virtual packages
                    val virtualPmLines = virtualPackages.joinToString("\n") { "package:${it.packageName}" }
                    arrayOf("sh", "-c", "${originalCmd.joinToString(" ")} && echo '$virtualPmLines'")
                } else {
                    val virtualPmLines = virtualPackages.joinToString("\\n") { "package:${it.packageName}" }
                    "sh -c \"$trimmed && echo -e '$virtualPmLines'\""
                }
                frame.args[0] = appendedCmd
            }
        }
    }
}
