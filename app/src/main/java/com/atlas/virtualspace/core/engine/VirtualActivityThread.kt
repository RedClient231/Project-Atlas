package com.atlas.virtualspace.core.engine

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Window
import dalvik.system.DexClassLoader
import timber.log.Timber
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages the lifecycle of guest Activities running inside Atlas's process.
 *
 * ## Role
 *
 * Android's real `ActivityThread` is a system-internal class that manages
 * Activity instances, handles Binder callbacks from the ActivityManagerService,
 * and dispatches lifecycle events. We cannot replace it, but we can manage
 * our OWN in-process guest Activity instances alongside it.
 *
 * This class:
 * - Maintains a registry of running guest Activities per package
 * - Provides the correct [ClassLoader] and [Resources] for each guest
 * - Handles lifecycle dispatch (create → start → resume → pause → stop → destroy)
 * - Manages the guest Activity's window/decor view attachment
 * - Tracks guest Activity state for the [VirtualActivityManager]
 *
 * ## Threading Model
 *
 * All lifecycle calls are dispatched on the **main thread** (UI thread) via
 * [mainHandler]. This matches Android's real behavior — Activities always
 * receive lifecycle callbacks on the main thread.
 *
 * ## Memory Safety
 *
 * Guest Activities are held via [WeakReference] in the registry. If a guest
 * Activity leaks (doesn't call onDestroy), the GC can still reclaim it.
 * The [DexClassLoader] is cached per-package to avoid re-loading .dex files.
 */
object VirtualActivityThread {

    private const val TAG = "Atlas:VActThread"

    // ─── State ───────────────────────────────────────────────────────────────

    /** Main thread handler for posting lifecycle callbacks. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Cache of DexClassLoaders keyed by package name.
     * Reused across multiple Activity launches from the same package.
     */
    private val classLoaderCache = ConcurrentHashMap<String, DexClassLoader>()

    /**
     * Cache of Resources objects keyed by package name.
     * Created once per package from the APK's asset paths.
     */
    private val resourcesCache = ConcurrentHashMap<String, Resources>()

    /**
     * Registry of currently active (not destroyed) guest Activities.
     * Key = unique activity ID (packageName + "/" + activityClass + "@" + hashCode)
     */
    private val activeActivities = ConcurrentHashMap<String, GuestActivityRecord>()

    // ─── Data Classes ────────────────────────────────────────────────────────

    /**
     * Holds all state for a single running guest Activity instance.
     */
    data class GuestActivityRecord(
        val packageName: String,
        val activityClassName: String,
        val activityRef: WeakReference<Activity>,
        val hostActivity: WeakReference<VirtualStubActivity>,
        var state: LifecycleState = LifecycleState.INITIALIZING,
        val createdAt: Long = System.currentTimeMillis()
    ) {
        val id: String get() = "$packageName/$activityClassName@${activityRef.hashCode()}"
    }

    enum class LifecycleState {
        INITIALIZING,
        CREATED,
        STARTED,
        RESUMED,
        PAUSED,
        STOPPED,
        DESTROYED
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Gets or creates a [DexClassLoader] for the given package.
     *
     * The ClassLoader loads:
     * - The base APK's .dex files
     * - All split APK .dex files
     * - Native .so libraries from the lib directory
     *
     * The ClassLoader is cached — subsequent calls for the same package
     * return the same instance (no re-parsing of .dex files).
     *
     * @param packageName  The virtual app's package name.
     * @param apkPath      Absolute path to the base APK.
     * @param splitPaths   List of absolute paths to split APKs.
     * @param nativeLibDir Absolute path to the extracted native lib dir (or null).
     * @param parentLoader The parent ClassLoader (usually Atlas's own).
     * @return The cached or newly created [DexClassLoader].
     */
    fun getOrCreateClassLoader(
        packageName: String,
        apkPath: String,
        splitPaths: List<String>,
        nativeLibDir: String?,
        parentLoader: ClassLoader
    ): DexClassLoader {
        return classLoaderCache.getOrPut(packageName) {
            val dexOutputDir = File(
                VirtualEngine.getConfig().virtualRootPath,
                "dex_opt/$packageName"
            )
            if (!dexOutputDir.exists()) dexOutputDir.mkdirs()

            val dexPath = buildString {
                append(apkPath)
                for (split in splitPaths) {
                    append(File.pathSeparator)
                    append(split)
                }
            }

            val libPath = resolveNativeLibPath(nativeLibDir)

            Timber.d("$TAG: Creating ClassLoader for %s (dex=%s, lib=%s)",
                packageName, dexPath, libPath ?: "none")

            DexClassLoader(
                dexPath,
                dexOutputDir.absolutePath,
                libPath,
                parentLoader
            )
        }
    }

    /**
     * Gets or creates a [Resources] object for the given package.
     *
     * Uses reflection on [AssetManager.addAssetPath] to add the guest APK's
     * assets. This allows the guest Activity to inflate layouts, load drawables,
     * access strings, etc. from its own APK.
     *
     * @param packageName The virtual app's package name.
     * @param apkPath     Absolute path to the base APK.
     * @param splitPaths  Paths to split APKs (added as additional asset paths).
     * @param hostContext The host Activity's context (for display metrics).
     * @return A [Resources] instance backed by the guest APK's assets.
     */
    fun getOrCreateResources(
        packageName: String,
        apkPath: String,
        splitPaths: List<String>,
        hostContext: Context
    ): Resources {
        return resourcesCache.getOrPut(packageName) {
            val assetManager = AssetManager::class.java.getDeclaredConstructor().newInstance()
            val addAssetPath = AssetManager::class.java.getDeclaredMethod(
                "addAssetPath", String::class.java
            )
            addAssetPath.isAccessible = true

            // Add base APK
            addAssetPath.invoke(assetManager, apkPath)

            // Add split APKs
            for (split in splitPaths) {
                if (File(split).exists()) {
                    addAssetPath.invoke(assetManager, split)
                }
            }

            val metrics = hostContext.resources.displayMetrics
            val config = hostContext.resources.configuration

            Resources(assetManager, metrics, config)
        }
    }

    /**
     * Loads all native (.so) libraries from the guest app's lib directory
     * into the current process.
     *
     * After this call, the .so regions are visible in /proc/self/maps,
     * which is essential for GameGuardian to detect and scan the game.
     *
     * @param packageName The package name (for logging).
     * @param nativeLibDir Absolute path to the native lib directory.
     */
    fun loadNativeLibraries(packageName: String, nativeLibDir: String?) {
        if (nativeLibDir.isNullOrBlank()) return

        val libRoot = File(nativeLibDir)
        if (!libRoot.exists()) return

        // Find the first matching ABI directory
        val supportedAbis = android.os.Build.SUPPORTED_ABIS
        var loadedFrom: File? = null

        for (abi in supportedAbis) {
            val abiDir = File(libRoot, abi)
            if (abiDir.exists() && abiDir.isDirectory) {
                loadedFrom = abiDir
                break
            }
        }

        // Fallback: .so files directly in lib root
        if (loadedFrom == null && libRoot.listFiles()?.any { it.name.endsWith(".so") } == true) {
            loadedFrom = libRoot
        }

        if (loadedFrom == null) {
            Timber.d("$TAG: No native libs found for %s", packageName)
            return
        }

        val soFiles = loadedFrom.listFiles()?.filter { it.name.endsWith(".so") }?.sorted() ?: return
        Timber.i("$TAG: Loading %d native libs for %s from %s",
            soFiles.size, packageName, loadedFrom.absolutePath)

        // First pass: load all libs, collecting failures
        val deferred = mutableListOf<File>()
        for (so in soFiles) {
            try {
                System.load(so.absolutePath)
                Timber.d("$TAG: Loaded %s", so.name)
            } catch (e: UnsatisfiedLinkError) {
                deferred.add(so)
            } catch (e: Exception) {
                Timber.w(e, "$TAG: Failed to load %s", so.name)
            }
        }

        // Second pass: retry deferred (dependency order resolved)
        for (so in deferred) {
            try {
                System.load(so.absolutePath)
                Timber.d("$TAG: Loaded (deferred) %s", so.name)
            } catch (e: Exception) {
                Timber.w(e, "$TAG: Still failed to load %s", so.name)
            }
        }
    }

    /**
     * Registers a newly created guest Activity in the active registry.
     *
     * @param record The [GuestActivityRecord] to track.
     */
    fun registerActivity(record: GuestActivityRecord) {
        activeActivities[record.id] = record
        Timber.d("$TAG: Registered guest activity %s (total active: %d)",
            record.id, activeActivities.size)
    }

    /**
     * Updates the lifecycle state of a registered guest Activity.
     */
    fun updateState(record: GuestActivityRecord, newState: LifecycleState) {
        record.state = newState
        if (newState == LifecycleState.DESTROYED) {
            activeActivities.remove(record.id)
            Timber.d("$TAG: Unregistered guest activity %s (total active: %d)",
                record.id, activeActivities.size)
        }
    }

    /**
     * Returns the number of currently active (non-destroyed) guest Activities
     * for a given package. Used by [VirtualEngine] to determine if an app
     * is "running."
     */
    fun getActiveActivityCount(packageName: String): Int {
        return activeActivities.values.count {
            it.packageName == packageName && it.state != LifecycleState.DESTROYED
        }
    }

    /**
     * Returns all active guest Activity records for a given package.
     */
    fun getActiveActivities(packageName: String): List<GuestActivityRecord> {
        return activeActivities.values.filter {
            it.packageName == packageName && it.state != LifecycleState.DESTROYED
        }
    }

    /**
     * Destroys all guest Activities for a package (used during force-stop).
     * Posts destroy calls on the main thread.
     */
    fun destroyAllForPackage(packageName: String) {
        val toDestroy = activeActivities.values.filter { it.packageName == packageName }
        for (record in toDestroy) {
            mainHandler.post {
                try {
                    val guest = record.activityRef.get()
                    if (guest != null) {
                        val onDestroy = Activity::class.java.getDeclaredMethod("onDestroy")
                        onDestroy.isAccessible = true
                        onDestroy.invoke(guest)
                    }
                } catch (e: Exception) {
                    Timber.w(e, "$TAG: Error destroying guest activity %s", record.id)
                }
                record.state = LifecycleState.DESTROYED
                activeActivities.remove(record.id)
            }
        }
        Timber.i("$TAG: Destroyed %d activities for %s", toDestroy.size, packageName)
    }

    /**
     * Clears the ClassLoader and Resources cache for a package.
     * Called when an app is uninstalled from the virtual space.
     */
    fun clearCacheForPackage(packageName: String) {
        classLoaderCache.remove(packageName)
        resourcesCache.remove(packageName)
        Timber.d("$TAG: Cleared caches for %s", packageName)
    }

    /**
     * Clears all caches. Called during engine shutdown.
     */
    fun clearAll() {
        destroyAllRunning()
        classLoaderCache.clear()
        resourcesCache.clear()
        activeActivities.clear()
    }

    // ─── Private Helpers ─────────────────────────────────────────────────────

    /**
     * Destroys all currently active guest Activities (engine shutdown path).
     */
    private fun destroyAllRunning() {
        val packages = activeActivities.values.map { it.packageName }.toSet()
        for (pkg in packages) {
            destroyAllForPackage(pkg)
        }
    }

    /**
     * Resolves the native library path from the given directory.
     * Returns the path to the first ABI-matching subdirectory, or null.
     */
    private fun resolveNativeLibPath(nativeLibDir: String?): String? {
        if (nativeLibDir.isNullOrBlank()) return null
        val root = File(nativeLibDir)
        if (!root.exists()) return null

        val supportedAbis = android.os.Build.SUPPORTED_ABIS
        for (abi in supportedAbis) {
            val abiDir = File(root, abi)
            if (abiDir.exists() && abiDir.isDirectory &&
                abiDir.listFiles()?.any { it.name.endsWith(".so") } == true
            ) {
                return abiDir.absolutePath
            }
        }

        // Maybe .so files directly in root
        if (root.listFiles()?.any { it.name.endsWith(".so") } == true) {
            return root.absolutePath
        }

        return null
    }
}
