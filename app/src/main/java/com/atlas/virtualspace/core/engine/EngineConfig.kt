package com.atlas.virtualspace.core.engine

import android.content.Context
import java.io.File

/**
 * Configuration for the Atlas virtual engine runtime.
 *
 * Provides paths, limits, and feature flags used by [VirtualEngine] and
 * the hook/IPC layers.
 *
 * @property virtualRootPath      Absolute path to the virtual filesystem root.
 * @property maxConcurrentApps    Maximum number of virtual apps that can run simultaneously.
 * @property enable64BitSupport   Whether to launch virtual apps in 64-bit mode when available.
 * @property debugMode            Whether to emit verbose hook and IPC debug logs.
 * @property enableNativeHooks    Whether to load and activate the native JNI hook bridge.
 */
data class EngineConfig(
    val virtualRootPath: String,
    val maxConcurrentApps: Int = DEFAULT_MAX_CONCURRENT_APPS,
    val enable64BitSupport: Boolean = true,
    val debugMode: Boolean = false,
    val enableNativeHooks: Boolean = true,
) {

    /** The classloader used to resolve target classes during hook setup. */
    var classLoader: ClassLoader? = null
        private set

    /**
     * Returns the APK storage directory for the given [packageName].
     */
    fun apkDirForPackage(packageName: String): File =
        File(virtualRootPath, "app/$packageName")

    /**
     * Returns the data directory for the given [packageName].
     */
    fun dataDirForPackage(packageName: String): File =
        File(virtualRootPath, "data/$packageName")

    /**
     * Returns the cache directory for the given [packageName].
     */
    fun cacheDirForPackage(packageName: String): File =
        File(virtualRootPath, "cache/$packageName")

    companion object {
        private const val DEFAULT_MAX_CONCURRENT_APPS = 5

        /**
         * Creates a default [EngineConfig] derived from the application [context].
         *
         * The virtual root is placed under the app's private data directory.
         */
        fun default(context: Context): EngineConfig {
            // CRITICAL: Must use context.dataDir (NOT context.filesDir) to match
            // VirtualFileSystem which uses context.dataDir for virtualRoot.
            // context.dataDir = /data/data/{pkg}
            // context.filesDir = /data/data/{pkg}/files
            // Using filesDir previously caused the app registry to be stored at
            // a different path than where VirtualFileSystem creates the actual
            // directories, making the registry never found.
            val rootDir = File(context.dataDir, "virtual_root").absolutePath
            return EngineConfig(
                virtualRootPath = rootDir,
            ).apply {
                classLoader = context.classLoader
            }
        }
    }
}
