package com.atlas.virtualspace.core.ipc

import android.os.Bundle
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import com.atlas.virtualspace.core.engine.ProcessState
import com.atlas.virtualspace.core.engine.VirtualEngine
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages the connection to a single virtual app process.
 *
 * Each [ProcessConnector] is responsible for:
 * - Setting up Binder death notifications so the host is informed when the
 *   virtual process dies unexpectedly.
 * - Sending commands to the virtual process and receiving responses.
 * - Periodically pinging the virtual process to detect silent deaths.
 * - Cleaning up resources and updating the [ProcessRecord][com.atlas.virtualspace.core.engine.ProcessRecord]
 *   state on process death.
 * - Providing a reconnection mechanism when a process is restarted.
 *
 * ## Typical lifecycle
 * ```kotlin
 * val connector = ProcessConnector("com.example.app", binder)
 * connector.connect()
 * // ... send commands ...
 * connector.disconnect()
 * ```
 *
 * ## Heartbeat
 * After calling [startHeartbeat], the connector will periodically ping the
 * virtual process at the configured interval. If a ping fails, the connector
 * treats it as a process death and invokes [handleProcessDeath].
 *
 * ## Thread safety
 * This class is safe to use from multiple threads. The [connected] flag is
 * backed by an [AtomicBoolean], command counting uses [AtomicLong], and
 * the death recipient is stored in an [AtomicReference].
 */
class ProcessConnector(
    private val packageName: String,
    private val binder: IBinder,
) {
    companion object {
        private const val TAG = "Atlas:ProcessConnector"

        /** Default heartbeat interval in milliseconds. */
        private const val DEFAULT_HEARTBEAT_INTERVAL_MS = 10_000L

        /** Default number of consecutive ping failures before declaring death. */
        private const val DEFAULT_HEARTBEAT_FAILURE_THRESHOLD = 3
    }

    /** Whether the connector is currently in a connected state. */
    private val connected = AtomicBoolean(false)

    /** Death recipient for receiving Binder death notifications. */
    private val deathRecipientRef = AtomicReference<IBinder.DeathRecipient>(null)

    /** Local VirtualIPC instance used for sending commands. */
    private val ipc: VirtualIPC = VirtualIPC()

    /** Handler for scheduling heartbeat pings on the main looper. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Whether heartbeat monitoring is currently active. */
    private val heartbeatActive = AtomicBoolean(false)

    /** Consecutive heartbeat failures. Reset to 0 on success. */
    private val heartbeatFailures = AtomicInteger(0)

    /** Number of consecutive ping failures before declaring process death. */
    @Volatile
    var heartbeatFailureThreshold: Int = DEFAULT_HEARTBEAT_FAILURE_THRESHOLD

    /** Interval between heartbeat pings in milliseconds. */
    @Volatile
    var heartbeatIntervalMs: Long = DEFAULT_HEARTBEAT_INTERVAL_MS

    /** Command statistics. */
    private val commandsSent = AtomicLong(0)
    private val commandsSucceeded = AtomicLong(0)
    private val commandsFailed = AtomicLong(0)

    // ──────────────────────────────────────────────────────────
    //  Connection lifecycle
    // ──────────────────────────────────────────────────────────

    /**
     * Connects to the virtual process by:
     * 1. Registering a [IBinder.DeathRecipient] for death notifications.
     * 2. Pinging the process to verify it is alive.
     * 3. Resetting statistics and failure counters.
     *
     * @return [Result.success] if the connection is alive and death
     *         notifications are set up, [Result.failure] otherwise.
     */
    fun connect(): Result<Unit> {
        return runCatching {
            if (connected.get()) {
                Log.w(TAG, "Already connected to $packageName — skipping")
                return Result.success(Unit)
            }

            // Verify the binder is alive before setting up death notifications.
            if (!binder.isBinderAlive) {
                throw DeadObjectException("Binder for $packageName is not alive")
            }

            // Set up death notification.
            val deathRecipient = object : IBinder.DeathRecipient {
                override fun binderDied() {
                    Log.w(TAG, "Virtual process died (death notification): $packageName")
                    handleProcessDeath()
                }
            }

            binder.linkToDeath(deathRecipient, 0)
            deathRecipientRef.set(deathRecipient)

            // Reset counters.
            heartbeatFailures.set(0)
            connected.set(true)
            Log.i(TAG, "Connected to virtual process: $packageName")
            Unit
        }.onFailure { e ->
            Log.e(TAG, "Failed to connect to $packageName", e)
            connected.set(false)
        }
    }

    /**
     * Disconnects from the virtual process by:
     * 1. Stopping the heartbeat if active.
     * 2. Unlinking the death recipient.
     * 3. Updating the connected flag.
     *
     * @return [Result.success] if cleanup completed, [Result.failure] if
     *         the death recipient could not be unlinked (e.g. already dead).
     */
    fun disconnect(): Result<Unit> {
        return runCatching {
            // Stop heartbeat first.
            stopHeartbeat()

            if (!connected.getAndSet(false)) {
                Log.d(TAG, "Not connected to $packageName — nothing to disconnect")
                return Result.success(Unit)
            }

            unlinkDeathRecipient()
            Log.i(TAG, "Disconnected from virtual process: $packageName")
            Unit
        }.onFailure { e ->
            Log.e(TAG, "Error during disconnect for $packageName", e)
        }
    }

    // ──────────────────────────────────────────────────────────
    //  Messaging
    // ──────────────────────────────────────────────────────────

    /**
     * Sends an [IpcCommand] to the virtual process and waits for a response.
     *
     * @param command The command to send.
     * @param timeoutMs Timeout in milliseconds for the Binder transaction.
     * @return [Result.success] with the [IpcResponse], or [Result.failure]
     *         if the connector is not connected, the process died, or the
     *         transaction failed.
     */
    fun sendCommand(command: IpcCommand, timeoutMs: Long = 5000): Result<IpcResponse> {
        commandsSent.incrementAndGet()

        val result = runCatching {
            if (!connected.get()) {
                throw IllegalStateException("Not connected to $packageName — call connect() first")
            }

            if (!binder.isBinderAlive) {
                handleProcessDeath()
                throw DeadObjectException("Process $packageName is dead")
            }

            val data = Bundle().apply {
                putString("action", command.action)
                putBundle("extras", command.extras)
                putString("package_name", packageName)
            }

            // Determine the transaction code from the command action.
            val transactionCode = actionToTransactionCode(command.action)

            val resultBundle = ipc.sendCommand(binder, transactionCode, data, timeoutMs)
                .getOrThrow()

            IpcResponse(
                success = resultBundle.getBoolean("success", true),
                data = resultBundle,
                error = resultBundle.getString("error"),
            )
        }.onFailure { e ->
            when (e) {
                is DeadObjectException -> {
                    Log.w(TAG, "Process $packageName died during sendCommand", e)
                    handleProcessDeath()
                }
                is RemoteException ->
                    Log.e(TAG, "Remote exception sending to $packageName", e)
                else ->
                    Log.e(TAG, "Failed to send command to $packageName (action=${command.action})", e)
            }
        }

        if (result.isSuccess) {
            commandsSucceeded.incrementAndGet()
        } else {
            commandsFailed.incrementAndGet()
        }

        return result
    }

    /**
     * Returns `true` if the virtual process is believed to be alive.
     *
     * This checks both the local connected flag and the Binder liveness.
     */
    fun isAlive(): Boolean {
        if (!connected.get()) return false
        return binder.isBinderAlive
    }

    /**
     * Returns the package name of the virtual process this connector manages.
     */
    fun getPackageName(): String = packageName

    /**
     * Returns whether this connector is in a connected state.
     */
    fun isConnected(): Boolean = connected.get()

    // ──────────────────────────────────────────────────────────
    //  Heartbeat
    // ──────────────────────────────────────────────────────────

    /**
     * Starts periodic heartbeat monitoring of the virtual process.
     *
     * Every [heartbeatIntervalMs] milliseconds, the connector will ping
     * the process's Binder. If [heartbeatFailureThreshold] consecutive
     * pings fail, the connector treats it as a process death.
     *
     * Has no effect if heartbeat is already active.
     */
    fun startHeartbeat(
        intervalMs: Long = DEFAULT_HEARTBEAT_INTERVAL_MS,
        failureThreshold: Int = DEFAULT_HEARTBEAT_FAILURE_THRESHOLD,
    ) {
        if (heartbeatActive.getAndSet(true)) {
            Log.d(TAG, "Heartbeat already active for $packageName")
            return
        }

        heartbeatIntervalMs = intervalMs.coerceAtLeast(1000L)
        heartbeatFailureThreshold = failureThreshold.coerceAtLeast(1)
        heartbeatFailures.set(0)

        scheduleNextHeartbeat()
        Log.i(TAG, "Heartbeat started for $packageName (interval=${heartbeatIntervalMs}ms, threshold=$failureThreshold)")
    }

    /**
     * Stops the periodic heartbeat monitoring.
     */
    fun stopHeartbeat() {
        if (heartbeatActive.getAndSet(false)) {
            mainHandler.removeCallbacks(heartbeatRunnable)
            Log.i(TAG, "Heartbeat stopped for $packageName")
        }
    }

    /**
     * Returns whether heartbeat monitoring is currently active.
     */
    fun isHeartbeatActive(): Boolean = heartbeatActive.get()

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (!heartbeatActive.get() || !connected.get()) return

            val alive = binder.isBinderAlive
            if (alive) {
                heartbeatFailures.set(0)
            } else {
                val failures = heartbeatFailures.incrementAndGet()
                Log.w(TAG, "Heartbeat ping failed for $packageName ($failures/$heartbeatFailureThreshold)")
                if (failures >= heartbeatFailureThreshold) {
                    Log.e(TAG, "Heartbeat threshold exceeded for $packageName — declaring process dead")
                    handleProcessDeath()
                    return
                }
            }

            // Schedule the next heartbeat.
            if (heartbeatActive.get()) {
                scheduleNextHeartbeat()
            }
        }
    }

    private fun scheduleNextHeartbeat() {
        mainHandler.postDelayed(heartbeatRunnable, heartbeatIntervalMs)
    }

    // ──────────────────────────────────────────────────────────
    //  Death handling
    // ──────────────────────────────────────────────────────────

    /**
     * Called when the virtual process dies (either via death notification
     * or a detected Binder failure).
     *
     * This method:
     * 1. Marks the connector as disconnected.
     * 2. Stops the heartbeat.
     * 3. Notifies [VirtualEngine] of the process death.
     * 4. Updates the process state to [ProcessState.CRASHED].
     * 5. Cleans up the death recipient.
     */
    private fun handleProcessDeath() {
        if (!connected.getAndSet(false)) {
            // Already handled; avoid duplicate processing.
            return
        }

        Log.w(TAG, "Handling process death for $packageName")

        // Stop heartbeat.
        stopHeartbeat()

        // Clean up the death recipient to avoid further callbacks.
        unlinkDeathRecipient()

        // Notify the engine, which will update the ProcessRecord state to CRASHED.
        runCatching { VirtualEngine.forceStopApp(packageName) }
            .onFailure { e ->
                Log.w(TAG, "forceStopApp failed during death handling for $packageName", e)
            }

        // Also explicitly notify the engine's death handler for state tracking.
        runCatching { VirtualEngine.notifyProcessDeath(packageName) }
            .onFailure { e ->
                Log.w(TAG, "notifyProcessDeath failed for $packageName", e)
            }

        Log.i(TAG, "Process death handled for $packageName — state set to CRASHED")
    }

    // ──────────────────────────────────────────────────────────
    //  Statistics
    // ──────────────────────────────────────────────────────────

    /** Returns the total number of commands sent through this connector. */
    fun getCommandsSent(): Long = commandsSent.get()

    /** Returns the number of commands that succeeded. */
    fun getCommandsSucceeded(): Long = commandsSucceeded.get()

    /** Returns the number of commands that failed. */
    fun getCommandsFailed(): Long = commandsFailed.get()

    /** Returns the current heartbeat failure count. */
    fun getHeartbeatFailures(): Int = heartbeatFailures.get()

    // ──────────────────────────────────────────────────────────
    //  Internal helpers
    // ──────────────────────────────────────────────────────────

    /**
     * Safely unlinks the death recipient. Catches and logs exceptions that
     * occur when the binder is already dead.
     */
    private fun unlinkDeathRecipient() {
        val recipient = deathRecipientRef.getAndSet(null) ?: return
        runCatching { binder.unlinkToDeath(recipient, 0) }
            .onFailure { e ->
                // Expected if the binder is already dead.
                Log.d(TAG, "unlinkToDeath failed for $packageName (process may already be dead)", e)
            }
    }

    /**
     * Maps a command action string to a Binder transaction code.
     */
    private fun actionToTransactionCode(action: String): Int {
        return when (action.uppercase()) {
            "INSTALL" -> VirtualIPC.TRANSACTION_INSTALL
            "LAUNCH" -> VirtualIPC.TRANSACTION_LAUNCH
            "FORCE_STOP" -> VirtualIPC.TRANSACTION_FORCE_STOP
            "GET_PROCESS_INFO" -> VirtualIPC.TRANSACTION_GET_PROCESS_INFO
            "SYNC_DATA" -> VirtualIPC.TRANSACTION_SYNC_DATA
            else -> {
                Log.w(TAG, "Unknown action '$action', using GET_PROCESS_INFO as default")
                VirtualIPC.TRANSACTION_GET_PROCESS_INFO
            }
        }
    }

    override fun toString(): String =
        "ProcessConnector(packageName=$packageName, connected=${connected.get()}, alive=${isAlive()})"
}

/**
 * Extension property to check if a Binder is alive.
 * Uses [IBinder.pingBinder] which returns `false` if the remote process is dead.
 */
private val IBinder.isBinderAlive: Boolean
    get() = runCatching { pingBinder() }.getOrDefault(false)
