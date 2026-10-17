package net.yexe.anicome

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.*
import androidx.compose.material.icons.filled.*
import org.junit.Assert.*
import org.junit.Test

class SettingsStyleInteractionTest : InteractionFixture() {
    @Test fun orderControlsMoveTheVisibleRowsAndHidingKeepsTheRemainingOrder() {
        val order = mutableStateOf(listOf("adjust", "watch", "fetch"))
        val view = fixture { HomeSectionOrder(order.value) { order.value = it } }
        fun top(label: String) = nodes(view).first { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label } }.boundsInRoot.top
        try {
            paparazzi.snapshot(view, "home_order_before")
            assertTrue(top("再生の調整") < top("視聴"))
            click(view, "視聴：上へ移動")
            paparazzi.snapshot(view, "home_order_after")
            assertEquals(listOf("watch", "adjust", "fetch"), order.value)
            assertTrue(top("視聴") < top("再生の調整"))
            click(view, "視聴")
            paparazzi.snapshot(view, "home_order_hidden")
            assertEquals(listOf("adjust", "fetch"), order.value)
            assertTrue(top("視聴") > top("コメント取得"))
        } finally { view.disposeComposition() }
    }
    @Test fun aChoiceRowActsOnceAndExposesItsSelectedState() {
        var calls = 0
        val selected = mutableStateOf(false)
        val view = fixture { PreferenceChoice("標準", selected.value, { selected.value = true; calls++ }) }
        try {
            paparazzi.snapshot(view, "choice_before")
            click(view, "標準")
            paparazzi.snapshot(view, "choice_selected")
            assertEquals(1, calls)
            assertTrue(nodes(view).any { it.config.getOrNull(SemanticsProperties.Selected) == true && it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton })
        } finally { view.disposeComposition() }
    }
    @Test fun menuActionsAreButtonsAndRunOnce() {
        var calls = 0
        val view = fixture { ReferenceGroup { MenuAction("削除", androidx.compose.material.icons.Icons.Filled.Delete, destructive = true) { calls++ } } }
        try {
            paparazzi.snapshot(view, "menu_action")
            click(view, "削除")
            assertEquals(1, calls)
            assertTrue(nodes(view).any { it.config.getOrNull(SemanticsProperties.Role) == Role.Button })
        } finally { view.disposeComposition() }
    }
    @Test fun longFormsKeepTheirActionVisibleBelowScrollableInputs() {
        val view = fixture {
            StudioFormLayout("入力項目", actions = { androidx.compose.material3.Button({}) { AppText("保存") } }) {
                repeat(20) { androidx.compose.material3.OutlinedTextField("", {}, label = { AppText("作品名") }) }
            }
        }
        try {
            paparazzi.snapshot(view, "form_footer_visible")
            val save = nodes(view).first { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "保存" } }
            assertTrue(save.boundsInRoot.top > 0)
            assertTrue(save.boundsInRoot.bottom <= view.height)
        } finally { view.disposeComposition() }
    }
    @Test fun movingBeyondTheVisibleOrderHasNoEffect() {
        val order = listOf("adjust", "watch", "fetch")
        assertEquals(order, moveHomeSection(order, "adjust", -1))
        assertEquals(order, moveHomeSection(order, "fetch", 1))
        assertEquals(order, moveHomeSection(order, "resume", -1))
        assertEquals(listOf("watch", "adjust", "fetch"), moveHomeSection(order, "watch", -1))
    }
}
