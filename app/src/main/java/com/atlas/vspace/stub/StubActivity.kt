package com.atlas.vspace.stub

import android.app.Activity
import android.os.Bundle
import timber.log.Timber

/**
 * Base class for every stub Activity Atlas declares in its manifest.
 *
 * A stub Activity is a placeholder. It only exists so the Android framework
 * will fork our `:pN` process and hand us an Activity lifecycle. Before
 * `onCreate` actually runs, the H.mCallback hook installed in
 * [com.atlas.vspace.core.HCallback] has already rewritten the intent +
 * ActivityInfo to point at the real guest Activity. In practice this class's
 * `onCreate` is rarely invoked — the guest's onCreate runs instead because
 * Instrumentation.newActivity returns the guest class by then.
 *
 * If we ever DO see this stub activate (hook didn't fire, parse failed,
 * etc.) we finish immediately so the user isn't left on a blank screen.
 */
open class StubActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.w(
            "[StubActivity] onCreate fell through — hook did not redirect. " +
                "intent=%s",
            intent
        )
        finish()
    }
}

// ────────────────────────────────────────────────────────────────────────
//  Concrete stub classes — one per (slot, launchMode) combination.
//
//  We keep 10 slots × 4 modes = 40 classes. Each one only exists to be
//  named as a distinct component in the manifest; they share the same
//  behaviour from [StubActivity].
// ────────────────────────────────────────────────────────────────────────

class StubActivity_P0_Std : StubActivity()
class StubActivity_P0_Top : StubActivity()
class StubActivity_P0_Task : StubActivity()
class StubActivity_P0_Instance : StubActivity()
class StubActivity_P1_Std : StubActivity()
class StubActivity_P1_Top : StubActivity()
class StubActivity_P1_Task : StubActivity()
class StubActivity_P1_Instance : StubActivity()
class StubActivity_P2_Std : StubActivity()
class StubActivity_P2_Top : StubActivity()
class StubActivity_P2_Task : StubActivity()
class StubActivity_P2_Instance : StubActivity()
class StubActivity_P3_Std : StubActivity()
class StubActivity_P3_Top : StubActivity()
class StubActivity_P3_Task : StubActivity()
class StubActivity_P3_Instance : StubActivity()
class StubActivity_P4_Std : StubActivity()
class StubActivity_P4_Top : StubActivity()
class StubActivity_P4_Task : StubActivity()
class StubActivity_P4_Instance : StubActivity()
class StubActivity_P5_Std : StubActivity()
class StubActivity_P5_Top : StubActivity()
class StubActivity_P5_Task : StubActivity()
class StubActivity_P5_Instance : StubActivity()
class StubActivity_P6_Std : StubActivity()
class StubActivity_P6_Top : StubActivity()
class StubActivity_P6_Task : StubActivity()
class StubActivity_P6_Instance : StubActivity()
class StubActivity_P7_Std : StubActivity()
class StubActivity_P7_Top : StubActivity()
class StubActivity_P7_Task : StubActivity()
class StubActivity_P7_Instance : StubActivity()
class StubActivity_P8_Std : StubActivity()
class StubActivity_P8_Top : StubActivity()
class StubActivity_P8_Task : StubActivity()
class StubActivity_P8_Instance : StubActivity()
class StubActivity_P9_Std : StubActivity()
class StubActivity_P9_Top : StubActivity()
class StubActivity_P9_Task : StubActivity()
class StubActivity_P9_Instance : StubActivity()
