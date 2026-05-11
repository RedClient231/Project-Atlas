package com.atlas.virtualspace.core.hook

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import rikka.shizuku.Shizuku
import java.lang.reflect.Method
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Shizuku integration for Project Atlas.
 *
 * Shizuku provides elevated (ADB/shell) privileges without requiring root.
 * Atlas uses Shizuku for operations that need system-level access:
 *
 * - **Installing apps** via `pm install` — bypasses the user-confirmation
 *   dialog, allowing silent installs into the virtual space.
 * - **Force-stopping apps** via `am force-stop` — kills virtual app processes
 *   that may be stuck or unresponsive.
 * - **Querying package info** via `pm dump` — retrieves detailed package
 *   metadata that is not available through the standard PackageManager API.
 *
 * ## Lifecycle
 *
 * 1. Call [initialize] once during app startup.
 * 2. Check [isShizukuAvailable] before attempting operations.
 * 3. Call [requestPermission] from an Activity to obtain user consent.
 * 4. Use [executeWithShizuku], [installAppWithShizuku], etc.
 *
 * ## Graceful degradation
 *
 * If Shizuku is not installed or not running, all operations return
 * [Result.failure] with a descriptive error. The app continues to function
 * without Shizuku — it just cannot perform elevated operations.
 */
object ShizukuIntegration {

    private const val TAG = "Atlas:Shizuku"

    /** Shizuku permission request code. */
    private const val SHIZUKU_PERMISSION_REQUEST_CODE = 10042

    /** Timeout for shell command execution via Shizuku (seconds). */
    private const val COMMAND_TIMEOUT_SECONDS = 30L

    /** Whether Shizuku is currently connected and available. */
    private val isAvailable = AtomicBoolean(false)

    /** Whether the user has granted Shizuku permission. */
    private val isPermissionGranted = AtomicBoolean(false)

    /** Listener for Shizuku binder lifecycle events. */
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received — service is available")
        isAvailable.set(true)
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder dead — service is unavailable")
        isAvailable.set(false)
    }

    /** Latch for waiting on permission result from an Activity. */
    private var permissionLatch = CountDownLatch(1)
    private val permissionResult = AtomicReference<Result<Unit>?>(null)

    // ──────────────────────────────────────────────────────────
    //  Initialization
    // ──────────────────────────────────────────────────────────

    /**
     * Initializes the Shizuku integration.
     *
     * Registers binder lifecycle listeners and checks the current
     * Shizuku availability and permission state.
     *
     * Must be called once during app startup (e.g. in [AtlasApplication.onCreate]).
     *
     * @param context Application context.
     * @return [Result.success] if initialization completed (Shizuku may or
     *         may not be available), [Result.failure] on unrecoverable error.
     */
    fun initialize(context: Context): Result<Unit> {
        return runCatching {
            Log.i(TAG, "Initializing Shizuku integration")

            // Register binder lifecycle listeners
            Shizuku.addBinderReceivedListener(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)

            // Register permission result listener — this is CRITICAL for
            // requestPermission() to work. Without it, the CountDownLatch
            // always times out and status stays "unknown".
            val listener = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResults: Int) {
                    this@ShizukuIntegration.onRequestPermissionResult(requestCode, intArrayOf(grantResults))
                }
            }
            permissionListener = listener
            Shizuku.addRequestPermissionResultListener(listener)

            // Check initial state
            isAvailable.set(Shizuku.getBinder() != null)
            isPermissionGranted.set(checkPermission(context))

            Log.i(
                TAG,
                "Shizuku initialized — available: ${isAvailable.get()}, permission: ${isPermissionGranted.get()}"
            )
            Unit
        }.onFailure { e ->
            when (e) {
                is ClassNotFoundException -> {
                    Log.w(TAG, "Shizuku classes not found — Shizuku is not installed")
                    isAvailable.set(false)
                }
                is NoClassDefFoundError -> {
                    Log.w(TAG, "Shizuku runtime not available — Shizuku is not installed")
                    isAvailable.set(false)
                }
                else -> {
                    Log.e(TAG, "Failed to initialize Shizuku integration", e)
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────
    //  Availability & Permission
    // ──────────────────────────────────────────────────────────

    /**
     * Returns `true` if Shizuku service is installed, running, and
     * the binder is accessible.
     */
    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.getBinder() != null
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Returns `true` if the user has granted Shizuku permission to this app.
     */
    fun isShizukuPermissionGranted(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            isPermissionGranted.get()
        }
    }

    /**
     * Requests Shizuku permission from the user.
     *
     * This must be called from an [Activity] context because it may launch
     * a permission request dialog. If Shizuku is not available, returns
     * a failure immediately.
     *
     * @param activity The activity to use for the permission request.
     * @return [Result.success] if permission was granted,
     *         [Result.failure] if denied or Shizuku is unavailable.
     */
    fun requestPermission(activity: Activity): Result<Unit> {
        if (!isShizukuAvailable()) {
            val msg = "Shizuku is not available — install and start Shizuku first"
            Log.w(TAG, msg)
            return Result.failure(IllegalStateException(msg))
        }

        if (isShizukuPermissionGranted()) {
            Log.d(TAG, "Shizuku permission already granted")
            isPermissionGranted.set(true)
            return Result.success(Unit)
        }

        return try {
            // Reset latch for a fresh wait
            permissionLatch = CountDownLatch(1)
            permissionResult.set(null)

            Log.i(TAG, "Requesting Shizuku permission")
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)

            // Wait for the result callback (with timeout)
            val granted = permissionLatch.await(60, TimeUnit.SECONDS)
            if (!granted) {
                Log.w(TAG, "Shizuku permission request timed out")
                return Result.failure(RuntimeException("Permission request timed out"))
            }

            val result = permissionResult.get()
                ?: return Result.failure(RuntimeException("No permission result received"))

            result.onSuccess {
                isPermissionGranted.set(true)
                Log.i(TAG, "Shizuku permission granted")
            }.onFailure {
                isPermissionGranted.set(false)
                Log.w(TAG, "Shizuku permission denied: ${it.message}")
            }

            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request Shizuku permission", e)
            Result.failure(e)
        }
    }

    @Volatile
    private var permissionListener: Shizuku.OnRequestPermissionResultListener? = null

    /**
     * Callback for Shizuku permission request results.
     */
    private fun onRequestPermissionResult(requestCode: Int, grantResults: IntArray) {
        if (requestCode != SHIZUKU_PERMISSION_REQUEST_CODE) return

        val grantResult = grantResults.firstOrNull() ?: PackageManager.PERMISSION_DENIED
        val result = if (grantResult == PackageManager.PERMISSION_GRANTED) {
            Result.success(Unit)
        } else {
            Result.failure(SecurityException("Shizuku permission denied by user"))
        }

        permissionResult.set(result)
        permissionLatch.countDown()
    }

    // ──────────────────────────────────────────────────────────
    //  Shell command execution
    // ──────────────────────────────────────────────────────────

    /**
     * Executes a shell command via Shizuku.
     *
     * Uses [ShizukuService.newProcess] to run the command with shell (ADB) privileges.
     * The command's stdout is captured and returned as a string.
     *
     * @param command The shell command to execute (e.g. `"pm list packages"`).
     * @return [Result.success] with the command's stdout output,
     *         [Result.failure] if Shizuku is unavailable, permission is not
     *         granted, or the command fails.
     */
    fun executeWithShizuku(command: String): Result<String> {
        if (!isShizukuAvailable()) {
            return Result.failure(IllegalStateException("Shizuku is not available"))
        }

        if (!isShizukuPermissionGranted()) {
            return Result.failure(SecurityException("Shizuku permission not granted"))
        }

        return runCatching {
            Log.d(TAG, "Executing via Shizuku: $command")

            val process = invokeNewProcess(arrayOf("sh", "-c", command), null, null)

            val stdout = StringBuilder()
            val stderr = StringBuilder()

            val stdoutReader = BufferedReader(InputStreamReader(process.inputStream))
            val stderrReader = BufferedReader(InputStreamReader(process.errorStream))

            // Read stdout in a separate thread to avoid deadlock
            val stdoutThread = Thread {
                try {
                    var line: String?
                    while (stdoutReader.readLine().also { line = it } != null) {
                        stdout.appendLine(line)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error reading stdout from Shizuku process", e)
                }
            }

            val stderrThread = Thread {
                try {
                    var line: String?
                    while (stderrReader.readLine().also { line = it } != null) {
                        stderr.appendLine(line)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error reading stderr from Shizuku process", e)
                }
            }

            stdoutThread.start()
            stderrThread.start()

            // Wait for the process to complete with timeout
            val completed = process.waitFor()
            stdoutThread.join(COMMAND_TIMEOUT_SECONDS * 1000)
            stderrThread.join(COMMAND_TIMEOUT_SECONDS * 1000)

            stdoutReader.close()
            stderrReader.close()

            val output = stdout.toString().trim()
            val errors = stderr.toString().trim()

            if (completed != 0) {
                Log.w(TAG, "Shizuku command exited with code $completed: $command")
                if (errors.isNotEmpty()) {
                    Log.w(TAG, "stderr: $errors")
                }
                // Return the output even on non-zero exit — some commands
                // produce useful output before exiting with an error.
                if (output.isNotEmpty()) {
                    return Result.success(output)
                }
                throw RuntimeException("Command failed (exit $completed): $errors")
            }

            Log.d(TAG, "Shizuku command completed successfully (${output.length} chars)")
            output
        }.onFailure { e ->
            when (e) {
                is RemoteException ->
                    Log.e(TAG, "Shizuku remote exception while executing: $command", e)
                is SecurityException ->
                    Log.e(TAG, "Shizuku security exception — permission may have been revoked", e)
                else ->
                    Log.e(TAG, "Failed to execute Shizuku command: $command", e)
            }
        }
    }

    // ──────────────────────────────────────────────────────────

    /**
     * Installs an APK file using `pm install` via Shizuku.
     *
     * This performs a system-level install (no user confirmation dialog),
     * which is useful for installing apps into the virtual space silently.
     *
     * The APK must be accessible from the shell's perspective — typically
     * this means it should be in a world-readable location or in the app's
     * own files directory (with `cp` to a temporary location first).
     *
     * @param apkPath Absolute path to the APK file on the device filesystem.
     * @return [Result.success] with `true` if the install succeeded,
     *         [Result.failure] if Shizuku is unavailable or the install failed.
     */
    fun installAppWithShizuku(apkPath: String): Result<Boolean> {
        if (!isShizukuAvailable()) {
            return Result.failure(IllegalStateException("Shizuku is not available"))
        }

        if (!isShizukuPermissionGranted()) {
            return Result.failure(SecurityException("Shizuku permission not granted"))
        }

        return runCatching {
            Log.i(TAG, "Installing APK via Shizuku: $apkPath")

            // Copy the APK to a temporary world-readable location first,
            // since `pm install` runs as shell user and may not have access
            // to the app's private directory.
            val tmpApkPath = "/data/local/tmp/atlas_install_${System.currentTimeMillis()}.apk"

            // Copy to tmp
            val copyResult = executeWithShizuku("cp \"$apkPath\" \"$tmpApkPath\" && chmod 644 \"$tmpApkPath\"")
            if (copyResult.isFailure) {
                Log.w(TAG, "Failed to copy APK to tmp — trying direct install")
                // Try direct install without copying
                val directResult = executeWithShizuku("pm install -r -t \"$apkPath\"")
                if (directResult.isFailure) {
                    return Result.failure(
                        directResult.exceptionOrNull()
                            ?: RuntimeException("Direct install failed")
                    )
                }
                val success = directResult.getOrDefault("").contains("Success", ignoreCase = true)
                return Result.success(success)
            }

            // Install from tmp
            val installResult = executeWithShizuku("pm install -r -t \"$tmpApkPath\"")

            // Clean up tmp file
            executeWithShizuku("rm -f \"$tmpApkPath\"")

            if (installResult.isFailure) {
                return Result.failure(
                    installResult.exceptionOrNull()
                        ?: RuntimeException("pm install failed")
                )
            }

            val output = installResult.getOrDefault("")
            val success = output.contains("Success", ignoreCase = true)

            if (success) {
                Log.i(TAG, "APK installed successfully via Shizuku: $apkPath")
            } else {
                Log.w(TAG, "APK install may have failed — output: $output")
            }

            success
        }.onFailure { e ->
            Log.e(TAG, "Failed to install APK via Shizuku: $apkPath", e)
        }
    }

    /**
     * Force-stops a running application via `am force-stop` through Shizuku.
     *
     * This is more reliable than the standard ActivityManager API because
     * it uses ADB-level privileges to ensure the process is killed.
     *
     * @param packageName The package name of the app to force-stop.
     * @return [Result.success] if the force-stop command was sent,
     *         [Result.failure] if Shizuku is unavailable or the command failed.
     */
    fun forceStopWithShizuku(packageName: String): Result<Unit> {
        if (!isShizukuAvailable()) {
            return Result.failure(IllegalStateException("Shizuku is not available"))
        }

        if (!isShizukuPermissionGranted()) {
            return Result.failure(SecurityException("Shizuku permission not granted"))
        }

        return runCatching {
            Log.i(TAG, "Force-stopping app via Shizuku: $packageName")

            val result = executeWithShizuku("am force-stop $packageName")
            if (result.isFailure) {
                throw result.exceptionOrNull()
                    ?: RuntimeException("am force-stop failed for $packageName")
            }

            Log.i(TAG, "Force-stop command sent successfully for: $packageName")
            Unit
        }.onFailure { e ->
            Log.e(TAG, "Failed to force-stop app via Shizuku: $packageName", e)
        }
    }

    /**
     * Retrieves detailed package information via `pm dump` through Shizuku.
     *
     * This returns much more information than the standard PackageManager
     * APIs, including granted permissions, signatures, component states,
     * and more.
     *
     * @param packageName The package name to dump.
     * @return [Result.success] with the raw dump output as a string,
     *         [Result.failure] if Shizuku is unavailable or the command failed.
     */
    fun getPackageInfoWithShizuku(packageName: String): Result<String> {
        if (!isShizukuAvailable()) {
            return Result.failure(IllegalStateException("Shizuku is not available"))
        }

        if (!isShizukuPermissionGranted()) {
            return Result.failure(SecurityException("Shizuku permission not granted"))
        }

        return runCatching {
            Log.d(TAG, "Getting package info via Shizuku: $packageName")

            val result = executeWithShizuku("pm dump $packageName")
            if (result.isFailure) {
                throw result.exceptionOrNull()
                    ?: RuntimeException("pm dump failed for $packageName")
            }

            val output = result.getOrDefault("")
            if (output.isBlank()) {
                Log.w(TAG, "pm dump returned empty output for: $packageName")
            }

            Log.d(TAG, "Retrieved package info for $packageName (${output.length} chars)")
            output
        }.onFailure { e ->
            Log.e(TAG, "Failed to get package info via Shizuku: $packageName", e)
            throw e
        }
    }

    // ──────────────────────────────────────────────────────────
    //  Cleanup
    // ──────────────────────────────────────────────────────────

    /**
     * Returns the current Shizuku status as a string suitable for UI display.
     *
     * Possible values:
     * - "running" — Shizuku is available AND permission is granted
     * - "available" — Shizuku is running but permission not yet granted
     * - "not_installed" — Shizuku is not installed or not running
     * - "unknown" — Could not determine status (error)
     */
    fun getShizukuStatus(): String {
        return try {
            when {
                Shizuku.getBinder() != null && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> "running"
                Shizuku.getBinder() != null -> "available"
                else -> "not_installed"
            }
        } catch (e: Exception) {
            when (e) {
                is ClassNotFoundException, is NoClassDefFoundError -> "not_installed"
                else -> "unknown"
            }
        }
    }

    /**
     * Removes all Shizuku listeners and cleans up resources.
     *
     * Call this during app shutdown to avoid leaking listeners.
     */
    fun cleanup() {
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            permissionListener?.let {
                Shizuku.removeRequestPermissionResultListener(it)
            }
            permissionListener = null
            Log.i(TAG, "Shizuku integration cleaned up")
        } catch (e: Exception) {
            Log.w(TAG, "Error during Shizuku cleanup", e)
        }
    }

    // ──────────────────────────────────────────────────────────
    //  Internal helpers
    // ──────────────────────────────────────────────────────────

    /**
     * Invokes Shizuku.newProcess() via reflection since it is private in the API.
     */
    private fun invokeNewProcess(cmd: Array<String>, env: Array<String>?, dir: String?): Process {
        val method: Method = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        method.isAccessible = true
        return method.invoke(null, cmd, env, dir) as Process
    }

    /**
     * Checks whether Shizuku permission is currently granted.
     */
    private fun checkPermission(context: Context): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            // Shizuku may not be installed — treat as not granted
            false
        }
    }
}
