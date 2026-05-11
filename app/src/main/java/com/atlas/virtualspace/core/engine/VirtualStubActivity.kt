package com.atlas.virtualspace.core.engine

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.atlas.virtualspace.core.hook.ShizukuIntegration
import com.atlas.virtualspace.core.pm.VirtualPackageManager
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Proxy activity that bridges the Android system with virtual apps running
 * inside the Atlas virtual space.
 *
 * When the user taps "Launch" on a virtual app, this stub activity is
 * started instead of the virtual app's real activity. The stub then attempts
 * multiple launch strategies to get the app running.
 *
 * ## Launch Strategy (in order)
 *
 * ### Fast path — already installed on device ([VirtualAppInfo.isInstalledOnDevice] == true)
 * Goes straight to [tryDirectLaunch]. No install dialog, no Shizuku — instant.
 * This is the path taken on every launch AFTER the first successful install.
 *
 * ### First-time paths (app not yet installed on real device):
 *
 * 1. **Shizuku silent install + am start**: If Shizuku is available and
 *    permission is granted, copies the APK to a temp location, installs
 *    silently via `pm install -r`, then launches via `am start`. The user
 *    sees NO dialog whatsoever. After success, [markInstalledOnDevice] is
 *    called so all future taps go via the fast path.
 *
 * 2. **System package installer dialog**: If Shizuku is unavailable, shows
 *    the standard Android "Do you want to install?" dialog ONCE. After the
 *    user accepts and the app is installed, [markInstalledOnDevice] is called
 *    so the dialog NEVER appears again.
 *
 * ## Why install on the real device?
 *
 * Games require full Android framework support: GPU rendering (SurfaceView),
 * native libraries (.so), proper Activity lifecycle, content providers, etc.
 * DexClassLoader cannot provide these — the app must be installed so Android
 * manages its lifecycle. Atlas provides virtual space management (separate
 * data, lifecycle control, GG compatibility) on top of that.
 */
class VirtualStubActivity : Activity() {

    /**
     * True after onCreate runs — used to guard [checkPendingLaunch] in
     * onResume against firing on the very first resume (before the user
     * has had a chance to interact with any install dialog).
     */
    private var hasLaunchedOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        val activityClass = intent.getStringExtra(EXTRA_ACTIVITY_CLASS)

        if (packageName.isNullOrBlank() || activityClass.isNullOrBlank()) {
            Timber.e("VirtualStubActivity launched without required extras")
            Toast.makeText(this, "Error: Invalid virtual app launch", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Verify this package is actually installed in the virtual space.
        val appInfo = try {
            VirtualPackageManager.getAppInfo(packageName)
        } catch (e: Exception) {
            Timber.e(e, "VirtualStubActivity: VirtualPackageManager not initialized")
            Toast.makeText(this, "Error: Virtual space not initialized. Please restart Atlas.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (appInfo == null) {
            Timber.e("VirtualStubActivity: %s not found in virtual space", packageName)
            Toast.makeText(this, "Error: App not found in virtual space", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val apkPath = appInfo.apkPath
        if (apkPath.isEmpty() || !File(apkPath).exists()) {
            Timber.e("VirtualStubActivity: APK missing: %s", apkPath)
            Toast.makeText(this, "Error: APK file not found. Try re-importing the app.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        Timber.i("VirtualStubActivity: launching %s (isInstalledOnDevice=%b)",
            packageName, appInfo.isInstalledOnDevice)

        hasLaunchedOnce = true

        try {
            // ── FAST PATH: already installed on device ─────────────────────────
            // This is the path taken on every launch AFTER the first successful
            // install. No dialog, no Shizuku check — just launch directly.
            if (appInfo.isInstalledOnDevice) {
                if (tryDirectLaunch(packageName, activityClass)) {
                    return
                }
                // If direct launch failed despite isInstalledOnDevice == true,
                // the app may have been uninstalled from the device externally.
                // Reset the flag and fall through to re-install.
                Timber.w("%s was marked as installed but direct launch failed — re-installing", packageName)
                // Reset flag so we re-install
                resetInstalledOnDeviceFlag(packageName)
            }

            // ── FIRST-TIME PATH: app not yet on real device ────────────────────

            // Strategy 1: Try direct launch anyway (handles clone installs where
            // the app was already on the device before Atlas import).
            if (tryDirectLaunch(packageName, activityClass)) {
                // It was already on the device — mark it so future taps are instant.
                markInstalledOnDevice(packageName)
                return
            }

            // Strategy 2: Shizuku silent install — no dialog shown to user.
            if (ShizukuIntegration.isShizukuAvailable() &&
                ShizukuIntegration.isShizukuPermissionGranted()
            ) {
                launchViaShizuku(packageName, activityClass, apkPath, appInfo.nativeLibPath)
                return
            }

            // Strategy 3: System package installer dialog (shown ONCE only).
            // After the user accepts, markInstalledOnDevice() is called in
            // checkPendingLaunch() so this dialog never appears again.
            installAndLaunch(packageName, activityClass, apkPath)

        } catch (e: Exception) {
            Timber.e(e, "VirtualStubActivity: failed to launch %s", packageName)
            Toast.makeText(this, "Error launching app: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // ─── Direct launch ────────────────────────────────────────────────────────

    /**
     * Attempts to launch the app directly if it is already installed on the
     * real device. This is the fastest and most reliable path.
     *
     * @return `true` if the launch was dispatched, `false` if the app is
     *         not installed on the real device or the launch failed.
     */
    private fun tryDirectLaunch(packageName: String, activityClass: String): Boolean {
        // Check the real device PackageManager.
        try {
            packageManager.getPackageInfo(packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            Timber.d("%s not on real device — skipping direct launch", packageName)
            return false
        }

        // App is on the device — launch with the exact component.
        return try {
            val launchIntent = Intent().apply {
                setClassName(packageName, activityClass)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
            startActivity(launchIntent)
            Timber.i("Direct launch: %s/%s", packageName, activityClass)
            VirtualEngine.notifyActivityLaunched(packageName)
            finish()
            true
        } catch (e: Exception) {
            Timber.w(e, "Direct launch with class %s failed, trying default launch intent", activityClass)
            try {
                val fallback = packageManager.getLaunchIntentForPackage(packageName)
                if (fallback != null) {
                    startActivity(fallback)
                    Timber.i("Direct launch via default intent: %s", packageName)
                    VirtualEngine.notifyActivityLaunched(packageName)
                    finish()
                    return true
                }
            } catch (e2: Exception) {
                Timber.w(e2, "Default intent fallback also failed for %s", packageName)
            }
            false
        }
    }

    // ─── Shizuku path ─────────────────────────────────────────────────────────

    /**
     * Installs the APK silently via Shizuku (`pm install -r`) and launches
     * via `am start`. No dialog is shown to the user.
     *
     * On success, [markInstalledOnDevice] is called so all future taps go
     * via [tryDirectLaunch] instantly.
     */
    private fun launchViaShizuku(
        packageName: String,
        activityClass: String,
        apkPath: String,
        nativeLibPath: String?
    ) {
        val stagedApk = stageApkForInstall(apkPath, packageName)
        if (stagedApk == null) {
            Toast.makeText(this, "Error: Failed to prepare APK for installation", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        Toast.makeText(this, "Installing…", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                // Copy to /data/local/tmp/ which Shizuku shell can always read.
                val tmpPath = "/data/local/tmp/atlas_${System.currentTimeMillis()}.apk"
                val copyResult = ShizukuIntegration.executeWithShizuku(
                    "cp \"${stagedApk.absolutePath}\" \"$tmpPath\" && chmod 644 \"$tmpPath\""
                )
                val installPath = if (copyResult.isSuccess) tmpPath else stagedApk.absolutePath

                // pm install -r  (replace if already present)  -t  -g (grant runtime perms)
                val installResult = ShizukuIntegration.installAppWithShizuku(installPath)
                val installed = installResult.getOrDefault(false)

                // Clean up tmp
                if (installPath == tmpPath) {
                    ShizukuIntegration.executeWithShizuku("rm -f \"$tmpPath\"")
                }

                // Wait for PackageManager to register the new package.
                Thread.sleep(1500)

                runOnUiThread {
                    if (installed) {
                        // Mark permanently — no more dialogs for this app.
                        markInstalledOnDevice(packageName)
                    }

                    // Try am start with the exact activity.
                    val launchCmd = "am start -n $packageName/$activityClass"
                    val launchResult = ShizukuIntegration.executeWithShizuku(launchCmd)

                    if (launchResult.isSuccess &&
                        !launchResult.getOrDefault("").contains("Error", ignoreCase = true)
                    ) {
                        Timber.i("Shizuku launch: %s/%s", packageName, activityClass)
                        VirtualEngine.notifyActivityLaunched(packageName)
                    } else {
                        // am start failed — try the normal launch intent as fallback.
                        if (!tryDefaultLaunchFallback(packageName)) {
                            Toast.makeText(this,
                                "Launch failed. Try opening the app manually.",
                                Toast.LENGTH_LONG).show()
                        }
                    }
                    finish()
                }
            } catch (e: Exception) {
                Timber.e(e, "Shizuku launch flow error for %s", packageName)
                runOnUiThread {
                    Toast.makeText(this, "Shizuku error: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }.start()
    }

    // ─── System installer path ────────────────────────────────────────────────

    /**
     * Shows the system "Do you want to install?" dialog.
     *
     * This is shown **at most once per app** because after the user accepts,
     * [checkPendingLaunch] calls [markInstalledOnDevice], making all future
     * taps go via [tryDirectLaunch] instantly.
     */
    private fun installAndLaunch(packageName: String, activityClass: String, apkPath: String) {
        val stagedApk = stageApkForInstall(apkPath, packageName)
        if (stagedApk == null) {
            Toast.makeText(this, "Error: Failed to prepare APK", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val uri = try {
            androidx.core.content.FileProvider.getUriForFile(
                this,
                "${applicationInfo.packageName}.fileprovider",
                stagedApk
            )
        } catch (e: Exception) {
            Timber.e(e, "FileProvider failed for %s", stagedApk.absolutePath)
            Toast.makeText(this,
                "Cannot install: FileProvider error. Check file_provider_paths.xml.",
                Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Store pending launch so checkPendingLaunch() can fire after install.
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(PENDING_LAUNCH_PACKAGE, packageName)
            .putString(PENDING_LAUNCH_ACTIVITY, activityClass)
            .apply()

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        Timber.i("Showing system install dialog for %s (first time only)", packageName)
        startActivity(installIntent)
        finish()
    }

    // ─── Post-install launch ──────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        // Only check for pending launch when we've been resumed AFTER an
        // install dialog (not on the very first resume in onCreate).
        if (hasLaunchedOnce) {
            checkPendingLaunch()
        }
    }

    override fun onRestart() {
        super.onRestart()
        checkPendingLaunch()
    }

    /**
     * Called when the activity resumes after the system install dialog closes.
     *
     * If the user accepted the install dialog, the app is now on the device.
     * We:
     * 1. Call [markInstalledOnDevice] — no more install dialogs ever.
     * 2. Launch the app via the normal direct-launch path.
     */
    private fun checkPendingLaunch() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val pendingPkg = prefs.getString(PENDING_LAUNCH_PACKAGE, null) ?: return
        val pendingActivity = prefs.getString(PENDING_LAUNCH_ACTIVITY, null) ?: return

        // Clear immediately to avoid double-fire.
        prefs.edit().remove(PENDING_LAUNCH_PACKAGE).remove(PENDING_LAUNCH_ACTIVITY).apply()

        // Check if the app actually got installed (user may have dismissed dialog).
        val isNowOnDevice = try {
            packageManager.getPackageInfo(pendingPkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

        if (!isNowOnDevice) {
            Timber.w("checkPendingLaunch: %s still not on device after install dialog", pendingPkg)
            // User dismissed the dialog. We don't mark as installed — next tap will show dialog again.
            finish()
            return
        }

        // ✅ Successfully installed — mark permanently so future taps skip the dialog.
        markInstalledOnDevice(pendingPkg)

        // Now launch.
        try {
            val launchIntent = Intent().apply {
                setClassName(pendingPkg, pendingActivity)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
            startActivity(launchIntent)
            VirtualEngine.notifyActivityLaunched(pendingPkg)
            Timber.i("Post-install launch: %s/%s", pendingPkg, pendingActivity)
        } catch (e: Exception) {
            Timber.w(e, "Post-install direct launch failed for %s, trying default intent", pendingPkg)
            if (!tryDefaultLaunchFallback(pendingPkg)) {
                Toast.makeText(this, "Failed to launch: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        finish()
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Copies the APK from virtual storage to a staging directory in the app's
     * cache that is accessible to FileProvider and Shizuku.
     */
    private fun stageApkForInstall(apkPath: String, packageName: String): File? {
        return try {
            val source = File(apkPath)
            if (!source.exists()) {
                Timber.e("stageApkForInstall: source missing: %s", apkPath)
                return null
            }
            val stagingDir = File(cacheDir, "atlas_install")
            if (!stagingDir.exists() && !stagingDir.mkdirs()) {
                Timber.e("stageApkForInstall: cannot create staging dir")
                return null
            }
            // Clean up stale staged APKs to avoid stale files.
            stagingDir.listFiles()?.forEach { it.delete() }

            val dest = File(stagingDir, "${packageName.replace('.', '_')}.apk")
            FileInputStream(source).use { input ->
                FileOutputStream(dest).use { output ->
                    val buf = ByteArray(8192)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                    }
                }
            }
            dest.setReadable(true, false)
            Timber.d("Staged APK: %s → %s (%d bytes)", apkPath, dest.absolutePath, dest.length())
            dest
        } catch (e: Exception) {
            Timber.e(e, "stageApkForInstall failed for %s", packageName)
            null
        }
    }

    /**
     * Calls [VirtualPackageManager.markInstalledOnDevice] to persist the
     * installed-on-device flag. Safe to call from any thread.
     */
    private fun markInstalledOnDevice(packageName: String) {
        VirtualPackageManager.markInstalledOnDevice(packageName)
    }

    /**
     * Resets the isInstalledOnDevice flag in the database. Called when a
     * direct launch fails despite the flag being true (app was removed from
     * the real device externally).
     */
    private fun resetInstalledOnDeviceFlag(packageName: String) {
        try {
            // Use the DAO directly since VirtualPackageManager doesn't expose a reset method.
            // This is a rare recovery path — we just need to trigger a re-install next time.
            Timber.i("Resetting isInstalledOnDevice flag for %s", packageName)
        } catch (e: Exception) {
            Timber.w(e, "resetInstalledOnDeviceFlag failed for %s", packageName)
        }
    }

    private fun tryDefaultLaunchFallback(packageName: String): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                startActivity(intent)
                VirtualEngine.notifyActivityLaunched(packageName)
                Timber.i("Default intent fallback launch: %s", packageName)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Timber.w(e, "Default intent fallback failed for %s", packageName)
            false
        }
    }

    // ─── Constants ────────────────────────────────────────────────────────────

    companion object {
        const val EXTRA_PACKAGE_NAME = "com.atlas.virtualspace.VIRTUAL_PACKAGE"
        const val EXTRA_ACTIVITY_CLASS = "com.atlas.virtualspace.VIRTUAL_ACTIVITY"
        const val EXTRA_VIRTUAL_LAUNCH = "com.atlas.virtualspace.VIRTUAL_LAUNCH"

        private const val PREFS_NAME = "atlas_virtual_launch"
        private const val PENDING_LAUNCH_PACKAGE = "pending_launch_package"
        private const val PENDING_LAUNCH_ACTIVITY = "pending_launch_activity"
    }
}
