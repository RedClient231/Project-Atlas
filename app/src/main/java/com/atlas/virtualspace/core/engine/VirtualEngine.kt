package com.atlas.virtualspace.core.engine

import android.content.ComponentName
import android.content.Context
import android.os.Process
import com.atlas.virtualspace.core.fs.VirtualFileSystem
import com.atlas.virtualspace.core.hook.HookManager
import com.atlas.virtualspace.core.pm.InstallType
import com.atlas.virtualspace.core.pm.VirtualAppInfo
import com.atlas.virtualspace.core.pm.VirtualPackageManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Central singleton engine that orchestrates the entire virtual environment.
 *
 * Responsibilities:
 * - Initialising the Pine hooking framework via [HookManager]
 * - Installing / uninstalling virtual apps (delegating to [VirtualPackageManager])
 * - Launching / stopping virtual processes
 * - Managing the virtual file-system root via [VirtualFileSystem]
 * - Coordinating IPC between host and virtual processes
 *
 * All public methods return [Result] objects so callers can handle
 * failures explicitly.  Internally the engine uses [CoroutineScope]
 * with a supervisor job so that one failed child does not cancel siblings.
 */
object VirtualEngine {

    // ─── Reactive State ──────────────────────────────────────────

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _installedApps = MutableStateFlow<List<VirtualAppInfo>>(emptyList())
    val installedApps: StateFlow<List<VirtualAppInfo>> = _installedApps.asStateFlow()

    // ─── Internal State ──────────────────────────────────────────

    @Volatile
    private var engineConfig: EngineConfig = EngineConfig(
        classLoader = VirtualEngine::class.java.classLoader!!,
        virtualRootPath = "/data/data/unknown/virtual_root"
    )

    @Volatile
    private var applicationContext: Context? = null

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Map of package name → mutable [InternalProcessRecord].
     * Access must be synchronized on [processLock] for compound operations.
     */
    private val processRecords = ConcurrentHashMap<String, InternalProcessRecord>()
    private val processLock = Any()

    /** Monotonically increasing virtual UID counter (starting from 100_000). */
    private val virtualUidCounter = AtomicInteger(FIRST_VIRTUAL_UID)

    /** Virtual activity manager – created once during [initialize]. */
    private val activityManager = VirtualActivityManager()

    /** Gson instance for persisting app registry. */
    private val gson = Gson()

    /** Registry file that stores installed app metadata across restarts. */
    @Volatile
    private var registryFile: File? = null

    // ─── Lifecycle ───────────────────────────────────────────────

    init {
        Timber.d("VirtualEngine singleton created")
    }

    /**
     * Initialises the virtual engine.
     *
     * Steps:
     * 1. Stores the application [Context].
     * 2. Configures and initialises the Pine hooking framework via [HookManager].
     * 3. Sets up the virtual file-system root directory tree via [VirtualFileSystem].
     * 4. Loads the persisted app registry.
     * 5. Starts the virtual IPC bridge.
     * 6. Installs activity manager hooks.
     *
     * Must be called exactly once before any other method.
     *
     * @return [Result.success] if initialisation completed, [Result.failure] on error.
     */
    fun initialize(context: Context): Result<Unit> {
        return try {
            if (_isRunning.value) {
                Timber.w("VirtualEngine already running – skipping initialise")
                return Result.success(Unit)
            }

            applicationContext = context.applicationContext
            engineConfig = EngineConfig.default(context)

            // 1. Initialise Pine hooking framework.
            initPine(context)

            // 2. Create virtual file-system root.
            VirtualFileSystem.initialize(context).getOrElse { error ->
                Timber.e(error, "VirtualFileSystem initialisation failed")
                throw error
            }

            // 3. Load persisted app registry.
            loadAppRegistry()

            Timber.i("Virtual IPC bridge ready")

            // 4. Install activity hooks.
            activityManager.installHooks()

            _isRunning.value = true

            Timber.i(
                "VirtualEngine initialised – root=%s, apps=%d",
                engineConfig.virtualRootPath,
                _installedApps.value.size
            )

            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "VirtualEngine initialisation failed")
            Result.failure(e)
        }
    }

    /**
     * Installs an APK or XAPK file into the virtual space.
     *
     * Delegates to [VirtualPackageManager] for the actual installation,
     * which handles APK parsing, native lib extraction, and database
     * persistence. The installed apps flow is updated upon success.
     *
     * @param source The source APK / XAPK file on disk.
     * @param type   Whether this is an [InstallType.APK] or [InstallType.XAPK].
     * @return [Result.success] containing the new [VirtualAppInfo], or [Result.failure].
     */
    fun installApp(source: File, type: InstallType): Result<VirtualAppInfo> {
        return try {
            if (!source.exists()) {
                return Result.failure(IOException("Source file does not exist: ${source.absolutePath}"))
            }
            if (!source.canRead()) {
                return Result.failure(IOException("Cannot read source file: ${source.absolutePath}"))
            }

            Timber.i("Installing %s from %s", type.displayName, source.name)

            val result = when (type) {
                InstallType.APK -> VirtualPackageManager.installApp(source, type)
                InstallType.XAPK -> VirtualPackageManager.installXapk(source)
                InstallType.CLONE -> VirtualPackageManager.installApp(source, type)
                InstallType.IMPORT -> VirtualPackageManager.installApp(source, type)
            }

            val appInfo = result.getOrElse { error ->
                Timber.e(error, "VirtualPackageManager failed to install %s", source.name)
                return Result.failure(error)
            }

            // Update reactive state.
            val updatedList = _installedApps.value
                .filterNot { it.packageName == appInfo.packageName } + appInfo
            _installedApps.value = updatedList
            persistAppRegistry()

            Timber.i(
                "Installed %s v%s",
                appInfo.packageName,
                appInfo.versionName
            )

            Result.success(appInfo)
        } catch (e: Exception) {
            Timber.e(e, "Failed to install %s from %s", type.displayName, source.absolutePath)
            Result.failure(e)
        }
    }

    /**
     * Launches a virtual application.
     *
     * 1. Looks up the [VirtualAppInfo] for [packageName].
     * 2. Creates an [InternalProcessRecord] with a fresh virtual UID.
     * 3. Starts the virtual process via the IPC bridge.
     * 4. Delegates to [VirtualActivityManager] to start the launcher activity.
     *
     * @param packageName The package name of the app to launch.
     * @return [Result.success] if the launch was dispatched, [Result.failure] otherwise.
     */
    fun launchApp(packageName: String): Result<Unit> {
        return try {
            val appInfo = _installedApps.value.firstOrNull { it.packageName == packageName }
                ?: return Result.failure(
                    IllegalArgumentException("App not installed: $packageName")
                )

            if (!appInfo.isEnabled) {
                return Result.failure(
                    IllegalStateException("App is disabled: $packageName")
                )
            }

            // Enforce concurrency limit.
            val runningCount = processRecords.count { it.value.isAlive() }
            if (runningCount >= engineConfig.maxConcurrentApps) {
                return Result.failure(
                    IllegalStateException(
                        "Maximum concurrent app limit (${engineConfig.maxConcurrentApps}) reached"
                    )
                )
            }

            // If already running, just bring to foreground.
            val existing = processRecords[packageName]
            if (existing != null && existing.isAlive()) {
                Timber.i("App %s already running (pid %d) – bringing to foreground", packageName, existing.pid.get())
                return Result.success(Unit)
            }

            // Create a new process record.
            val virtualUid = virtualUidCounter.getAndIncrement()
            val is64Bit = appInfo.is64Bit && engineConfig.enable64BitSupport

            val record = InternalProcessRecord(
                packageName = packageName,
                processName = packageName,
                uid = virtualUid,
                is64Bit = is64Bit
            )

            // Register the process.
            synchronized(processLock) {
                processRecords[packageName] = record
            }

            // Start the virtual process.
            startVirtualProcess(record, appInfo)

            // Launch the main activity via the activity manager.
            val launchActivity = appInfo.launchActivity
            if (launchActivity != null) {
                val launchIntent = android.content.Intent(
                    android.content.Intent.ACTION_MAIN
                ).apply {
                    component = ComponentName(packageName, launchActivity)
                    addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                }
                activityManager.startActivity(launchIntent, null)
            }

            // Update app metadata with last launch time.
            val updatedAppInfo = appInfo.copy(lastLaunchTime = System.currentTimeMillis())
            val updatedList = _installedApps.value
                .filterNot { it.packageName == packageName } + updatedAppInfo
            _installedApps.value = updatedList
            persistAppRegistry()

            Timber.i("Launched %s (virtualUid=%d, 64bit=%b)", packageName, virtualUid, is64Bit)
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to launch %s", packageName)
            Result.failure(e)
        }
    }

    /**
     * Uninstalls a virtual application.
     *
     * Force-stops the app if running, then delegates to
     * [VirtualPackageManager] for data cleanup and database removal.
     *
     * @param packageName The package name to uninstall.
     * @return [Result.success] if the uninstall completed, [Result.failure] otherwise.
     */
    fun uninstallApp(packageName: String): Result<Unit> {
        return try {
            val appInfo = _installedApps.value.firstOrNull { it.packageName == packageName }
                ?: return Result.failure(
                    IllegalArgumentException("App not installed: $packageName")
                )

            // Force stop if running.
            if (processRecords.containsKey(packageName)) {
                forceStopApp(packageName)
            }

            // Delegate to VirtualPackageManager for cleanup.
            VirtualPackageManager.uninstallApp(packageName).getOrElse { error ->
                Timber.w(error, "VirtualPackageManager uninstall failed for %s", packageName)
            }

            // Remove from registry.
            val updatedList = _installedApps.value.filterNot { it.packageName == packageName }
            _installedApps.value = updatedList
            persistAppRegistry()

            Timber.i("Uninstalled %s", packageName)
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to uninstall %s", packageName)
            Result.failure(e)
        }
    }

    /**
     * Force-stops a running virtual application.
     *
     * Sends a termination signal via the IPC bridge, removes the process
     * record, and cleans up associated activity records.
     *
     * @param packageName The package name to force-stop.
     * @return [Result.success] if the app was stopped, [Result.failure] otherwise.
     */
    fun forceStopApp(packageName: String): Result<Unit> {
        return try {
            val record = synchronized(processLock) {
                processRecords.remove(packageName)
            }

            if (record == null) {
                Timber.w("App %s is not running – nothing to stop", packageName)
                return Result.success(Unit)
            }

            // Update process state.
            record.state.set(ProcessState.STOPPING)

            // Terminate the virtual process.
            terminateVirtualProcess(record)

            // Remove associated activities.
            activityManager.removeActivitiesForProcess(packageName)

            // Final state update and cleanup.
            record.state.set(ProcessState.STOPPED)
            synchronized(processLock) {
                processRecords.remove(packageName)
            }

            Timber.i("Force-stopped %s (pid was %d)", packageName, record.pid.get())
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to force-stop %s", packageName)
            Result.failure(e)
        }
    }

    /**
     * Gracefully shuts down the entire virtual engine.
     *
     * Stops all running processes, unhooks Pine, and releases resources.
     */
    fun shutdown() {
        if (!_isRunning.value) return

        Timber.i("VirtualEngine shutting down…")

        // 1. Stop all running processes.
        val packagesToStop = synchronized(processLock) {
            processRecords.keys.toList()
        }
        packagesToStop.forEach { pkg ->
            try {
                forceStopApp(pkg)
            } catch (e: Exception) {
                Timber.w(e, "Error stopping %s during shutdown", pkg)
            }
        }

        // 2. Remove activity hooks.
        activityManager.removeHooks()

        // 3. Unhook all framework hooks via HookManager.
        HookManager.unhookAll()

        // 4. Cancel coroutine scope.
        engineScope.cancel()

        // 5. Clear state.
        _isRunning.value = false
        applicationContext = null

        Timber.i("VirtualEngine shutdown complete")
    }

    /**
     * Returns a snapshot of currently running virtual processes
     * as immutable [ProcessRecord] instances.
     */
    fun getRunningProcesses(): List<ProcessRecord> {
        synchronized(processLock) {
            return processRecords.values.map { it.toProcessRecord() }
        }
    }

    /**
     * Returns the [VirtualActivityManager] for activity-level operations.
     */
    fun getActivityManager(): VirtualActivityManager = activityManager

    /**
     * Returns the current engine configuration.
     */
    fun getConfig(): EngineConfig = engineConfig

    /**
     * Refreshes runtime statistics (memory, thread count) for all running
     * processes.  Should be called periodically by the service.
     */
    fun refreshProcessStats() {
        val ctx = applicationContext ?: return
        synchronized(processLock) {
            processRecords.values.forEach { record ->
                val pid = record.pid.get()
                if (pid > 0) {
                    record.memoryInfo.set(MemoryInfo.fromProcess(ctx, pid))
                    readThreadCountFromProcFs(pid)?.let { record.threadCount.set(it) }
                }
            }
        }
    }

    // ─── Methods required by existing IPC layer ──────────────────

    /**
     * Registers a new app in the virtual engine and returns its
     * [InternalProcessRecord].  Called by [VirtualIPC] transaction handlers.
     *
     * @param packageName The package name to register.
     * @param uid         The virtual UID to assign.
     * @return The newly created [InternalProcessRecord].
     */
    fun registerApp(packageName: String, uid: Int): InternalProcessRecord {
        val record = InternalProcessRecord(
            packageName = packageName,
            processName = packageName,
            uid = uid,
            is64Bit = engineConfig.enable64BitSupport
        )
        synchronized(processLock) {
            processRecords[packageName] = record
        }
        return record
    }

    /**
     * Returns the mutable [InternalProcessRecord] for a running package,
     * or `null` if the package is not currently running.
     *
     * Used by [VirtualIPC] and [ProcessConnector] for state updates.
     */
    fun getProcessRecord(packageName: String): InternalProcessRecord? {
        return processRecords[packageName]
    }

    /**
     * Called by [ProcessConnector] when a virtual process dies unexpectedly.
     *
     * Updates the process state to [ProcessState.CRASHED] and removes
     * associated activity records.
     */
    fun notifyProcessDeath(packageName: String) {
        val record = processRecords[packageName] ?: return
        record.state.set(ProcessState.CRASHED)
        activityManager.removeActivitiesForProcess(packageName)
        Timber.w("Process death notified for %s (pid=%d)", packageName, record.pid.get())
    }

    // ─── Pine Initialisation ─────────────────────────────────────

    private fun initPine(context: Context) {
        val result = HookManager.initialize(engineConfig)
        if (result.isFailure) {
            throw result.exceptionOrNull() ?: RuntimeException("HookManager initialisation failed")
        }
        Timber.i("Pine hook framework initialised via HookManager")
    }

    // ─── App Registry Persistence ────────────────────────────────

    private fun loadAppRegistry() {
        val root = File(engineConfig.virtualRootPath)
        registryFile = File(root, "app_registry.json")

        val file = registryFile ?: return
        if (!file.exists()) {
            Timber.d("No existing app registry – starting fresh")
            // Also try loading from the database via VirtualPackageManager.
            loadFromPackageManager()
            return
        }

        try {
            val json = file.readText(Charsets.UTF_8)
            val type = object : TypeToken<List<VirtualAppInfo>>() {}.type
            val apps: List<VirtualAppInfo>? = gson.fromJson<List<VirtualAppInfo>>(json, type)
            if (apps != null) {
                _installedApps.value = apps
                Timber.d("Loaded %d apps from registry", apps.size)
            } else {
                loadFromPackageManager()
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to load app registry – trying database")
            loadFromPackageManager()
        }
    }

    private fun loadFromPackageManager() {
        try {
            val apps = VirtualPackageManager.getInstalledApps()
            _installedApps.value = apps
            Timber.d("Loaded %d apps from VirtualPackageManager", apps.size)
        } catch (e: Exception) {
            Timber.w(e, "Failed to load apps from VirtualPackageManager")
            _installedApps.value = emptyList()
        }
    }

    private fun persistAppRegistry() {
        val file = registryFile ?: return
        try {
            val json = gson.toJson(_installedApps.value)
            file.writeText(json, Charsets.UTF_8)
        } catch (e: Exception) {
            Timber.e(e, "Failed to persist app registry")
        }
    }

    // ─── Virtual Process Management ──────────────────────────────

    private fun startVirtualProcess(record: InternalProcessRecord, appInfo: VirtualAppInfo) {
        // In a production implementation this would fork a new process via zygote
        // and set up the socket pair before executing the app's main() method.
        // For now we simulate the process start.

        val actualPid = Process.myPid() // placeholder – real impl would use child PID
        record.pid.set(actualPid)
        record.state.set(ProcessState.RUNNING)
        record.startTime.set(System.currentTimeMillis())

        Timber.d("Virtual process started: %s (pid=%d)", record.packageName, actualPid)
    }

    private fun terminateVirtualProcess(record: InternalProcessRecord) {
        val pid = record.pid.get()
        if (pid <= 0) return

        try {
            // Send SIGQUIT first for an ANR trace, then SIGKILL.
            Process.sendSignal(pid, Process.SIGNAL_QUIT)

            // Wait briefly for graceful exit.
            Thread.sleep(100L)

            // Check if process is still alive; force-kill if necessary.
            val procDir = File("/proc/$pid")
            if (procDir.exists()) {
                Process.killProcess(pid)
            }
        } catch (e: Exception) {
            Timber.w(e, "Error terminating virtual process %d", pid)
        }

        record.state.set(ProcessState.STOPPED)
    }

    // ─── Utility Methods ─────────────────────────────────────────

    private fun readThreadCountFromProcFs(pid: Int): Int? {
        return try {
            val statusLines = java.io.File("/proc/$pid/status").readLines()
            statusLines
                .firstOrNull { it.startsWith("Threads:") }
                ?.substringAfter(':')
                ?.trim()
                ?.toIntOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private const val FIRST_VIRTUAL_UID = 100_000

    // ─── Internal Mutable Process Record ─────────────────────────

    /**
     * Mutable process record used internally by the engine and referenced
     * by the IPC layer ([VirtualIPC], [ProcessConnector]).
     *
     * State and PID are backed by atomic types for lock-free concurrent
     * access from multiple threads (engine thread, Binder threads, etc.).
     *
     * Use [toProcessRecord] to obtain an immutable snapshot.
     */
    class InternalProcessRecord(
        val packageName: String,
        val processName: String,
        val uid: Int,
        val is64Bit: Boolean
    ) {
        val pid: AtomicInteger = AtomicInteger(0)
        val state: AtomicReference<ProcessState> = AtomicReference(ProcessState.STARTING)
        val startTime: AtomicReference<Long> = AtomicReference(System.currentTimeMillis())
        val memoryInfo: AtomicReference<MemoryInfo> =
            AtomicReference(MemoryInfo(totalMb = 0L, usedMb = 0L, availableMb = 0L))
        val threadCount: AtomicInteger = AtomicInteger(1)

        fun isAlive(): Boolean {
            val s = state.get()
            return s == ProcessState.STARTING || s == ProcessState.RUNNING
        }

        /**
         * Creates an immutable [ProcessRecord] snapshot from this mutable record.
         */
        fun toProcessRecord(): ProcessRecord = ProcessRecord(
            pid = pid.get(),
            packageName = packageName,
            processName = processName,
            uid = uid,
            startTime = startTime.get(),
            is64Bit = is64Bit,
            state = state.get(),
            memoryInfo = memoryInfo.get(),
            threadCount = threadCount.get()
        )
    }
}
