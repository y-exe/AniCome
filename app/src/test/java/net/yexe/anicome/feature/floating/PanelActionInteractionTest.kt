package net.yexe.anicome

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import org.junit.Assert.*
import org.junit.Test

class PanelActionInteractionTest : InteractionFixture() {
    @Test fun choosingAndReorderingActionsChangesTheActualCompactPanelAndKeepsCloseAvailable() {
        var actions by mutableStateOf(DefaultPanelActions)
        var editing by mutableStateOf(true)
        var section = ""; var confirming = false; var finished = false
        val view = fixture {
            if (editing) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { PanelActionEditor(actions) { action, operation -> actions = changePanelActions(actions, action, operation) } }
            else FloatingPanelContent(FloatingPanelState(overlay = true), FloatingPanelOptions(compact = true, actions = actions), false, {}, {}, {}, {},
                requestClose = { confirming = true }, tools = { section = it }, toggleComments = {})
        }
        view.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewDetachedFromWindow(v: android.view.View) {}
            override fun onViewAttachedToWindow(v: android.view.View) {
                v.postDelayed({
                    val sync = nodes(view).first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("同期の詳細") == true }
                    assertNotNull(sync.config.getOrNull(SemanticsProperties.Disabled)); click(view, "NGワード")
                }, 300)
                v.postDelayed({ click(view, "同期の詳細") }, 700)
                v.postDelayed({ click(view, "同期の詳細：上へ移動") }, 1100)
                v.postDelayed({ assertEquals(listOf(PanelAction.TOGGLE, PanelAction.TOOLS, PanelAction.SYNC, PanelAction.OPEN), actions); editing = false }, 1500)
                v.postDelayed({
                    val titles = actions.map { it.title }
                    val rendered = nodes(view).filter { it.config.getOrNull(SemanticsActions.OnClick) != null }
                        .mapNotNull { node -> node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull { it in titles }?.let { node.boundsInRoot.left to it } }
                        .distinct().sortedBy { it.first }.map { it.second }
                    assertTrue(rendered.isEmpty())
                    assertFalse(texts(view).contains("NGワード")); click(view, "コメント表示の設定"); assertEquals("settings", section)
                    click(view, "パネルを閉じる"); assertTrue(confirming); finished = true
                }, 2000)
            }
        })
        try { paparazzi.gif(view, "panel_action_edit", end = 2300, fps = 10); assertTrue(finished) }
        finally { view.disposeComposition() }
    }
}
