package org.setbd.cloner.engine.stub

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import org.setbd.cloner.util.ClonerLog

/**
 * Declared proxy activity the engine launches on behalf of guest activities.
 *
 * From the system's point of view ONLY this class runs. The instrumentation
 * hook instantiates the real guest activity class instead, so this class
 * body normally never executes. It exists to keep the system happy (a valid,
 * installable component) and as a hard fallback when the engine cannot map
 * the launch onto a guest activity.
 */
open class StubActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ClonerLog.w(
            TAG,
            "stub activity reached directly — engine mapping failed; finishing safely"
        )
        // Never leave the user staring at a silent black flash: say WHY the
        // guest did not appear and close cleanly.
        Toast.makeText(
            this,
            "SETBD Cloner: this clone could not be mapped — reopen it from the launcher",
            Toast.LENGTH_LONG
        ).show()
        finish()
    }

    private companion object {
        const val TAG = "StubActivity"
    }
}

/** Stub used for guest activities whose theme is translucent/dialog-style. */
open class StubTransparentActivity : StubActivity()

/** Stub for guest activities declared with launchMode="singleTask". */
open class StubSingleTaskActivity : StubActivity()

/** Stub for guest activities declared with launchMode="singleInstance". */
open class StubSingleInstanceActivity : StubActivity()

/** Keys shared between the launcher-side mapping and the instrumentation hook. */
object ExtraKeys {
    /** Wrapped guest intent inside every stub launch intent. */
    const val GUEST_INTENT = "org.setbd.cloner.engine.GUEST_INTENT"

    /** Guest component class name resolved at launch time. */
    const val GUEST_CLASS = "org.setbd.cloner.engine.GUEST_CLASS"

    /** Clone id owning the guest process state. */
    const val CLONE_ID = "org.setbd.cloner.engine.CLONE_ID"
}
