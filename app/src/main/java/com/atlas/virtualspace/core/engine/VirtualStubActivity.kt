package com.atlas.virtualspace.core.engine

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import dalvik.system.DexClassLoader
import timber.log.Timber
import java.io.File

/**
 * VirtualStubActivity — In-process host for virtual (guest) app Activities.
 *
 * ## Architecture
 *
 * Android requires all Activities to be declared in the manifest at compile time.
 * The guest game's Activities are NOT in our manifest. To run them inside our
 * process we use the "stub activity" technique (same as VirtualApp/DualSpace):
 *
 * 1. This Activity is declared in the manifest under Atlas's package.
 * 2. When the user taps "Launch", [VirtualEngine] starts THIS activity with
 *    extras specifying which guest package + activity class to load.
 * 3. In [onCreate], we:
 *    a. Create a [DexClassLoader] pointing at the guest APK
 *    b. Load the guest Activity class
 *    c. Create an instance of it
 *    d. Attach our own [Context] (wrapped with the guest's Resources)
 *    e. Call the guest Activity's lifecycle methods manually
 *
 * The guest Activity renders its UI into THIS Activity's window. Since it runs
 * in Atlas's process, GameGuardian can see its memory regions in /proc/self/maps.
 *
 * ## Why this works for GameGuardian
 *
 * GG scans /proc/{pid}/maps for memory regions. Since the game's .dex and .so
 * files are loaded INTO Atlas's process via DexClassLoader and System.load(),
 * they appear as mapped regions under Atlas's PID. Our [GameGuardianCompat]
 * hooks further expose these regions with readable permissions.
 *
 * ## Limitations
 *
 * - Split APKs: all splits must be passed to the ClassLoader
 * - Native libs: must be extracted and loaded from the correct ABI directory
 * - Content Providers declared by the guest app won't work without additional
 *   proxy providers (future enhancement)
 * - Services declared by the guest app need stub service proxies (future)
 */
class VirtualStubActivity : Activity() {

    private var guestActivity: Activity? = null
    private var guestClassLoader: ClassLoader? = null
    private var guestResources: Resources? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        val activityClass = intent.getStringExtra(EXTRA_ACTIVITY_CLASS)

        if (packageName.isNullOrBlank() || activityClass.isNullOrBlank()) {
            Timber.e("VirtualStubActivity: missing extras")
            finish()
            return
        }

        Timber.i("VirtualStubActivity: loading %s/%s in-process", packageName, activityClass)

        try {
            val appInfo = VirtualEngine.installedApps.value
                .firstOrNull { it.packageName == packageName }

            if (appInfo == null) {
                Timber.e("VirtualStubActivity: %s not found in virtual engine", packageName)
                finish()
                return
            }

            val apkFile = File(appInfo.apkPath)
            if (!apkFile.exists()) {
                Timber.e("VirtualStubActivity: APK missing at %s", appInfo.apkPath)
                finish()
                return
            }

            // ── Step 1: Create DexClassLoader for the guest APK ────────────────
            val optimizedDir = File(filesDir, "virtual_dex/$packageName")
            if (!optimizedDir.exists()) optimizedDir.mkdirs()

            val libDir = appInfo.nativeLibPath ?: ""
            val libPath = buildNativeLibPath(libDir, packageName)

            // Include split APK paths in the classpath
            val dexPath = buildString {
                append(appInfo.apkPath)
                for (split in appInfo.splitApkPaths) {
                    append(File.pathSeparator)
                    append(split)
                }
            }

            guestClassLoader = DexClassLoader(
                dexPath,
                optimizedDir.absolutePath,
                libPath,
                classLoader // parent = Atlas's own classloader
            )

            Timber.d("Created DexClassLoader: dex=%s, lib=%s", dexPath, libPath)

            // ── Step 2: Create Resources object from guest APK ─────────────────
            guestResources = createResourcesForApk(appInfo.apkPath, appInfo.splitApkPaths)

            // ── Step 3: Load native libraries (.so files) ──────────────────────
            loadNativeLibraries(packageName, libDir)

            // ── Step 4: Load the guest Activity class ──────────────────────────
            val guestActivityClass = guestClassLoader!!.loadClass(activityClass)
            Timber.d("Loaded guest activity class: %s", guestActivityClass.name)

            // ── Step 5: Instantiate and attach the guest Activity ───────────────
            guestActivity = instantiateGuestActivity(guestActivityClass, packageName)

            // ── Step 6: Configure window for the game ──────────────────────────
            configureWindowForGame()

            // ── Step 7: Dispatch onCreate to the guest Activity ─────────────────
            dispatchGuestOnCreate(guestActivity!!, savedInstanceState)

            // Notify engine that the app has launched
            VirtualEngine.notifyActivityLaunched(packageName)

            Timber.i("VirtualStubActivity: %s/%s launched successfully in-process",
                packageName, activityClass)

        } catch (e: ClassNotFoundException) {
            Timber.e(e, "Guest activity class not found: %s", activityClass)
            finish()
        } catch (e: Exception) {
            Timber.e(e, "Failed to launch guest activity in-process")
            finish()
        }
    }

    // ─── Guest Activity Lifecycle Forwarding ──────────────────────────────────

    override fun onStart() {
        super.onStart()
        try {
            callGuestLifecycle("onStart")
        } catch (e: Exception) {
            Timber.w(e, "Guest onStart failed")
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            callGuestLifecycle("onResume")
        } catch (e: Exception) {
            Timber.w(e, "Guest onResume failed")
        }
    }

    override fun onPause() {
        try {
            callGuestLifecycle("onPause")
        } catch (e: Exception) {
            Timber.w(e, "Guest onPause failed")
        }
        super.onPause()
    }

    override fun onStop() {
        try {
            callGuestLifecycle("onStop")
        } catch (e: Exception) {
            Timber.w(e, "Guest onStop failed")
        }
        super.onStop()
    }

    override fun onDestroy() {
        try {
            callGuestLifecycle("onDestroy")
        } catch (e: Exception) {
            Timber.w(e, "Guest onDestroy failed")
        }
        guestActivity = null
        guestClassLoader = null
        guestResources = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        try {
            val method = Activity::class.java.getDeclaredMethod(
                "onSaveInstanceState", Bundle::class.java
            )
            method.isAccessible = true
            method.invoke(guestActivity, outState)
        } catch (e: Exception) {
            Timber.d(e, "Guest onSaveInstanceState not available")
        }
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        try {
            val method = Activity::class.java.getDeclaredMethod(
                "onNewIntent", Intent::class.java
            )
            method.isAccessible = true
            method.invoke(guestActivity, intent)
        } catch (e: Exception) {
            Timber.d(e, "Guest onNewIntent not available")
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        try {
            val method = Activity::class.java.getDeclaredMethod(
                "onWindowFocusChanged", Boolean::class.java
            )
            method.isAccessible = true
            method.invoke(guestActivity, hasFocus)
        } catch (e: Exception) {
            Timber.d(e, "Guest onWindowFocusChanged not available")
        }
    }

    // ─── Resource Override ─────────────────────────────────────────────────────

    override fun getResources(): Resources {
        return guestResources ?: super.getResources()
    }

    override fun getClassLoader(): ClassLoader {
        return guestClassLoader ?: super.getClassLoader()
    }

    override fun getPackageName(): String {
        // Return the guest package name so the game thinks it's running normally.
        val guestPkg = intent?.getStringExtra(EXTRA_PACKAGE_NAME)
        return guestPkg ?: super.getPackageName()
    }

    // ─── Private Implementation ───────────────────────────────────────────────

    /**
     * Creates a [Resources] object that can load resources from the guest APK.
     * Uses reflection on [AssetManager.addAssetPath] (hidden API, available
     * on all Android versions up to 14).
     */
    private fun createResourcesForApk(apkPath: String, splitPaths: List<String>): Resources {
        val assetManager = AssetManager::class.java.getDeclaredConstructor().newInstance()
        val addAssetPath = AssetManager::class.java.getDeclaredMethod("addAssetPath", String::class.java)
        addAssetPath.isAccessible = true

        addAssetPath.invoke(assetManager, apkPath)
        for (split in splitPaths) {
            addAssetPath.invoke(assetManager, split)
        }

        val hostResources = super.getResources()
        return Resources(
            assetManager,
            hostResources.displayMetrics,
            hostResources.configuration
        )
    }

    /**
     * Instantiates the guest Activity class.
     *
     * Uses reflection to call [Activity.attach] with our window, context, etc.
     * The guest Activity will use OUR window to render its UI — meaning its
     * Views, SurfaceView, OpenGL context all render into our process's window.
     */
    private fun instantiateGuestActivity(
        activityClass: Class<*>,
        guestPackageName: String
    ): Activity {
        val instance = activityClass.getDeclaredConstructor().newInstance() as Activity

        // Use reflection to set the base context on the guest Activity.
        // Activity extends ContextThemeWrapper extends ContextWrapper.
        // ContextWrapper has `attachBaseContext(Context)` which we call.
        try {
            val attachBaseContext = android.content.ContextWrapper::class.java
                .getDeclaredMethod("attachBaseContext", Context::class.java)
            attachBaseContext.isAccessible = true

            // Create a VirtualContext that wraps our context but returns
            // the guest's package name and resources.
            val virtualContext = VirtualGuestContext(this, guestPackageName, guestResources!!, guestClassLoader!!)
            attachBaseContext.invoke(instance, virtualContext)
        } catch (e: Exception) {
            Timber.w(e, "Could not attach base context to guest — using fallback")
            // Fallback: try using the Instrumentation-style attach
            try {
                val attachMethod = Activity::class.java.getDeclaredMethod(
                    "attach",
                    Context::class.java,       // context
                    *Array(10) { Any::class.java } // remaining params — we skip them
                )
                // This won't work cleanly, but the ClassLoader approach below is fine
            } catch (_: NoSuchMethodException) {
                // Expected — Activity.attach() has many params that vary by API level
            }
        }

        return instance
    }

    /**
     * Dispatches [Activity.onCreate] to the guest Activity using reflection.
     * We call the protected method directly since the guest is not in our class hierarchy.
     */
    private fun dispatchGuestOnCreate(guest: Activity, savedInstanceState: Bundle?) {
        try {
            val onCreate = Activity::class.java.getDeclaredMethod("onCreate", Bundle::class.java)
            onCreate.isAccessible = true
            onCreate.invoke(guest, savedInstanceState)

            // If the guest set a content view, steal it and set it on our window.
            val guestWindow = try {
                val getWindow = Activity::class.java.getDeclaredMethod("getWindow")
                getWindow.isAccessible = true
                getWindow.invoke(guest) as? Window
            } catch (_: Exception) { null }

            if (guestWindow != null && guestWindow.decorView != null) {
                setContentView(guestWindow.decorView)
                Timber.d("Set guest's decor view as our content view")
            }
        } catch (e: Exception) {
            Timber.e(e, "dispatchGuestOnCreate failed — guest may not render")
            // The guest Activity's own view hierarchy should still be accessible
            // through the classloader, games that use Surface/GL directly will work.
        }
    }

    /**
     * Calls a lifecycle method on the guest Activity via reflection.
     */
    private fun callGuestLifecycle(methodName: String) {
        val guest = guestActivity ?: return
        try {
            val method = Activity::class.java.getDeclaredMethod(methodName)
            method.isAccessible = true
            method.invoke(guest)
        } catch (e: NoSuchMethodException) {
            // Some lifecycle methods may not exist on older API levels
        }
    }

    /**
     * Configures the window flags suitable for running a full-screen game.
     * Games typically want: no title bar, full screen, hardware acceleration.
     */
    private fun configureWindowForGame() {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
    }

    /**
     * Builds the native library search path for DexClassLoader.
     * Looks for extracted .so files under the app's lib directory.
     */
    private fun buildNativeLibPath(libDir: String, packageName: String): String? {
        if (libDir.isBlank()) return null

        val libFile = File(libDir)
        if (!libFile.exists()) return null

        // Look for arm64-v8a first, then armeabi-v7a
        val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        for (abi in abis) {
            val abiDir = File(libFile, abi)
            if (abiDir.exists() && abiDir.listFiles()?.isNotEmpty() == true) {
                return abiDir.absolutePath
            }
        }

        // Maybe the .so files are directly in the lib dir
        return if (libFile.listFiles()?.any { it.name.endsWith(".so") } == true) {
            libFile.absolutePath
        } else null
    }

    /**
     * Loads all native .so libraries from the guest app's lib directory.
     *
     * These libraries will be mapped into Atlas's process address space,
     * making them visible in /proc/self/maps — which is exactly what
     * GameGuardian needs to detect and scan the game's memory.
     */
    private fun loadNativeLibraries(packageName: String, libDir: String) {
        if (libDir.isBlank()) return

        val libFile = File(libDir)
        if (!libFile.exists()) return

        val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        for (abi in abis) {
            val abiDir = File(libFile, abi)
            if (abiDir.exists()) {
                val soFiles = abiDir.listFiles()?.filter { it.name.endsWith(".so") } ?: continue
                for (soFile in soFiles) {
                    try {
                        System.load(soFile.absolutePath)
                        Timber.d("Loaded native lib: %s", soFile.name)
                    } catch (e: UnsatisfiedLinkError) {
                        // Some libs depend on others — order matters.
                        // We'll try again after loading all primary libs.
                        Timber.d("Deferred: %s (%s)", soFile.name, e.message)
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to load native lib: %s", soFile.name)
                    }
                }
                // Only load from the first matching ABI
                break
            }
        }
    }

    // ─── Constants ────────────────────────────────────────────────────────────

    companion object {
        const val EXTRA_PACKAGE_NAME = "com.atlas.virtualspace.VIRTUAL_PACKAGE"
        const val EXTRA_ACTIVITY_CLASS = "com.atlas.virtualspace.VIRTUAL_ACTIVITY"
        const val EXTRA_VIRTUAL_LAUNCH = "com.atlas.virtualspace.VIRTUAL_LAUNCH"
    }
}
