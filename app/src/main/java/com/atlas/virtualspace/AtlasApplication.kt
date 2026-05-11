package com.atlas.virtualspace

import android.app.Application
import android.os.Process
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.atlas.virtualspace.core.engine.VirtualEngine
import com.atlas.virtualspace.core.hook.ShizukuIntegration
import com.atlas.virtualspace.core.pm.VirtualPackageManager
import com.atlas.virtualspace.data.database.AppDatabase
import com.atlas.virtualspace.diagnostics.AtlasLogcatReporter
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Application class for Project Atlas.
 *
 * Responsibilities:
 * - Annotated with `@HiltAndroidApp` for dependency injection.
 * - Initialises Timber logging (debug trees in debug builds, a
 *   conservative [ReleaseTree] in release builds).
 * - Registers a [ProcessLifecycleObserver] to initialise and shut down
 *   the [VirtualEngine] in lockstep with the app's process lifecycle.
 * - Installs an uncaught-exception handler that persists crash stacks
 *   to a local file for later diagnostics.
 */
@HiltAndroidApp
class AtlasApplication : Application() {

    // ─── Lifecycle Observer ──────────────────────────────────────

    private val engineLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            // App came to foreground – no action needed; the service
            // manages engine lifecycle.
        }

        override fun onStop(owner: LifecycleOwner) {
            // App went to background – the engine keeps running via
            // the foreground service.
        }
    }

    // ─── Application Lifecycle ───────────────────────────────────

    override fun onCreate() {
        super.onCreate()

        // 1. Install Timber logging.
        installTimber()

        // 2. Initialize the logcat reporter FIRST so that any subsequent
        //    errors or crashes are captured to internal storage.
        AtlasLogcatReporter.initialize(this)

        // 3. Install uncaught-exception handler (uses AtlasLogcatReporter).
        installCrashHandler()

        // 4. Initialize core singletons BEFORE any feature tries to use them.
        //    This prevents "lateinit property has not been initialized" crashes
        //    when the user tries to install an APK before the engine service starts.
        initializeCoreComponents()

        // 5. Register process lifecycle observer.
        ProcessLifecycleOwner.get().lifecycle.addObserver(engineLifecycleObserver)

        Timber.i("AtlasApplication initialised")
    }

    // ─── Timber Setup ────────────────────────────────────────────

    private fun installTimber() {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(ReleaseTree())
        }
    }

    // ─── Core Component Initialization ────────────────────────────

    /**
     * Initializes all core singletons that are required before any feature
     * (install, launch, settings) can work.
     *
     * Previously these were only initialized when [VirtualEngineService]
     * started, which caused crashes when the user tried to install an APK
     * before the service was running.
     *
     * Now they are initialized eagerly in [onCreate] so that the app is
     * always in a usable state.
     */
    private fun initializeCoreComponents() {
        try {
            // Initialize virtual filesystem first — many components depend on it.
            val vfsResult = com.atlas.virtualspace.core.fs.VirtualFileSystem.initialize(this)
            if (vfsResult.isFailure) {
                Timber.e(vfsResult.exceptionOrNull(), "VirtualFileSystem initialization failed in Application.onCreate")
            }

            // Initialize Room database and VirtualPackageManager.
            val database = AppDatabase.create(this)
            VirtualPackageManager.initialize(database)
            VirtualPackageManager.setContext(this)

            // Start the virtual engine foreground service.
            // This ensures the engine is initialized and ready before the user
            // tries to launch any apps. The service keeps the engine alive
            // even when the UI is in the background.
            try {
                com.atlas.virtualspace.core.engine.VirtualEngineService.start(this)
            } catch (e: Exception) {
                Timber.w(e, "Failed to start VirtualEngineService — engine will start on first launch")
            }

            // Initialize Shizuku integration (non-fatal — app works without Shizuku).
            val shizukuResult = ShizukuIntegration.initialize(this)
            if (shizukuResult.isFailure) {
                Timber.w(shizukuResult.exceptionOrNull(), "Shizuku integration not available")
            }

            Timber.i("Core components initialized: VFS=%s, Shizuku=%s",
                vfsResult.isSuccess, shizukuResult.isSuccess)
        } catch (e: Exception) {
            Timber.e(e, "Critical error during core component initialization")
        }
    }

    // ─── Engine State ────────────────────────────────────────────

    /**
     * Returns `true` if the virtual engine is currently initialised and running.
     */
    fun isEngineRunning(): Boolean = VirtualEngine.isRunning.value

    // ─── Crash Handler ───────────────────────────────────────────

    private val crashLogDir by lazy {
        // Use internal storage: /data/data/{pkg}/app_atlas_reports/
        // This is accessible via Settings > Storage and is NOT on SD card.
        File(getDir("atlas_reports", MODE_PRIVATE), "crash_logs").also { dir ->
            if (!dir.exists()) dir.mkdirs()
        }
    }

    /**
     * Installs a custom [Thread.UncaughtExceptionHandler] that persists
     * the full stack trace to a timestamped file under `crash_logs/`
     * before delegating to the original handler (which typically kills
     * the process).
     */
    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                persistCrashLog(thread, throwable)
            } catch (e: Exception) {
                // If we can't write the crash log there's nothing we can do.
            }

            // Also write to the diagnostics reporter (internal storage)
            try {
                AtlasLogcatReporter.reportCrash(thread, throwable)
            } catch (_: Exception) {
                // Best effort
            }

            // Delegate to the original handler so the process still terminates
            // with the standard dialog / reporting flow.
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Writes the crash stack trace to a file named `crash_<timestamp>.log`.
     *
     * Each log entry includes:
     * - Timestamp
     * - Thread name
     * - Full stack trace
     * - Device / build info
     */
    private fun persistCrashLog(thread: Thread, throwable: Throwable) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val logFile = File(crashLogDir, "crash_$timestamp.log")

        val stringWriter = StringWriter()
        val printWriter = PrintWriter(stringWriter)

        printWriter.println("=== Atlas Crash Log ===")
        printWriter.println("Timestamp : $timestamp")
        printWriter.println("Thread    : ${thread.name} (id=${thread.id})")
        printWriter.println("PID       : ${Process.myPid()}")
        printWriter.println("Device    : ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        printWriter.println("Android   : ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        printWriter.println("App       : ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        printWriter.println()

        throwable.printStackTrace(printWriter)

        // Also include any suppressed exceptions.
        throwable.suppressed?.forEach { suppressed ->
            printWriter.println("Suppressed:")
            suppressed.printStackTrace(printWriter)
        }

        // Walk the cause chain.
        var cause = throwable.cause
        while (cause != null) {
            printWriter.println("Caused by:")
            cause.printStackTrace(printWriter)
            cause = cause.cause
        }

        printWriter.flush()

        FileOutputStream(logFile).use { output ->
            output.write(stringWriter.toString().toByteArray(Charsets.UTF_8))
        }

        // Prune old crash logs (keep at most MAX_CRASH_LOGS).
        pruneCrashLogs()
    }

    /**
     * Removes the oldest crash log files if the count exceeds [MAX_CRASH_LOGS].
     */
    private fun pruneCrashLogs() {
        val logs = crashLogDir.listFiles()
            ?.filter { it.name.startsWith("crash_") && it.name.endsWith(".log") }
            ?.sortedBy { it.lastModified() }
            ?: return

        if (logs.size > MAX_CRASH_LOGS) {
            val toDelete = logs.take(logs.size - MAX_CRASH_LOGS)
            toDelete.forEach { file ->
                if (!file.delete()) {
                    Timber.w("Failed to delete old crash log: %s", file.name)
                }
            }
        }
    }

    // ─── Release Tree ────────────────────────────────────────────

    /**
     * A conservative Timber tree for release builds.
     *
     * Only logs **ERROR** and **WTF** (What a Terrible Failure) priorities
     * to avoid leaking sensitive information in production logs.  All other
     * priority levels are silently dropped.
     */
    private class ReleaseTree : Timber.Tree() {

        override fun isLoggable(tag: String?, priority: Int): Boolean {
            return priority >= android.util.Log.ERROR
        }

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (!isLoggable(tag, priority)) return

            // In release builds we still write to the system log so that
            // bug-report captures include the critical messages.
            // We do NOT use Log.v / Log.d / Log.i.
            when (priority) {
                android.util.Log.ERROR -> {
                    if (t != null) {
                        android.util.Log.e(tag, message, t)
                    } else {
                        android.util.Log.e(tag, message)
                    }
                }
                android.util.Log.ASSERT -> {
                    if (t != null) {
                        android.util.Log.wtf(tag, message, t)
                    } else {
                        android.util.Log.wtf(tag, message)
                    }
                }
            }
        }
    }

    companion object {
        private const val MAX_CRASH_LOGS = 20
    }
}
