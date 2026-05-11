package com.atlas.virtualspace.core.hook

import android.util.Log
import com.atlas.virtualspace.core.engine.EngineConfig
import top.canyie.pine.ISA
import top.canyie.pine.Pine
import top.canyie.pine.Pine.CallFrame
import top.canyie.pine.Pine.HookRecord
import top.canyie.pine.PineConfig
import top.canyie.pine.callback.MethodHook
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Central manager for all method hooks using the Pine framework.
 *
 * Provides a structured, thread-safe API for hooking Java methods, storing hook
 * references with rich metadata, and cleanly unhooking them. All hook operations
 * are safe to call from any thread.
 *
 * ## Hook lifecycle
 * 1. Call [initialize] once with the engine config to configure Pine.
 * 2. Use [hookMethod] or [hookAllMethods] to install hooks — each returns a
 *    unique hook ID string.
 * 3. Use [unhook] to remove a specific hook, or [unhookAll] to remove every
 *    registered hook.
 * 4. Query state with [isHooked], [getHookCount], [getHookInfo], [getAllHookInfo].
 *
 * ## Error isolation
 * Exceptions thrown by before/after hook callbacks are caught and logged so the
 * target method is never disrupted by a buggy hook.
 *
 * ## Hook groups
 * Hooks can optionally be assigned to a group via [hookMethod]/[hookAllMethods].
 * Use [unhookGroup] to remove all hooks in a group at once.
 */
object HookManager {

    private const val TAG = "Atlas:HookManager"

    // ──────────────────────────────────────────────────────────
    //  Internal state
    // ──────────────────────────────────────────────────────────

    /** Maps a hook ID to its Pine [HookRecord] reference. */
    private val hookCallbacks = ConcurrentHashMap<String, HookRecord>()

    /** Maps a hook ID to the [Method] it targets (needed for diagnostics). */
    private val hookTargets = ConcurrentHashMap<String, Method>()

    /** Maps a hook ID to its metadata [HookInfo]. */
    private val hookMetadata = ConcurrentHashMap<String, HookInfo>()

    /** Maps a group name to the set of hook IDs belonging to that group. */
    private val hookGroups = ConcurrentHashMap<String, MutableSet<String>>()

    /** Monotonic counter for generating unique hook IDs. */
    private val hookIdGenerator = AtomicInteger(0)

    @Volatile
    private var initialized = false

    /** Optional listener invoked when a hook callback throws an exception. */
    @Volatile
    private var hookErrorListener: HookErrorListener? = null

    // ──────────────────────────────────────────────────────────
    //  Initialization
    // ──────────────────────────────────────────────────────────

    /**
     * Configures the Pine framework and prepares the hook manager.
     *
     * Must be called before any hook operations. Calling a second time is a no-op.
     *
     * @param config The engine configuration containing the classloader and other settings.
     * @return [Result.success] if Pine was configured, or [Result.failure] on error.
     */
    fun initialize(config: EngineConfig): Result<Unit> {
        return runCatching {
            if (initialized) {
                Log.w(TAG, "HookManager already initialized, skipping re-initialization")
                return Result.success(Unit)
            }

            PineConfig.isa = ISA.ARM64
            PineConfig.experimental = true
            config.classLoader?.let { PineConfig.classLoader = it }

            // Force Pine to load and validate its native library early.
            Pine.ensureInitialized()

            initialized = true
            Log.i(TAG, "Pine hook framework initialized (isa=ARM64, experimental=true)")
        }.onFailure { e ->
            Log.e(TAG, "Failed to initialize Pine hook framework", e)
        }
    }

    // ──────────────────────────────────────────────────────────
    //  Single-method hook
    // ──────────────────────────────────────────────────────────

    /**
     * Hooks a single method on a class.
     *
     * @param className  Fully-qualified class name (e.g. `"android.app.ActivityManager"`).
     * @param methodName Name of the method to hook.
     * @param paramTypes Parameter types used to resolve the specific overload.
     * @param beforeHook Optional callback invoked *before* the original method executes.
     *                   Inside this callback you can read/modify [CallFrame.args] and
     *                   short-circuit the original by setting [CallFrame.result] or
     *                   [CallFrame.throwable].
     * @param afterHook  Optional callback invoked *after* the original method executes.
     *                   You can read/replace the [CallFrame.result] here.
     * @param group      Optional group name for batch unhook via [unhookGroup].
     * @return [Result.success] with the hook ID, or [Result.failure] if the class/method
     *         could not be found or Pine rejected the hook.
     */
    fun hookMethod(
        className: String,
        methodName: String,
        paramTypes: Array<Class<*>>,
        beforeHook: ((CallFrame) -> Unit)?,
        afterHook: ((CallFrame) -> Unit)?,
        group: String? = null,
    ): Result<String> {
        return runCatching {
            requireInitialized()

            val clazz = findClass(className)
                ?: throw ClassNotFoundException("Class not found: $className")

            val method = findMethod(clazz, methodName, paramTypes)
                ?: throw NoSuchMethodException(
                    "Method not found: $className.$methodName(${paramTypes.joinToString(",") { it.simpleName }})"
                )

            val hookId = generateHookId(className, methodName)
            val pineCallback = createMethodHook(beforeHook, afterHook)

            val hookRecord = Pine.hook(method, pineCallback)

            hookCallbacks[hookId] = hookRecord
            hookTargets[hookId] = method
            hookMetadata[hookId] = HookInfo(
                hookId = hookId,
                className = className,
                methodName = methodName,
                paramTypes = paramTypes.map { it.name },
                group = group,
                installedAt = System.currentTimeMillis(),
            )

            if (group != null) {
                hookGroups.getOrPut(group) { ConcurrentHashMap.newKeySet() }.add(hookId)
            }

            Log.d(TAG, "Hooked $className.$methodName → hookId=$hookId" +
                    if (group != null) " [group=$group]" else "")
            hookId
        }.onFailure { e ->
            when (e) {
                is ClassNotFoundException ->
                    Log.e(TAG, "Cannot hook $className.$methodName: class not found", e)
                is NoSuchMethodException ->
                    Log.e(TAG, "Cannot hook $className.$methodName: method not found", e)
                else ->
                    Log.e(TAG, "Failed to hook $className.$methodName", e)
            }
        }
    }

    // ──────────────────────────────────────────────────────────
    //  All-overloads hook
    // ──────────────────────────────────────────────────────────

    /**
     * Hooks **all overloads** of [methodName] on the given class.
     *
     * This is useful for methods with multiple signatures (e.g. `startActivity`).
     *
     * @param group Optional group name for batch unhook via [unhookGroup].
     * @return [Result.success] with the list of hook IDs (one per overload),
     *         or [Result.failure] if the class cannot be found.
     */
    fun hookAllMethods(
        className: String,
        methodName: String,
        beforeHook: ((CallFrame) -> Unit)?,
        afterHook: ((CallFrame) -> Unit)?,
        group: String? = null,
    ): Result<List<String>> {
        return runCatching {
            requireInitialized()

            val clazz = findClass(className)
                ?: throw ClassNotFoundException("Class not found: $className")

            val pineCallback = createMethodHook(beforeHook, afterHook)
            val hookRecords = Pine.hookAllMethods(clazz, methodName, pineCallback)

            if (hookRecords.isEmpty()) {
                Log.w(TAG, "No overloads found for $className.$methodName")
                return@runCatching emptyList<String>()
            }

            val hookIds = hookRecords.map { record ->
                val hookId = generateHookId(className, methodName)
                hookCallbacks[hookId] = record
                hookMetadata[hookId] = HookInfo(
                    hookId = hookId,
                    className = className,
                    methodName = methodName,
                    paramTypes = emptyList(),
                    group = group,
                    installedAt = System.currentTimeMillis(),
                )
                if (group != null) {
                    hookGroups.getOrPut(group) { ConcurrentHashMap.newKeySet() }.add(hookId)
                }
                hookId
            }

            // Store the first matching method for best-effort diagnostics.
            clazz.declaredMethods
                .firstOrNull { it.name == methodName }
                ?.let { method ->
                    hookIds.forEach { id -> hookTargets[id] = method }
                }

            Log.d(TAG, "Hooked ${hookRecords.size} overloads of $className.$methodName → hookIds=$hookIds" +
                    if (group != null) " [group=$group]" else "")
            hookIds
        }.onFailure { e ->
            Log.e(TAG, "Failed to hookAllMethods $className.$methodName", e)
        }
    }

    // ──────────────────────────────────────────────────────────
    //  Unhook
    // ──────────────────────────────────────────────────────────

    /**
     * Removes a specific hook by its ID.
     *
     * @return [Result.success] if the hook was removed, or [Result.failure] if
     *         the hook ID was not found.
     */
    fun unhook(hookId: String): Result<Unit> {
        return runCatching {
            val hookRecord = hookCallbacks.remove(hookId)
            hookTargets.remove(hookId)
            val info = hookMetadata.remove(hookId)

            if (hookRecord == null) {
                throw IllegalArgumentException("Hook ID not found: $hookId")
            }

            // Remove from group index.
            info?.group?.let { groupName ->
                hookGroups[groupName]?.remove(hookId)
                if (hookGroups[groupName].isNullOrEmpty()) {
                    hookGroups.remove(groupName)
                }
            }

            Pine.unhook(hookRecord)
            Log.d(TAG, "Unhooked hookId=$hookId")
        }.onFailure { e ->
            Log.e(TAG, "Failed to unhook $hookId", e)
        }
    }

    /**
     * Removes **all** registered hooks.
     *
     * @return [Result.success] when all hooks have been removed.
     */
    fun unhookAll(): Result<Unit> {
        return runCatching {
            val ids = hookCallbacks.keys.toList()
            var failedCount = 0

            for (hookId in ids) {
                val hookRecord = hookCallbacks.remove(hookId)
                hookTargets.remove(hookId)
                hookMetadata.remove(hookId)
                if (hookRecord != null) {
                    runCatching { Pine.unhook(hookRecord) }
                        .onFailure {
                            failedCount++
                            Log.w(TAG, "Failed to unhook $hookId during unhookAll", it)
                        }
                }
            }

            hookGroups.clear()

            val msg = if (failedCount > 0) {
                "Unhooked ${ids.size - failedCount}/${ids.size} hooks ($failedCount failures)"
            } else {
                "Unhooked all ${ids.size} hooks"
            }
            Log.i(TAG, msg)
        }.onFailure { e ->
            Log.e(TAG, "Failed during unhookAll", e)
        }
    }

    /**
     * Removes all hooks belonging to the specified [groupName].
     *
     * @return The number of hooks that were successfully removed.
     */
    fun unhookGroup(groupName: String): Int {
        val ids = hookGroups.remove(groupName) ?: return 0
        var removed = 0
        for (hookId in ids) {
            val hookRecord = hookCallbacks.remove(hookId)
            hookTargets.remove(hookId)
            hookMetadata.remove(hookId)
            if (hookRecord != null) {
                runCatching { Pine.unhook(hookRecord) }
                    .onSuccess { removed++ }
                    .onFailure { Log.w(TAG, "Failed to unhook $hookId in group $groupName", it) }
            }
        }
        Log.i(TAG, "Unhooked group '$groupName': $removed/${ids.size} hooks removed")
        return removed
    }

    // ──────────────────────────────────────────────────────────
    //  Query
    // ──────────────────────────────────────────────────────────

    /** Returns `true` if a hook with the given [hookId] is currently registered. */
    fun isHooked(hookId: String): Boolean = hookCallbacks.containsKey(hookId)

    /** Returns the number of currently active hooks. */
    fun getHookCount(): Int = hookCallbacks.size

    /**
     * Returns metadata for the given hook, or `null` if the hook ID is not found.
     */
    fun getHookInfo(hookId: String): HookInfo? = hookMetadata[hookId]

    /**
     * Returns a snapshot of metadata for all currently registered hooks.
     */
    fun getAllHookInfo(): List<HookInfo> = hookMetadata.values.toList()

    /**
     * Returns the set of hook IDs belonging to a group, or an empty set.
     */
    fun getHooksInGroup(groupName: String): Set<String> =
        hookGroups[groupName]?.toSet() ?: emptySet()

    /**
     * Returns the names of all hook groups that currently have at least one hook.
     */
    fun getActiveGroups(): Set<String> = hookGroups.keys.toSet()

    /**
     * Sets a listener that is invoked when a hook callback (before or after) throws
     * an exception. This allows the embedding application to collect hook errors
     * for diagnostics or crash reporting.
     */
    fun setHookErrorListener(listener: HookErrorListener?) {
        hookErrorListener = listener
    }

    /**
     * Dumps a diagnostic summary of all registered hooks to the log.
     */
    fun dumpDiagnostics() {
        val count = hookCallbacks.size
        val groups = hookGroups.keys
        Log.i(TAG, "═══ HookManager Diagnostics ═══")
        Log.i(TAG, "  Initialized: $initialized")
        Log.i(TAG, "  Active hooks: $count")
        Log.i(TAG, "  Groups: ${groups.joinToString(", ")}")
        for ((id, info) in hookMetadata) {
            Log.i(TAG, "  [$id] ${info.className}.${info.methodName}" +
                    if (info.group != null) " [${info.group}]" else "")
        }
        Log.i(TAG, "═══════════════════════════════")
    }

    // ──────────────────────────────────────────────────────────
    //  Internal helpers
    // ──────────────────────────────────────────────────────────

    private fun requireInitialized() {
        check(initialized) { "HookManager has not been initialized; call initialize() first" }
    }

    private fun generateHookId(className: String, methodName: String): String {
        val seq = hookIdGenerator.incrementAndGet()
        val shortClass = className.substringAfterLast('.')
        return "hook_${seq}_${shortClass}_${methodName}"
    }

    /**
     * Finds a class by name using multiple classloader strategies.
     * Returns `null` if the class cannot be found through any strategy.
     */
    private fun findClass(className: String): Class<*>? {
        // Strategy 1: Pine's configured classloader
        return runCatching {
            PineConfig.classLoader?.loadClass(className)
        }.getOrNull() ?: runCatching {
            // Strategy 2: Class.forName with Pine's classloader
            Class.forName(className, false, PineConfig.classLoader)
        }.getOrNull() ?: runCatching {
            // Strategy 3: System classloader
            ClassLoader.getSystemClassLoader().loadClass(className)
        }.getOrNull() ?: runCatching {
            // Strategy 4: Default Class.forName
            Class.forName(className)
        }.getOrNull()
    }

    /**
     * Resolves a specific method overload by name and parameter types.
     * Falls back to searching declared methods if [Class.getMethod] fails.
     */
    private fun findMethod(clazz: Class<*>, methodName: String, paramTypes: Array<Class<*>>): Method? {
        return runCatching { clazz.getMethod(methodName, *paramTypes) }
            .getOrNull()
            ?: runCatching { clazz.getDeclaredMethod(methodName, *paramTypes) }
                .getOrNull()
                ?.also { it.isAccessible = true }
    }

    /**
     * Creates a [MethodHook] that delegates to the optional [beforeHook] and [afterHook]
     * lambdas, catching and logging any exceptions they throw so the target method
     * is never disrupted by a buggy hook callback.
     */
    private fun createMethodHook(
        beforeHook: ((CallFrame) -> Unit)?,
        afterHook: ((CallFrame) -> Unit)?,
    ): MethodHook {
        return object : MethodHook() {
            override fun beforeCall(callFrame: CallFrame) {
                if (beforeHook != null) {
                    runCatching { beforeHook.invoke(callFrame) }
                        .onFailure { e ->
                            Log.e(TAG, "Exception in beforeHook callback", e)
                            hookErrorListener?.onHookError(callFrame, e, isBefore = true)
                        }
                }
            }

            override fun afterCall(callFrame: CallFrame) {
                if (afterHook != null) {
                    runCatching { afterHook.invoke(callFrame) }
                        .onFailure { e ->
                            Log.e(TAG, "Exception in afterHook callback", e)
                            hookErrorListener?.onHookError(callFrame, e, isBefore = false)
                        }
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────
    //  Public types
    // ──────────────────────────────────────────────────────────

    /**
     * Metadata for a single registered hook.
     *
     * @property hookId      Unique hook identifier.
     * @property className   Fully-qualified class name that was hooked.
     * @property methodName  Method name that was hooked.
     * @property paramTypes  Parameter type names (empty for hookAllMethods).
     * @property group       Optional group name.
     * @property installedAt Epoch millis when the hook was installed.
     */
    data class HookInfo(
        val hookId: String,
        val className: String,
        val methodName: String,
        val paramTypes: List<String>,
        val group: String?,
        val installedAt: Long,
    )

    /**
     * Listener interface for hook callback errors.
     */
    fun interface HookErrorListener {
        /**
         * Called when a before/after hook callback throws an exception.
         *
         * @param callFrame The call frame active at the time of the error.
         * @param error     The exception thrown by the hook callback.
         * @param isBefore  `true` if the error occurred in a before-hook,
         *                  `false` if in an after-hook.
         */
        fun onHookError(callFrame: CallFrame, error: Throwable, isBefore: Boolean)
    }
}
