package net.yexe.anicome

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test

class CommentAcquisitionUiTest : InteractionFixture() {
    @Test fun multiplierCanBeEditedAboveSliderRange() {
        val amount = mutableStateOf(5)
        val view = fixture { Column(Modifier.padding(16.dp)) {
            CommentAcquisitionSettings(amount.value, false, 5000, true, { amount.value = it }, {}, {}, {})
        } }
        var finished = false
        view.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewDetachedFromWindow(v: android.view.View) {}
            override fun onViewAttachedToWindow(v: android.view.View) {
                v.postDelayed({
                    val editor = nodes(view).first { it.config.getOrNull(SemanticsActions.SetText) != null }
                    assertTrue(editor.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("35")))
                }, 200)
                v.postDelayed({ click(view, "適用") }, 600)
                v.postDelayed({ assertEquals(35, amount.value); finished = true }, 900)
            }
        })
        try { paparazzi.gif(view, "acquisition_multiplier_edit", end = 1200, fps = 10); assertTrue(finished) }
        finally { view.disposeComposition() }
    }

    @Test fun countModeExplainsCombinedLimitAndLargeCountWarning() {
        val view = fixture { Column(Modifier.padding(16.dp)) {
            CommentAcquisitionSettings(5, true, 30000, true, {}, {}, {}, {})
        } }
        try {
            paparazzi.snapshot(view, "acquisition_combined_count")
            assertTrue(texts(view).any { it.contains("選択した取得元の合計") })
            assertTrue(texts(view).any { it.contains("動作が重くなる") })
            assertFalse(texts(view).contains("20倍"))
        } finally { view.disposeComposition() }
    }
}
