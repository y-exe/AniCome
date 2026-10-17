package net.yexe.anicome

import android.content.ContextWrapper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class PanelHomeRestoreTest : InteractionFixture() {
    @Test fun homeCanRecoverTheActivePanelWithoutOpeningItsSettings() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val settings = SettingsRepository(MemoryPreferences(), scope)
        val context = ContextWrapper(null)
        val panel = FloatingPanelRepository(context, settings, AppCoordinator(settings = settings, scope = scope), AppLockRepository(settings, scope),
            AdvancedSettingsRepository(settings, scope), DownloadRepository(context, settings, ReservationTransport()), scope)
        panel.active.value = true
        val requests = mutableListOf<PanelPlacementRequest>()
        scope.launch { panel.placements.collect { requests += it } }
        val model = FloatingPanelViewModel(panel)
        val view = fixture { SettingsScreen(home = true, panelModel = model) {} }
        try {
            paparazzi.snapshot(view, "home_panel_restore")
            assertTrue(texts(view).contains("パネルの位置を戻す")); click(view, "パネルの位置を戻す")
            assertEquals(DefaultPanelPlacement, requests.single())
        } finally { view.disposeComposition(); scope.cancel() }
    }
}
