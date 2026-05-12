package com.atlas.vspace.stub

/**
 * Static mapping between process slot + launchMode and the stub Activity
 * class declared in Atlas's manifest that occupies that slot with that mode.
 *
 * Atlas declares a fixed pool of stub Activities per process slot:
 *
 *   Slot p0:
 *     - StubActivity_P0_Std     (launchMode="standard")
 *     - StubActivity_P0_Single  (launchMode="singleTop")
 *     - StubActivity_P0_Task    (launchMode="singleTask")
 *     - StubActivity_P0_Instance(launchMode="singleInstance")
 *   ... same for p1..p9
 *
 * When a guest Activity requests `startActivity`, the proxy picks the stub
 * whose slot matches the guest's process and whose launchMode matches the
 * guest Activity's declared launchMode.
 *
 * Adding a slot is mechanical: add four more stub classes, four more manifest
 * entries, and extend [SLOT_COUNT].
 */
object StubRegistry {

    /** Number of concurrent guest processes Atlas supports. */
    const val SLOT_COUNT = 10

    /** Android launchMode constants, kept in a local enum for clarity. */
    enum class LaunchMode(val framework: Int) {
        STANDARD(0),
        SINGLE_TOP(1),
        SINGLE_TASK(2),
        SINGLE_INSTANCE(3);

        companion object {
            fun fromFramework(v: Int): LaunchMode = when (v) {
                1 -> SINGLE_TOP
                2 -> SINGLE_TASK
                3 -> SINGLE_INSTANCE
                else -> STANDARD
            }
        }
    }

    /**
     * Returns the fully-qualified stub Activity class for the given slot + mode.
     *
     * The class names follow the pattern `com.atlas.vspace.stub.StubActivity_P{slot}_{ModeSuffix}`.
     */
    fun stubClassName(slot: Int, mode: LaunchMode): String {
        require(slot in 0 until SLOT_COUNT) { "Slot $slot out of range" }
        val suffix = when (mode) {
            LaunchMode.STANDARD -> "Std"
            LaunchMode.SINGLE_TOP -> "Top"
            LaunchMode.SINGLE_TASK -> "Task"
            LaunchMode.SINGLE_INSTANCE -> "Instance"
        }
        return "com.atlas.vspace.stub.StubActivity_P${slot}_$suffix"
    }

    /**
     * Reverse lookup: given a stub class name, returns (slot, mode) or null
     * if the class name doesn't match our pattern.
     */
    fun parseStubClassName(className: String): Pair<Int, LaunchMode>? {
        if (!className.startsWith("com.atlas.vspace.stub.StubActivity_P")) return null
        val tail = className.removePrefix("com.atlas.vspace.stub.StubActivity_P")
        val underscore = tail.indexOf('_')
        if (underscore <= 0) return null
        val slot = tail.substring(0, underscore).toIntOrNull() ?: return null
        val modeSuffix = tail.substring(underscore + 1)
        val mode = when (modeSuffix) {
            "Std" -> LaunchMode.STANDARD
            "Top" -> LaunchMode.SINGLE_TOP
            "Task" -> LaunchMode.SINGLE_TASK
            "Instance" -> LaunchMode.SINGLE_INSTANCE
            else -> return null
        }
        return slot to mode
    }

    /** Extra key: original guest component we need to restore. */
    const val EXTRA_GUEST_COMPONENT = "com.atlas.vspace.extra.GUEST_COMPONENT"
    /** Extra key: original guest Intent action (preserved when proxied). */
    const val EXTRA_GUEST_ACTION = "com.atlas.vspace.extra.GUEST_ACTION"
    /** Extra key: guest package (redundant with component but used early in boot). */
    const val EXTRA_GUEST_PACKAGE = "com.atlas.vspace.extra.GUEST_PACKAGE"
}
