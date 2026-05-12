package com.atlas.vspace.loader

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import com.atlas.vspace.model.GuestAppManifest
import dalvik.system.DexClassLoader
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Parses guest APKs and produces the ClassLoader / Resources / AssetManager
 * that stub processes need to instantiate guest Activities.
 *
 * There is exactly one [GuestApkLoader] per Atlas-host + stub-process pair
 * (each process has its own instance, populated on demand).
 *
 * Caches are keyed by guest package name. Eviction is explicit — callers
 * call [evict] when a guest is force-stopped or uninstalled.
 */
class GuestApkLoader(private val hostContext: Context) {

    private data class Entry(
        val manifest: GuestAppManifest,
        val classLoader: DexClassLoader,
        val resources: Resources,
    )

    private val cache = ConcurrentHashMap<String, Entry>()

    /**
     * Loads (or returns cached) bundle for the given guest.
     *
     * @param manifest  Parsed guest manifest. Must point at readable APK files.
     * @param parent    Parent ClassLoader. Should be the stub Activity's own
     *                  ClassLoader so framework classes resolve correctly.
     */
    fun load(manifest: GuestAppManifest, parent: ClassLoader): GuestBundle {
        cache[manifest.packageName]?.let { existing ->
            return GuestBundle(existing.manifest, existing.classLoader, existing.resources)
        }

        val classLoader = buildClassLoader(manifest, parent)
        val resources = buildResources(manifest)

        val entry = Entry(manifest, classLoader, resources)
        cache[manifest.packageName] = entry

        Timber.i("[GuestApkLoader] Loaded %s from %s", manifest.packageName, manifest.apkPath)
        return GuestBundle(manifest, classLoader, resources)
    }

    fun get(packageName: String): GuestBundle? {
        val e = cache[packageName] ?: return null
        return GuestBundle(e.manifest, e.classLoader, e.resources)
    }

    fun evict(packageName: String) {
        cache.remove(packageName)
    }

    fun clear() {
        cache.clear()
    }

    private fun buildClassLoader(
        manifest: GuestAppManifest,
        parent: ClassLoader,
    ): DexClassLoader {
        val optimizedDir = File(hostContext.codeCacheDir, "vspace_dex/${manifest.packageName}")
        if (!optimizedDir.exists() && !optimizedDir.mkdirs()) {
            Timber.w("[GuestApkLoader] Could not create optimized dex dir: %s", optimizedDir)
        }

        val dexPath = buildString {
            append(manifest.apkPath)
            for (split in manifest.splitApkPaths) {
                if (File(split).exists()) {
                    append(File.pathSeparator)
                    append(split)
                }
            }
        }

        val libPath = resolveAbiLibPath(manifest.nativeLibDir)

        return DexClassLoader(dexPath, optimizedDir.absolutePath, libPath, parent)
    }

    private fun buildResources(manifest: GuestAppManifest): Resources {
        val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
        val addAssetPath = AssetManager::class.java.getDeclaredMethod(
            "addAssetPath", String::class.java
        )
        addAssetPath.isAccessible = true

        addAssetPath.invoke(assets, manifest.apkPath)
        for (split in manifest.splitApkPaths) {
            if (File(split).exists()) {
                addAssetPath.invoke(assets, split)
            }
        }

        val host = hostContext.resources
        return Resources(assets, host.displayMetrics, host.configuration)
    }

    /**
     * Returns the first ABI sub-directory of [nativeLibDir] that contains
     * .so files matching a supported device ABI, or null if none found.
     */
    private fun resolveAbiLibPath(nativeLibDir: String?): String? {
        if (nativeLibDir.isNullOrBlank()) return null
        val root = File(nativeLibDir)
        if (!root.exists()) return null

        for (abi in android.os.Build.SUPPORTED_ABIS) {
            val abiDir = File(root, abi)
            if (abiDir.isDirectory && abiDir.listFiles()?.any { it.name.endsWith(".so") } == true) {
                return abiDir.absolutePath
            }
        }
        // Some apps ship .so files flat in the lib dir
        if (root.listFiles()?.any { it.name.endsWith(".so") } == true) {
            return root.absolutePath
        }
        return null
    }
}

/**
 * Immutable view of a loaded guest. Callers should not keep long references
 * to the [GuestApkLoader] that produced this; use the bundle directly.
 */
data class GuestBundle(
    val manifest: GuestAppManifest,
    val classLoader: DexClassLoader,
    val resources: Resources,
)
