package com.atlas.virtualspace.core.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Foreground service that keeps the [VirtualEngine] alive while virtual
 * apps are running.
 *
 * Lifecycle:
 * - **onStartCommand** – initialises [VirtualEngine] if not already running,
 *   posts the foreground notification, and starts a periodic refresh coroutine.
 * - **onDestroy** – gracefully shuts down the engine and cancels all coroutines.
 *
 * The service also exposes a [VirtualEngineBinder] so that bound clients
 * (e.g. the main UI) can query engine state without going through
 * broadcasts or content providers.
 */
class VirtualEngineService : LifecycleService() {

    // ─── Binder for IPC ──────────────────────────────────────────

    /**
     * Binder exposed to bound clients.
     *
     * Provides direct access to the [VirtualEngine] singleton for querying
     * state such as running processes and installed apps.
     */
    inner class VirtualEngineBinder : Binder() {
        val service: VirtualEngineService
            get() = this@VirtualEngineService

        val isEngineRunning: Boolean
            get() = VirtualEngine.isRunning.value

        val runningProcessCount: Int
            get() = VirtualEngine.getRunningProcesses().size

        val installedAppCount: Int
            get() = VirtualEngine.installedApps.value.size
    }

    private val binder = VirtualEngineBinder()

    // ─── Notification ────────────────────────────────────────────

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    // ─── Periodic refresh ────────────────────────────────────────

    private var refreshJob: Job? = null

    // ─── Service Lifecycle ───────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Timber.i("VirtualEngineService created")
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        Timber.d("Client bound to VirtualEngineService")
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // Handle the stop-engine action from the notification.
        if (intent?.action == ACTION_STOP_ENGINE) {
            Timber.i("Stop engine action received – shutting down")
            stopSelf()
            return START_NOT_STICKY
        }

        // Initialise the engine if this is the first start.
        if (!VirtualEngine.isRunning.value) {
            val result = VirtualEngine.initialize(applicationContext)
            if (result.isFailure) {
                Timber.e(result.exceptionOrNull(), "VirtualEngine failed to initialise")
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // Post foreground notification immediately.
        val notification = buildForegroundNotification()
        startForeground(NOTIFICATION_ID, notification)

        // Start periodic state refresh.
        startPeriodicRefresh()

        Timber.i("VirtualEngineService started (START_STICKY)")
        return START_STICKY
    }

    override fun onDestroy() {
        // Cancel periodic refresh first.
        refreshJob?.cancel()
        refreshJob = null

        // Gracefully shut down the engine.
        try {
            VirtualEngine.shutdown()
        } catch (e: Exception) {
            Timber.e(e, "Error shutting down VirtualEngine in service onDestroy")
        }

        super.onDestroy()
        Timber.i("VirtualEngineService destroyed")
    }

    // ─── Notification Channel ────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = CHANNEL_DESCRIPTION
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    // ─── Foreground Notification ─────────────────────────────────

    private fun buildForegroundNotification(): Notification {
        val appCount = VirtualEngine.installedApps.value.size
        val processCount = VirtualEngine.getRunningProcesses().size

        // Create a pending intent that opens the main activity.
        val contentIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        // Build a stop action.
        val stopIntent = Intent(this, VirtualEngineService::class.java).apply {
            action = ACTION_STOP_ENGINE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            REQUEST_CODE_STOP,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(NOTIFICATION_TITLE)
            .setContentText(
                "$appCount app${if (appCount != 1) "s" else ""} installed · " +
                "$processCount running"
            )
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(contentIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                ACTION_STOP_LABEL,
                stopPendingIntent
            )
            .setOngoing(true)
            .setSilent(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /**
     * Updates the foreground notification with current engine state.
     */
    private fun updateNotification() {
        try {
            val notification = buildForegroundNotification()
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Timber.w(e, "Failed to update engine notification")
        }
    }

    // ─── Periodic Refresh ────────────────────────────────────────

    /**
     * Launches a coroutine that refreshes process stats and updates the
     * notification every [REFRESH_INTERVAL_MS] milliseconds.
     */
    private fun startPeriodicRefresh() {
        if (refreshJob?.isActive == true) return

        refreshJob = lifecycleScope.launch(Dispatchers.Default) {
            while (isActive) {
                try {
                    VirtualEngine.refreshProcessStats()
                    updateNotification()
                } catch (e: Exception) {
                    Timber.w(e, "Error during periodic engine refresh")
                }
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    // ─── Handle custom actions ───────────────────────────────────

    override fun onTaskRemoved(rootIntent: Intent?) {
        Timber.w("Task removed – keeping engine service alive")
        // Restart the service so the engine keeps running even if the
        // UI task is swiped away.
        val restartIntent = Intent(applicationContext, VirtualEngineService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(restartIntent)
        } else {
            startService(restartIntent)
        }
        super.onTaskRemoved(rootIntent)
    }

    // ─── Companion ───────────────────────────────────────────────

    companion object {
        private const val CHANNEL_ID = "atlas_engine"
        private const val CHANNEL_NAME = "Atlas Virtual Engine"
        private const val CHANNEL_DESCRIPTION = "Keeps the Atlas virtual engine running"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_TITLE = "Atlas Virtual Engine"
        private const val ACTION_STOP_ENGINE = "com.atlas.virtualspace.STOP_ENGINE"
        private const val ACTION_STOP_LABEL = "Stop Engine"
        private const val REQUEST_CODE_STOP = 2001
        private const val REFRESH_INTERVAL_MS = 5_000L

        /**
         * Convenience method to start the engine foreground service.
         */
        fun start(context: Context) {
            val intent = Intent(context, VirtualEngineService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Convenience method to stop the engine foreground service.
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, VirtualEngineService::class.java))
        }
    }
}
