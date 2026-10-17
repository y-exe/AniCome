package net.yexe.anicome

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test

class PreviewControlsUiTest : InteractionFixture() {
    @Test fun countButtonsChooseOneOfSixSizes() {
        val selected = mutableStateOf(5000)
        val view = fixture { Column { PreviewCommentCountPicker(selected.value) { selected.value = it } } }
        try {
            paparazzi.snapshot(view, "preview_counts")
            PreviewCommentCounts.forEach { assertTrue(texts(view).contains("${it}件")) }
            click(view, "30000件")
            paparazzi.snapshot(view, "preview_counts_selected")
            assertEquals(30000, selected.value)
            assertEquals(1, nodes(view).count { it.config.getOrNull(SemanticsProperties.Selected) == true })
        } finally { view.disposeComposition() }
    }
    @Test fun floatingSettingsFooterStaysVisibleWithoutScrolling() {
        var closed = false
        val view = fixture {
            Box(Modifier.height(420.dp)) {
                FloatingPlaybackSettingsContent(FloatingPanelState(position = "custom"), 14f, .75f, .7f,
                    {}, {}, {}, { _, _ -> }, { closed = true })
            }
        }
        try {
            paparazzi.snapshot(view, "floating_settings_footer")
            assertFalse(texts(view).contains("コメント表示"))
            val close = nodes(view).first { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { t -> t.text == "閉じる" } }
            assertTrue(close.boundsInRoot.top > 0)
            assertTrue(close.boundsInRoot.bottom <= 420 * paparazzi.context.resources.displayMetrics.density)
            click(view, "閉じる")
            assertTrue(closed)
        } finally { view.disposeComposition() }
    }
}
