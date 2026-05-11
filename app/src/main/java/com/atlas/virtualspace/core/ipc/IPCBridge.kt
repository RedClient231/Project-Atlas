package com.atlas.virtualspace.core.ipc

import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

// ──────────────────────────────────────────────────────────────
//  Top-level data classes shared across the IPC layer
// ──────────────────────────────────────────────────────────────

/**
 * Represents a command sent from the host to a virtual app process.
 *
 * @property action A string identifying the command type
 *           (e.g. `"INSTALL"`, `"LAUNCH"`, `"FORCE_STOP"`, `"SYNC_DATA"`).
 * @property extras Additional data for the command.
 */
data class IpcCommand(
    val action: String,
    val extras: Bundle,
)

/**
 * Represents the response from a virtual app process to an [IpcCommand].
 *
 * @property success Whether the command was processed successfully.
 * @property data Response data bundle, or `null` on failure.
 * @property error Error message if [success] is `false`, `null` otherwise.
 */
data class IpcResponse(
    val success: Boolean,
    val data: Bundle?,
    val error: String?,
)

// ──────────────────────────────────────────────────────────────
//  IPCBridge
// ──────────────────────────────────────────────────────────────

/**
 * High-level IPC bridge that manages connections to virtual app processes.
 *
 * Each virtual app gets its own [VirtualIPC] connection stored in [connections].
 * The bridge provides methods for point-to-point messaging ([sendToVirtualApp]),
 * broadcast ([broadcastToAll]), and connection lifecycle management.
 *
 * ## Retry support
 * [sendToVirtualApp] supports automatic retries via [maxRetries]. When a
 * transient [RemoteException] occurs, the bridge will retry the send up to
 * the configured number of times with a fixed back-off delay.
 *
 * ## Health monitoring
 * [isConnected] checks both the local map and a live [IBinder.pingBinder] call
 * to detect stale connections. [pruneDeadConnections] can be called periodically
 * to clean up entries whose binder has died.
 *
 * ## Thread safety
 * All operations are safe to call from any thread. [ConcurrentHashMap] provides
 * the underlying thread-safe storage.
 */
object IPCBridge {

    private const val TAG = "Atlas:IPCBridge"

    /** Delay between retries in milliseconds. */
    private const val RETRY_DELAY_MS = 200L

    /** Maps package names to their VirtualIPC connection objects. */
    private val connections = ConcurrentHashMap<String, VirtualIPC>()

    /** Maps package names to the raw Binder references from virtual processes. */
    private val processBinders = ConcurrentHashMap<String, IBinder>()

    /** Tracks when each connection was established (epoch millis). */
    private val connectionTimestamps = ConcurrentHashMap<String, Long>()

    /** Counters for monitoring. */
    private val totalSent = AtomicLong(0)
    private val totalSucceeded = AtomicLong(0)
    private val totalFailed = AtomicLong(0)

    // ──────────────────────────────────────────────────────────
    //  Connection lifecycle
    // ──────────────────────────────────────────────────────────

    /**
     * Establishes an IPC connection to a virtual app process.
     *
     * Creates a [VirtualIPC] instance wrapping the provided [processBinder]
     * and stores it for future message routing. If a connection already
     * exists for [packageName], it is closed and replaced.
     *
     * @param packageName The package name of the virtual app.
     * @param processBinder The Binder object exported by the virtual process.
     * @return [Result.success] if the connection was established,
     *         [Result.failure] if a connection already exists or setup failed.
     */
    fun establishConnection(packageName: String, processBinder: IBinder): Result<Unit> {
        return runCatching {
            if (connections.containsKey(packageName)) {
                Log.w(TAG, "Connection already exists for $packageName — replacing")
                closeConnection(packageName)
            }

            if (!processBinder.pingBinder()) {
                throw RemoteException("Process binder for $packageName is not alive")
            }

            val ipc = VirtualIPC()
            connections[packageName] = ipc
            processBinders[packageName] = processBinder
            connectionTimestamps[packageName] = System.currentTimeMillis()

            Log.i(TAG, "IPC connection established for $packageName")
        }.onFailure { e ->
            Log.e(TAG, "Failed to establish connection for $packageName", e)
        }
    }

    /**
     * Closes and removes the IPC connection for a virtual app.
     *
     * @param packageName The package name of the virtual app to disconnect.
     * @return [Result.success] if the connection was closed,
     *         [Result.failure] if no connection existed or cleanup failed.
     */
    fun closeConnection(packageName: String): Result<Unit> {
        return runCatching {
            val removed = connections.remove(packageName)
            processBinders.remove(packageName)
            connectionTimestamps.remove(packageName)

            if (removed == null) {
                Log.w(TAG, "No connection to close for $packageName")
            } else {
                Log.i(TAG, "IPC connection closed for $packageName")
            }
        }.onFailure { e ->
            Log.e(TAG, "Failed to close connection for $packageName", e)
        }
    }

    /**
     * Closes all connections and clears internal state.
     */
    fun closeAll(): Result<Unit> {
        return runCatching {
            val packageNames = connections.keys.toList()
            for (packageName in packageNames) {
                closeConnection(packageName)
            }
            Log.i(TAG, "All IPC connections closed (${packageNames.size})")
        }.onFailure { e ->
            Log.e(TAG, "Failed to close all connections", e)
        }
    }

    // ──────────────────────────────────────────────────────────
    //  Messaging
    // ──────────────────────────────────────────────────────────

    /**
     * Sends an [IpcCommand] to a specific virtual app.
     *
     * @param packageName The target virtual app's package name.
     * @param command The command to send.
     * @param maxRetries Number of retry attempts on transient failure. Default is 0.
     * @return [Result.success] with the [IpcResponse] from the virtual app,
     *         or [Result.failure] if the connection doesn't exist or the
     *         transaction failed after all retries.
     */
    fun sendToVirtualApp(
        packageName: String,
        command: IpcCommand,
        maxRetries: Int = 0,
    ): Result<IpcResponse> {
        totalSent.incrementAndGet()

        val result = sendWithRetry(packageName, command, maxRetries)

        if (result.isSuccess) {
            totalSucceeded.incrementAndGet()
        } else {
            totalFailed.incrementAndGet()
        }

        return result
    }

    /**
     * Broadcasts an [IpcCommand] to **all** connected virtual apps.
     *
     * Individual failures do not prevent delivery to other apps. The returned
     * map contains one entry per connected app with its individual result.
     *
     * @param command The command to broadcast.
     * @return Map of package names to their [Result]s.
     */
    fun broadcastToAll(command: IpcCommand): Map<String, Result<IpcResponse>> {
        val results = mutableMapOf<String, Result<IpcResponse>>()

        for (packageName in connections.keys.toList()) {
            results[packageName] = sendToVirtualApp(packageName, command)
        }

        val successCount = results.values.count { it.isSuccess }
        val failCount = results.size - successCount
        Log.d(TAG, "Broadcast '${command.action}' to ${results.size} apps: $successCount ok, $failCount failed")

        return results
    }

    // ──────────────────────────────────────────────────────────
    //  Query
    // ──────────────────────────────────────────────────────────

    /**
     * Returns `true` if an active IPC connection exists for [packageName].
     *
     * This checks both the local connection map and the Binder liveness.
     */
    fun isConnected(packageName: String): Boolean {
        val binder = processBinders[packageName] ?: return false
        return runCatching { binder.pingBinder() }.getOrDefault(false)
    }

    /**
     * Returns the set of package names with active connections.
     * Only includes packages whose Binder is still alive.
     */
    fun getConnectedApps(): Set<String> {
        return connections.keys.filter { packageName ->
            processBinders[packageName]?.let { binder ->
                runCatching { binder.pingBinder() }.getOrDefault(false)
            } ?: false
        }.toSet()
    }

    /**
     * Returns the number of stored connections (may include stale ones).
     */
    fun getConnectionCount(): Int = connections.size

    /**
     * Returns the epoch millis when the connection for [packageName] was
     * established, or `null` if no connection exists.
     */
    fun getConnectionTime(packageName: String): Long? = connectionTimestamps[packageName]

    // ──────────────────────────────────────────────────────────
    //  Health & maintenance
    // ──────────────────────────────────────────────────────────

    /**
     * Removes connections whose Binder is no longer alive.
     *
     * @return The number of dead connections that were pruned.
     */
    fun pruneDeadConnections(): Int {
        var pruned = 0
        val iterator = processBinders.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val alive = runCatching { entry.value.pingBinder() }.getOrDefault(false)
            if (!alive) {
                val packageName = entry.key
                connections.remove(packageName)
                connectionTimestamps.remove(packageName)
                iterator.remove()
                pruned++
                Log.d(TAG, "Pruned dead connection for $packageName")
            }
        }
        if (pruned > 0) {
            Log.i(TAG, "Pruned $pruned dead connection(s)")
        }
        return pruned
    }

    /**
     * Performs a health check by pinging all connected binders.
     *
     * @return Map of package names to their alive status.
     */
    fun healthCheck(): Map<String, Boolean> {
        val result = mutableMapOf<String, Boolean>()
        for ((packageName, binder) in processBinders) {
            result[packageName] = runCatching { binder.pingBinder() }.getOrDefault(false)
        }
        return result
    }

    // ──────────────────────────────────────────────────────────
    //  Statistics
    // ──────────────────────────────────────────────────────────

    /** Total number of commands sent (including retries). */
    fun getTotalSent(): Long = totalSent.get()

    /** Total number of commands that succeeded. */
    fun getTotalSucceeded(): Long = totalSucceeded.get()

    /** Total number of commands that failed. */
    fun getTotalFailed(): Long = totalFailed.get()

    // ──────────────────────────────────────────────────────────
    //  Internal helpers
    // ──────────────────────────────────────────────────────────

    /**
     * Sends a command with optional retry on transient failures.
     */
    private fun sendWithRetry(
        packageName: String,
        command: IpcCommand,
        maxRetries: Int,
    ): Result<IpcResponse> {
        var lastException: Exception? = null
        var attempts = 0

        while (attempts <= maxRetries) {
            try {
                val ipc = connections[packageName]
                    ?: throw IllegalStateException("No connection for $packageName")

                val binder = processBinders[packageName]
                    ?: throw IllegalStateException("No process binder for $packageName")

                val data = Bundle().apply {
                    putString("action", command.action)
                    putBundle("extras", command.extras)
                    putString("package_name", packageName)
                }

                val transactionCode = actionToTransactionCode(command.action)

                val result = ipc.sendCommand(binder, transactionCode, data)
                    .getOrThrow()

                return Result.success(
                    IpcResponse(
                        success = result.getBoolean("success", true),
                        data = result,
                        error = result.getString("error"),
                    )
                )
            } catch (e: Exception) {
                lastException = e
                attempts++
                if (attempts <= maxRetries) {
                    Log.d(TAG, "Retrying sendToVirtualApp for $packageName (attempt $attempts/$maxRetries)", e)
                    runCatching { Thread.sleep(RETRY_DELAY_MS) }
                }
            }
        }

        Log.e(TAG, "sendToVirtualApp failed for $packageName (action=${command.action}) after $attempts attempts", lastException)
        return Result.failure(lastException ?: RuntimeException("Unknown error"))
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
}
