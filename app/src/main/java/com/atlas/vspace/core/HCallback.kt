package com.atlas.vspace.core

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Message
import com.atlas.vspace.loader.GuestBundle
import com.atlas.vspace.stub.StubRegistry
import com.atlas.vspace.util.Reflect
import timber.log.Timber

/**
 * Hook installed on `ActivityThread.mH` in stub processes.
 *
 * `ActivityThread.H` is the main-thread Handler that dispatches lifecycle
 * messages from `system_server` (LAUNCH_ACTIVITY, PAUSE_ACTIVITY, etc.).
 * By adding ourselves as `mH.mCallback`, we get first look at every message
 * before H's own dispatch runs.
 *
 * We only care about LAUNCH_ACTIVITY (message code varies by API):
 * - Android 9+: H.EXECUTE_TRANSACTION (159) — unwrap ClientTransaction
 * - Android 8:  H.LAUNCH_ACTIVITY (100) — msg.obj is ActivityClientRecord
 *
 * For each LAUNCH_ACTIVITY targeting one of our stub classes, we swap the
 * intent's component and the ActivityClientRecord's activityInfo back to
 * the REAL guest values. Android then launches the guest Activity normally.
 */
class HCallback(
    private val guestBundleProvider: () -> GuestBundle?,
) : Handler.Callback {

    override fun handleMessage(msg: Message): Boolean {
        try {
            when (msg.what) {
                EXECUTE_TRANSACTION -> handleExecuteTransaction(msg)
                LEGACY_LAUNCH_ACTIVITY -> handleLegacyLaunch(msg)
            }
        } catch (t: Throwable) {
            Timber.e(t, "[HCallback] Failed to rewrite message what=%d", msg.what)
        }
        // Return false so the real H.handleMessage still runs — we only modify
        // the message, we don't consume it.
        return false
    }

    /**
     * Android 9+: messages arrive wrapped in `ClientTransaction` → a list of
     * `ClientTransactionItem`s. For launch we want the `LaunchActivityItem`.
     */
    private fun handleExecuteTransaction(msg: Message) {
        val txn = msg.obj ?: return
        val txnClass = txn.javaClass
        val callbacks: List<*> = Reflect.readField<List<*>>(txn, txnClass, "mActivityCallbacks")
            ?: return

        for (item in callbacks) {
            if (item == null) continue
            val itemClass = item.javaClass
            if (itemClass.simpleName != "LaunchActivityItem") continue
            rewriteLaunchItem(item, itemClass)
        }
    }

    /**
     * Android 8 (API 26/27): msg.obj is `ActivityClientRecord` directly.
     */
    private fun handleLegacyLaunch(msg: Message) {
        val record = msg.obj ?: return
        val recordClass = record.javaClass
        rewriteClientRecord(record, recordClass)
    }

    /** Unified rewriter — works on both LaunchActivityItem and ActivityClientRecord. */
    private fun rewriteLaunchItem(item: Any, itemClass: Class<*>) {
        val intent: Intent = Reflect.readField(item, itemClass, "mIntent") ?: return
        val rewritten = rewriteIntentAndInfo(intent, item, itemClass, "mInfo") ?: return

        Reflect.writeField(item, itemClass, "mIntent", rewritten.rewrittenIntent)
        Reflect.writeField(item, itemClass, "mInfo", rewritten.rewrittenActivityInfo)
    }

    private fun rewriteClientRecord(record: Any, recordClass: Class<*>) {
        val intent: Intent = Reflect.readField(record, recordClass, "intent") ?: return
        val rewritten = rewriteIntentAndInfo(intent, record, recordClass, "activityInfo") ?: return

        Reflect.writeField(record, recordClass, "intent", rewritten.rewrittenIntent)
        Reflect.writeField(record, recordClass, "activityInfo", rewritten.rewrittenActivityInfo)
    }

    private data class RewriteResult(
        val rewrittenIntent: Intent,
        val rewrittenActivityInfo: ActivityInfo,
    )

    private fun rewriteIntentAndInfo(
        intent: Intent,
        infoHolder: Any,
        infoHolderClass: Class<*>,
        infoFieldName: String,
    ): RewriteResult? {
        val currentComponent = intent.component ?: return null

        // Only rewrite if the component is one of our stubs
        val parsed = StubRegistry.parseStubClassName(currentComponent.className) ?: return null
        val (slot, _mode) = parsed

        val guestComponent: ComponentName =
            intent.getParcelableExtra(StubRegistry.EXTRA_GUEST_COMPONENT) ?: return null.also {
                Timber.w("[HCallback] Stub %s started but no guest component extra", currentComponent)
            }

        val bundle = guestBundleProvider()
            ?: return null.also {
                Timber.w("[HCallback] Stub %s started but no guest bundle available", currentComponent)
            }

        // --- Rewrite intent component ---
        val rewrittenIntent = Intent(intent).apply {
            component = guestComponent
            // Fold back the original action if it was stashed
            getStringExtra(StubRegistry.EXTRA_GUEST_ACTION)?.let { action = it }
            // Strip our scaffolding extras so the guest doesn't see them
            removeExtra(StubRegistry.EXTRA_GUEST_COMPONENT)
            removeExtra(StubRegistry.EXTRA_GUEST_PACKAGE)
            removeExtra(StubRegistry.EXTRA_GUEST_ACTION)
        }

        // --- Rewrite ActivityInfo to point at the guest ---
        val stubInfo: ActivityInfo = Reflect.readField(infoHolder, infoHolderClass, infoFieldName)
            ?: return null

        val rewrittenInfo = ActivityInfo(stubInfo).apply {
            name = guestComponent.className
            packageName = guestComponent.packageName
            applicationInfo = syntheticAppInfo(bundle, stubInfo.applicationInfo)
            if (bundle.manifest.mainActivityTheme != 0) {
                theme = bundle.manifest.mainActivityTheme
            }
        }

        Timber.i(
            "[HCallback] Rewrote slot=%d: stub=%s → guest=%s/%s",
            slot, currentComponent.className, guestComponent.packageName, guestComponent.className
        )

        return RewriteResult(rewrittenIntent, rewrittenInfo)
    }

    /**
     * Builds a synthetic [ApplicationInfo] for the guest. We copy Atlas's
     * own ApplicationInfo as a base (so process / classloader resolution
     * still works) and then override the fields the guest is sensitive to.
     */
    private fun syntheticAppInfo(
        bundle: GuestBundle,
        stubAppInfo: ApplicationInfo,
    ): ApplicationInfo {
        return ApplicationInfo(stubAppInfo).apply {
            packageName = bundle.manifest.packageName
            sourceDir = bundle.manifest.apkPath
            publicSourceDir = bundle.manifest.apkPath
            splitSourceDirs = bundle.manifest.splitApkPaths.toTypedArray()
            splitPublicSourceDirs = bundle.manifest.splitApkPaths.toTypedArray()
            nativeLibraryDir = bundle.manifest.nativeLibDir ?: nativeLibraryDir
            targetSdkVersion = bundle.manifest.targetSdk
            processName = stubAppInfo.processName // keep Atlas's stub process
            className = null  // no custom Application class
            name = null
            enabled = true
        }
    }

    companion object {
        /** ActivityThread.H.EXECUTE_TRANSACTION — Android 9+. */
        private const val EXECUTE_TRANSACTION = 159
        /** ActivityThread.H.LAUNCH_ACTIVITY — Android 8. */
        private const val LEGACY_LAUNCH_ACTIVITY = 100
    }
}
