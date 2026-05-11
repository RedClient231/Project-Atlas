package com.atlas.virtualspace

import android.app.Application
import android.os.Environment
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
 * - Registers a [ProcessLifecycleObserver] to manage the [VirtualEngine]
 *   lifecycle in lockstep with the app's process lifecycle.
 * - Installs an uncaught-exception handler that persists crash stacks
 *   to a local file for later diagnostics.
 *
 * ## Key Fix: DB init moved off the main thread
 * Room database creation (and all subsequent core-component init) is
 * dispatched on [Dispatchers.IO] via [appScope].  Previously this ran
 * synchronously on the main thread inside [onCreate], which caused
 * StrictMode violations and — worse — silently swallowed the
 * "Cannot access database on the main thread" exception, leaving
 * [VirtualPackageManager] permanently uninitialised and making every
 * "Launch" tap a silent no-op.
 */
@HiltAndroidApp
class AtlasApplication : Application() {

    // ─── Application-scoped coroutine scope ──────────────────────────────
    // Supervisor job so individual child failures do not cancel siblings.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ─── Lifecycle Observer ──────────────────────────────────────────────

    private val engineLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            // App came to foreground – engine is kept alive by the service.
        }

        override fun onStop(owner: LifecycleOwner) {
            // App went to background – engine keeps running via foreground service.
        }
    }

    // ─── Application Lifecycle ───────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()

        // 1. Install Timber logging first so everything below can log.
        installTimber()

        // 2. Initialize logcat reporter so crashes are captured to storage.
        AtlasLogcatReporter.initialize(this)

        // 3. Install uncaught-exception handler.
        installCrashHandler()

        // 4. Initialize core singletons ASYNCHRONOUSLY on the IO dispatcher.
        //    CRITICAL FIX: Room DB creation MUST NOT run on the main thread.
        //    Previously this was synchronous in onCreate(), causing the
        //    "Cannot access database on the main thread" exception to be
        //    swallowed, leaving VirtualPackageManager permanently uninitialised.
        appScope.launch {
            initializeCoreComponents()
        }

        // 5. Register process lifecycle observer.
        ProcessLifecycleOwner.get().lifecycle.addObserver(engineLifecycleObserver)

        Timber.i("AtlasApplication initialised — core init dispatched to IO thread")
    }

    // ─── Timber Setup ────────────────────────────────────────────────────

    private fun installTimber() {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(ReleaseTree())
        }
    }

    // ─── Core Component Initialization ───────────────────────────────────

    /**
     * Initialises all core singletons on the IO dispatcher.
     *
     * Order matters:
     * 1. VirtualFileSystem  — required by VirtualPackageManager
     * 2. AppDatabase        — required by VirtualPackageManager
     * 3. VirtualPackageManager — required by VirtualEngine
     * 4. VirtualEngineService  — starts VirtualEngine in its foreground service
     * 5. Shizuku               — optional, non-fatal
     */
    private suspend fun initializeCoreComponents() {
        try {
            // 1. Initialize virtual filesystem.
            val vfsResult = com.atlas.virtualspace.core.fs.VirtualFileSystem.initialize(this)
            if (vfsResult.isFailure) {
                Timber.e(vfsResult.exceptionOrNull(), "VirtualFileSystem initialization failed")
                // Non-fatal: the app can still show the UI and attempt to recover.
            }

            // 2. Create Room database ON THE IO THREAD.
            //    This is the critical fix — Room throws "Cannot access database on
            //    the main thread" if called on Main, which was silently caught
            //    before, leaving VirtualPackageManager uninitialised.
            val database = AppDatabase.create(this)

            // 3. Initialize VirtualPackageManager with the database.
            VirtualPackageManager.initialize(database)
            VirtualPackageManager.setContext(this)

            // 4. Start the virtual engine foreground service.
            //    The service calls VirtualEngine.initialize() in onStartCommand.
            try {
                com.atlas.virtualspace.core.engine.VirtualEngineService.start(this)
            } catch (e: Exception) {
                Timber.w(e, "Failed to start VirtualEngineService — will retry on first launch")
            }

            // 5. Initialize Shizuku (optional — app works without it).
            val shizukuResult = ShizukuIntegration.initialize(this)
            if (shizukuResult.isFailure) {
                Timber.w(shizukuResult.exceptionOrNull(), "Shizuku not available")
            }

            Timber.i(
                "Core components initialized: VFS=%b, DB=OK, Shizuku=%b",
                vfsResult.isSuccess,
                shizukuResult.isSuccess,
            )
        } catch (e: Exception) {
            Timber.e(e, "Critical error during async core component initialization")
        }
    }

    // ─── Engine State ────────────────────────────────────────────────────

    fun isEngineRunning(): Boolean = VirtualEngine.isRunning.value

    // ─── Crash Handler ───────────────────────────────────────────────────

    private val crashLogDir by lazy {
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "AtlasReports/crash_logs"
        ).also { dir -> if (!dir.exists()) dir.mkdirs() }
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try { persistCrashLog(thread, throwable) } catch (_: Exception) {}
            try { AtlasLogcatReporter.reportCrash(thread, throwable) } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun persistCrashLog(thread: Thread, throwable: Throwable) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val logFile = File(crashLogDir, "crash_$timestamp.log")

        val sw = StringWriter()
        val pw = PrintWriter(sw)
        pw.println("=== Atlas Crash Log ===")
        pw.println("Timestamp : $timestamp")
        pw.println("Thread    : ${thread.name} (id=${thread.id})")
        pw.println("PID       : ${Process.myPid()}")
        pw.println("Device    : ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        pw.println("Android   : ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        pw.println("App       : ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        pw.println()
        throwable.printStackTrace(pw)
        throwable.suppressed?.forEach { s -> pw.println("Suppressed:"); s.printStackTrace(pw) }
        var cause = throwable.cause
        while (cause != null) { pw.println("Caused by:"); cause.printStackTrace(pw); cause = cause.cause }
        pw.flush()

        FileOutputStream(logFile).use { it.write(sw.toString().toByteArray(Charsets.UTF_8)) }
        pruneCrashLogs()
    }

    private fun pruneCrashLogs() {
        val logs = crashLogDir.listFiles()
            ?.filter { it.name.startsWith("crash_") && it.name.endsWith(".log") }
            ?.sortedBy { it.lastModified() } ?: return
        if (logs.size > MAX_CRASH_LOGS) {
            logs.take(logs.size - MAX_CRASH_LOGS).forEach { it.delete() }
        }
    }

    // ─── Release Tree ────────────────────────────────────────────────────

    private class ReleaseTree : Timber.Tree() {
        override fun isLoggable(tag: String?, priority: Int) = priority >= android.util.Log.ERROR

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (!isLoggable(tag, priority)) return
            when (priority) {
                android.util.Log.ERROR ->
                    if (t != null) android.util.Log.e(tag, message, t)
                    else android.util.Log.e(tag, message)
                android.util.Log.ASSERT ->
                    if (t != null) android.util.Log.wtf(tag, message, t)
                    else android.util.Log.wtf(tag, message)
            }
        }
    }

    companion object {
        private const val MAX_CRASH_LOGS = 20
    }
}
