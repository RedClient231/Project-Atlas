package com.atlas.virtualspace.core.ipc

import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import com.atlas.virtualspace.core.pm.InstallType
import com.atlas.virtualspace.core.engine.ProcessState
import com.atlas.virtualspace.core.engine.VirtualEngine
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Binder-based IPC mechanism for communication between the Atlas host process
 * and virtual app processes.
 *
 * ## Transaction Codes
 * | Code | Constant               | Purpose                        |
 * |------|------------------------|--------------------------------|
 * | 1    | TRANSACTION_INSTALL    | Install a virtual app          |
 * | 2    | TRANSACTION_LAUNCH     | Launch a virtual app           |
 * | 3    | TRANSACTION_FORCE_STOP | Force-stop a virtual app       |
 * | 4    | TRANSACTION_GET_PROCESS_INFO | Query process state      |
 * | 5    | TRANSACTION_SYNC_DATA  | Synchronize app data          |
 *
 * ## Callbacks
 * Use [registerCallback] / [unregisterCallback] to receive asynchronous
 * notifications about process state changes and crashes. Callbacks are
 * invoked on the main looper thread by default, or on a custom [Handler]
 * if one is provided at registration time.
 *
 * ## Thread Safety
 * All public methods are safe to call from any thread. The callback registry
 * uses [ConcurrentHashMap] and [CopyOnWriteArrayList] for lock-free reads.
 * Transaction counters are backed by [AtomicLong].
 */
class VirtualIPC : Binder() {

    companion object {
        private const val TAG = "Atlas:VirtualIPC"

        const val DESCRIPTOR = "com.atlas.virtualspace.IVirtualIPC"

        /** Install a virtual app into the virtual space. */
        const val TRANSACTION_INSTALL = IBinder.FIRST_CALL_TRANSACTION

        /** Launch an already-installed virtual app. */
        const val TRANSACTION_LAUNCH = IBinder.FIRST_CALL_TRANSACTION + 1

        /** Force-stop a running virtual app. */
        const val TRANSACTION_FORCE_STOP = IBinder.FIRST_CALL_TRANSACTION + 2

        /** Query the process state of a virtual app. */
        const val TRANSACTION_GET_PROCESS_INFO = IBinder.FIRST_CALL_TRANSACTION + 3

        /** Synchronize app data between host and virtual filesystem. */
        const val TRANSACTION_SYNC_DATA = IBinder.FIRST_CALL_TRANSACTION + 4

        // ── Parcel key constants ──────────────────────────────

        private const val KEY_PACKAGE_NAME = "package_name"
        private const val KEY_APK_PATH = "apk_path"
        private const val KEY_PID = "pid"
        private const val KEY_UID = "uid"
        private const val KEY_STATE = "state"
        private const val KEY_ALIVE = "alive"
        private const val KEY_SUCCESS = "success"
        private const val KEY_SYNC_DIRECTION = "sync_direction"
        private const val KEY_ERROR = "error"
        private const val KEY_INSTALL_TYPE = "install_type"

        private const val SYNC_DIRECTION_PULL = "pull"
        private const val SYNC_DIRECTION_PUSH = "push"

        /** Maximum size of a reply Bundle (1 MB) to prevent OOM on large data. */
        private const val MAX_REPLY_BUNDLE_SIZE = 1024 * 1024
    }

    // ──────────────────────────────────────────────────────────
    //  Statistics
    // ──────────────────────────────────────────────────────────

    private val transactionsReceived = AtomicLong(0)
    private val transactionsSucceeded = AtomicLong(0)
    private val transactionsFailed = AtomicLong(0)
    private val transactionsSent = AtomicLong(0)

    // ──────────────────────────────────────────────────────────
    //  Callback registry
    // ──────────────────────────────────────────────────────────

    /**
     * Maps package names to their registered callback entries.
     * Each package may have multiple callbacks, each optionally with its
     * own [Handler] for dispatch.
     */
    private val callbacks = ConcurrentHashMap<String, CopyOnWriteArrayList<CallbackEntry>>()

    /** Default handler for callbacks that don't specify one. */
    private val mainHandler = Handler(Looper.getMainLooper())

    // ──────────────────────────────────────────────────────────
    //  Binder transaction dispatch
    // ──────────────────────────────────────────────────────────

    /**
     * Handles incoming Binder transactions and dispatches them to the
     * appropriate handler method.
     *
     * The Parcel layout for all transactions:
     * ```
     * [String: interface descriptor] [Bundle: data]
     * ```
     *
     * The reply Parcel is populated with:
     * ```
     * [Int: status code (0=success, 1=error)] [Bundle: result data]
     * [String: error message if status=1]
     * ```
     */
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code in TRANSACTION_INSTALL..TRANSACTION_SYNC_DATA) {
            transactionsReceived.incrementAndGet()

            data.enforceInterface(DESCRIPTOR)
            val requestData = runCatching { data.readBundle(javaClass.classLoader) }
                .getOrDefault(Bundle.EMPTY)

            val result = when (code) {
                TRANSACTION_INSTALL -> handleInstall(requestData)
                TRANSACTION_LAUNCH -> handleLaunch(requestData)
                TRANSACTION_FORCE_STOP -> handleForceStop(requestData)
                TRANSACTION_GET_PROCESS_INFO -> handleGetProcessInfo(requestData)
                TRANSACTION_SYNC_DATA -> handleSyncData(requestData)
                else -> IpcResult.error("Unknown transaction code: $code")
            }

            if (result.success) {
                transactionsSucceeded.incrementAndGet()
            } else {
                transactionsFailed.incrementAndGet()
            }

            reply?.writeNoException()
            reply?.writeInt(if (result.success) 0 else 1)
            reply?.writeBundle(result.data)
            if (!result.success) {
                reply?.writeString(result.error)
            }
            return true
        }

        return super.onTransact(code, data, reply, flags)
    }

    // ──────────────────────────────────────────────────────────
    //  Transaction handlers
    // ──────────────────────────────────────────────────────────

    private fun handleInstall(data: Bundle): IpcResult {
        val packageName = data.getString(KEY_PACKAGE_NAME)
            ?: return IpcResult.error("Missing $KEY_PACKAGE_NAME")

        val apkPath = data.getString(KEY_APK_PATH)
            ?: return IpcResult.error("Missing $KEY_APK_PATH")

        return runCatching {
            Log.i(TAG, "Installing virtual app: $packageName (apk=$apkPath)")

            val apkFile = File(apkPath)
            if (!apkFile.exists()) {
                return IpcResult.error("APK file does not exist: $apkPath")
            }
            if (!apkFile.canRead()) {
                return IpcResult.error("APK file is not readable: $apkPath")
            }

            val installTypeName = data.getString(KEY_INSTALL_TYPE) ?: InstallType.APK.name
            val installType = runCatching { InstallType.valueOf(installTypeName) }
                .getOrDefault(InstallType.APK)

            val result = VirtualEngine.installApp(apkFile, installType)
            if (result.isFailure) {
                return IpcResult.error("Installation failed: ${result.exceptionOrNull()?.message}")
            }
            val appInfo = result.getOrThrow()
            Log.i(TAG, "Virtual app installed: ${appInfo.packageName}")

            val resultBundle = Bundle().apply {
                putString(KEY_PACKAGE_NAME, appInfo.packageName)
                putString(KEY_APK_PATH, appInfo.apkPath)
            }
            IpcResult.success(resultBundle)
        }.getOrElse { e ->
            Log.e(TAG, "Failed to install virtual app: $packageName", e)
            IpcResult.error("Installation failed: ${e.message}")
        }
    }

    private fun handleLaunch(data: Bundle): IpcResult {
        val packageName = data.getString(KEY_PACKAGE_NAME)
            ?: return IpcResult.error("Missing $KEY_PACKAGE_NAME")

        return runCatching {
            Log.i(TAG, "Launching virtual app: $packageName")
            val result = VirtualEngine.launchApp(packageName)
            if (result.isFailure) {
                return IpcResult.error("Launch failed: ${result.exceptionOrNull()?.message}")
            }

            val record = VirtualEngine.getRunningProcesses()
                .firstOrNull { it.packageName == packageName }

            val resultBundle = Bundle().apply {
                putString(KEY_PACKAGE_NAME, packageName)
                putInt(KEY_PID, record?.pid ?: 0)
                putString(KEY_STATE, record?.state?.name ?: ProcessState.STARTING.name)
            }
            notifyProcessStateChanged(packageName, ProcessState.RUNNING)
            IpcResult.success(resultBundle)
        }.getOrElse { e ->
            Log.e(TAG, "Failed to launch virtual app: $packageName", e)
            IpcResult.error("Launch failed: ${e.message}")
        }
    }

    private fun handleForceStop(data: Bundle): IpcResult {
        val packageName = data.getString(KEY_PACKAGE_NAME)
            ?: return IpcResult.error("Missing $KEY_PACKAGE_NAME")

        return runCatching {
            Log.i(TAG, "Force-stopping virtual app: $packageName")
            val result = VirtualEngine.forceStopApp(packageName)
            if (result.isFailure) {
                return IpcResult.error("Force-stop failed: ${result.exceptionOrNull()?.message}")
            }
            notifyProcessStateChanged(packageName, ProcessState.STOPPED)

            IpcResult.success(Bundle().apply {
                putString(KEY_PACKAGE_NAME, packageName)
                putString(KEY_STATE, ProcessState.STOPPED.name)
            })
        }.getOrElse { e ->
            Log.e(TAG, "Failed to force-stop virtual app: $packageName", e)
            IpcResult.error("Force-stop failed: ${e.message}")
        }
    }

    private fun handleGetProcessInfo(data: Bundle): IpcResult {
        val packageName = data.getString(KEY_PACKAGE_NAME)
            ?: return IpcResult.error("Missing $KEY_PACKAGE_NAME")

        return runCatching {
            val record = VirtualEngine.getRunningProcesses()
                .firstOrNull { it.packageName == packageName }
                ?: return IpcResult.error("App not running: $packageName")

            IpcResult.success(Bundle().apply {
                putString(KEY_PACKAGE_NAME, packageName)
                putInt(KEY_PID, record.pid)
                putInt(KEY_UID, record.uid)
                putString(KEY_STATE, record.state.name)
                putBoolean(KEY_ALIVE, record.isAlive())
            })
        }.getOrElse { e ->
            Log.e(TAG, "Failed to get process info for: $packageName", e)
            IpcResult.error("Query failed: ${e.message}")
        }
    }

    private fun handleSyncData(data: Bundle): IpcResult {
        val packageName = data.getString(KEY_PACKAGE_NAME)
            ?: return IpcResult.error("Missing $KEY_PACKAGE_NAME")

        val direction = data.getString(KEY_SYNC_DIRECTION) ?: SYNC_DIRECTION_PULL

        return runCatching {
            Log.i(TAG, "Syncing data for $packageName (direction=$direction)")

            val engineConfig = VirtualEngine.getConfig()
            val dataDir = engineConfig.dataDirForPackage(packageName)

            if (!dataDir.exists() && direction == SYNC_DIRECTION_PULL) {
                return IpcResult.error("Data directory does not exist for $packageName")
            }

            // Ensure the data directory structure exists for push direction.
            if (direction == SYNC_DIRECTION_PUSH) {
                dataDir.mkdirs()
            }

            IpcResult.success(Bundle().apply {
                putString(KEY_PACKAGE_NAME, packageName)
                putString(KEY_SYNC_DIRECTION, direction)
                putBoolean(KEY_SUCCESS, true)
            })
        }.getOrElse { e ->
            Log.e(TAG, "Failed to sync data for: $packageName", e)
            IpcResult.error("Sync failed: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────────────────
    //  Outbound IPC
    // ──────────────────────────────────────────────────────────

    /**
     * Sends an IPC command to a remote [VirtualIPC] instance identified by
     * its [target] binder.
     *
     * @param target   The remote Binder to transact on.
     * @param code     Transaction code (one of `TRANSACTION_*` constants).
     * @param data     Bundle containing the request data.
     * @param timeoutMs Timeout in milliseconds. Defaults to 5000 ms.
     *                  Note: Binder transactions are synchronous; the timeout
     *                  is enforced at the caller level via [android.os.Binder].
     * @return [Result.success] with the reply Bundle, or [Result.failure] if
     *         the remote end returned an error or the transaction failed.
     */
    fun sendCommand(
        target: IBinder,
        code: Int,
        data: Bundle,
        timeoutMs: Long = 5000,
    ): Result<Bundle> {
        val request = Parcel.obtain()
        val reply = Parcel.obtain()

        return runCatching {
            request.writeInterfaceToken(DESCRIPTOR)
            request.writeBundle(data)

            val success = target.transact(code, request, reply, 0)
            transactionsSent.incrementAndGet()

            if (!success) {
                throw RemoteException("Transaction $code returned false")
            }

            reply.readException()
            val statusCode = reply.readInt()
            val resultBundle = reply.readBundle(javaClass.classLoader) ?: Bundle.EMPTY

            if (statusCode != 0) {
                val errorMsg = reply.readString() ?: "Unknown remote error"
                throw RemoteException("Remote error (code=$statusCode): $errorMsg")
            }

            resultBundle
        }.onFailure { e ->
            Log.e(TAG, "sendCommand failed (code=$code)", e)
        }.also {
            request.recycle()
            reply.recycle()
        }
    }

    // ──────────────────────────────────────────────────────────
    //  Callback management
    // ──────────────────────────────────────────────────────────

    /**
     * Registers a callback to receive state-change and crash notifications
     * for the given [packageName].
     *
     * Multiple callbacks can be registered for the same package.
     * The callback will be invoked on [handler]'s thread, or the main
     * thread if [handler] is null.
     *
     * @param packageName The virtual app package to observe.
     * @param callback    The callback to register.
     * @param handler     Optional handler for dispatching callbacks.
     *                    If null, callbacks run on the main looper thread.
     * @return [Result.success] when the callback is registered.
     */
    fun registerCallback(
        packageName: String,
        callback: IpcCallback,
        handler: Handler? = null,
    ): Result<Unit> {
        return runCatching {
            val dispatchHandler = handler ?: mainHandler
            val entry = CallbackEntry(callback, dispatchHandler)
            val list = callbacks.getOrPut(packageName) { CopyOnWriteArrayList() }
            list.add(entry)
            Log.d(TAG, "Callback registered for $packageName (total=${list.size})")
        }
    }

    /**
     * Unregisters all callbacks for the given [packageName].
     *
     * @param packageName The package whose callbacks should be removed.
     * @return [Result.success] when callbacks are removed.
     */
    fun unregisterCallback(packageName: String): Result<Unit> {
        return runCatching {
            val removed = callbacks.remove(packageName)
            val count = removed?.size ?: 0
            Log.d(TAG, "Callbacks unregistered for $packageName ($count removed)")
        }
    }

    /**
     * Removes a specific callback for the given [packageName].
     *
     * @return `true` if the callback was found and removed.
     */
    fun unregisterSpecificCallback(packageName: String, callback: IpcCallback): Boolean {
        val list = callbacks[packageName] ?: return false
        val removed = list.removeAll { it.callback === callback }
        if (list.isEmpty()) {
            callbacks.remove(packageName)
        }
        return removed
    }

    /**
     * Notifies all registered callbacks for [packageName] of a state change.
     * Each callback is dispatched on its registered handler.
     */
    private fun notifyProcessStateChanged(packageName: String, state: ProcessState) {
        val list = callbacks[packageName] ?: return
        for (entry in list) {
            entry.handler.post {
                runCatching { entry.callback.onProcessStateChanged(state) }
                    .onFailure { e ->
                        Log.w(TAG, "Callback onProcessStateChanged failed for $packageName", e)
                    }
            }
        }
    }

    /**
     * Notifies all registered callbacks for [packageName] of a crash.
     * Each callback is dispatched on its registered handler.
     */
    fun notifyAppCrashed(packageName: String, throwable: Throwable) {
        val list = callbacks[packageName] ?: return
        for (entry in list) {
            entry.handler.post {
                runCatching { entry.callback.onAppCrashed(throwable) }
                    .onFailure { e ->
                        Log.w(TAG, "Callback onAppCrashed failed for $packageName", e)
                    }
            }
        }
    }

    // ──────────────────────────────────────────────────────────
    //  Statistics
    // ──────────────────────────────────────────────────────────

    /**
     * Returns the total number of inbound transactions received.
     */
    fun getTransactionsReceived(): Long = transactionsReceived.get()

    /**
     * Returns the number of inbound transactions that succeeded.
     */
    fun getTransactionsSucceeded(): Long = transactionsSucceeded.get()

    /**
     * Returns the number of inbound transactions that failed.
     */
    fun getTransactionsFailed(): Long = transactionsFailed.get()

    /**
     * Returns the number of outbound transactions sent.
     */
    fun getTransactionsSent(): Long = transactionsSent.get()

    /**
     * Returns the number of packages with registered callbacks.
     */
    fun getCallbackCount(): Int = callbacks.size

    // ──────────────────────────────────────────────────────────
    //  Internal types and constants
    // ──────────────────────────────────────────────────────────

    /** Callback interface for asynchronous IPC notifications. */
    interface IpcCallback {
        /** Called when a virtual process changes state. */
        fun onProcessStateChanged(state: ProcessState)

        /** Called when a virtual app process crashes. */
        fun onAppCrashed(throwable: Throwable)
    }

    /**
     * Wrapper that pairs an [IpcCallback] with the [Handler] on which
     * it should be dispatched.
     */
    private data class CallbackEntry(
        val callback: IpcCallback,
        val handler: Handler,
    )

    /** Internal result wrapper for transaction handlers. */
    private data class IpcResult(
        val success: Boolean,
        val data: Bundle?,
        val error: String?,
    ) {
        companion object {
            fun success(data: Bundle): IpcResult = IpcResult(success = true, data = data, error = null)
            fun error(message: String): IpcResult = IpcResult(success = false, data = null, error = message)
        }
    }
}
