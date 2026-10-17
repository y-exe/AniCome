package net.yexe.anicome

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import org.junit.Assert.*
import org.junit.Test

class PanelPlacementInteractionTest : InteractionFixture() {
    @Test fun subtitleGuidancePlacesOnlyTheChosenOrientationAndOffersReset() {
        var options by mutableStateOf(FloatingPanelOptions())
        val applied = mutableListOf<Pair<PanelCorner, Boolean>>()
        var reset = false; var finished = false
        val view = fixture { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            PanelPlacementEditor(options, { corner, landscape ->
                applied += corner to landscape
                options = if (landscape) options.copy(landscapeX = corner.position.x, landscapeY = corner.position.y) else options.copy(x = corner.position.x, y = corner.position.y)
            }, { reset = true; options = FloatingPanelOptions() })
        } }
        view.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewDetachedFromWindow(v: android.view.View) {}
            override fun onViewAttachedToWindow(v: android.view.View) {
                v.postDelayed({ assertFalse(texts(view).contains("右下")); click(view, "左上") }, 300)
                v.postDelayed({ click(view, "横画面") }, 700)
                v.postDelayed({ click(view, "上") }, 1100)
                v.postDelayed({ assertFalse(texts(view).contains("左上")); click(view, "右下") }, 1500)
                v.postDelayed({
                    assertEquals(listOf(PanelCorner.TOP_LEFT to false, PanelCorner.BOTTOM_RIGHT to true), applied)
                    assertEquals(0f, options.x); assertEquals(0f, options.y); assertEquals(1f, options.landscapeY)
                    click(view, "パネルの位置を戻す"); assertTrue(reset); finished = true
                }, 1900)
            }
        })
        try { paparazzi.gif(view, "panel_subtitle_placement", end = 2200, fps = 10); assertTrue(finished) }
        finally { view.disposeComposition() }
    }
}
