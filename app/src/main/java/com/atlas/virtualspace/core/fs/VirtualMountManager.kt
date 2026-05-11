package com.atlas.virtualspace.core.fs

import com.atlas.virtualspace.core.pm.VirtualAppInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages mount points and path redirection for virtual processes.
 *
 * When a virtual app is launched the manager creates a set of [MountPoint]s
 * that redirect the app's real filesystem accesses to their virtual
 * counterparts. The resolution logic in [resolvePath] walks the mount table
 * to translate any path a virtual process might request.
 *
 * Thread-safety: all mutable state is stored in [ConcurrentHashMap], and
 * compound operations (setup/teardown) are guarded by per-package monitors.
 */
object VirtualMountManager {

    // ───────────────────── Public types ───────────────────────────────────────

    enum class MountType {
        /** Bind-mount: source directory appears at target path. */
        BIND,
        /** Overlay: upper + lower layers merged at target path. */
        OVERLAY,
        /** tmpfs: in-memory filesystem mounted at target (e.g. for /proc spoofing). */
        TMPFS
    }

    data class MountPoint(
        /** The real/virtual source directory that should be visible at [target]. */
        val source: String,
        /** The path inside the virtual process where [source] will appear. */
        val target: String,
        val type: MountType,
        val readOnly: Boolean = false
    )

    // ───────────────────── Internal state ─────────────────────────────────────

    /**
     * Keyed by mount-target path. A ConcurrentHashMap guarantees safe
     * iteration from [resolvePath] while another thread adds/removes points.
     */
    private val mountPoints = ConcurrentHashMap<String, MountPoint>()

    /**
     * Tracks which package owns which targets, so [teardownAppMounts] can
     * remove them without scanning the entire mount table.
     */
    private val packageMounts = ConcurrentHashMap<String, MutableSet<String>>()

    /** Per-package lock for compound setup/teardown operations. */
    private val packageLocks = ConcurrentHashMap<String, Any>()

    private fun lockFor(packageName: String): Any =
        packageLocks.getOrPut(packageName) { Any() }

    // ───────────────────── Mount CRUD ─────────────────────────────────────────

    /**
     * Registers a new mount point.
     *
     * If a mount point already exists at [target] it is replaced.
     *
     * @return [Result.failure] if [source] or [target] is blank.
     */
    fun addMountPoint(
        source: String,
        target: String,
        type: MountType,
        readOnly: Boolean = false
    ): Result<Unit> = runCatching {
        require(source.isNotBlank()) { "Source path must not be blank" }
        require(target.isNotBlank()) { "Target path must not be blank" }

        val mount = MountPoint(
            source = source.removeSuffix("/"),
            target = target.removeSuffix("/"),
            type = type,
            readOnly = readOnly
        )
        mountPoints[mount.target] = mount
    }

    /**
     * Removes the mount point whose target equals [target].
     *
     * @return [Result.success] whether or not a matching point was found.
     */
    fun removeMountPoint(target: String): Result<Unit> = runCatching {
        val normalised = target.removeSuffix("/")
        mountPoints.remove(normalised)
        // Also remove from any package tracking set
        for ((_, targets) in packageMounts) {
            targets.remove(normalised)
        }
    }

    /**
     * Returns all mount points associated with [packageName].
     *
     * Association is established by [setupAppMounts]; if no mounts have been
     * set up yet the list is empty.
     */
    fun getMountPointsForPackage(packageName: String): List<MountPoint> {
        val targets = packageMounts[packageName] ?: return emptyList()
        return targets.mapNotNull { mountPoints[it] }
    }

    // ───────────────────── App mount lifecycle ────────────────────────────────

    /**
     * Creates and registers the standard set of mount points needed by
     * [packageName] based on its [appInfo].
     *
     * Any previous mounts for the same package are torn down first.
     */
    fun setupAppMounts(packageName: String, appInfo: VirtualAppInfo): Result<Unit> = runCatching {
        synchronized(lockFor(packageName)) {
            // Tear down any stale mounts for this package
            teardownAppMounts(packageName)

            val targets = mutableSetOf<String>()
            val defaultPoints = getDefaultMountPoints(packageName, appInfo)

            for (mp in defaultPoints) {
                mountPoints[mp.target] = mp
                targets.add(mp.target)
            }

            packageMounts[packageName] = targets
        }
    }

    /**
     * Removes all mount points previously set up for [packageName].
     */
    fun teardownAppMounts(packageName: String): Result<Unit> = runCatching {
        synchronized(lockFor(packageName)) {
            val targets = packageMounts.remove(packageName) ?: return@runCatching
            for (target in targets) {
                mountPoints.remove(target)
            }
        }
    }

    // ───────────────────── Path resolution ────────────────────────────────────

    /**
     * Resolves [path] through the current mount table for [packageName].
     *
     * The algorithm finds the **longest-prefix match** among all mount
     * targets (including both package-specific and global mounts), then
     * rewrites the path so that the target prefix is replaced with the
     * source prefix.
     *
     * If no mount matches, [path] is returned unchanged.
     */
    fun resolvePath(path: String, packageName: String): String {
        val normalised = path.removeSuffix("/")

        // Build candidate list: package-specific + global
        val pkgTargets = packageMounts[packageName] ?: emptySet()
        val candidates = mutableListOf<MountPoint>()

        for (target in pkgTargets) {
            mountPoints[target]?.let { candidates.add(it) }
        }
        // Also include any global (non-package) mounts
        for ((_, mp) in mountPoints) {
            if (mp.target !in pkgTargets) {
                candidates.add(mp)
            }
        }

        // Find longest-prefix match
        var bestMatch: MountPoint? = null
        var bestLength = -1

        for (mp in candidates) {
            if (normalised == mp.target || normalised.startsWith(mp.target + "/")) {
                if (mp.target.length > bestLength) {
                    bestMatch = mp
                    bestLength = mp.target.length
                }
            }
        }

        val match = bestMatch ?: return path

        // Rewrite: replace target prefix with source prefix
        val suffix = normalised.substring(match.target.length)
        return match.source + suffix
    }

    // ───────────────────── Default mount blueprint ────────────────────────────

    /**
     * Returns the standard set of mount points needed by [packageName].
     *
     * Layout (all targets are the "real" paths the app expects to see):
     * ```
     * /data/data/{pkg}           → virtual apps dir   (BIND, rw)
     * /data/user/0/{pkg}         → virtual apps dir   (BIND, rw)
     * /storage/emulated/0/Android/obb/{pkg}
     *                            → virtual obb dir    (BIND, rw)
     * /data/data/{pkg}/lib       → virtual lib dir    (BIND, ro)
     * /data/data/{pkg}/shared_prefs
     *                            → virtual prefs dir  (BIND, rw)
     * /data/data/{pkg}/databases → virtual db dir     (BIND, rw)
     * /data/data/{pkg}/cache     → virtual cache dir  (BIND, rw)
     * /data/data/{pkg}/files     → virtual files dir  (BIND, rw)
     * ```
     */
    fun getDefaultMountPoints(packageName: String, appInfo: VirtualAppInfo): List<MountPoint> {
        val vfs = VirtualFileSystem
        val points = mutableListOf<MountPoint>()

        val appDir = vfs.getAppDataDir(packageName).absolutePath
        val obbDir = vfs.getAppObbDir(packageName).absolutePath
        val libDir = vfs.getAppLibDir(packageName).absolutePath
        val prefsDir = vfs.getAppSharedPrefsDir(packageName).absolutePath
        val dbDir = vfs.getAppDatabaseDir(packageName).absolutePath
        val cacheDir = vfs.getAppCacheDir(packageName).absolutePath
        val filesDir = vfs.getAppFilesDir(packageName).absolutePath

        // Primary data directory
        points += MountPoint(
            source = appDir,
            target = "/data/data/$packageName",
            type = MountType.BIND,
            readOnly = false
        )

        // Secondary user-0 alias
        points += MountPoint(
            source = appDir,
            target = "/data/user/0/$packageName",
            type = MountType.BIND,
            readOnly = false
        )

        // OBB
        points += MountPoint(
            source = obbDir,
            target = "/storage/emulated/0/Android/obb/$packageName",
            type = MountType.BIND,
            readOnly = false
        )

        // Native libs (read-only to prevent tampering)
        points += MountPoint(
            source = libDir,
            target = "/data/data/$packageName/lib",
            type = MountType.BIND,
            readOnly = true
        )

        // Shared preferences
        points += MountPoint(
            source = prefsDir,
            target = "/data/data/$packageName/shared_prefs",
            type = MountType.BIND,
            readOnly = false
        )

        // Databases
        points += MountPoint(
            source = dbDir,
            target = "/data/data/$packageName/databases",
            type = MountType.BIND,
            readOnly = false
        )

        // Cache
        points += MountPoint(
            source = cacheDir,
            target = "/data/data/$packageName/cache",
            type = MountType.BIND,
            readOnly = false
        )

        // Files
        points += MountPoint(
            source = filesDir,
            target = "/data/data/$packageName/files",
            type = MountType.BIND,
            readOnly = false
        )

        // If the app is a game, mount a tmpfs at /data/data/{pkg}/app_webview/cache
        // to prevent WebView cache from consuming persistent storage
        if (appInfo.isGame) {
            points += MountPoint(
                source = "size=64m",
                target = "/data/data/$packageName/app_webview/cache",
                type = MountType.TMPFS,
                readOnly = false
            )
        }

        return points
    }

    // ───────────────────── Debug / testing ────────────────────────────────────

    /** Returns a snapshot of every registered mount point. */
    fun getAllMountPoints(): List<MountPoint> = mountPoints.values.toList()

    /** Removes all mount points. Intended for tests only. */
    fun clearAll() {
        mountPoints.clear()
        packageMounts.clear()
    }
}
