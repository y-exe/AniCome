package net.yexe.anicome

import org.junit.Test
import org.junit.Assert.*

class SyncUndoStaleButtonTest : InteractionFixture() {
    @Test fun timingControlsDoNotOfferUndo() {
        val view = fixture { TimingControls() }
        try { paparazzi.snapshot(view, "timing_no_undo"); assertFalse(texts(view).any { it.contains("取り消") }) }
        finally { view.disposeComposition() }
    }
}
