package com.atlas.vspace.model

/**
 * One slot in the pool of stub processes Atlas declares in its manifest.
 *
 * Each slot corresponds to a single `android:process=":pN"` label.
 * At any time a slot is either free or bound to exactly one guest package.
 *
 * The slot also tracks which guest Activity is currently "inside" its
 * stub Activity class so that [HCallback] can rewrite the component
 * correctly on the next LAUNCH_ACTIVITY message.
 */
data class VirtualProcessSlot(
    val index: Int,
    /** Null when the slot is free. */
    var guestPackage: String? = null,
    /** Last time this slot was bound to a guest (epoch millis). Used for LRU eviction. */
    var lastUsedMillis: Long = 0L,
) {
    val processName: String get() = ":p$index"
    val isFree: Boolean get() = guestPackage == null

    fun bind(pkg: String) {
        guestPackage = pkg
        lastUsedMillis = System.currentTimeMillis()
    }

    fun release() {
        guestPackage = null
    }
}
