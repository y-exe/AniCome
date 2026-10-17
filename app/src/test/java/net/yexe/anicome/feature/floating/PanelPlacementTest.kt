package net.yexe.anicome

import android.content.ContextWrapper
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PanelPlacementTest {
    @Test fun lateAndCoalescedPositionWritesAreAcknowledgedWithoutApplyingAnOldPosition() {
        val writes = PanelPositionWrites()
        writes.sent("first", false); writes.sent("second", false); writes.sent("same-coordinates-again", false)
        assertTrue(writes.observed("first", false)); assertTrue(writes.observed("same-coordinates-again", false))
        assertFalse(writes.observed("external", false))
        writes.sent("portrait", false); writes.sent("landscape", true)
        assertTrue(writes.observed("landscape", true)); assertTrue(writes.observed("portrait", false))
        writes.sent("old", false); assertFalse(writes.observed("external-reset", false)); assertFalse(writes.observed("old", false))
    }
    @Test fun eachSavedPositionHasItsOwnRevisionEvenForIdenticalCoordinates() = runTest {
        val settings = SettingsRepository(MemoryPreferences(), backgroundScope)
        val request = PanelPlacementRequest(portrait = PanelPosition(1f, .3f))
        settings.applyPanelPlacement(request, "first"); runCurrent()
        val first = panelOptions(settings.load()); assertEquals("first", first.positionRevision)
        settings.applyPanelPlacement(request, "second"); runCurrent()
        val second = panelOptions(settings.load()); assertEquals("second", second.positionRevision)
        assertEquals(first.x, second.x); assertEquals(first.y, second.y); assertEquals("", second.landscapePositionRevision)
        assertFalse(portableKey("panelPositionRevision")); assertFalse(portableKey("panelLandscapePositionRevision"))
    }
    @Test fun floatingStatusAndResumeShareTheVisibilityRepositoryAndRespectTheAppLock() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        Session.hiddenUntil.value = 0; Session.hiddenEpisode.value = ""
        Session.playback.value = Playback(title = "作品", playing = true)
        try {
            val context = ContextWrapper(null); val settings = SettingsRepository(MemoryPreferences(), scope)
            val security = AppLockRepository(settings, scope)
            val visibility = CommentVisibilityRepository(scope) { 0L }
            val floating = FloatingPanelRepository(context, settings, AppCoordinator(settings = settings, scope = scope), security,
                AdvancedSettingsRepository(settings, scope), DownloadRepository(context, settings, ReservationTransport()), scope, visibility)
            withTimeout(5000) { security.state.first { it.loaded } }
            visibility.hide("30")
            val hidden = withTimeout(5000) { floating.state.first { it.temporaryHidden } }
            assertEquals("30秒後にコメントを再開", hidden.label)
            floating.resumeComments()
            withTimeout(5000) { visibility.state.first { !it.active } }
            assertEquals(0L, Session.hiddenUntil.value)
            val lockedSettings = SettingsRepository(MemoryPreferences().apply { updateData { it.toMutablePreferences().apply { this[stringPreferencesKey("lockMode")] = "pin" } } }, scope)
            val lockedSecurity = AppLockRepository(lockedSettings, scope)
            withTimeout(5000) { lockedSecurity.state.first { it.loaded && it.mode == "pin" } }
            val lockedPanel = FloatingPanelRepository(context, lockedSettings, AppCoordinator(settings = lockedSettings, scope = scope), lockedSecurity,
                AdvancedSettingsRepository(lockedSettings, scope), DownloadRepository(context, lockedSettings, ReservationTransport()), scope, visibility)
            visibility.hide("30"); lockedPanel.resumeComments()
            assertEquals(30000L, Session.hiddenUntil.value)
        } finally { scope.cancel(); Session.hiddenUntil.value = 0; Session.hiddenEpisode.value = ""; Session.playback.value = Playback() }
    }
    private fun panel(settings: SettingsRepository, scope: CoroutineScope): FloatingPanelRepository {
        val context = ContextWrapper(null)
        return FloatingPanelRepository(context, settings, AppCoordinator(settings = settings, scope = scope), AppLockRepository(settings, scope),
            AdvancedSettingsRepository(settings, scope), DownloadRepository(context, settings, ReservationTransport()), scope)
    }
    @Test fun subtitleGuidanceOffersOnlyTheOppositeSideAndKeepsFreeChoiceWithoutSubtitles() {
        assertEquals(listOf(PanelCorner.TOP_LEFT, PanelCorner.TOP_RIGHT), subtitleSafeCorners("bottom"))
        assertEquals(listOf(PanelCorner.BOTTOM_LEFT, PanelCorner.BOTTOM_RIGHT), subtitleSafeCorners("top"))
        assertEquals(PanelCorner.entries, subtitleSafeCorners("none"))
    }
    @Test fun placementIsAtomicAndDoesNotChangeTheOtherOrientationOrCollapseState() = runTest {
        val settings = SettingsRepository(MemoryPreferences(), backgroundScope)
        settings.putBoolean("panelCollapsed", false); runCurrent()
        settings.applyPanelPlacement(PanelPlacementRequest(portrait = PanelCorner.TOP_LEFT.position)); runCurrent()
        var options = panelOptions(settings.load())
        assertEquals(0f, options.x); assertEquals(0f, options.y); assertEquals(1f, options.landscapeX); assertEquals(.3f, options.landscapeY); assertFalse(options.startCollapsed)
        settings.applyPanelPlacement(PanelPlacementRequest(landscape = PanelCorner.BOTTOM_RIGHT.position)); runCurrent()
        options = panelOptions(settings.load())
        assertEquals(0f, options.x); assertEquals(0f, options.y); assertEquals(1f, options.landscapeX); assertEquals(1f, options.landscapeY)
    }
    @Test fun resetRestoresBothOrientationsAndSendsACommandEvenWhenAlreadyAtDefaults() = runTest {
        val settings = SettingsRepository(MemoryPreferences(), backgroundScope)
        val panel = panel(settings, backgroundScope)
        val requests = mutableListOf<PanelPlacementRequest>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { panel.placements.collect { requests += it } }
        panel.place(PanelCorner.BOTTOM_LEFT, true); runCurrent()
        assertNull(requests.single().forOrientation(false)); assertEquals(PanelPosition(0f, 1f), requests.single().forOrientation(true))
        panel.resetPosition(); runCurrent(); panel.resetPosition(); runCurrent()
        assertEquals(listOf(DefaultPanelPlacement, DefaultPanelPlacement), requests.drop(1))
        val options = panelOptions(settings.load())
        assertEquals(1f, options.x); assertEquals(.3f, options.y); assertEquals(1f, options.landscapeX); assertEquals(.3f, options.landscapeY)
        assertTrue(options.startCollapsed); assertTrue(options.landscapeCollapsed)
        val reopened = panel(settings, backgroundScope); runCurrent()
        assertEquals(options, reopened.options.value)
    }
}
