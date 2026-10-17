package net.yexe.anicome

import androidx.compose.runtime.*
import androidx.compose.ui.semantics.*
import org.junit.Test
import org.junit.Assert.*

class CustomPositionInteractionTest : InteractionFixture() {
    @Test fun customKeepsTheExistingChoiceStyleAndOffersFinePositionAdjustment() {
        var position by mutableStateOf("center"); var y by mutableFloatStateOf(.5f); var done = false
        val view = fixture { CommentPositionContent(position, { position = it }, y, { y = it }) }
        view.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewDetachedFromWindow(v: android.view.View) {}
            override fun onViewAttachedToWindow(v: android.view.View) {
                v.postDelayed({ assertFalse(texts(view).contains("下")); click(view, "カスタム") }, 200)
                v.postDelayed({
                    assertEquals("custom", position)
                    val slider = nodes(view).first { it.config.getOrNull(SemanticsActions.SetProgress) != null }
                    slider.config[SemanticsActions.SetProgress].action!!.invoke(.231f)
                }, 500)
                v.postDelayed({ assertEquals(.231f, y, .0001f); click(view, "上") }, 800)
                v.postDelayed({ assertEquals("top", position); assertEquals(.231f, y, .0001f); done = true }, 1100)
            }
        })
        try { paparazzi.gif(view, "custom_comment_position", end = 1400, fps = 10); assertTrue(done) }
        finally { view.disposeComposition() }
    }
}
