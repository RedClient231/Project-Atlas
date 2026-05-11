package com.atlas.virtualspace.feature.logcat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.atlas.virtualspace.R
import com.atlas.virtualspace.data.database.AppDatabase
import com.atlas.virtualspace.data.database.AppLogEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that continuously captures logcat output and
 * persists it into the [AppLogEntry] database.
 *
 * The service runs a `logcat` subprocess whose output is parsed line
 * by line. Each parsed line is converted to an [AppLogEntry] and
 * inserted via [com.atlas.virtualspace.data.database.AppLogDao].
 *
 * The service creates a persistent notification on the
 * "atlas_logcat" channel (required for foreground services on API 26+).
 */
class LogcatService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var logcatProcess: Process? = null

    // ─── Service lifecycle ────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        Timber.i("LogcatService created and promoted to foreground")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CAPTURE -> startCapture()
            ACTION_STOP_CAPTURE -> stopCapture()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopCapture()
        serviceScope.cancel()
        super.onDestroy()
        Timber.i("LogcatService destroyed")
    }

    // ─── Capture control ──────────────────────────────────────────────────

    private fun startCapture() {
        if (logcatProcess != null) {
            Timber.w("Logcat capture already running")
            return
        }

        serviceScope.launch {
            try {
                // Clear previous logcat buffer first to avoid duplicates
                val clearProcess = Runtime.getRuntime().exec(arrayOf("logcat", "-c"))
                clearProcess.waitFor()

                // Start reading logcat
                logcatProcess = Runtime.getRuntime().exec(arrayOf("logcat", "-v", "threadtime"))
                val reader = BufferedReader(
                    InputStreamReader(logcatProcess!!.inputStream),
                    BUFFER_SIZE,
                )

                val database = AppDatabase.create(this@LogcatService)
                val dao = database.appLogDao()
                val packageNameFilter = getPackageNameFilter()

                var line: String?
                while (isActive) {
                    line = reader.readLine() ?: break
                    if (line.isBlank()) continue

                    val entry = parseLogcatLine(line) ?: continue

                    // Apply package filter if set
                    if (packageNameFilter != null && entry.packageName != packageNameFilter) {
                        // If no specific package, use tag-based filtering
                        continue
                    }

                    dao.insert(entry)
                }

                reader.close()
            } catch (e: CancellationException) {
                // Normal shutdown
            } catch (e: Exception) {
                Timber.e(e, "Logcat capture failed")
            } finally {
                logcatProcess = null
            }
        }

        Timber.i("Logcat capture started")
    }

    private fun stopCapture() {
        logcatProcess?.let {
            it.destroy()
            logcatProcess = null
        }
        Timber.i("Logcat capture stopped")
    }

    // ─── Logcat line parsing ──────────────────────────────────────────────

    /**
     * Parses a logcat `-v threadtime` line into an [AppLogEntry].
     *
     * Expected format: `MM-DD HH:MM:SS.mmm PID TID LEVEL TAG: MESSAGE`
     * Example: `03-15 14:22:01.234  1234  5678 I ActivityManager: App started`
     */
    private fun parseLogcatLine(line: String): AppLogEntry? {
        return try {
            // Minimum valid line length
            if (line.length < 20) return null

            // Date/time: MM-DD HH:MM:SS.mmm
            val dateStr = line.substring(0, 18).trim()
            val timestamp = parseLogcatTimestamp(dateStr)

            // Remaining: PID TID LEVEL TAG: MESSAGE
            val remainder = line.substring(19).trim()
            val parts = remainder.split(Regex("\\s+"), limit = 4)
            if (parts.size < 4) return null

            val pid = parts[0].toIntOrNull() ?: return null
            val tid = parts[1].toIntOrNull() ?: return null
            val levelChar = parts[2]
            val tagMessage = parts[3]

            val level = when (levelChar) {
                "V" -> android.util.Log.VERBOSE
                "D" -> android.util.Log.DEBUG
                "I" -> android.util.Log.INFO
                "W" -> android.util.Log.WARN
                "E" -> android.util.Log.ERROR
                "F" -> android.util.Log.ASSERT
                else -> return null
            }

            val colonIdx = tagMessage.indexOf(':')
            val (tag, message) = if (colonIdx > 0) {
                tagMessage.substring(0, colonIdx).trim() to
                        tagMessage.substring(colonIdx + 1).trim()
            } else {
                tagMessage to ""
            }

            AppLogEntry(
                packageName = determinePackageName(pid),
                level = level,
                tag = tag,
                message = message,
                timestamp = timestamp,
                threadName = tid.toString(),
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Converts a logcat date string (MM-DD HH:MM:SS.mmm) to epoch millis.
     */
    private fun parseLogcatTimestamp(dateStr: String): Long {
        return try {
            val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            val full = "$year-$dateStr"
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
            sdf.parse(full)?.time ?: System.currentTimeMillis()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }

    /**
     * Attempts to map a PID to a package name using /proc.
     */
    private fun determinePackageName(pid: Int): String {
        return try {
            val cmdline = java.io.File("/proc/$pid/cmdline").readText().trim('\u0000')
            if (cmdline.isNotBlank()) cmdline else pid.toString()
        } catch (_: Exception) {
            pid.toString()
        }
    }

    private fun getPackageNameFilter(): String? {
        // Could be extended to read from intent extras / shared prefs
        return null
    }

    // ─── Notification ─────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.logcat_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.logcat_channel_description)
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.logcat_notification_title))
                .setContentText(getString(R.string.logcat_notification_text))
                .setSmallIcon(R.drawable.ic_logcat_notification)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            NotificationCompat.Builder(this)
                .setContentTitle(getString(R.string.logcat_notification_title))
                .setContentText(getString(R.string.logcat_notification_text))
                .setSmallIcon(R.drawable.ic_logcat_notification)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }

    companion object {
        private const val CHANNEL_ID = "atlas_logcat"
        private const val NOTIFICATION_ID = 2001
        private const val BUFFER_SIZE = 8192

        const val ACTION_START_CAPTURE = "com.atlas.virtualspace.action.START_LOGCAT"
        const val ACTION_STOP_CAPTURE = "com.atlas.virtualspace.action.STOP_LOGCAT"

        fun start(context: Context) {
            val intent = Intent(context, LogcatService::class.java).apply {
                action = ACTION_START_CAPTURE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, LogcatService::class.java).apply {
                action = ACTION_STOP_CAPTURE
            }
            context.startService(intent)
        }
    }
}
