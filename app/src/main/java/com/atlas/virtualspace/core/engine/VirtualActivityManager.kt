package com.atlas.virtualspace.core.engine

import android.content.ComponentName
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import com.atlas.virtualspace.core.ipc.IPCBridge
import com.atlas.virtualspace.core.ipc.IpcCommand
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Activity lifecycle states used by [ActivityRecord].
 */
enum class ActivityState {
    /** Activity instance is being created. */
    INITIALIZING,
    /** Activity is in the foreground and has focus. */
    RESUMED,
    /** Activity has lost focus but is still visible. */
    PAUSED,
    /** Activity is no longer visible. */
    STOPPED,
    /** Activity has been destroyed. */
    DESTROYED
}

/**
 * Immutable record of a virtual activity instance tracked by the engine.
 *
 * @property token         Unique Binder token identifying this activity across processes.
 * @property componentName The resolved [ComponentName] of the virtual activity.
 * @property state         Current lifecycle state.
 * @property packageName   Package name of the hosting virtual app.
 * @property pid           PID of the hosting virtual process.
 * @property createdAt     Epoch millis when this record was created.
 */
data class ActivityRecord(
    val token: IBinder,
    val componentName: ComponentName,
    val state: ActivityState,
    val packageName: String,
    val pid: Int,
    val createdAt: Long = System.currentTimeMillis()
) : Parcelable {

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeStrongBinder(token)
        componentName.writeToParcel(parcel, flags)
        parcel.writeInt(state.ordinal)
        parcel.writeString(packageName)
        parcel.writeInt(pid)
        parcel.writeLong(createdAt)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<ActivityRecord> {
        override fun createFromParcel(parcel: Parcel): ActivityRecord {
            val token = parcel.readStrongBinder() ?: Binder()
            val componentName = ComponentName.CREATOR.createFromParcel(parcel)
            val state = ActivityState.entries[parcel.readInt()]
            val packageName = parcel.readString() ?: ""
            val pid = parcel.readInt()
            val createdAt = parcel.readLong()
            return ActivityRecord(
                token = token,
                componentName = componentName,
                state = state,
                packageName = packageName,
                pid = pid,
                createdAt = createdAt
            )
        }

        override fun newArray(size: Int): Array<ActivityRecord?> = arrayOfNulls(size)
    }
}

/**
 * Manages the lifecycle of virtual activities inside the Atlas virtual space.
 *
 * Responsibilities:
 * - Intercepting `Instrumentation.execStartActivity` via [HookManager]
 *   to redirect activity starts into the virtual environment.
 * - Resolving [Intent] targets to virtual components.
 * - Maintaining a thread-safe registry of live [ActivityRecord]s.
 * - Communicating state changes to the host process via [IPCBridge].
 *
 * This class is **not** a singleton; a single instance is owned by [VirtualEngine].
 */
class VirtualActivityManager internal constructor() {

    private val tokenSequence = AtomicLong(0L)

    /**
     * Thread-safe registry: activity token → record.
     * Compound operations must synchronize on [registryLock].
     */
    private val activityRegistry = ConcurrentHashMap<IBinder, ActivityRecord>()

    private val registryLock = Any()

    /**
     * Hook IDs installed by [installHooks], so we can unhook on shutdown.
     */
    private val installedHookIds = mutableListOf<String>()

    @Volatile
    private var isHooked = false

    // ─── Public API ──────────────────────────────────────────────

    /**
     * Starts a virtual activity.
     *
     * 1. Resolves the [Intent] to a virtual [ComponentName].
     * 2. Finds the hosting virtual process.
     * 3. Creates an [ActivityRecord] and registers it.
     * 4. Notifies the virtual process via [IPCBridge] to launch the activity.
     *
     * @param intent  The original intent. Must have a component or a resolvable action.
     * @param options Optional launch options bundle.
     * @return [Result.success] if the activity was dispatched, [Result.failure] otherwise.
     */
    fun startActivity(intent: Intent, options: Bundle?): Result<Unit> {
        return try {
            val component = resolveVirtualComponent(intent)
                ?: return Result.failure(
                    IllegalArgumentException(
                        "Cannot resolve virtual component for intent: $intent"
                    )
                )

            val processRecord = findRunningProcess(component.packageName)
                ?: return Result.failure(
                    IllegalStateException(
                        "No running virtual process for package ${component.packageName}"
                    )
                )

            val pid = processRecord.pid.get()

            val token = generateToken()
            val record = ActivityRecord(
                token = token,
                componentName = component,
                state = ActivityState.INITIALIZING,
                packageName = component.packageName,
                pid = pid
            )

            synchronized(registryLock) {
                activityRegistry[token] = record
            }

            // Notify the virtual process via the IPC bridge.
            notifyActivityStartViaIpc(record, intent, options)

            Timber.i(
                "VirtualActivity started: %s in process %d",
                component.flattenToShortString(),
                pid
            )

            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to start virtual activity for intent: %s", intent)
            Result.failure(e)
        }
    }

    /**
     * Finishes (destroys) a virtual activity identified by its [token].
     *
     * @param token The Binder token returned in [ActivityRecord.token].
     * @return [Result.success] if the activity was found and destroyed,
     *         [Result.failure] otherwise.
     */
    fun finishActivity(token: IBinder): Result<Unit> {
        return try {
            val record = synchronized(registryLock) {
                activityRegistry[token]?.let { current ->
                    val updated = current.copy(state = ActivityState.DESTROYED)
                    activityRegistry[token] = updated
                    updated
                }
            } ?: return Result.failure(
                IllegalArgumentException("No activity record for token $token")
            )

            // Notify the virtual process.
            notifyActivityFinishViaIpc(record)

            synchronized(registryLock) {
                activityRegistry.remove(token)
            }

            Timber.i(
                "VirtualActivity finished: %s",
                record.componentName.flattenToShortString()
            )

            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to finish virtual activity for token: %s", token)
            Result.failure(e)
        }
    }

    /**
     * Returns a snapshot of all currently tracked activity records.
     */
    fun getRunningActivities(): List<ActivityRecord> {
        synchronized(registryLock) {
            return activityRegistry.values.toList()
        }
    }

    /**
     * Returns the number of currently tracked activities.
     */
    fun runningActivityCount(): Int = activityRegistry.size

    /**
     * Updates the state of an activity in the registry.
     *
     * @return The updated [ActivityRecord], or `null` if the token was not found.
     */
    fun updateActivityState(token: IBinder, newState: ActivityState): ActivityRecord? {
        return synchronized(registryLock) {
            val current = activityRegistry[token] ?: return null
            val updated = current.copy(state = newState)
            activityRegistry[token] = updated
            updated
        }
    }

    /**
     * Installs Pine hooks via [HookManager] to intercept activity starts
     * in the host framework.
     *
     * Hooks `android.app.Instrumentation.execStartActivity` so that we can
     * redirect the target component into the virtual environment when needed.
     */
    fun installHooks() {
        if (isHooked) return

        try {
            val result = com.atlas.virtualspace.core.hook.HookManager.hookAllMethods(
                "android.app.Instrumentation",
                "execStartActivity",
                beforeHook = { callFrame ->
                    interceptActivityStart(callFrame)
                },
                afterHook = null
            )

            if (result.isSuccess) {
                installedHookIds.addAll(result.getOrDefault(emptyList()))
                isHooked = true
                Timber.i("VirtualActivityManager hooks installed (%d overloads)", installedHookIds.size)
            } else {
                Timber.w(result.exceptionOrNull(), "Could not hook execStartActivity; hook skipped")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to install VirtualActivityManager hooks")
        }
    }

    /**
     * Removes all Pine hooks installed by this manager.
     */
    fun removeHooks() {
        val hookManager = com.atlas.virtualspace.core.hook.HookManager
        installedHookIds.forEach { hookId ->
            hookManager.unhook(hookId)
        }
        installedHookIds.clear()
        isHooked = false
        Timber.i("VirtualActivityManager hooks removed")
    }

    /**
     * Removes all activity records whose host process is identified by [packageName].
     * Called when a process is force-stopped or crashes.
     */
    fun removeActivitiesForProcess(packageName: String) {
        synchronized(registryLock) {
            val toRemove = activityRegistry.entries
                .filter { it.value.packageName == packageName }
                .map { it.key }

            toRemove.forEach { token ->
                val record = activityRegistry.remove(token)
                record?.let {
                    Timber.d(
                        "Removed activity %s for stopped package %s",
                        it.componentName.flattenToShortString(),
                        packageName
                    )
                }
            }
        }
    }

    // ─── Internal helpers ────────────────────────────────────────

    private fun generateToken(): IBinder {
        return object : Binder() {
            override fun toString(): String =
                "AtlasActivityToken{${tokenSequence.incrementAndGet()}}"
        }
    }

    /**
     * Resolves an [Intent] to a virtual [ComponentName].
     *
     * If the intent already specifies a component we return it directly.
     * Otherwise we attempt to resolve via the installed apps registry.
     */
    private fun resolveVirtualComponent(intent: Intent): ComponentName? {
        // Direct component – most common case.
        intent.component?.let { return it }

        // Action-based resolution.
        val action = intent.action ?: return null
        val pkg = intent.`package` ?: return null

        val activityClass = resolveMainActivityForPackage(pkg)
        return if (activityClass != null) {
            ComponentName(pkg, activityClass)
        } else {
            Timber.w("Cannot resolve action %s in package %s", action, pkg)
            null
        }
    }

    /**
     * Finds the main activity class name for a virtual package.
     */
    private fun resolveMainActivityForPackage(packageName: String): String? {
        return try {
            VirtualEngine.installedApps.value.firstOrNull {
                it.packageName == packageName
            }?.launchActivity
        } catch (e: Exception) {
            Timber.w(e, "Failed to resolve main activity for %s", packageName)
            null
        }
    }

    /**
     * Locates the [InternalProcessRecord] for a running virtual package, or returns
     * `null` if the package is not currently running.
     */
    private fun findRunningProcess(packageName: String): VirtualEngine.InternalProcessRecord? {
        return VirtualEngine.getProcessRecord(packageName)?.takeIf { it.isAlive() }
    }

    /**
     * Hook callback that intercepts `Instrumentation.execStartActivity`.
     *
     * Inspects the intent; if the target component belongs to a virtual
     * package, we rewrite the intent to carry extra metadata so the
     * internal routing knows this is a virtual launch.
     */
    private fun interceptActivityStart(callFrame: top.canyie.pine.core.CallFrame) {
        try {
            // execStartActivity signature varies by API level, but the Intent
            // is always among the parameters.
            val intent = callFrame.args?.filterIsInstance<Intent>()?.firstOrNull() ?: return

            val targetPkg = intent.component?.packageName ?: intent.`package` ?: return

            val isVirtualPackage = VirtualEngine.installedApps.value.any {
                it.packageName == targetPkg
            }

            if (!isVirtualPackage) return

            // Mark the intent so that our internal routing knows this is a
            // virtual launch rather than a regular one.
            intent.putExtra(EXTRA_VIRTUAL_LAUNCH, true)
            intent.putExtra(EXTRA_REAL_COMPONENT, intent.component?.flattenToShortString())

            Timber.d("Intercepted activity start for virtual package: %s", targetPkg)
        } catch (e: Exception) {
            Timber.w(e, "Error in startActivity hook")
        }
    }

    /**
     * Sends an IPC command to the virtual process to start an activity.
     */
    private fun notifyActivityStartViaIpc(
        record: ActivityRecord,
        intent: Intent,
        options: Bundle?
    ) {
        try {
            val command = IpcCommand(
                action = "LAUNCH",
                extras = Bundle().apply {
                    putString("package_name", record.packageName)
                    putString("component", record.componentName.flattenToShortString())
                    putBoolean(EXTRA_VIRTUAL_LAUNCH, true)
                    intent.extras?.let { putBundle("intent_extras", it) }
                    options?.let { putBundle("launch_options", it) }
                }
            )
            IPCBridge.sendToVirtualApp(record.packageName, command)
        } catch (e: Exception) {
            Timber.e(e, "IPC notifyActivityStart failed for %s", record.componentName)
            throw e
        }
    }

    /**
     * Sends an IPC command to the virtual process to finish an activity.
     */
    private fun notifyActivityFinishViaIpc(record: ActivityRecord) {
        try {
            val command = IpcCommand(
                action = "FORCE_STOP",
                extras = Bundle().apply {
                    putString("package_name", record.packageName)
                    putString("component", record.componentName.flattenToShortString())
                }
            )
            IPCBridge.sendToVirtualApp(record.packageName, command)
        } catch (e: Exception) {
            Timber.e(e, "IPC notifyActivityFinish failed for %s", record.componentName)
            throw e
        }
    }

    internal companion object {
        const val EXTRA_VIRTUAL_LAUNCH = "com.atlas.virtualspace.VIRTUAL_LAUNCH"
        const val EXTRA_REAL_COMPONENT = "com.atlas.virtualspace.REAL_COMPONENT"
    }
}
