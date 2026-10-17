package net.yexe.anicome

import androidx.compose.runtime.*
import androidx.compose.ui.semantics.*
import org.junit.Test
import org.junit.Assert.*

class FloatingSettingsInteractionTest : InteractionFixture() {
    @Test fun popupEditsOnlyVisibilityPositionTimingSizeOpacityAndSpeed() {
        var state by mutableStateOf(FloatingPanelState(overlay = true))
        val values = mutableMapOf("size" to 17f, "opacity" to 1f, "speed" to .5f)
        var closed = false; var done = false
        val view = fixture {
            FloatingPlaybackSettingsContent(state, values.getValue("size"), values.getValue("opacity"), values.getValue("speed"),
                { state = state.copy(overlay = !state.overlay) }, { state = state.copy(position = it) },
                { state = state.copy(offset = if (it == null) 0 else state.offset + it) }, { key, value -> values[key] = value }, { closed = true })
        }
        view.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewDetachedFromWindow(v: android.view.View) {}
            override fun onViewAttachedToWindow(v: android.view.View) {
                v.postDelayed({ click(view, "コメントを表示") }, 200)
                v.postDelayed({ assertFalse(state.overlay); click(view, "上") }, 500)
                v.postDelayed({ assertEquals("top", state.position); click(view, "1秒早める") }, 800)
                v.postDelayed({
                    assertEquals(1000L, state.offset)
                    val sliders = nodes(view).filter { it.config.getOrNull(SemanticsActions.SetProgress) != null }
                    assertEquals(3, sliders.size)
                    sliders[0].config[SemanticsActions.SetProgress].action!!.invoke(20f)
                    sliders[1].config[SemanticsActions.SetProgress].action!!.invoke(.5f)
                    sliders[2].config[SemanticsActions.SetProgress].action!!.invoke(1f)
                    assertEquals(20f, values["size"]); assertEquals(.5f, values["opacity"]); assertEquals(1f, values["speed"])
                    assertFalse(texts(view).any { it.contains("取り消") || it == "NGワード" || it == "取得元" })
                    click(view, "閉じる"); assertTrue(closed); done = true
                }, 1200)
            }
        })
        try { paparazzi.gif(view, "floating_settings_actions", end = 1500, fps = 10); assertTrue(done) }
        finally { view.disposeComposition() }
    }
}
