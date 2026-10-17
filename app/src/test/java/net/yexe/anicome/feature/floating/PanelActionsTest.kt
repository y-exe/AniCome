package net.yexe.anicome

import androidx.datastore.preferences.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PanelActionsTest {
    @Test fun oldSettingsKeepTheFourOriginalActionsAndMalformedSettingsStayUsable() {
        assertEquals(DefaultPanelActions, panelActions(null))
        assertEquals(DefaultPanelActions, panelActions("unknown"))
        assertEquals(emptyList<PanelAction>(), panelActions(""))
        assertEquals(listOf(PanelAction.SYNC, PanelAction.OPEN), panelActions("sync,unknown,open,sync"))
        assertEquals(4, panelActions("sync,open,toggle,ng,tools").size)
        val options = panelOptions(SettingsSnapshot(emptyPreferences()))
        assertEquals(DefaultPanelActions, options.actions); assertTrue(options.snapToEdge)
    }
    @Test fun actionsCanBeHiddenReorderedAndRestoredButNeverExceedFour() {
        val initial = DefaultPanelActions
        assertEquals(initial, changePanelActions(initial, PanelAction.SYNC, "show"))
        val removed = changePanelActions(initial, PanelAction.NG, "hide")
        val added = changePanelActions(removed, PanelAction.SYNC, "show")
        val moved = changePanelActions(added, PanelAction.SYNC, "up")
        assertEquals(listOf(PanelAction.TOGGLE, PanelAction.TOOLS, PanelAction.SYNC, PanelAction.OPEN), moved)
        assertEquals(moved, changePanelActions(moved, PanelAction.TOGGLE, "up"))
        assertEquals(moved, changePanelActions(moved, PanelAction.OPEN, "down"))
        assertEquals(initial, changePanelActions(emptyList(), PanelAction.SYNC, "reset"))
        assertEquals(moved, changePanelActions(moved, PanelAction.NG, "down"))
    }
    @Test fun rapidEditsReadTheLatestStoredOrderAndBothNewSettingsArePortable() = runTest {
        val settings = SettingsRepository(MemoryPreferences(), backgroundScope)
        settings.panelAction(PanelAction.NG, "hide")
        settings.panelAction(PanelAction.SYNC, "show")
        settings.panelAction(PanelAction.SYNC, "up")
        runCurrent()
        assertEquals(listOf(PanelAction.TOGGLE, PanelAction.TOOLS, PanelAction.SYNC, PanelAction.OPEN), panelOptions(settings.load()).actions)
        val recreated = SettingsRepository(settings.store, backgroundScope)
        assertEquals(panelOptions(settings.load()).actions, panelOptions(recreated.load()).actions)
        validatePortableSetting("panelActions", JSONObject().put("type", "string").put("value", "sync,toggle,tools"))
        validatePortableSetting("panelSnap", JSONObject().put("type", "boolean").put("value", false))
        for (bad in listOf("sync,sync", "unknown", "toggle,tools,ng,open,sync")) {
            try { validatePortableSetting("panelActions", JSONObject().put("type", "string").put("value", bad)); fail() } catch (_: IllegalArgumentException) { }
        }
        settings.putBoolean("panelSnap", false); runCurrent(); assertFalse(panelOptions(settings.load()).snapToEdge)
    }
    @Test fun snappingChoosesTheClosestSideAndNeverMovesTheVerticalPosition() {
        assertEquals(PanelPosition(0f, .3f), settledPanelPosition(.49f, .3f, true))
        assertEquals(PanelPosition(1f, .7f), settledPanelPosition(.5f, .7f, true))
        assertEquals(PanelPosition(.27f, .7f), settledPanelPosition(.27f, .7f, false))
        assertEquals(PanelPosition(0f, 1f), settledPanelPosition(-3f, 2f, true))
        assertEquals(PanelPosition(1f, .3f), settledPanelPosition(Float.NaN, Float.POSITIVE_INFINITY, true))
    }
}
