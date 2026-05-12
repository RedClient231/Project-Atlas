package com.atlas.vspace.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.atlas.vspace.model.GuestAppManifest
import com.atlas.vspace.model.VirtualProcessSlot
import com.atlas.vspace.stub.StubRegistry
import timber.log.Timber

/**
 * Builds the Intent that Atlas hands to Android's `startActivity` to kick
 * off a guest Activity.
 *
 * The output intent targets a stub Activity in the right process slot with
 * a launchMode matching the guest. The original guest component, action,
 * and any extras the caller provided are preserved under well-known extra
 * keys so [HCallback] can reconstruct them on the stub process side.
 */
object ActivityRouter {

    /**
     * Builds the stub-redirecting intent for the guest's main launcher activity.
     *
     * @param hostContext The Atlas host Context (for resolving its own package).
     * @param slot The already-acquired slot for this guest.
     * @param manifest The guest's parsed manifest.
     */
    fun buildLaunchIntent(
        hostContext: Context,
        slot: VirtualProcessSlot,
        manifest: GuestAppManifest,
    ): Intent {
        val launchMode = StubRegistry.LaunchMode.STANDARD
        val stubClass = StubRegistry.stubClassName(slot.index, launchMode)

        val originalComponent = ComponentName(manifest.packageName, manifest.mainActivity)

        val stubComponent = ComponentName(hostContext.packageName, stubClass)

        val intent = Intent(Intent.ACTION_MAIN).apply {
            component = stubComponent
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

            // Stash the real guest component so the H callback can restore it
            putExtra(StubRegistry.EXTRA_GUEST_COMPONENT, originalComponent)
            putExtra(StubRegistry.EXTRA_GUEST_PACKAGE, manifest.packageName)
            putExtra(StubRegistry.EXTRA_GUEST_ACTION, Intent.ACTION_MAIN)
        }

        Timber.i(
            "[ActivityRouter] Routed %s/%s → %s (%s)",
            manifest.packageName, manifest.mainActivity, stubClass, slot.processName
        )

        return intent
    }
}
