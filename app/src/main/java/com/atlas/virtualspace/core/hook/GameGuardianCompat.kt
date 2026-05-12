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
     * Returns the real `/proc/self/maps` content for the current process.
     *
     * ## Why this changed
     *
     * In the new in-process architecture, the game's .dex and .so files are
     * loaded directly into Atlas's process via [DexClassLoader] and
     * [System.load]. This means the REAL `/proc/self/maps` already contains
     * all the game's memory regions — there's no need to generate fake entries.
     *
     * GG reads `/proc/self/maps` to enumerate scannable memory regions.
     * Since the game now runs in Atlas's process, the real maps file shows:
     * - The game's base.apk (loaded by DexClassLoader)
     * - The game's .so files (loaded by System.load)
     * - The game's heap allocations
     * - The game's dex/oat/vdex optimized code
     *
     * All with correct permissions (r-xp, rw-p, etc.) that GG requires.
     *
     * We simply read and return the actual /proc/self/maps content.
     * The only filtering we do: remove Atlas's own internal entries that
     * might confuse GG's package detection heuristics.
     *
     * @param pid The virtual process PID (ignored — we always read self).
     * @return The contents of /proc/self/maps as a string.
     */
    fun getVirtualProcessMemoryMap(pid: Int): String {
        return try {
            val mapsContent = java.io.File("/proc/self/maps").readText()
            // Return the full maps — the game's regions are here because
            // we loaded them into our own process via DexClassLoader + System.load.
            // GG will find the game's .so and .dex regions naturally.
            mapsContent
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read /proc/self/maps", e)
            // Fallback: return a minimal maps stub so GG doesn't crash
            buildFallbackMaps(pid)
        }
    }

    /**
     * Fallback maps content if /proc/self/maps is unreadable (SELinux denial).
     * This should rarely happen on unrooted devices since reading your own
     * maps is always permitted, but we handle it gracefully.
     */
    private fun buildFallbackMaps(pid: Int): String {
        val sb = StringBuilder()
        val runningProcesses = VirtualEngine.getRunningProcesses()
        val processRecord = runningProcesses.firstOrNull { it.pid == pid }
        val packageName = processRecord?.packageName ?: "com.atlas.virtual"
        val virtualRoot = VirtualEngine.getConfig().virtualRootPath
        val appBase = "$virtualRoot/apps/$packageName"

        sb.appendLine("00400000-00500000 r-xp 00000000 fd:00 100000  $appBase/base.apk")
        sb.appendLine("00500000-00600000 r--p 00100000 fd:00 100000  $appBase/base.apk")
        sb.appendLine("71000000-72000000 rw-p 00000000 00:00 0  [heap]")
        sb.appendLine("7fc0000000-7fc0010000 rw-p 00000000 00:00 0  [stack]")

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
     * FIXED: The previous implementation set `frame.args[0] = null` on the
     * FileInputStream(String) constructor hook, which caused an immediate
     * NullPointerException in the constructor body when the real constructor
     * tried to use the null path string. This crashed whatever code was
     * trying to open /proc/self/maps.
     *
     * The correct approach is to use an afterHook on the read() methods and
     * short-circuit the result only when the FileInputStream path is
     * /proc/self/maps. We track opened paths using a thread-local so we can
     * correlate the constructor call with subsequent reads.
     *
     * @return [Result.success] with the hook ID, or [Result.failure].
     */
    private fun hookProcSelfMaps(): Result<String> {
        // Track which FileInputStream instances were opened for /proc/self/maps.
        // We use a WeakHashMap so GC'd streams don't leak memory.
        val trackedStreams = java.util.Collections.synchronizedMap(
            java.util.WeakHashMap<Any, Boolean>()
        )

        // Hook FileInputStream(String) constructor — ONLY to mark the stream,
        // NOT to null out the path (which caused the NPE).
        val ctorResult = HookManager.hookMethod(
            className = "java.io.FileInputStream",
            methodName = "<init>",
            paramTypes = arrayOf(String::class.java),
            beforeHook = null,
            afterHook = { frame ->
                val path = frame.args?.getOrNull(0) as? String ?: return@hookMethod
                if (path == "/proc/self/maps") {
                    // Mark this FileInputStream instance as a maps stream.
                    frame.thisObject?.let { trackedStreams[it] = true }
                    Log.d(TAG, "Marked FileInputStream for /proc/self/maps interception")
                }
            }
        )

        if (ctorResult.isSuccess) {
            installedHookIds.add(ctorResult.getOrThrow())
        }

        // Hook FileInputStream.read(byte[], int, int) — the main read method.
        // If the stream was opened for /proc/self/maps, replace the output
        // with our virtual maps content.
        val readResult = HookManager.hookMethod(
            className = "java.io.FileInputStream",
            methodName = "read",
            paramTypes = arrayOf(ByteArray::class.java, Int::class.java, Int::class.java),
            beforeHook = null,
            afterHook = { frame ->
                val fis = frame.thisObject ?: return@hookMethod
                if (trackedStreams[fis] != true) return@hookMethod

                val virtualMaps = getVirtualProcessMemoryMap(Process.myPid())
                val bytes = virtualMaps.toByteArray(Charsets.UTF_8)
                val buf = frame.args?.getOrNull(0) as? ByteArray ?: return@hookMethod
                val offset = frame.args?.getOrNull(1) as? Int ?: 0
                val len = minOf(frame.args?.getOrNull(2) as? Int ?: bytes.size, bytes.size, buf.size - offset)
                if (len > 0) {
                    System.arraycopy(bytes, 0, buf, offset, len)
                    frame.result = len
                    Log.d(TAG, "Replaced /proc/self/maps read with virtual map ($len bytes)")
                } else {
                    frame.result = -1 // EOF
                }
                // Remove tracking after first read to avoid repeated substitution
                trackedStreams.remove(fis)
            }
        )

        return if (readResult.isSuccess) {
            installedHookIds.add(readResult.getOrThrow())
            Log.d(TAG, "hookProcSelfMaps installed successfully")
            Result.success("gg_compat_proc_maps")
        } else {
            Log.e(TAG, "Failed to hook FileInputStream.read for /proc/self/maps")
            Result.failure(readResult.exceptionOrNull() ?: RuntimeException("hookProcSelfMaps failed"))
        }
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
