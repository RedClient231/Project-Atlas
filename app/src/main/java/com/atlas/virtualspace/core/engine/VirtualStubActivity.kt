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
 * multiple launch strategies to get the app running:
 *
 * ## Launch Strategy (in order)
 *
 * 1. **Direct launch**: If the app is already installed on the real device,
 *    launch it directly via `startActivity` with the resolved component.
 *    This is the fastest and most reliable path.
 *
 * 2. **Shizuku install + launch**: If Shizuku is available and the app is
 *    NOT installed on the real device, copy the APK to a temp location
 *    accessible by Shizuku, install silently via `pm install`, then launch
 *    via `am start`.
 *
 * 3. **System package installer**: If Shizuku is unavailable, copy the APK
 *    to the cache directory, get a FileProvider URI, and show the standard
 *    Android install dialog. After installation, launch the app.
 *
 * ## Why install on the real device?
 *
 * Unlike simple Java apps, games require full Android framework support:
 * GPU-accelerated rendering (Surface/SurfaceView), native libraries (.so),
 * proper Activity lifecycle, content providers, and more. Loading an APK
 * via DexClassLoader cannot provide these — the app will crash or show
 * nothing. The only reliable way to run a full Android app (especially
 * games) is to install it on the device and let Android manage its lifecycle.
 *
 * Atlas provides "virtual space" management (separate data, independent
 * lifecycle control, isolated storage) rather than process-level isolation.
 */
class VirtualStubActivity : Activity() {

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

        // Verify this package is actually installed in the virtual space
        val appInfo = try {
            VirtualPackageManager.getAppInfo(packageName)
        } catch (e: Exception) {
            Timber.e(e, "VirtualStubActivity: VirtualPackageManager not initialized")
            Toast.makeText(this, "Error: Virtual space not initialized. Please restart Atlas.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (appInfo == null) {
            Timber.e("VirtualStubActivity: Package %s not found in virtual space", packageName)
            Toast.makeText(this, "Error: App not found in virtual space", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        Timber.i("VirtualStubActivity: Launching %s/%s", packageName, activityClass)

        try {
            val apkPath = appInfo.apkPath
            if (apkPath.isEmpty() || !File(apkPath).exists()) {
                Timber.e("VirtualStubActivity: APK path missing or does not exist: %s", apkPath)
                Toast.makeText(this, "Error: APK file not found", Toast.LENGTH_LONG).show()
                finish()
                return
            }

            // ── Strategy 1: Direct launch if already installed on device ──
            if (tryDirectLaunch(packageName, activityClass)) {
                return
            }

            // ── Strategy 2: Shizuku install + launch ──
            if (ShizukuIntegration.isShizukuAvailable() && ShizukuIntegration.isShizukuPermissionGranted()) {
                launchViaShizuku(packageName, activityClass, apkPath, appInfo.nativeLibPath)
                return
            }

            // ── Strategy 3: System package installer ──
            installAndLaunch(packageName, activityClass, apkPath)

        } catch (e: Exception) {
            Timber.e(e, "VirtualStubActivity: Failed to launch %s/%s", packageName, activityClass)
            Toast.makeText(this, "Error launching app: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    /**
     * Copies the APK from virtual storage to a staging directory in the app's
     * cache that is accessible to FileProvider and Shizuku.
     *
     * The virtual root is at /data/data/{atlas}/virtual_root/apps/{pkg}/base.apk
     * which is NOT accessible to the system package installer or Shizuku's
     * shell process. We copy to /data/data/{atlas}/cache/atlas_install/ which
     * IS accessible via our FileProvider configuration.
     *
     * @return The staged File, or null if the copy failed.
     */
    private fun stageApkForInstall(apkPath: String, packageName: String): File? {
        return try {
            val sourceFile = File(apkPath)
            if (!sourceFile.exists()) {
                Timber.e("Stage APK: source does not exist: %s", apkPath)
                return null
            }

            // Create staging directory in cache (accessible to FileProvider)
            val stagingDir = File(cacheDir, "atlas_install")
            if (!stagingDir.exists() && !stagingDir.mkdirs()) {
                Timber.e("Stage APK: failed to create staging dir: %s", stagingDir.absolutePath)
                return null
            }

            // Clean up old staged APKs
            stagingDir.listFiles()?.forEach { it.delete() }

            // Copy APK to staging with a sanitized name
            val stagedFile = File(stagingDir, "${packageName.replace('.', '_')}.apk")
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(stagedFile).use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                }
            }

            // Make the file world-readable so Shizuku shell can access it
            stagedFile.setReadable(true, false)

            Timber.i("Stage APK: copied %s → %s (%d bytes)", apkPath, stagedFile.absolutePath, stagedFile.length())
            stagedFile
        } catch (e: Exception) {
            Timber.e(e, "Stage APK: failed to copy APK for installation")
            null
        }
    }

    /**
     * Attempts to launch the app directly if it's already installed on the
     * real device. This is the fastest and most reliable path.
     *
     * @return `true` if the launch was dispatched, `false` if the app is
     *         not installed or the launch failed.
     */
    private fun tryDirectLaunch(packageName: String, activityClass: String): Boolean {
        // First, check if the app is installed on the real device
        try {
            packageManager.getPackageInfo(packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            Timber.d("App %s not installed on device — trying other strategies", packageName)
            return false
        }

        // App is installed — try to launch with the exact component
        return try {
            val launchIntent = Intent().apply {
                setClassName(packageName, activityClass)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
            startActivity(launchIntent)
            Timber.i("VirtualStubActivity: Launched %s directly (already installed on device)", packageName)

            // Update process state
            VirtualEngine.notifyActivityLaunched(packageName)
            finish()
            true
        } catch (e: Exception) {
            Timber.w(e, "Direct launch with class %s failed — trying launchIntentForPackage", activityClass)

            // Fallback: try the system's default launch intent for the package
            try {
                val defaultIntent = packageManager.getLaunchIntentForPackage(packageName)
                if (defaultIntent != null) {
                    startActivity(defaultIntent)
                    Timber.i("VirtualStubActivity: Launched %s via default launch intent", packageName)
                    VirtualEngine.notifyActivityLaunched(packageName)
                    finish()
                    return true
                }
            } catch (e2: Exception) {
                Timber.w(e2, "Default launch intent also failed for %s", packageName)
            }
            false
        }
    }

    /**
     * Installs the APK via Shizuku's `pm install` command and then launches
     * the activity using `am start`.
     *
     * This runs on a background thread because Shizuku operations are blocking.
     * The APK is first staged to a temp location accessible by Shizuku's shell.
     */
    private fun launchViaShizuku(
        packageName: String,
        activityClass: String,
        apkPath: String,
        nativeLibPath: String?
    ) {
        // Stage the APK to a location accessible by Shizuku
        val stagedApk = stageApkForInstall(apkPath, packageName)
        if (stagedApk == null) {
            Toast.makeText(this, "Error: Failed to prepare APK for installation", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Show a brief toast so the user knows something is happening
        Toast.makeText(this, "Installing ${packageName.substringAfterLast('.')}...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                // Step 1: Copy the staged APK to /data/local/tmp/ which is
                // guaranteed to be accessible by Shizuku's shell process.
                val tmpApkPath = "/data/local/tmp/atlas_install_${System.currentTimeMillis()}.apk"
                val copyResult = ShizukuIntegration.executeWithShizuku("cp \"${stagedApk.absolutePath}\" \"$tmpApkPath\" && chmod 644 \"$tmpApkPath\"")
                
                val installPath = if (copyResult.isSuccess) {
                    tmpApkPath
                } else {
                    Timber.w(copyResult.exceptionOrNull(), "Shizuku cp to tmp failed — trying direct install from staging")
                    // Try direct install from the staged location
                    stagedApk.absolutePath
                }

                // Step 2: Install the APK via Shizuku
                val installResult = ShizukuIntegration.installAppWithShizuku(installPath)
                if (installResult.isFailure) {
                    Timber.w(installResult.exceptionOrNull(), "Shizuku install failed for %s", packageName)
                    // Even if install fails (e.g. app already installed), try to launch anyway
                } else if (installResult.getOrDefault(false)) {
                    Timber.i("Shizuku install succeeded for %s", packageName)
                }

                // Clean up /data/local/tmp copy
                if (installPath == tmpApkPath) {
                    ShizukuIntegration.executeWithShizuku("rm -f \"$tmpApkPath\"")
                }

                // Small delay to let the package manager register the app
                Thread.sleep(1500)

                // Step 3: Try launching with the exact activity class
                val launchCmd = "am start -n $packageName/$activityClass"
                val launchResult = ShizukuIntegration.executeWithShizuku(launchCmd)

                runOnUiThread {
                    if (launchResult.isSuccess) {
                        val output = launchResult.getOrDefault("")
                        if (output.contains("Error", ignoreCase = true) || output.contains("not exist", ignoreCase = true)) {
                            Timber.w("am start returned error: %s", output)
                            // Try default launch intent as fallback
                            if (!tryDefaultLaunchFallback(packageName)) {
                                Toast.makeText(this, "Launch failed. Try opening the app manually.", Toast.LENGTH_LONG).show()
                            }
                        } else {
                            Timber.i("VirtualStubActivity: Launched %s via Shizuku am start", packageName)
                            VirtualEngine.notifyActivityLaunched(packageName)
                        }
                    } else {
                        Timber.e(launchResult.exceptionOrNull(), "Shizuku launch command failed")
                        // Fallback: try launching via package manager intent
                        if (!tryDefaultLaunchFallback(packageName)) {
                            Toast.makeText(
                                this,
                                "Failed to launch via Shizuku: ${launchResult.exceptionOrNull()?.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    finish()
                }
            } catch (e: Exception) {
                Timber.e(e, "Error in Shizuku launch flow")
                runOnUiThread {
                    Toast.makeText(this, "Shizuku launch error: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }.start()
    }

    /**
     * Tries to launch the app using the system PackageManager's default
     * launch intent. Used as a fallback when `am start -n` fails.
     */
    private fun tryDefaultLaunchFallback(packageName: String): Boolean {
        return try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                Timber.i("VirtualStubActivity: Launched %s via default launch intent (fallback)", packageName)
                VirtualEngine.notifyActivityLaunched(packageName)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Timber.w(e, "Default launch intent fallback failed for %s", packageName)
            false
        }
    }

    /**
     * Installs the APK using the system package installer and then launches it.
     * This shows the system install confirmation dialog to the user.
     *
     * The APK is first staged to the cache directory, then shared via
     * FileProvider with a content:// URI.
     */
    private fun installAndLaunch(packageName: String, activityClass: String, apkPath: String) {
        try {
            // Stage the APK to cache directory (accessible by FileProvider)
            val stagedApk = stageApkForInstall(apkPath, packageName)
            if (stagedApk == null) {
                Toast.makeText(this, "Error: Failed to prepare APK for installation", Toast.LENGTH_LONG).show()
                finish()
                return
            }

            // Get a content URI via FileProvider
            val uri = try {
                androidx.core.content.FileProvider.getUriForFile(
                    this,
                    "${applicationInfo.packageName}.fileprovider",
                    stagedApk
                )
            } catch (e: Exception) {
                Timber.e(e, "FileProvider failed for staged APK: %s", stagedApk.absolutePath)
                null
            }

            if (uri != null) {
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                Timber.i("VirtualStubActivity: Requesting system install for %s", packageName)

                // Store the launch info so we can launch after install completes
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                prefs.edit()
                    .putString(PENDING_LAUNCH_PACKAGE, packageName)
                    .putString(PENDING_LAUNCH_ACTIVITY, activityClass)
                    .apply()

                startActivity(installIntent)
            } else {
                // Last resort: try direct file URI (may not work on Android 7+)
                Timber.w("FileProvider URI failed — cannot install without Shizuku or FileProvider")
                Toast.makeText(
                    this,
                    "Cannot install without Shizuku. Please install Shizuku and grant permission.",
                    Toast.LENGTH_LONG
                ).show()
            }

            finish()
        } catch (e: Exception) {
            Timber.e(e, "Failed to initiate system install")
            Toast.makeText(this, "Cannot install: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onRestart() {
        super.onRestart()
        checkPendingLaunch()
    }

    override fun onResume() {
        super.onResume()
        checkPendingLaunch()
    }

    private fun checkPendingLaunch() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val pendingPkg = prefs.getString(PENDING_LAUNCH_PACKAGE, null)
        val pendingActivity = prefs.getString(PENDING_LAUNCH_ACTIVITY, null)

        if (pendingPkg != null && pendingActivity != null) {
            // Clear the pending launch
            prefs.edit().remove(PENDING_LAUNCH_PACKAGE).remove(PENDING_LAUNCH_ACTIVITY).apply()

            // Try to launch the app now that it's installed
            try {
                val launchIntent = Intent().apply {
                    setClassName(pendingPkg, pendingActivity)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                }
                startActivity(launchIntent)
                VirtualEngine.notifyActivityLaunched(pendingPkg)
                Timber.i("VirtualStubActivity: Launched %s after install", pendingPkg)
            } catch (e: Exception) {
                Timber.e(e, "Failed to launch %s after install", pendingPkg)
                // Fallback: try default launch intent
                try {
                    val defaultIntent = packageManager.getLaunchIntentForPackage(pendingPkg)
                    if (defaultIntent != null) {
                        startActivity(defaultIntent)
                        VirtualEngine.notifyActivityLaunched(pendingPkg)
                    } else {
                        Toast.makeText(this, "Failed to launch: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                } catch (e2: Exception) {
                    Toast.makeText(this, "Failed to launch: ${e2.message}", Toast.LENGTH_LONG).show()
                }
            }
            finish()
        }
    }

    companion object {
        /** Intent extra: the virtual app's package name. */
        const val EXTRA_PACKAGE_NAME = "com.atlas.virtualspace.VIRTUAL_PACKAGE"

        /** Intent extra: the fully-qualified activity class name to launch. */
        const val EXTRA_ACTIVITY_CLASS = "com.atlas.virtualspace.VIRTUAL_ACTIVITY"

        /** Intent extra: flag indicating this is a virtual launch. */
        const val EXTRA_VIRTUAL_LAUNCH = "com.atlas.virtualspace.VIRTUAL_LAUNCH"

        private const val PREFS_NAME = "atlas_virtual_launch"
        private const val PENDING_LAUNCH_PACKAGE = "pending_launch_package"
        private const val PENDING_LAUNCH_ACTIVITY = "pending_launch_activity"
    }
}
