package com.atlas.virtualspace.core.fs

import android.content.Context
import android.os.Build
import android.system.Os
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * Manages the virtual filesystem that isolates virtual app data from the host
 * Android environment.
 *
 * The virtual root lives at `{context.dataDir}/virtual_root` and mirrors the
 * standard Android data layout under per-package subdirectories.
 *
 * Thread-safety: all public methods are safe to call from any thread. The
 * underlying [File] operations are inherently atomic at the POSIX level for
 * single-path mutations; compound operations (e.g. [createAppStorage]) are
 * internally synchronised per package name.
 */
object VirtualFileSystem {

    private lateinit var virtualRoot: File

    private val STANDARD_SUBDIRS = listOf(
        "apps",
        "shared_prefs",
        "databases",
        "cache",
        "files",
        "obb",
        "lib"
    )

    /**
     * Per-package lock to prevent races when multiple callers try to
     * create/delete storage for the same package concurrently.
     */
    private val packageLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    private fun lockFor(packageName: String): Any =
        packageLocks.getOrPut(packageName) { Any() }

    // ───────────────────────────── Initialisation ─────────────────────────────

    /**
     * Creates the virtual FS root and standard subdirectories.
     *
     * Must be called once during Application.onCreate() before any other
     * method is invoked. Calling it again is a safe no-op.
     *
     * @return [Result.success] if the root tree exists when this method returns,
     *         or [Result.failure] wrapping the first IOException encountered.
     */
    fun initialize(context: Context): Result<Unit> = runCatching {
        virtualRoot = File(context.dataDir, "virtual_root")

        if (!virtualRoot.exists() && !virtualRoot.mkdirs()) {
            throw IOException("Failed to create virtual root: ${virtualRoot.absolutePath}")
        }

        for (subdir in STANDARD_SUBDIRS) {
            val dir = File(virtualRoot, subdir)
            if (!dir.exists() && !dir.mkdirs()) {
                throw IOException("Failed to create subdir: ${dir.absolutePath}")
            }
        }

        // Set restrictive permissions on the virtual root (rwx------)
        setSecurePermissions(virtualRoot)
    }

    // ───────────────────────────── Path helpers ──────────────────────────────

    /** Root directory of the entire virtual filesystem. */
    fun getVirtualRoot(): File = virtualRoot

    /** /virtual_root/apps/{packageName}/ */
    fun getAppDataDir(packageName: String): File =
        File(File(virtualRoot, "apps"), packageName)

    /** /virtual_root/apps/{packageName}/shared_prefs/ */
    fun getAppSharedPrefsDir(packageName: String): File =
        File(getAppDataDir(packageName), "shared_prefs")

    /** /virtual_root/apps/{packageName}/databases/ */
    fun getAppDatabaseDir(packageName: String): File =
        File(getAppDataDir(packageName), "databases")

    /** /virtual_root/apps/{packageName}/cache/ */
    fun getAppCacheDir(packageName: String): File =
        File(getAppDataDir(packageName), "cache")

    /** /virtual_root/apps/{packageName}/files/ */
    fun getAppFilesDir(packageName: String): File =
        File(getAppDataDir(packageName), "files")

    /** /virtual_root/obb/{packageName}/ */
    fun getAppObbDir(packageName: String): File =
        File(File(virtualRoot, "obb"), packageName)

    /** /virtual_root/lib/{packageName}/ */
    fun getAppLibDir(packageName: String): File =
        File(File(virtualRoot, "lib"), packageName)

    // ───────────────────── App storage lifecycle ──────────────────────────────

    /**
     * Creates the complete directory tree for [packageName]:
     * ```
     * apps/{pkg}/
     * apps/{pkg}/shared_prefs/
     * apps/{pkg}/databases/
     * apps/{pkg}/cache/
     * apps/{pkg}/files/
     * apps/{pkg}/code_cache/
     * apps/{pkg}/app_webview/
     * obb/{pkg}/
     * lib/{pkg}/
     * ```
     */
    fun createAppStorage(packageName: String): Result<Unit> = runCatching {
        synchronized(lockFor(packageName)) {
            val appDir = getAppDataDir(packageName)
            val requiredDirs = listOf(
                appDir,
                File(appDir, "shared_prefs"),
                File(appDir, "databases"),
                File(appDir, "cache"),
                File(appDir, "files"),
                File(appDir, "code_cache"),
                File(appDir, "app_webview"),
                getAppObbDir(packageName),
                getAppLibDir(packageName)
            )

            for (dir in requiredDirs) {
                if (!dir.exists() && !dir.mkdirs()) {
                    throw IOException("Failed to create app dir: ${dir.absolutePath}")
                }
                setSecurePermissions(dir)
            }
        }
    }

    /**
     * Recursively deletes all data associated with [packageName],
     * including OBB and native-lib directories.
     */
    fun deleteAppStorage(packageName: String): Result<Unit> = runCatching {
        synchronized(lockFor(packageName)) {
            val appDir = getAppDataDir(packageName)
            val obbDir = getAppObbDir(packageName)
            val libDir = getAppLibDir(packageName)

            deleteRecursively(appDir)
            deleteRecursively(obbDir)
            deleteRecursively(libDir)

            packageLocks.remove(packageName)
        }
    }

    /**
     * Calculates the total storage consumed by [packageName] in bytes,
     * including app data, OBB, and native-lib directories.
     */
    fun getAppStorageSize(packageName: String): Long {
        val dirs = listOf(
            getAppDataDir(packageName),
            getAppObbDir(packageName),
            getAppLibDir(packageName)
        )
        return dirs.sumOf { dir -> calculateSize(dir) }
    }

    // ───────────────────── Symlink & mirroring ────────────────────────────────

    /**
     * Creates a symbolic link at [link] pointing to [target].
     *
     * If [link] already exists it is removed first. Parent directories of
     * [link] are created automatically.
     */
    fun createSymlink(target: File, link: File): Result<Unit> = runCatching {
        val parent = link.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Cannot create parent dir for symlink: ${parent.absolutePath}")
        }
        if (link.exists()) {
            deleteRecursively(link)
        }
        try {
            Os.symlink(target.absolutePath, link.absolutePath)
        } catch (e: Exception) {
            // Fallback: try NIO symlink creation
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Files.createSymbolicLink(link.toPath(), target.toPath())
            } else {
                throw IOException("symlink creation failed", e)
            }
        }
    }

    /**
     * Copies [source] from the real filesystem into [target] inside the
     * virtual filesystem. Works for both files and directories.
     */
    fun mirrorFile(source: File, target: File): Result<Unit> = runCatching {
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("Cannot create parent dir: ${parent.absolutePath}")
        }
        if (source.isDirectory) {
            copyDir(source, target)
        } else {
            copyFile(source, target)
        }
    }

    // ───────────────────── Path resolution ────────────────────────────────────

    /**
     * Translates a real Android filesystem path into its virtual equivalent.
     *
     * Recognised patterns:
     * - `/data/data/{pkg}/…` → `/virtual_root/apps/{pkg}/…`
     * - `/data/user/0/{pkg}/…` → `/virtual_root/apps/{pkg}/…`
     * - `/data/app/{pkg}/…` → `/virtual_root/apps/{pkg}/…`
     * - `/storage/emulated/0/Android/obb/{pkg}/…` → `/virtual_root/obb/{pkg}/…`
     * - `/data/data/{pkg}/lib/…` → `/virtual_root/lib/{pkg}/…`
     *
     * If the path does not match any known pattern it is returned unchanged.
     */
    fun resolvePath(packageName: String, originalPath: String): File {
        val path = originalPath.removeSuffix("/")

        // /data/data/{pkg}/lib/… → /virtual_root/lib/{pkg}/…
        val libPrefix = "/data/data/$packageName/lib"
        if (path.startsWith(libPrefix)) {
            val rest = path.substring(libPrefix.length)
            return File(File(getAppLibDir(packageName), packageName), rest.removePrefix("/"))
        }

        // /data/data/{pkg}/… → /virtual_root/apps/{pkg}/…
        val dataPrefix = "/data/data/$packageName"
        if (path.startsWith(dataPrefix)) {
            val rest = path.substring(dataPrefix.length).removePrefix("/")
            return if (rest.isEmpty()) getAppDataDir(packageName) else File(getAppDataDir(packageName), rest)
        }

        // /data/user/0/{pkg}/… → /virtual_root/apps/{pkg}/…
        val userPrefix = "/data/user/0/$packageName"
        if (path.startsWith(userPrefix)) {
            val rest = path.substring(userPrefix.length).removePrefix("/")
            return if (rest.isEmpty()) getAppDataDir(packageName) else File(getAppDataDir(packageName), rest)
        }

        // /data/app/{pkg}/… → /virtual_root/apps/{pkg}/…
        val appPrefix = "/data/app"
        if (path.startsWith(appPrefix)) {
            val rest = path.substring(appPrefix.length).removePrefix("/")
            // Typically /data/app/{pkg}-{hash}/base.apk
            return File(getAppDataDir(packageName), rest)
        }

        // /storage/emulated/0/Android/obb/{pkg}/… → /virtual_root/obb/{pkg}/…
        val obbPrefix = "/storage/emulated/0/Android/obb/$packageName"
        if (path.startsWith(obbPrefix)) {
            val rest = path.substring(obbPrefix.length).removePrefix("/")
            return if (rest.isEmpty()) getAppObbDir(packageName) else File(getAppObbDir(packageName), rest)
        }

        // Unrecognised – return as-is
        return File(originalPath)
    }

    // ───────────────────── Orphan cleanup ─────────────────────────────────────

    /**
     * Removes virtual storage directories that belong to packages no longer
     * present in [installedPackages].
     *
     * This is typically called once at startup to reclaim disk space left
     * behind by a crashed or interrupted uninstall.
     */
    fun cleanupOrphanedStorage(installedPackages: Set<String>): Result<Unit> = runCatching {
        val appsDir = File(virtualRoot, "apps")
        val obbDir = File(virtualRoot, "obb")
        val libDir = File(virtualRoot, "lib")

        // Clean app data dirs
        val appDirs = appsDir.listFiles()?.asList() ?: emptyList()
        for (dir in appDirs) {
            if (dir.isDirectory && dir.name !in installedPackages) {
                deleteRecursively(dir)
            }
        }

        // Clean OBB dirs
        val obbDirs = obbDir.listFiles()?.asList() ?: emptyList()
        for (dir in obbDirs) {
            if (dir.isDirectory && dir.name !in installedPackages) {
                deleteRecursively(dir)
            }
        }

        // Clean lib dirs
        val libDirs = libDir.listFiles()?.asList() ?: emptyList()
        for (dir in libDirs) {
            if (dir.isDirectory && dir.name !in installedPackages) {
                deleteRecursively(dir)
            }
        }
    }

    // ───────────────────── Internal utilities ─────────────────────────────────

    /**
     * Recursively deletes [file]. Returns `true` if all deletions succeeded.
     */
    private fun deleteRecursively(file: File): Boolean {
        if (file.isDirectory) {
            val children = file.listFiles()
            if (children != null) {
                for (child in children) {
                    if (!deleteRecursively(child)) {
                        return false
                    }
                }
            }
        }
        return file.delete()
    }

    /**
     * Calculates the total size of [file] (recursive for directories).
     */
    private fun calculateSize(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        var total = 0L
        val children = file.listFiles()
        if (children != null) {
            for (child in children) {
                total += calculateSize(child)
            }
        }
        return total
    }

    /**
     * Copies a single file from [source] to [target], replacing [target]
     * if it already exists.
     */
    private fun copyFile(source: File, target: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                }
            }
        }
        // Preserve last-modified timestamp
        target.setLastModified(source.lastModified())
    }

    /**
     * Recursively copies [source] directory to [target].
     */
    private fun copyDir(source: File, target: File) {
        if (!target.exists() && !target.mkdirs()) {
            throw IOException("Failed to create target directory: ${target.absolutePath}")
        }
        val children = source.listFiles() ?: return
        for (child in children) {
            val targetChild = File(target, child.name)
            if (child.isDirectory) {
                copyDir(child, targetChild)
            } else {
                copyFile(child, targetChild)
            }
        }
        target.setLastModified(source.lastModified())
    }

    /**
     * Sets restrictive POSIX permissions (rwx------) on [dir] when the
     * underlying filesystem supports it.
     */
    private fun setSecurePermissions(dir: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val perms = setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE
                )
                Files.setPosixFilePermissions(dir.toPath(), perms)
            } catch (_: UnsupportedOperationException) {
                // Non-POSIX filesystem (e.g. FUSE on some devices) – ignore
            }
        }
        dir.setWritable(true, true)
        dir.setReadable(true, true)
        dir.setExecutable(true, true)
    }
}
