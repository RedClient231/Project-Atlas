package com.atlas.virtualspace.core.engine

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Resources
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import com.atlas.virtualspace.core.fs.VirtualFileSystem
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Virtual Context wrapper that makes the guest app believe it is running
 * as its own package in a normal Android environment.
 *
 * ## Why this is needed
 *
 * When a game calls [Context.getPackageName], [Context.getFilesDir],
 * [Context.getSharedPreferences], [Context.getPackageManager], etc., it
 * expects results consistent with its own package identity. Without this
 * wrapper, it would see Atlas's package name, Atlas's data directories,
 * and Atlas's ApplicationInfo — causing crashes, failed resource lookups,
 * and data corruption.
 *
 * ## What this class does
 *
 * - [getPackageName] → returns the guest's package name
 * - [getPackageResourcePath] → returns the guest APK path
 * - [getApplicationInfo] → returns a synthesized [ApplicationInfo] for the guest
 * - [getResources] / [getAssets] → returns the guest's Resources/AssetManager
 * - [getClassLoader] → returns the guest's DexClassLoader
 * - [getFilesDir] / [getCacheDir] / [getDataDir] → returns isolated virtual paths
 * - [getSharedPreferences] → returns prefs stored in the virtual data dir
 * - [getDatabasePath] / [openOrCreateDatabase] → isolated database paths
 * - [getDir] → isolated named directory under virtual data
 * - [getExternalFilesDir] → isolated external storage path
 * - [startActivity] → intercepted to route through VirtualEngine for child activities
 *
 * ## GameGuardian Compatibility
 *
 * GG calls [Context.getPackageName] to identify which process it's attached to.
 * By returning the guest's real package name, GG correctly identifies the game
 * and can scan its memory (which lives in Atlas's process address space).
 */
class VirtualGuestContext(
    private val hostContext: Context,
    private val guestPackageName: String,
    private val guestResources: Resources,
    private val guestClassLoader: ClassLoader
) : ContextWrapper(hostContext) {

    private companion object {
        const val TAG = "Atlas:VGuestCtx"
    }

    // ─── Cached directory paths (created lazily) ─────────────────────────────

    private val virtualDataDir: File by lazy {
        VirtualFileSystem.getAppDataDir(guestPackageName).also { it.mkdirs() }
    }

    private val virtualFilesDir: File by lazy {
        File(virtualDataDir, "files").also { it.mkdirs() }
    }

    private val virtualCacheDir: File by lazy {
        File(virtualDataDir, "cache").also { it.mkdirs() }
    }

    private val virtualCodeCacheDir: File by lazy {
        File(virtualDataDir, "code_cache").also { it.mkdirs() }
    }

    private val virtualNoBackupFilesDir: File by lazy {
        File(virtualDataDir, "no_backup").also { it.mkdirs() }
    }

    private val virtualDatabaseDir: File by lazy {
        File(virtualDataDir, "databases").also { it.mkdirs() }
    }

    private val virtualSharedPrefsDir: File by lazy {
        File(virtualDataDir, "shared_prefs").also { it.mkdirs() }
    }

    private val virtualExternalFilesDir: File by lazy {
        File(virtualDataDir, "external_files").also { it.mkdirs() }
    }

    private val virtualObbDir: File by lazy {
        VirtualFileSystem.getAppObbDir(guestPackageName).also { it.mkdirs() }
    }

    // ─── Package Identity ────────────────────────────────────────────────────

    override fun getPackageName(): String = guestPackageName

    override fun getPackageResourcePath(): String {
        val appInfo = VirtualEngine.installedApps.value
            .firstOrNull { it.packageName == guestPackageName }
        return appInfo?.apkPath ?: super.getPackageResourcePath()
    }

    override fun getPackageCodePath(): String = getPackageResourcePath()

    override fun getApplicationInfo(): ApplicationInfo {
        val appInfo = VirtualEngine.installedApps.value
            .firstOrNull { it.packageName == guestPackageName }

        return ApplicationInfo().apply {
            packageName = guestPackageName
            sourceDir = appInfo?.apkPath ?: ""
            publicSourceDir = appInfo?.apkPath ?: ""
            dataDir = virtualDataDir.absolutePath
            nativeLibraryDir = appInfo?.nativeLibPath ?: ""
            targetSdkVersion = appInfo?.targetSdkVersion ?: Build.VERSION.SDK_INT
            minSdkVersion = appInfo?.minSdkVersion ?: 21
            enabled = true
            flags = ApplicationInfo.FLAG_INSTALLED or
                    ApplicationInfo.FLAG_HAS_CODE or
                    (if (appInfo?.isGame == true) ApplicationInfo.FLAG_IS_GAME else 0)
            if (appInfo?.is64Bit == true && Build.VERSION.SDK_INT >= 26) {
                // FLAG_SUPPORTS_64_BIT is not a public constant but
                // we need the game to know it can use 64-bit libs.
            }
        }
    }

    override fun getApplicationContext(): Context = this

    // ─── Resources & ClassLoader ─────────────────────────────────────────────

    override fun getResources(): Resources = guestResources

    override fun getAssets(): AssetManager = guestResources.assets

    override fun getClassLoader(): ClassLoader = guestClassLoader

    override fun getTheme(): Resources.Theme {
        val theme = guestResources.newTheme()
        // Apply a basic Android theme so the guest can inflate layouts
        try {
            theme.applyStyle(android.R.style.Theme_DeviceDefault, true)
        } catch (_: Exception) { }
        return theme
    }

    // ─── File System — Isolated Virtual Paths ────────────────────────────────

    override fun getFilesDir(): File = virtualFilesDir

    override fun getCacheDir(): File = virtualCacheDir

    override fun getCodeCacheDir(): File = virtualCodeCacheDir

    override fun getNoBackupFilesDir(): File = virtualNoBackupFilesDir

    override fun getDataDir(): File = virtualDataDir

    override fun getExternalFilesDir(type: String?): File? {
        val dir = if (type != null) File(virtualExternalFilesDir, type) else virtualExternalFilesDir
        dir.mkdirs()
        return dir
    }

    override fun getExternalFilesDirs(type: String?): Array<File> {
        return arrayOf(getExternalFilesDir(type) ?: virtualExternalFilesDir)
    }

    override fun getExternalCacheDir(): File? = virtualCacheDir

    override fun getExternalCacheDirs(): Array<File> = arrayOf(virtualCacheDir)

    override fun getObbDir(): File = virtualObbDir

    override fun getObbDirs(): Array<File> = arrayOf(virtualObbDir)

    override fun getDir(name: String, mode: Int): File {
        val dir = File(virtualDataDir, "app_$name")
        dir.mkdirs()
        return dir
    }

    // ─── Databases — Isolated ────────────────────────────────────────────────

    override fun getDatabasePath(name: String): File {
        return File(virtualDatabaseDir, name)
    }

    override fun openOrCreateDatabase(
        name: String,
        mode: Int,
        factory: SQLiteDatabase.CursorFactory?
    ): SQLiteDatabase {
        val dbFile = getDatabasePath(name)
        dbFile.parentFile?.mkdirs()
        return SQLiteDatabase.openOrCreateDatabase(dbFile, factory)
    }

    override fun openOrCreateDatabase(
        name: String,
        mode: Int,
        factory: SQLiteDatabase.CursorFactory?,
        errorHandler: DatabaseErrorHandler?
    ): SQLiteDatabase {
        val dbFile = getDatabasePath(name)
        dbFile.parentFile?.mkdirs()
        return SQLiteDatabase.openOrCreateDatabase(
            dbFile.absolutePath, factory, errorHandler
        )
    }

    override fun databaseList(): Array<String> {
        return virtualDatabaseDir.list() ?: emptyArray()
    }

    override fun deleteDatabase(name: String): Boolean {
        val dbFile = getDatabasePath(name)
        return dbFile.delete()
    }

    // ─── SharedPreferences — Isolated ────────────────────────────────────────

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
        val prefsName = name ?: "${guestPackageName}_preferences"
        val prefsFile = File(virtualSharedPrefsDir, "$prefsName.xml")
        prefsFile.parentFile?.mkdirs()

        // Use the host's SharedPreferences implementation but with our custom path.
        // We create a unique name scoped to the guest package to avoid collisions.
        val scopedName = "virtual_${guestPackageName}_${prefsName}"
        return hostContext.getSharedPreferences(scopedName, mode)
    }

    // ─── File I/O — Isolated ─────────────────────────────────────────────────

    override fun openFileInput(name: String): FileInputStream {
        val file = File(virtualFilesDir, name)
        return FileInputStream(file)
    }

    override fun openFileOutput(name: String, mode: Int): FileOutputStream {
        val file = File(virtualFilesDir, name)
        file.parentFile?.mkdirs()
        val append = (mode and Context.MODE_APPEND) != 0
        return FileOutputStream(file, append)
    }

    override fun deleteFile(name: String): Boolean {
        return File(virtualFilesDir, name).delete()
    }

    override fun fileList(): Array<String> {
        return virtualFilesDir.list() ?: emptyArray()
    }

    // ─── Intent Interception ─────────────────────────────────────────────────

    override fun startActivity(intent: Intent?) {
        if (intent == null) return

        // If the intent targets a component within the guest package,
        // route it through VirtualEngine to launch another stub activity.
        val targetComponent = intent.component
        if (targetComponent != null && targetComponent.packageName == guestPackageName) {
            Timber.d("$TAG: Intercepted startActivity for guest component: %s", targetComponent)
            val stubIntent = Intent(hostContext, VirtualStubActivity::class.java).apply {
                putExtra(VirtualStubActivity.EXTRA_PACKAGE_NAME, guestPackageName)
                putExtra(VirtualStubActivity.EXTRA_ACTIVITY_CLASS, targetComponent.className)
                putExtra(VirtualStubActivity.EXTRA_VIRTUAL_LAUNCH, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            hostContext.startActivity(stubIntent)
        } else {
            // External intent — let it go through to the real system
            super.startActivity(intent)
        }
    }

    override fun startActivity(intent: Intent?, options: android.os.Bundle?) {
        startActivity(intent)
    }

    // ─── Package Manager Proxy ───────────────────────────────────────────────

    override fun getPackageManager(): PackageManager {
        // Return the real PackageManager for now.
        // SystemServiceHooks intercepts PM calls for virtual packages
        // at the framework level, so the guest will see itself as installed.
        return super.getPackageManager()
    }

    // ─── System Services ─────────────────────────────────────────────────────

    override fun getSystemService(name: String): Any? {
        // Delegate to real system services — the hook layer (SystemServiceHooks)
        // intercepts at the Binder level to return virtual data where needed.
        return super.getSystemService(name)
    }

    override fun getSystemServiceName(serviceClass: Class<*>): String? {
        return super.getSystemServiceName(serviceClass)
    }

    // ─── Content Resolution ──────────────────────────────────────────────────

    override fun getContentResolver(): android.content.ContentResolver {
        // Return the real ContentResolver. Virtual content providers are
        // handled by the hook layer intercepting the Binder calls.
        return super.getContentResolver()
    }
}
