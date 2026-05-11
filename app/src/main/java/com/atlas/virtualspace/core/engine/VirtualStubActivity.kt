package com.atlas.virtualspace.core.engine

import android.app.Activity
import android.content.Intent
import android.content.res.Resources
import android.os.Bundle
import android.widget.Toast
import com.atlas.virtualspace.core.pm.VirtualPackageManager
import dalvik.system.PathClassLoader
import timber.log.Timber
import java.io.File
import java.lang.reflect.InvocationTargetException

/**
 * Proxy activity that acts as a bridge between the Android system and
 * virtual apps running inside the Atlas virtual space.
 *
 * When the user taps "Launch" on a virtual app, this stub activity is
 * started instead of the virtual app's real activity. The stub loads the
 * virtual app's APK via a custom ClassLoader and attempts to launch the
 * target activity.
 *
 * ## Launch Strategy
 *
 * Since the virtual app's activities are NOT declared in Atlas's manifest,
 * we cannot use a direct `startActivity()` call targeting the real class.
 * Instead, we use a two-pronged approach:
 *
 * 1. **Shizuku available**: Install the APK temporarily via Shizuku's
 *    `pm install` and launch the activity normally. Uninstall when done.
 * 2. **No Shizuku**: Use the system package installer to install, then
 *    launch via the standard intent.
 *
 * Both approaches ensure the app runs in its own process with full
 * Android lifecycle support.
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

            // Strategy 1: Try launching via Shizuku (elevated install + am start)
            val shizuku = com.atlas.virtualspace.core.hook.ShizukuIntegration
            if (shizuku.isShizukuAvailable() && shizuku.isShizukuPermissionGranted()) {
                launchViaShizuku(packageName, activityClass, apkPath, appInfo.nativeLibPath)
                return
            }

            // Strategy 2: Try direct launch using the system PackageManager
            // If the app is also installed on the real device, we can launch it directly
            try {
                val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                if (launchIntent != null) {
                    Timber.i("VirtualStubActivity: App %s is installed on device, launching directly", packageName)
                    startActivity(launchIntent)
                    finish()
                    return
                }
            } catch (_: Exception) {
                // Not installed on device — fall through
            }

            // Strategy 3: Use the session-based package installer to install the APK
            // and then launch it. This requires REQUEST_INSTALL_PACKAGES permission.
            installAndLaunch(packageName, activityClass, apkPath)

        } catch (e: Exception) {
            Timber.e(e, "VirtualStubActivity: Failed to launch %s/%s", packageName, activityClass)
            Toast.makeText(this, "Error launching app: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    /**
     * Installs the APK via Shizuku's pm install command and then launches
     * the activity using am start.
     */
    private fun launchViaShizuku(
        packageName: String,
        activityClass: String,
        apkPath: String,
        nativeLibPath: String?
    ) {
        Thread {
            try {
                val shizuku = com.atlas.virtualspace.core.hook.ShizukuIntegration

                // First, install the APK via Shizuku
                // Use -r (replace) and -t (allow test) flags
                // Also add -g to grant all permissions automatically
                val installResult = shizuku.installAppWithShizuku(apkPath)
                if (installResult.isFailure) {
                    Timber.w(installResult.exceptionOrNull(), "Shizuku install failed, trying direct launch")
                    // Even if install fails (e.g. app already installed), try to launch
                }

                // Small delay to let the package manager register the app
                Thread.sleep(1000)

                // Launch the activity using am start
                val launchCmd = "am start -n $packageName/$activityClass"
                val launchResult = shizuku.executeWithShizuku(launchCmd)

                runOnUiThread {
                    if (launchResult.isSuccess) {
                        Timber.i("VirtualStubActivity: Launched %s via Shizuku", packageName)
                    } else {
                        Timber.e(launchResult.exceptionOrNull(), "Shizuku launch failed")
                        Toast.makeText(this, "Failed to launch via Shizuku: ${launchResult.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
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
     * Installs the APK using the system package installer and then launches it.
     * This shows the system install confirmation dialog to the user.
     */
    private fun installAndLaunch(packageName: String, activityClass: String, apkPath: String) {
        try {
            // Use the standard Android package installer
            val apkFile = File(apkPath)
            if (!apkFile.exists()) {
                Toast.makeText(this, "APK file not found: $apkPath", Toast.LENGTH_LONG).show()
                finish()
                return
            }

            // Create an install intent using the FileProvider
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${applicationInfo.packageName}.fileprovider",
                apkFile
            )

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
            finish()
        } catch (e: Exception) {
            Timber.e(e, "Failed to initiate system install")
            Toast.makeText(this, "Cannot install: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onRestart() {
        super.onRestart()
        // Check if there's a pending launch after install
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
                Timber.i("VirtualStubActivity: Launched %s after install", pendingPkg)
            } catch (e: Exception) {
                Timber.e(e, "Failed to launch %s after install", pendingPkg)
                Toast.makeText(this, "Failed to launch: ${e.message}", Toast.LENGTH_LONG).show()
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
