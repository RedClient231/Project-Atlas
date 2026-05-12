package com.atlas.vspace.core

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import com.atlas.vspace.loader.GuestBundle
import timber.log.Timber

/**
 * Replacement `Instrumentation` installed on `ActivityThread.mInstrumentation`
 * in stub processes.
 *
 * When Android's ActivityThread has finished rewriting the LAUNCH_ACTIVITY
 * message (courtesy of [HCallback]) it calls `Instrumentation.newActivity`
 * to actually instantiate the Activity class. At that point `className` is
 * the real guest class (e.g. `com.some.game.MainActivity`). The default
 * Instrumentation would try to load it via Atlas's own ClassLoader and
 * fail with `ClassNotFoundException`.
 *
 * Our override loads guest classes via [GuestBundle.classLoader], which
 * has the guest APK's `.dex` files on its path.
 *
 * We delegate everything else to the original Instrumentation so existing
 * framework behaviour (activity lifecycle, test hooks, etc.) is preserved.
 */
class InstrumentationShim(
    private val delegate: Instrumentation,
    private val guestBundleProvider: () -> GuestBundle?,
) : Instrumentation() {

    override fun newActivity(
        cl: ClassLoader?,
        className: String,
        intent: Intent?,
    ): Activity {
        val bundle = guestBundleProvider()
        if (bundle == null) {
            return super.newActivity(cl, className, intent)
        }

        // If the target class is from the guest package, use the guest's ClassLoader
        if (className.startsWith(bundle.manifest.packageName)) {
            try {
                val guestClass = bundle.classLoader.loadClass(className)
                Timber.i("[InstrumentationShim] Instantiating guest %s via DexClassLoader", className)
                return guestClass.getDeclaredConstructor().newInstance() as Activity
            } catch (t: Throwable) {
                Timber.e(t, "[InstrumentationShim] Failed to load guest class %s", className)
                // fall through to default loader
            }
        }

        return super.newActivity(cl, className, intent)
    }

    // ----- Delegation boilerplate for callbacks the framework invokes -----

    override fun callActivityOnCreate(activity: Activity, icicle: android.os.Bundle?) {
        delegate.callActivityOnCreate(activity, icicle)
    }

    override fun callActivityOnStart(activity: Activity) = delegate.callActivityOnStart(activity)
    override fun callActivityOnRestart(activity: Activity) = delegate.callActivityOnRestart(activity)
    override fun callActivityOnResume(activity: Activity) = delegate.callActivityOnResume(activity)
    override fun callActivityOnPause(activity: Activity) = delegate.callActivityOnPause(activity)
    override fun callActivityOnStop(activity: Activity) = delegate.callActivityOnStop(activity)
    override fun callActivityOnDestroy(activity: Activity) = delegate.callActivityOnDestroy(activity)
}
