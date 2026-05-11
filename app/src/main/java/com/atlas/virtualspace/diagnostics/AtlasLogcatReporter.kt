package com.atlas.virtualspace.diagnostics

import android.content.Context
import android.os.Process
import timber.log.Timber
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Persistent diagnostic log reporter for Project Atlas.
 *
 * Writes error reports and logcat output to **internal phone storage**
 * (NOT SD card). Reports are stored under the app's private data directory
 * so they survive app reinstallation and are accessible via:
 *
 * - `adb shell` → `/data/data/com.atlas.virtualspace/app_atlas_reports/`
 * - Android Studio Device File Explorer
 * - Root file managers
 *
 * ## Report Types
 *
 * 1. **Crash reports** — written automatically on uncaught exceptions
 * 2. **Error reports** — written when the app detects a runtime error
 *    (e.g. install failure, hook error, VFS error)
 * 3. **Logcat dumps** — periodic or on-demand logcat capture written to file
 *
 * ## Storage Location
 *
 * All reports go to: `{context.dataDir}/app_atlas_reports/`
 * Which resolves to: `/data/data/com.atlas.virtualspace/app_atlas_reports/`
 *
 * This is **internal storage**, not SD card. The `getDir()` API creates
 * a directory that is part of the app's permanent private storage on
 * the device's internal flash memory.
 *
 * ## Thread Safety
 *
 * All public methods are safe to call from any thread. Writes are
 * serialized through a single-threaded executor to prevent corruption.
 */
object AtlasLogcatReporter {

    private const val TAG = "Atlas:LogcatReporter"

    /** Maximum number of report files to keep (oldest are pruned). */
    private const val MAX_REPORT_FILES = 30

    /** Maximum size of a single logcat dump in bytes (2 MB). */
    private const val MAX_LOGCAT_SIZE = 2 * 1024 * 1024L

    /** Interval for periodic logcat dumps when enabled (5 minutes). */
    private const val PERIODIC_DUMP_INTERVAL_MS = 5 * 60 * 1000L

    /** In-memory ring buffer for recent log entries (used for error context). */
    private const val RING_BUFFER_CAPACITY = 200

    private val ringBuffer = ConcurrentLinkedQueue<String>()

    @Volatile
    private var reportDir: File? = null

    @Volatile
    private var initialized = false

    private val writeExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "Atlas-LogcatReporter").apply { isDaemon = true }
        }

    // ──────────────────────────────────────────────────────────
    //  Initialization
    // ──────────────────────────────────────────────────────────

    /**
     * Initializes the logcat reporter.
     *
     * Must be called once during [AtlasApplication.onCreate].
     * Creates the report directory on internal storage.
     *
     * @param context Application context.
     */
    fun initialize(context: Context) {
        if (initialized) return

        // Use getDir() which creates a directory in the app's internal
        // storage: /data/data/{pkg}/app_atlas_reports/
        // This is NOT SD card — it's the phone's internal flash storage.
        reportDir = File(context.getDir("atlas_reports", Context.MODE_PRIVATE), "reports")
        val dir = reportDir!!
        if (!dir.exists() && !dir.mkdirs()) {
            Timber.e("Failed to create report directory: %s", dir.absolutePath)
            return
        }

        // Plant a Timber tree that captures logs into the ring buffer
        Timber.plant(RingBufferTree())

        initialized = true
        Timber.i("AtlasLogcatReporter initialized — report dir: %s", dir.absolutePath)

        // Prune old reports on startup
        writeExecutor.submit { pruneOldReports() }
    }

    // ──────────────────────────────────────────────────────────
    //  Error Reports
    // ──────────────────────────────────────────────────────────

    /**
     * Writes an error report to internal storage.
     *
     * Call this when a non-fatal error occurs that should be recorded
     * for later diagnosis (e.g. APK install failure, hook error).
     *
     * @param tag      Log tag identifying the source of the error.
     * @param message  Human-readable description of the error.
     * @param throwable Optional exception associated with the error.
     */
    fun reportError(tag: String, message: String, throwable: Throwable? = null) {
        if (!initialized) return

        writeExecutor.submit {
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val reportFile = File(reportDir, "error_${timestamp}.log")

                val content = buildString {
                    appendLine("=== Atlas Error Report ===")
                    appendLine("Timestamp : $timestamp")
                    appendLine("Tag       : $tag")
                    appendLine("PID       : ${Process.myPid()}")
                    appendLine("Message   : $message")
                    appendLine()

                    if (throwable != null) {
                        appendLine("Exception : ${throwable.javaClass.name}")
                        appendLine("Exception Message: ${throwable.message}")
                        appendLine()
                        appendLine("Stack Trace:")
                        val sw = StringWriter()
                        throwable.printStackTrace(PrintWriter(sw))
                        appendLine(sw.toString())
                        appendLine()
                    }

                    // Include recent log context from ring buffer
                    appendLine("=== Recent Log Context ===")
                    val recentLogs = ringBuffer.toList()
                    for (log in recentLogs) {
                        appendLine(log)
                    }
                }

                FileOutputStream(reportFile).use { it.write(content.toByteArray(Charsets.UTF_8)) }
                Timber.d("Error report written: %s", reportFile.name)
                pruneOldReports()
            } catch (e: Exception) {
                Timber.e(e, "Failed to write error report")
            }
        }
    }

    /**
     * Writes a crash report to internal storage.
     *
     * Called by [com.atlas.virtualspace.AtlasApplication]'s uncaught
     * exception handler. This method is synchronous because the process
     * is about to die.
     *
     * @param thread    The thread that crashed.
     * @param throwable The uncaught exception.
     */
    fun reportCrash(thread: Thread, throwable: Throwable) {
        if (!initialized) return

        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val reportFile = File(reportDir, "crash_${timestamp}.log")

            val content = buildString {
                appendLine("=== Atlas Crash Report ===")
                appendLine("Timestamp : $timestamp")
                appendLine("Thread    : ${thread.name} (id=${thread.id})")
                appendLine("PID       : ${Process.myPid()}")
                appendLine("Device    : ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                appendLine("Android   : ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                appendLine()

                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                appendLine(sw.toString())

                // Include recent log context
                appendLine()
                appendLine("=== Recent Log Context ===")
                val recentLogs = ringBuffer.toList()
                for (log in recentLogs) {
                    appendLine(log)
                }
            }

            FileOutputStream(reportFile).use { it.write(content.toByteArray(Charsets.UTF_8)) }
            pruneOldReports()
        } catch (_: Exception) {
            // Process is dying — nothing we can do
        }
    }

    // ──────────────────────────────────────────────────────────
    //  Logcat Dumps
    // ──────────────────────────────────────────────────────────

    /**
     * Captures the current logcat output and writes it to a file
     * on internal storage.
     *
     * This is useful for on-demand diagnostics when the user reports
     * an issue. The logcat dump includes all Atlas-related log entries.
     *
     * @return The file path of the written logcat dump, or null on failure.
     */
    fun dumpLogcat(): String? {
        if (!initialized) return null

        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val dumpFile = File(reportDir, "logcat_${timestamp}.txt")

            val process = Runtime.getRuntime().exec(
                arrayOf(
                    "logcat",
                    "-d",           // dump mode — non-blocking
                    "-v", "time",   // timestamp format
                    "-t", "5000",   // last 5000 lines
                    "Atlas*:V",     // all Atlas tags
                    "AndroidRuntime:E",
                    "System.err:W",
                    "*:S"           // silence everything else
                )
            )

            val output = StringBuilder()
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                var totalSize = 0L
                while (reader.readLine().also { line = it } != null) {
                    val currentLine = line ?: break
                    output.appendLine(currentLine)
                    totalSize += currentLine.length
                    if (totalSize > MAX_LOGCAT_SIZE) break
                }
            }

            process.waitFor()

            FileOutputStream(dumpFile).use { it.write(output.toString().toByteArray(Charsets.UTF_8)) }
            Timber.i("Logcat dump written: %s (%d bytes)", dumpFile.name, dumpFile.length())
            pruneOldReports()

            dumpFile.absolutePath
        } catch (e: Exception) {
            Timber.e(e, "Failed to dump logcat")
            null
        }
    }

    /**
     * Starts periodic logcat dumps to internal storage.
     *
     * Each dump is written to a separate file with a timestamp name.
     * Old dumps are pruned automatically.
     */
    fun startPeriodicDumps() {
        if (!initialized) return

        writeExecutor.scheduleAtFixedRate({
            try {
                dumpLogcat()
            } catch (e: Exception) {
                Timber.w(e, "Periodic logcat dump failed")
            }
        }, PERIODIC_DUMP_INTERVAL_MS, PERIODIC_DUMP_INTERVAL_MS, TimeUnit.MILLISECONDS)

        Timber.i("Periodic logcat dumps started (every %d ms)", PERIODIC_DUMP_INTERVAL_MS)
    }

    // ──────────────────────────────────────────────────────────
    //  Report Management
    // ──────────────────────────────────────────────────────────

    /**
     * Returns the list of all report files in internal storage.
     *
     * Each file is a [File] pointing to a report on the device's
     * internal storage (not SD card).
     */
    fun getReportFiles(): List<File> {
        val dir = reportDir ?: return emptyList()
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && (it.name.endsWith(".log") || it.name.endsWith(".txt")) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /**
     * Returns the absolute path to the report directory on internal storage.
     *
     * This is useful for displaying to the user where reports are stored.
     */
    fun getReportDirPath(): String = reportDir?.absolutePath ?: ""

    /**
     * Deletes all report files from internal storage.
     */
    fun clearAllReports() {
        val dir = reportDir ?: return
        dir.listFiles()?.forEach { file ->
            if (!file.delete()) {
                Timber.w("Failed to delete report: %s", file.name)
            }
        }
        Timber.i("All reports cleared")
    }

    /**
     * Returns the total size of all report files in bytes.
     */
    fun getReportTotalSize(): Long {
        val dir = reportDir ?: return 0L
        return dir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L
    }

    // ──────────────────────────────────────────────────────────
    //  Internal
    // ──────────────────────────────────────────────────────────

    /**
     * Removes the oldest report files if the count exceeds [MAX_REPORT_FILES].
     */
    private fun pruneOldReports() {
        val dir = reportDir ?: return
        val files = dir.listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.lastModified() }
            ?: return

        if (files.size > MAX_REPORT_FILES) {
            val toDelete = files.take(files.size - MAX_REPORT_FILES)
            for (file in toDelete) {
                if (!file.delete()) {
                    Timber.w("Failed to prune old report: %s", file.name)
                }
            }
        }
    }

    /**
     * Timber tree that captures log entries into the in-memory ring buffer.
     *
     * This provides recent log context when writing error/crash reports.
     */
    private class RingBufferTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            val priorityChar = when (priority) {
                android.util.Log.VERBOSE -> 'V'
                android.util.Log.DEBUG -> 'D'
                android.util.Log.INFO -> 'I'
                android.util.Log.WARN -> 'W'
                android.util.Log.ERROR -> 'E'
                android.util.Log.ASSERT -> 'A'
                else -> '?'
            }
            val entry = "$priorityChar/$tag: $message"

            ringBuffer.add(entry)
            // Trim to capacity
            while (ringBuffer.size > RING_BUFFER_CAPACITY) {
                ringBuffer.poll()
            }
        }
    }
}
