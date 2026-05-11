package com.atlas.virtualspace.core.engine

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import com.atlas.virtualspace.core.hook.ShizukuIntegration
import com.atlas.virtualspace.core.pm.VirtualPackageManager
import dalvik.system.DexClassLoader
import timber.log.Timber
import java.io.File

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
 *    NOT installed on the real device, install it silently via `pm install`
 *    through Shizuku, then launch via `am start`. The app runs in its own
 *    process with full Android lifecycle and GPU support.
 *
 * 3. **System package installer**: If Shizuku is unavailable, show the
 *    standard Android install dialog. After installation, launch the app.
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
     */
    private fun launchViaShizuku(
        packageName: String,
        activityClass: String,
        apkPath: String,
        nativeLibPath: String?
    ) {
        // Show a brief toast so the user knows something is happening
        Toast.makeText(this, "Installing ${packageName.substringAfterLast('.')}...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                // Step 1: Install the APK via Shizuku
                val installResult = ShizukuIntegration.installAppWithShizuku(apkPath)
                if (installResult.isFailure) {
                    Timber.w(installResult.exceptionOrNull(), "Shizuku install failed for %s", packageName)
                    // Even if install fails (e.g. app already installed), try to launch anyway
                } else if (installResult.getOrDefault(false)) {
                    Timber.i("Shizuku install succeeded for %s", packageName)
                }

                // Small delay to let the package manager register the app
                Thread.sleep(1500)

                // Step 2: Try launching with the exact activity class
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
     */
    private fun installAndLaunch(packageName: String, activityClass: String, apkPath: String) {
        try {
            val apkFile = File(apkPath)
            if (!apkFile.exists()) {
                Toast.makeText(this, "APK file not found: $apkPath", Toast.LENGTH_LONG).show()
                finish()
                return
            }

            // Try FileProvider URI first
            val uri = try {
                androidx.core.content.FileProvider.getUriForFile(
                    this,
                    "${applicationInfo.packageName}.fileprovider",
                    apkFile
                )
            } catch (e: Exception) {
                Timber.w(e, "FileProvider failed for %s — trying content URI fallback", apkPath)
                // Fallback: try using a content URI via MediaStore
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
                // Last resort: try to launch via Shizuku without installing first
                // (in case the app is already installed but we couldn't detect it)
                Toast.makeText(
                    this,
                    "Cannot install without Shizuku. Please install Shizuku and grant permission.",
                    Toast.LENGTH_LONG
                ).show()
                Timber.w("No way to install/launch %s — Shizuku not available and FileProvider failed", packageName)
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
