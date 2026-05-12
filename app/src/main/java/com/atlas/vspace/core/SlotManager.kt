package com.atlas.vspace.core

import com.atlas.vspace.model.VirtualProcessSlot
import com.atlas.vspace.stub.StubRegistry
import timber.log.Timber

/**
 * Allocates and releases [VirtualProcessSlot]s.
 *
 * This lives exclusively in the Atlas host process. Stub processes never
 * touch it — they know their own slot from their process name.
 *
 * Thread-safety: every mutating method holds the intrinsic lock on `this`.
 * Non-mutating reads are fine unlocked because they only look at the
 * visible-through-lock state for logging.
 */
class SlotManager {

    private val slots: List<VirtualProcessSlot> =
        List(StubRegistry.SLOT_COUNT) { VirtualProcessSlot(it) }

    /**
     * Returns the slot already bound to [pkg] if any, otherwise allocates a
     * free slot, otherwise evicts the least-recently-used slot.
     *
     * Never returns null as long as SLOT_COUNT > 0.
     */
    @Synchronized
    fun acquire(pkg: String): VirtualProcessSlot {
        slots.firstOrNull { it.guestPackage == pkg }?.let {
            it.lastUsedMillis = System.currentTimeMillis()
            return it
        }

        slots.firstOrNull { it.isFree }?.let { free ->
            free.bind(pkg)
            Timber.i("[SlotManager] Acquired fresh slot %s for %s", free.processName, pkg)
            return free
        }

        // All busy — evict LRU.
        val lru = slots.minByOrNull { it.lastUsedMillis }!!
        Timber.w(
            "[SlotManager] All slots busy — evicting %s (was %s) for %s",
            lru.processName, lru.guestPackage, pkg
        )
        lru.release()
        lru.bind(pkg)
        return lru
    }

    @Synchronized
    fun release(pkg: String) {
        slots.firstOrNull { it.guestPackage == pkg }?.release()
    }

    @Synchronized
    fun slotOf(pkg: String): VirtualProcessSlot? =
        slots.firstOrNull { it.guestPackage == pkg }

    @Synchronized
    fun snapshot(): List<VirtualProcessSlot> = slots.map { it.copy() }
}
