package com.atlas.virtualspace.core.engine

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import com.atlas.virtualspace.core.pm.VirtualPackageManager
import timber.log.Timber

/**
 * Proxy activity that acts as a bridge between the Android system and
 * virtual apps running inside the Atlas virtual space.
 *
 * When the user taps "Launch" on a virtual app, this stub activity is
 * started instead of the virtual app's real activity (which is not
 * installed on the device). The stub then loads the virtual app's
 * APK via a custom ClassLoader and launches the target activity
 * within the Atlas process.
 *
 * ## How it works
 *
 * 1. The launch pipeline creates an [Intent] targeting this activity
 *    with extras carrying the target package name and activity class.
 * 2. Android starts [VirtualStubActivity] because it IS declared in
 *    the manifest and IS installed.
 * 3. [onCreate] reads the extras, resolves the virtual app's APK path,
 *    creates a [dalvik.system.PathClassLoader] for it, and loads the
 *    target activity class.
 * 4. The target activity is instantiated and its lifecycle methods are
 *    delegated to, running within the Atlas host process.
 *
 * ## Security
 *
 * Only launches packages that are registered in the virtual space
 * database. Rejects any attempt to launch an unregistered package.
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
            // Create a ClassLoader that can load classes from the virtual APK
            val apkPath = appInfo.apkPath
            val libPath = appInfo.nativeLibPath

            if (apkPath == null || !java.io.File(apkPath).exists()) {
                Timber.e("VirtualStubActivity: APK path missing or does not exist: %s", apkPath)
                Toast.makeText(this, "Error: APK file not found", Toast.LENGTH_LONG).show()
                finish()
                return
            }

            // Build the class loader with the virtual APK and its native libraries
            val librarySearchPath = libPath ?: ""
            val classLoader = dalvik.system.PathClassLoader(
                apkPath,
                librarySearchPath,
                this.classLoader
            )

            // Load the target activity class
            val targetClass = classLoader.loadClass(activityClass)

            // Create a new intent that will launch the target activity within our process
            val launchIntent = Intent(this, targetClass)
            launchIntent.putExtra(EXTRA_VIRTUAL_LAUNCH, true)
            launchIntent.putExtra(EXTRA_PACKAGE_NAME, packageName)

            // Copy over any original extras (excluding our internal ones)
            val sourceExtras = intent.extras
            if (sourceExtras != null) {
                for (key in sourceExtras.keySet()) {
                    if (key != EXTRA_PACKAGE_NAME && key != EXTRA_ACTIVITY_CLASS && key != EXTRA_VIRTUAL_LAUNCH) {
                        launchIntent.putExtra(key, sourceExtras.get(key))
                    }
                }
            }

            // Start the virtual activity
            startActivity(launchIntent)

            // NOTE: Do NOT call VirtualEngine.launchApp() here — this stub
            // is already being called FROM VirtualEngine.launchApp(), so
            // calling it again would cause a recursive launch attempt.
            // The engine has already registered the process record and
            // updated the launch time before starting this stub.

            // Finish the stub — the real activity takes over
            finish()

            Timber.i("VirtualStubActivity: Successfully launched %s", activityClass)
        } catch (e: ClassNotFoundException) {
            Timber.e(e, "VirtualStubActivity: Activity class not found: %s", activityClass)
            Toast.makeText(this, "Error: Could not find activity ${activityClass.substringAfterLast('.')}", Toast.LENGTH_LONG).show()
            finish()
        } catch (e: Exception) {
            Timber.e(e, "VirtualStubActivity: Failed to launch %s/%s", packageName, activityClass)
            Toast.makeText(this, "Error launching app: ${e.message}", Toast.LENGTH_LONG).show()
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
    }
}
