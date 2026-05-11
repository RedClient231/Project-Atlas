package com.atlas.virtualspace.core.hook

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * JNI bridge for native-level hooks required for GameGuardian compatibility
 * and low-level process/memory virtualization.
 *
 * The native library `atlas_native` must be packaged as a shared object
 * (libatlas_native.so) for both armeabi-v7a and arm64-v8a ABIs.
 *
 * ## Native hooks provided
 * - **mmap**: Exposes virtual process memory maps so tools like GameGuardian
 *   can enumerate memory regions belonging to a virtual app.
 * - **open/openat**: Redirects `/proc/self/` reads to virtualized `/proc/`
 *   trees that reflect the virtual process state instead of the host.
 * - **fork**: Tracks child processes spawned by virtual apps so that Atlas
 *   can manage their lifecycle and resource isolation.
 *
 * ## Thread safety
 * All public methods are thread-safe. The [initializeNativeHooks] and
 * [teardownNativeHooks] methods are guarded by a [ReentrantLock] to prevent
 * concurrent initialization/teardown races.
 *
 * ## Lifecycle
 * 1. The companion object's `init` block loads `atlas_native` on first access.
 * 2. Call [initializeNativeHooks] with the virtual FS root path.
 * 3. Use the query methods ([getMemoryMap], [getProcessList], [isVirtualProcess]).
 * 4. Call [teardownNativeHooks] when the virtual engine shuts down.
 */
class NativeHookBridge {

    companion object {
        private const val TAG = "Atlas:NativeBridge"

        /** Lock guarding initialization and teardown to prevent races. */
        private val lifecycleLock = ReentrantLock()

        /** Whether the native bridge has been successfully initialized. */
        private val nativeInitialized = AtomicBoolean(false)

        /** Whether the native library was successfully loaded. */
        private val nativeLibraryLoaded = AtomicBoolean(false)

        /** The virtual root path used during initialization, for diagnostics. */
        @Volatile
        private var initializedPath: String? = null

        init {
            runCatching {
                System.loadLibrary("atlas_native")
                nativeLibraryLoaded.set(true)
                Log.i(TAG, "Native library atlas_native loaded successfully")
            }.onFailure { e ->
                Log.e(TAG, "Failed to load atlas_native library — native hooks unavailable", e)
            }
        }

        // ──────────────────────────────────────────────────────
        //  External JNI declarations
        // ──────────────────────────────────────────────────────

        /**
         * Hooks the `mmap` system call to expose virtual process memory maps.
         *
         * @return 0 on success, negative errno value on failure.
         */
        @JvmStatic
        external fun hookMmap(): Int

        /**
         * Hooks `open` and `openat` system calls to redirect `/proc/self/`
         * file reads to a virtualized `/proc/` tree.
         *
         * @return 0 on success, negative errno value on failure.
         */
        @JvmStatic
        external fun hookOpen(): Int

        /**
         * Hooks the `fork` system call to track child processes spawned by
         * virtual apps. Each fork from a virtual process is recorded so that
         * Atlas can manage the child's lifecycle.
         *
         * @return 0 on success, negative errno value on failure.
         */
        @JvmStatic
        external fun hookFork(): Int

        /**
         * Returns the memory map for a virtual process, equivalent to reading
         * `/proc/{pid}/maps`.
         *
         * The returned byte array contains the text content of the maps file,
         * UTF-8 encoded, with each line in the standard format:
         * `address perms offset dev inode pathname`
         *
         * @param pid The PID of the virtual process.
         * @return UTF-8 encoded content of the virtual process memory map.
         */
        @JvmStatic
        external fun getMemoryMap(pid: Int): ByteArray

        /**
         * Returns the PIDs of all currently-running virtual processes.
         *
         * @return Array of PIDs. Empty if no virtual processes are running.
         */
        @JvmStatic
        external fun getProcessList(): IntArray

        /**
         * Checks whether a given PID belongs to a virtual app process.
         *
         * @param pid The PID to check.
         * @return `true` if the PID is a virtual app process.
         */
        @JvmStatic
        external fun isVirtualProcess(pid: Int): Boolean

        /**
         * Initializes the native bridge with the virtual filesystem root path.
         *
         * This must be called before any other native hook functions. It sets up:
         * - The virtual `/proc/` overlay directory
         * - Memory map tracking structures
         * - Process fork tracking
         *
         * @param virtualRootPath Absolute path to the virtual FS root
         *        (e.g. `/data/data/com.atlas.virtualspace/virtual/`).
         * @return 0 on success, negative errno value on failure.
         */
        @JvmStatic
        external fun initNativeBridge(virtualRootPath: String): Int

        /**
         * Cleans up all native hooks and releases resources.
         *
         * After calling this, no native hook functions may be used until
         * [initNativeBridge] is called again.
         *
         * @return 0 on success, negative errno value on failure.
         */
        @JvmStatic
        external fun cleanupNativeBridge(): Int

        // ──────────────────────────────────────────────────────
        //  Kotlin wrapper
        // ──────────────────────────────────────────────────────

        /**
         * High-level initialization that:
         * 1. Verifies the native library is loaded.
         * 2. Calls [initNativeBridge] with the virtual FS root.
         * 3. Installs the mmap, open, and fork hooks.
         *
         * If any step fails, previously-installed hooks are cleaned up and
         * a [Result.failure] is returned.
         *
         * Thread-safe: concurrent calls are serialized; only the first
         * successful call takes effect.
         *
         * @param path Absolute path to the virtual filesystem root.
         * @return [Result.success] if all native hooks were installed,
         *         [Result.failure] otherwise.
         */
        fun initializeNativeHooks(path: String): Result<Unit> {
            return lifecycleLock.withLock {
                runCatching {
                    if (nativeInitialized.get()) {
                        Log.w(TAG, "Native hooks already initialized (path=$initializedPath), skipping")
                        return Result.success(Unit)
                    }

                    if (!nativeLibraryLoaded.get()) {
                        throw NativeHookException("Native library atlas_native is not loaded")
                    }

                    if (path.isBlank()) {
                        throw NativeHookException("Virtual root path must not be blank")
                    }

                    Log.i(TAG, "Initializing native hooks (virtualRoot=$path)")

                    // Step 1: Initialize the native bridge
                    val initResult = initNativeBridge(path)
                    if (initResult != 0) {
                        val msg = "initNativeBridge failed with code $initResult"
                        Log.e(TAG, msg)
                        throw NativeHookException(msg)
                    }
                    Log.d(TAG, "Native bridge initialized (path=$path)")

                    // Step 2: Install hooks — if any fails, clean up and abort
                    val mmapResult = hookMmap()
                    if (mmapResult != 0) {
                        val msg = "hookMmap failed with code $mmapResult"
                        Log.e(TAG, msg)
                        runCatching { cleanupNativeBridge() }
                        throw NativeHookException(msg)
                    }
                    Log.d(TAG, "mmap hook installed")

                    val openResult = hookOpen()
                    if (openResult != 0) {
                        val msg = "hookOpen failed with code $openResult"
                        Log.e(TAG, msg)
                        runCatching { cleanupNativeBridge() }
                        throw NativeHookException(msg)
                    }
                    Log.d(TAG, "open/openat hook installed")

                    val forkResult = hookFork()
                    if (forkResult != 0) {
                        val msg = "hookFork failed with code $forkResult"
                        Log.e(TAG, msg)
                        runCatching { cleanupNativeBridge() }
                        throw NativeHookException(msg)
                    }
                    Log.d(TAG, "fork hook installed")

                    nativeInitialized.set(true)
                    initializedPath = path
                    Log.i(TAG, "All native hooks initialized successfully")
                    Result.success(Unit)
                }.getOrElse { e ->
                    Log.e(TAG, "Native hook initialization failed", e)
                    Result.failure(e)
                }
            }
        }

        /**
         * Cleans up all native hooks and releases resources.
         *
         * Thread-safe: concurrent calls are serialized.
         *
         * @return [Result.success] if cleanup succeeded, [Result.failure] otherwise.
         */
        fun teardownNativeHooks(): Result<Unit> {
            return lifecycleLock.withLock {
                runCatching {
                    if (!nativeInitialized.getAndSet(false)) {
                        Log.d(TAG, "Native hooks not initialized — nothing to tear down")
                        return Result.success(Unit)
                    }

                    val result = cleanupNativeBridge()
                    if (result != 0) {
                        throw NativeHookException("cleanupNativeBridge failed with code $result")
                    }
                    initializedPath = null
                    Log.i(TAG, "Native hooks cleaned up successfully")
                }.onFailure { e ->
                    Log.e(TAG, "Failed to clean up native hooks", e)
                }
            }
        }

        // ──────────────────────────────────────────────────────
        //  Query helpers
        // ──────────────────────────────────────────────────────

        /**
         * Returns a decoded UTF-8 string of the memory map for the given virtual process.
         *
         * @param pid The PID of the virtual process.
         * @return The memory map as a human-readable string, or an empty string on error.
         */
        fun getMemoryMapString(pid: Int): String {
            if (!nativeInitialized.get()) {
                Log.w(TAG, "Cannot get memory map: native hooks not initialized")
                return ""
            }
            return runCatching {
                String(getMemoryMap(pid), Charsets.UTF_8)
            }.getOrElse { e ->
                Log.e(TAG, "Failed to get memory map for pid $pid", e)
                ""
            }
        }

        /**
         * Returns the list of virtual process PIDs as a Kotlin [List].
         */
        fun getProcessListAsList(): List<Int> {
            if (!nativeInitialized.get()) {
                Log.w(TAG, "Cannot get process list: native hooks not initialized")
                return emptyList()
            }
            return runCatching { getProcessList().toList() }
                .getOrElse { e ->
                    Log.e(TAG, "Failed to get process list", e)
                    emptyList()
                }
        }

        /**
         * Safe wrapper for [isVirtualProcess] that returns `false` on any error.
         */
        fun isVirtualProcessSafe(pid: Int): Boolean {
            if (!nativeInitialized.get()) return false
            return runCatching { isVirtualProcess(pid) }.getOrDefault(false)
        }

        /**
         * Returns whether the native bridge is currently initialized and ready.
         */
        fun isInitialized(): Boolean = nativeInitialized.get()

        /**
         * Returns whether the native library was loaded successfully.
         */
        fun isNativeLibraryLoaded(): Boolean = nativeLibraryLoaded.get()

        /**
         * Returns the virtual root path that was used during initialization,
         * or `null` if not initialized.
         */
        fun getInitializedPath(): String? = initializedPath
    }

    /**
     * Exception thrown when a native hook operation fails.
     *
     * @property detailMessage Human-readable description of the failure.
     */
    class NativeHookException(detailMessage: String) : Exception(detailMessage)
}
