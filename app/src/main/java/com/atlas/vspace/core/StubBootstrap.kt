package com.atlas.vspace.core

import android.app.Application
import android.app.Instrumentation
import android.os.Handler
import com.atlas.vspace.loader.GuestApkLoader
import com.atlas.vspace.loader.GuestBundle
import com.atlas.vspace.loader.GuestManifestParser
import com.atlas.vspace.model.GuestAppManifest
import com.atlas.vspace.stub.StubRegistry
import com.atlas.vspace.util.Reflect
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference

/**
 * One-time bootstrap that runs in each stub process's Application.onCreate.
 *
 * Responsibilities:
 * 1. Determine which slot this process represents (parse from process name)
 * 2. Find the guest package that was routed to this slot from Atlas host
 * 3. Load the guest APK via [GuestApkLoader]
 * 4. Install [HCallback] on ActivityThread.mH to rewrite LAUNCH_ACTIVITY
 * 5. Install [InstrumentationShim] on ActivityThread.mInstrumentation
 *
 * After this method returns, when Android dispatches LAUNCH_ACTIVITY for
 * our stub Activity, the hooks take over and the guest's real Activity
 * class is instantiated and run.
 */
object StubBootstrap {

    /**
     * Holds the guest bundle for this stub process. Set by [bootstrap]
     * once the guest is resolved. Read by [HCallback] and [InstrumentationShim].
     *
     * Exposed as an [AtomicReference] so hook callbacks on the main thread
     * see the latest value without synchronization.
     */
    private val bundleRef = AtomicReference<GuestBundle?>(null)

    /** Returns the current guest bundle, or null if not yet bootstrapped. */
    fun currentBundle(): GuestBundle? = bundleRef.get()

    /**
     * Bootstraps a stub process. Call from `AtlasStubApp.onCreate` in a
     * `:pN` process.
     *
     * @param app The stub process's Application.
     * @param guestPackage The guest package name this stub should host.
     *                     Learned from the incoming Intent (the host sets
     *                     `EXTRA_GUEST_PACKAGE` when routing).
     */
    fun bootstrap(app: Application, guestPackage: String) {
        Timber.i("[StubBootstrap] Booting stub process for %s", guestPackage)

        val manifest = resolveGuestManifest(app, guestPackage)
        if (manifest == null) {
            Timber.e("[StubBootstrap] Could not parse guest manifest for %s — stub will no-op", guestPackage)
            return
        }

        val loader = GuestApkLoader(app)
        val bundle = loader.load(manifest, StubBootstrap::class.java.classLoader!!)
        bundleRef.set(bundle)

        val hookedH = installHCallback()
        val hookedInstr = installInstrumentationShim()

        Timber.i(
            "[StubBootstrap] Ready — guest=%s hCallback=%s instrShim=%s",
            guestPackage, hookedH, hookedInstr,
        )
    }

    // ─── Guest manifest resolution ───────────────────────────────────────

    /**
     * Looks up the guest manifest from persistent metadata kept by the host.
     *
     * For v1 we read a simple JSON registry file written by the host when
     * it installs a guest. A more robust approach (ContentProvider) can be
     * added later.
     */
    private fun resolveGuestManifest(app: Application, guestPackage: String): GuestAppManifest? {
        val registryFile = java.io.File(
            app.filesDir.parentFile,  // /data/data/com.atlas.../
            "vspace_registry/$guestPackage.json"
        )
        if (!registryFile.exists()) {
            Timber.w("[StubBootstrap] No registry entry at %s", registryFile)
            return null
        }

        return try {
            val json = registryFile.readText()
            parseRegistryJson(json)
        } catch (t: Throwable) {
            Timber.e(t, "[StubBootstrap] Failed to read registry for %s", guestPackage)
            null
        }
    }

    /**
     * Minimal JSON parser for the registry file format. We use plain
     * java.util tools to avoid a Gson dependency in the hot bootstrap path.
     *
     * Expected shape:
     *   {"packageName":"...","mainActivity":"...","apkPath":"...",
     *    "splits":["..."],"nativeLibDir":"...","targetSdk":33,
     *    "versionName":"1.0","versionCode":1,"appName":"...","theme":0}
     */
    private fun parseRegistryJson(json: String): GuestAppManifest? {
        val obj = org.json.JSONObject(json)
        val splitsArr = obj.optJSONArray("splits")
        val splits = if (splitsArr != null) {
            List(splitsArr.length()) { i -> splitsArr.getString(i) }
        } else emptyList()

        return GuestAppManifest(
            packageName = obj.getString("packageName"),
            appName = obj.optString("appName", obj.getString("packageName")),
            versionName = obj.optString("versionName", "unknown"),
            versionCode = obj.optLong("versionCode", 0L),
            mainActivity = obj.getString("mainActivity"),
            apkPath = obj.getString("apkPath"),
            splitApkPaths = splits,
            nativeLibDir = obj.optString("nativeLibDir").takeIf { it.isNotBlank() },
            targetSdk = obj.optInt("targetSdk", 21),
            mainActivityTheme = obj.optInt("theme", 0),
        )
    }

    // ─── ActivityThread.mH hook ──────────────────────────────────────────

    private fun installHCallback(): Boolean {
        val atClass = Reflect.classOrNull("android.app.ActivityThread") ?: return false

        val currentAT = Reflect.methodOrNull(atClass, "currentActivityThread")
            ?.invoke(null) ?: return false

        val mH: Handler = Reflect.readField(currentAT, atClass, "mH") ?: return false

        val existing: Handler.Callback? = Reflect.readField(mH, Handler::class.java, "mCallback")
        val our = HCallback { bundleRef.get() }

        // Chain: our callback first; if it returns false we fall through to
        // any previously-installed callback, then to H's own handleMessage.
        val combined: Handler.Callback = Handler.Callback { msg ->
            val consumed = try {
                our.handleMessage(msg)
            } catch (t: Throwable) {
                Timber.e(t, "[StubBootstrap] HCallback threw")
                false
            }
            if (consumed) true else existing?.handleMessage(msg) ?: false
        }

        return Reflect.writeField(mH, Handler::class.java, "mCallback", combined)
    }

    // ─── ActivityThread.mInstrumentation hook ───────────────────────────

    private fun installInstrumentationShim(): Boolean {
        val atClass = Reflect.classOrNull("android.app.ActivityThread") ?: return false

        val currentAT = Reflect.methodOrNull(atClass, "currentActivityThread")
            ?.invoke(null) ?: return false

        val original: Instrumentation = Reflect.readField(currentAT, atClass, "mInstrumentation")
            ?: return false

        val shim = InstrumentationShim(original) { bundleRef.get() }
        return Reflect.writeField(currentAT, atClass, "mInstrumentation", shim)
    }
}
