package com.atlas.virtualspace.core.pm

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import com.atlas.virtualspace.core.fs.VirtualFileSystem
import com.atlas.virtualspace.core.fs.VirtualMountManager
import com.atlas.virtualspace.data.database.AppDatabase
import com.atlas.virtualspace.data.database.VirtualAppDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.dongliu.apk.parser.ApkFile
import net.dongliu.apk.parser.bean.ApkMeta
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Manages the lifecycle of virtual apps: installation, uninstallation,
 * querying, and data management.
 *
 * All public methods are blocking. Callers are expected to dispatch onto a
 * background thread (e.g. via `withContext(Dispatchers.IO)`).
 *
 * **Dependency note:** This class uses `net.dongliu:apk-parser` for APK
 * metadata extraction. Make sure the dependency is declared in build.gradle.
 */
object VirtualPackageManager {

    private lateinit var database: AppDatabase
    private lateinit var appContext: Context
    private lateinit var dao: VirtualAppDao

    // ───────────────────────── Initialisation ─────────────────────────────────

    /**
     * Initialises the package manager. Must be called once during
     * Application.onCreate() *after* [AppDatabase] has been created.
     */
    fun initialize(database: AppDatabase): Result<Unit> = runCatching {
        this.database = database
        this.dao = database.virtualAppDao()
    }

    /**
     * Sets the application [Context]. Called separately so the manager can
     * resolve package-manager resources without storing a static reference
     * prematurely.
     */
    fun setContext(context: Context) {
        appContext = context.applicationContext
    }

    // ───────────────────────── Installation ───────────────────────────────────

    /**
     * Installs an APK into the virtual space.
     *
     * The method performs the following steps:
     * 1. Parse APK metadata via [parseApkInfo]
     * 2. Copy the APK into virtual storage
     * 3. Create per-app directory structure via [VirtualFileSystem.createAppStorage]
     * 4. Extract native libraries matching the device ABI
     * 5. Copy OBB files if present on the real filesystem
     * 6. Persist [VirtualAppInfo] in the database
     *
     * @param apkFile  The source APK file (may be outside the virtual FS).
     * @param type     How the APK reached the installer (see [InstallType]).
     * @return         The persisted [VirtualAppInfo] on success.
     */
    fun installApp(apkFile: File, type: InstallType): Result<VirtualAppInfo> = runCatching {
        require(apkFile.exists()) { "APK file does not exist: ${apkFile.absolutePath}" }
        require(apkFile.canRead()) { "APK file is not readable: ${apkFile.absolutePath}" }

        // 1. Parse metadata
        val appInfo = parseApkInfo(apkFile).getOrThrow()

        // 2. Copy APK into virtual storage
        val appDir = VirtualFileSystem.getAppDataDir(appInfo.packageName)
        val virtualApk = File(appDir, "base.apk")
        VirtualFileSystem.mirrorFile(apkFile, virtualApk).getOrThrow()

        // 3. Create app storage tree
        VirtualFileSystem.createAppStorage(appInfo.packageName).getOrThrow()

        // 4. Extract native libraries
        val libDir = VirtualFileSystem.getAppLibDir(appInfo.packageName)
        val libResult = extractNativeLibs(apkFile, libDir)
        val nativeLibPath = if (libResult.isSuccess) libDir.absolutePath else null

        // 5. Copy OBB if present on real device
        val obbResult = copyObbFromDevice(appInfo.packageName)
        val obbPath = if (obbResult.isSuccess) {
            VirtualFileSystem.getAppObbDir(appInfo.packageName).absolutePath
        } else null

        // 6. Build and persist entity
        val now = System.currentTimeMillis()
        val virtualApp = VirtualAppInfo(
            packageName = appInfo.packageName,
            appName = appInfo.appName,
            versionName = appInfo.versionName,
            versionCode = appInfo.versionCode,
            apkPath = virtualApk.absolutePath,
            splitApkPaths = emptyList(),
            obbPath = obbPath,
            nativeLibPath = nativeLibPath,
            installTime = now,
            updateTime = now,
            targetSdkVersion = appInfo.targetSdk,
            minSdkVersion = appInfo.minSdk,
            is64Bit = appInfo.is64Bit,
            isGame = appInfo.isGame,
            isEnabled = true,
            launchActivity = appInfo.launchActivity,
            permissions = appInfo.permissions,
            installType = type
        )

        runBlocking(Dispatchers.IO) {
            dao.insert(virtualApp)
        }

        virtualApp
    }

    /**
     * Installs an XAPK bundle into the virtual space.
     *
     * XAPK is a ZIP archive containing:
     * - `base.apk` (or `{package}.apk`) — the base APK
     * - `config.{abi}.apk` — split APKs for ABI, density, locale, etc.
     * - `{package}.{version}.obb` — optional OBB files under `obb/` prefix
     * - `manifest.json` — metadata (optional)
     *
     * The method extracts each component, installs the base APK via
     * [installApp], then installs split APKs and OBB.
     */
    fun installXapk(xapkFile: File): Result<VirtualAppInfo> = runCatching {
        require(xapkFile.exists()) { "XAPK file does not exist: ${xapkFile.absolutePath}" }

        // Temporary extraction directory
        val stagingDir = File(xapkFile.parentFile, ".xapk_staging_${System.currentTimeMillis()}")
        if (!stagingDir.mkdirs()) {
            throw IOException("Failed to create staging dir: ${stagingDir.absolutePath}")
        }

        try {
            // Extract XAPK contents
            val extractedFiles = unzipTo(xapkFile, stagingDir)

            // Identify base APK (first .apk that is NOT a config split)
            val baseApk = extractedFiles.firstOrNull {
                it.name.endsWith(".apk") && !it.name.startsWith("config.")
            } ?: throw IOException("No base APK found in XAPK bundle")

            // Install the base APK first
            val baseResult = installApp(baseApk, InstallType.XAPK)
            val virtualApp = baseResult.getOrThrow()

            // Install split APKs
            val splitApkPaths = mutableListOf<String>()
            val splitApks = extractedFiles.filter {
                it.name.endsWith(".apk") && it.name.startsWith("config.")
            }
            for (splitApk in splitApks) {
                val target = File(
                    VirtualFileSystem.getAppDataDir(virtualApp.packageName),
                    splitApk.name
                )
                VirtualFileSystem.mirrorFile(splitApk, target).getOrThrow()
                splitApkPaths.add(target.absolutePath)
            }

            // Handle OBB files within the XAPK
            val obbFiles = extractedFiles.filter { it.name.endsWith(".obb") }
            for (obbFile in obbFiles) {
                copyObb(obbFile, virtualApp.packageName).getOrThrow()
            }

            // Update the database record with split APK paths
            val updated = virtualApp.copy(
                splitApkPaths = splitApkPaths,
                obbPath = if (obbFiles.isNotEmpty()) {
                    VirtualFileSystem.getAppObbDir(virtualApp.packageName).absolutePath
                } else virtualApp.obbPath
            )
            runBlocking(Dispatchers.IO) {
                dao.update(updated)
            }

            updated
        } finally {
            // Always clean up staging directory
            stagingDir.deleteRecursively()
        }
    }

    // ───────────────────────── Uninstallation ─────────────────────────────────

    /**
     * Completely removes a virtual app:
     * 1. Deletes from the database
     * 2. Deletes virtual storage directories
     * 3. Tears down mount points
     */
    fun uninstallApp(packageName: String): Result<Unit> = runCatching {
        // 1. Delete from database FIRST — this is the most critical step
        //    and should succeed even if storage cleanup fails
        try {
            runBlocking(Dispatchers.IO) {
                dao.deleteByPackage(packageName)
            }
        } catch (e: Exception) {
            Timber.w(e, "Database delete failed for %s, continuing with storage cleanup", packageName)
        }

        // 2. Delete virtual storage (non-fatal — data may already be gone)
        try {
            VirtualFileSystem.deleteAppStorage(packageName).getOrThrow()
        } catch (e: Exception) {
            Timber.w(e, "Storage delete failed for %s (may already be removed)", packageName)
        }

        // 3. Tear down mount points (non-fatal)
        try {
            VirtualMountManager.teardownAppMounts(packageName).getOrThrow()
        } catch (e: Exception) {
            Timber.w(e, "Mount teardown failed for %s", packageName)
        }
        
        Timber.i("Uninstall completed for %s", packageName)
    }

    // ───────────────────────── Queries ────────────────────────────────────────

    fun getAppInfo(packageName: String): VirtualAppInfo? = runBlocking(Dispatchers.IO) {
        dao.getByPackage(packageName)
    }

    fun getInstalledApps(): List<VirtualAppInfo> = runBlocking(Dispatchers.IO) {
        dao.getAll().first()
    }

    fun isAppInstalled(packageName: String): Boolean = runBlocking(Dispatchers.IO) {
        dao.getByPackage(packageName) != null
    }

    /**
     * Returns the app icon from the virtual APK, or null if it cannot be loaded.
     */
    fun getAppIcon(packageName: String): Drawable? {
        val appInfo = getAppInfo(packageName) ?: return null
        val apkFile = File(appInfo.apkPath)
        if (!apkFile.exists()) return null

        return try {
            ApkFile(apkFile).use { apk ->
                val iconData = apk.iconFile
                if (iconData != null && iconData.data?.isNotEmpty() == true) {
                    BitmapFactory.decodeByteArray(iconData.data, 0, iconData.data!!.size)?.let { BitmapDrawable(appContext.resources, it) }
                } else {
                    // Fallback: try the system PackageManager for the real app
                    try {
                        appContext.packageManager.getApplicationIcon(packageName)
                    } catch (_: PackageManager.NameNotFoundException) {
                        null
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    // ───────────────────────── Actions ────────────────────────────────────────

    /**
     * Launches a virtual app by delegating to the VirtualEngine.
     *
     * Before launching, mount points are (re-)established and launch stats
     * are updated in the database.
     */
    fun launchApp(packageName: String): Result<Unit> = runCatching {
        val appInfo = getAppInfo(packageName)
            ?: throw IllegalArgumentException("App not installed: $packageName")

        if (!appInfo.isEnabled) {
            throw IllegalStateException("App is disabled: $packageName")
        }

        // Refresh mount points
        VirtualMountManager.setupAppMounts(packageName, appInfo).getOrThrow()

        // Update launch statistics
        val now = System.currentTimeMillis()
        runBlocking(Dispatchers.IO) {
            dao.updateLaunchStats(packageName, now)
        }

        // Delegate to VirtualEngine (in core.engine package)
        com.atlas.virtualspace.core.engine.VirtualEngine.launchApp(packageName).getOrThrow()
    }

    /**
     * Clears runtime data (cache, databases, shared_prefs) while keeping the
     * APK registration and native libraries intact.
     */
    fun clearAppData(packageName: String): Result<Unit> = runCatching {
        val vfs = VirtualFileSystem
        val dirsToClear = listOf(
            vfs.getAppCacheDir(packageName),
            File(vfs.getAppDataDir(packageName), "code_cache"),
            vfs.getAppDatabaseDir(packageName),
            vfs.getAppSharedPrefsDir(packageName),
            File(vfs.getAppDataDir(packageName), "app_webview")
        )

        for (dir in dirsToClear) {
            if (dir.exists()) {
                dir.deleteRecursively()
                dir.mkdirs()
            }
        }
    }

    /**
     * Exports the virtual APK (and any split APKs) to [targetDir] for sharing.
     *
     * @return The exported file (or the base APK if no splits exist).
     */
    fun exportApp(packageName: String, targetDir: File): Result<File> = runCatching {
        val appInfo = getAppInfo(packageName)
            ?: throw IllegalArgumentException("App not installed: $packageName")

        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IOException("Cannot create export directory: ${targetDir.absolutePath}")
        }

        val sourceApk = File(appInfo.apkPath)
        if (!sourceApk.exists()) {
            throw IOException("APK file missing: ${appInfo.apkPath}")
        }

        val sanitisedName = appInfo.appName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val exportedApk = File(targetDir, "${sanitisedName}_${appInfo.versionName}.apk")
        VirtualFileSystem.mirrorFile(sourceApk, exportedApk).getOrThrow()

        // If there are split APKs, copy them alongside
        for (splitPath in appInfo.splitApkPaths) {
            val splitSource = File(splitPath)
            if (splitSource.exists()) {
                val splitTarget = File(targetDir, splitSource.name)
                VirtualFileSystem.mirrorFile(splitSource, splitTarget).getOrThrow()
            }
        }

        exportedApk
    }

    // ───────────────────── Private: APK parsing ───────────────────────────────

    /**
     * Parses an APK file and extracts metadata into an [AppInfo] DTO.
     *
     * Uses the `apk-parser` library which reads the AndroidManifest without
     * installing the APK on the device.
     */
    private fun parseApkInfo(file: File): Result<AppInfo> = runCatching {
        ApkFile(file).use { apk ->
            val meta: ApkMeta = apk.apkMeta

            val packageName = meta.packageName
                ?: throw IOException("APK has no package name")

            val appName = meta.label
                ?: packageName.substringAfterLast('.')

            val versionName = meta.versionName ?: "unknown"
            val versionCode = runCatching { meta.versionCode?.toLong() }.getOrNull() ?: 0L

            val targetSdk = meta.targetSdkVersion?.toIntOrNull() ?: 0
            val minSdk = meta.minSdkVersion?.toIntOrNull() ?: 0

            // Determine if the APK contains 64-bit native code by scanning
            // the ZIP for lib/arm64-v8a/ entries
            val is64Bit = has64BitLibs(file)

            // Heuristic: game detection based on features
            val isGame = meta.usesFeatures?.any {
                it.name.contains("game", ignoreCase = true)
            } ?: false

            // CRITICAL FIX: Resolve launch activity from the APK manifest,
            // NOT from the system PackageManager. The system PM only knows
            // about apps actually installed on the device. For APKs that
            // are just files (the most common case), we must parse the
            // manifest directly using apk-parser.
            val launchActivity = resolveLaunchActivityFromApk(apk, packageName)

            val permissions = meta.permissions?.map { it.name } ?: emptyList()

            val nativeLibs = (meta.usesFeatures ?: emptyList())
                .map { it.name }
                .filter { it.startsWith("android.hardware.") && it.contains("opengl") }

            // Split configs: density / ABI features for reference
            val splitConfigs = (meta.usesFeatures ?: emptyList())
                .map { it.name }
                .filter { feat ->
                    feat.startsWith("android.hardware.screen.") ||
                            feat.startsWith("android.hardware.opengles.")
                }

            val icon = try {
                val iconFile = apk.iconFile
                if (iconFile != null && iconFile.data?.isNotEmpty() == true) {
                    BitmapFactory.decodeByteArray(iconFile.data, 0, iconFile.data!!.size)?.let { BitmapDrawable(appContext.resources, it) }
                } else null
            } catch (_: Exception) {
                null
            }

            AppInfo(
                packageName = packageName,
                appName = appName,
                versionName = versionName,
                versionCode = versionCode,
                targetSdk = targetSdk,
                minSdk = minSdk,
                is64Bit = is64Bit,
                isGame = isGame,
                launchActivity = launchActivity,
                permissions = permissions,
                nativeLibs = nativeLibs,
                splitConfigs = splitConfigs,
                obbFiles = emptyList(),
                icon = icon
            )
        }
    }

    /**
     * Resolves the launch activity from the APK manifest.
     *
     * Strategy (in order of preference):
     * 1. Parse the APK's AndroidManifest.xml using apk-parser to find
     *    activities with ACTION_MAIN + CATEGORY_LAUNCHER intent filters.
     * 2. Fallback: Try the system PackageManager (only works for apps
     *    that are already installed on the device, e.g. clone installs).
     * 3. Last resort: return null (the app cannot be launched).
     */
    private fun resolveLaunchActivityFromApk(apk: ApkFile, packageName: String): String? {
        // Strategy 1: Use apk-parser's ApkMeta to get the launchable activity.
        // The apk-parser library (2.6.10) exposes launch activities via
        // the ApkMeta object itself. We try to find an activity with
        // ACTION_MAIN + CATEGORY_LAUNCHER intent filters.
        try {
            val meta = apk.apkMeta

            // Try using reflection to access activity list since the API
            // varies between apk-parser versions. The launchable activity
            // is typically the one with MAIN/LAUNCHER intent filter.
            // ApkParser 2.6.10 stores activities in ApkMeta.
            try {
                val activitiesField = ApkMeta::class.java.getDeclaredField("activities")
                activitiesField.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val activities = activitiesField.get(meta) as? List<*>
                if (activities != null && activities.isNotEmpty()) {
                    for (activityObj in activities) {
                        if (activityObj == null) continue
                        val nameField = activityObj.javaClass.getDeclaredField("name")
                        nameField.isAccessible = true
                        val activityName = nameField.get(activityObj) as? String ?: continue

                        val intentFiltersField = activityObj.javaClass.getDeclaredField("intentFilters")
                        intentFiltersField.isAccessible = true
                        @Suppress("UNCHECKED_CAST")
                        val filters = intentFiltersField.get(activityObj) as? List<*>
                        if (filters != null) {
                            for (filterObj in filters) {
                                if (filterObj == null) continue
                                val actionsField = filterObj.javaClass.getDeclaredField("actions")
                                actionsField.isAccessible = true
                                @Suppress("UNCHECKED_CAST")
                                val actions = actionsField.get(filterObj) as? List<String>
                                val categoriesField = filterObj.javaClass.getDeclaredField("categories")
                                categoriesField.isAccessible = true
                                @Suppress("UNCHECKED_CAST")
                                val categories = categoriesField.get(filterObj) as? List<String>

                                val hasMainAction = actions?.any { it == "android.intent.action.MAIN" } == true
                                val hasLauncherCategory = categories?.any { it == "android.intent.category.LAUNCHER" } == true
                                if (hasMainAction && hasLauncherCategory) {
                                    var resolvedName = activityName
                                    if (resolvedName.startsWith(".")) {
                                        resolvedName = "$packageName$resolvedName"
                                    } else if (!resolvedName.contains(".")) {
                                        resolvedName = "$packageName.$resolvedName"
                                    }
                                    return resolvedName
                                }
                            }
                        }
                    }
                }
            } catch (refEx: Exception) {
                Timber.d(refEx, "Reflection-based activity lookup failed, trying fallback")
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse launch activity from APK manifest")
        }

        // Strategy 2: Try system PackageManager (works for clone installs).
        try {
            val launchIntent = appContext.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                return launchIntent.component?.className
            }
        } catch (_: Exception) {
            // Package not installed on device — this is normal for file imports
        }

        Timber.w("Could not resolve launch activity for %s — app may not be launchable", packageName)
        return null
    }

    /**
     * Checks whether the APK contains 64-bit native libraries by scanning
     * the ZIP central directory for `lib/arm64-v8a/` entries.
     *
     * This is more reliable than checking `<uses-feature>` because not all
     * apps declare Vulkan features even when they ship 64-bit .so files.
     */
    private fun has64BitLibs(apkFile: File): Boolean {
        return try {
            ZipInputStream(FileInputStream(apkFile)).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    val name = entry!!.name
                    if (name.startsWith("lib/arm64-v8a/") && name.endsWith(".so") && !entry!!.isDirectory) {
                        return true
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    // ───────────────────── Private: Native lib extraction ─────────────────────

    /**
     * Extracts .so files from [apkFile] matching the current device's
     * supported ABIs and places them into [targetDir].
     *
     * The directory structure follows Android convention:
     * ```
     * targetDir/
     *   arm64-v8a/
     *     libfoo.so
     *   armeabi-v7a/
     *     libbar.so
     * ```
     */
    private fun extractNativeLibs(apkFile: File, targetDir: File): Result<Unit> = runCatching {
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IOException("Cannot create lib dir: ${targetDir.absolutePath}")
        }

        val abiSet = Build.SUPPORTED_ABIS.toSet()

        ZipInputStream(FileInputStream(apkFile)).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val name = entry!!.name
                // Match lib/{abi}/lib*.so
                if (name.startsWith("lib/") && name.endsWith(".so") && !entry!!.isDirectory) {
                    val parts = name.split("/")
                    if (parts.size == 3) {
                        val abi = parts[1]
                        if (abi in abiSet) {
                            val soFile = File(targetDir, "${abi}/${parts[2]}")
                            if (!soFile.parentFile!!.exists() && !soFile.parentFile!!.mkdirs()) {
                                throw IOException("Cannot create abi dir: ${soFile.parentFile!!.absolutePath}")
                            }
                            FileOutputStream(soFile).use { out ->
                                val buffer = ByteArray(8192)
                                var read: Int
                                while (zip.read(buffer).also { read = it } != -1) {
                                    out.write(buffer, 0, read)
                                }
                            }
                            soFile.setExecutable(true, false)
                            soFile.setReadable(true, false)
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    // ───────────────────── Private: OBB handling ──────────────────────────────

    /**
     * Copies OBB files that the *real* installed app has on the device
     * into the virtual OBB directory.
     *
     * This is best-effort: many apps don't have OBB files, and that's fine.
     * Returns success even if no OBB files exist — only fails on I/O errors
     * when OBB files ARE present but can't be copied.
     */
    private fun copyObbFromDevice(packageName: String): Result<Unit> = runCatching {
        val realObbDir = File("/storage/emulated/0/Android/obb/$packageName")
        if (!realObbDir.exists() || !realObbDir.isDirectory) {
            // No OBB directory on device — this is normal for most apps
            return Result.success(Unit)
        }
        val virtualObbDir = VirtualFileSystem.getAppObbDir(packageName)
        if (!virtualObbDir.exists() && !virtualObbDir.mkdirs()) {
            throw IOException("Cannot create virtual OBB dir: ${virtualObbDir.absolutePath}")
        }

        val obbFiles = realObbDir.listFiles()?.filter { it.name.endsWith(".obb") } ?: emptyList()
        if (obbFiles.isEmpty()) {
            // No OBB files — not an error, just no OBB data to copy
            return Result.success(Unit)
        }

        for (obb in obbFiles) {
            val target = File(virtualObbDir, obb.name)
            VirtualFileSystem.mirrorFile(obb, target).getOrThrow()
        }
    }

    /**
     * Copies a single OBB [source] file into the virtual OBB directory for
     * [packageName].
     */
    private fun copyObb(source: File, packageName: String): Result<Unit> = runCatching {
        val virtualObbDir = VirtualFileSystem.getAppObbDir(packageName)
        if (!virtualObbDir.exists() && !virtualObbDir.mkdirs()) {
            throw IOException("Cannot create virtual OBB dir: ${virtualObbDir.absolutePath}")
        }
        val target = File(virtualObbDir, source.name)
        VirtualFileSystem.mirrorFile(source, target).getOrThrow()
    }

    // ───────────────────── Private: ZIP utilities ─────────────────────────────

    /**
     * Extracts all entries from [zipFile] into [destDir] and returns the
     * list of extracted [File]s.
     */
    private fun unzipTo(zipFile: File, destDir: File): List<File> {
        val extracted = mutableListOf<File>()

        if (!zipFile.exists() || zipFile.length() == 0L) {
            throw IOException("ZIP file is empty or does not exist: ${zipFile.absolutePath}")
        }

        java.util.zip.ZipFile(zipFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val outFile = File(destDir, entry.name)

                // Security: prevent zip-slip (path traversal)
                val canonicalDest = destDir.canonicalPath
                val canonicalOut = outFile.canonicalPath
                if (!canonicalOut.startsWith(canonicalDest + File.separator) && canonicalOut != canonicalDest) {
                    throw IOException("Zip slip detected: ${entry.name}")
                }

                if (entry.isDirectory) {
                    if (!outFile.exists() && !outFile.mkdirs()) {
                        throw IOException("Cannot create dir: ${outFile.absolutePath}")
                    }
                } else {
                    outFile.parentFile?.let { parent ->
                        if (!parent.exists() && !parent.mkdirs()) {
                            throw IOException("Cannot create parent dir: ${parent.absolutePath}")
                        }
                    }
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(outFile).use { out ->
                            val buffer = ByteArray(8192)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                out.write(buffer, 0, read)
                            }
                        }
                    }
                    extracted.add(outFile)
                }
            }
        }

        return extracted
    }

    // ───────────────────── Extension helpers ──────────────────────────────────

    /**
     * Checks if the APK meta declares any of the given feature names.
     */
    private fun ApkMeta.isFeatureAnyOf(vararg names: String): Boolean {
        val nameSet = names.toSet()
        return usesFeatures?.any { it.name in nameSet } ?: false
    }
}
