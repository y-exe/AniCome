package net.yexe.anicome

import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CommentVisibilityTest {
    @Before fun before() { Session.hiddenUntil.value = 0L; Session.hiddenEpisode.value = ""; Session.playback.value = Playback(); Session.manualPlayback.value = null }
    @After fun after() { before(); Session.comments.value = emptyList(); Session.overlay.value = false }
    @Test fun countdownUsesElapsedTimeAndResumesWithoutAnotherPlaybackEvent() = runTest {
        val repo = CommentVisibilityRepository(backgroundScope) { testScheduler.currentTime }
        repo.hide("15"); runCurrent()
        assertEquals(15, repo.state.value.remainingSeconds); assertTrue(repo.state.value.active)
        advanceTimeBy(1100); runCurrent(); assertEquals(14, repo.state.value.remainingSeconds)
        advanceTimeBy(13900); runCurrent()
        assertFalse(repo.state.value.active); assertEquals(0L, Session.hiddenUntil.value)
    }
    @Test fun extendingTheTimerCancelsTheOldExpiryAndManualResumeCancelsTheNewOne() = runTest {
        val repo = CommentVisibilityRepository(backgroundScope) { testScheduler.currentTime }
        repo.hide("15"); runCurrent(); advanceTimeBy(10_000); runCurrent()
        repo.hide("30"); runCurrent(); assertEquals(30, repo.state.value.remainingSeconds)
        advanceTimeBy(5000); runCurrent(); assertEquals(25, repo.state.value.remainingSeconds)
        repo.hide("none"); runCurrent(); assertFalse(repo.state.value.active)
        advanceTimeBy(60_000); runCurrent(); assertEquals(0L, Session.hiddenUntil.value)
    }
    @Test fun currentEpisodeStaysHiddenAcrossATemporaryDisconnectButNotTheNextEpisode() = runTest {
        val episode = Playback(title = "作品", subtitle = "第1話", mediaId = "ep1")
        Session.playback.value = episode
        val repo = CommentVisibilityRepository(backgroundScope) { testScheduler.currentTime }
        repo.hide("episode"); runCurrent(); assertTrue(repo.state.value.episodeHidden)
        Session.playback.value = Playback(); runCurrent(); assertTrue(repo.state.value.episodeHidden)
        Session.playback.value = episode; runCurrent(); assertTrue(repo.state.value.episodeHidden)
        Session.playback.value = episode.copy(subtitle = "第2話", mediaId = "ep2"); runCurrent()
        assertFalse(repo.state.value.active); assertEquals("", Session.hiddenEpisode.value)
        Session.playback.value = episode; runCurrent(); assertFalse(repo.state.value.active)
    }
    @Test fun noEpisodeDoesNotCreateAnEpisodeMuteAndManualPlaybackHasPriority() = runTest {
        val repo = CommentVisibilityRepository(backgroundScope) { testScheduler.currentTime }
        repo.hide("episode"); runCurrent(); assertFalse(repo.state.value.canHideEpisode); assertFalse(repo.state.value.active)
        Session.playback.value = Playback(title = "検出された作品")
        Session.manualPlayback.value = Playback(title = "手動の作品")
        repo.hide("episode"); runCurrent()
        assertEquals(Session.manualPlayback.value!!.key, Session.hiddenEpisode.value)
        Session.playback.value = Playback(title = "検出だけ変わった作品"); runCurrent(); assertTrue(repo.state.value.active)
        Session.manualPlayback.value = null; runCurrent(); assertFalse(repo.state.value.active)
    }
    @Test fun hidingDoesNotPauseVideoRemoveAaOrChangeTheOverlaySwitch() = runTest {
        val comments = listOf(Comment(1000, "　■\n ■　", raw = "{\"body\":\"AA\"}"))
        Session.comments.value = comments; Session.overlay.value = true
        Session.playback.value = Playback(title = "作品", playing = true)
        val repo = CommentVisibilityRepository(backgroundScope) { testScheduler.currentTime }
        repo.hide("300"); runCurrent(); assertEquals(300, repo.state.value.remainingSeconds)
        assertSame(comments, Session.comments.value); assertTrue(Session.playback.value.playing); assertTrue(Session.overlay.value)
        repo.hide("none"); runCurrent(); assertSame(comments, Session.comments.value)
    }
    @Test fun switchingBetweenTimedAndEpisodeHidingAndRejectingBadInputKeepsTheChosenMode() = runTest {
        Session.playback.value = Playback(title = "作品")
        val repo = CommentVisibilityRepository(backgroundScope) { testScheduler.currentTime }
        repo.hide("60"); runCurrent(); assertEquals(60, repo.state.value.remainingSeconds)
        repo.hide("episode"); runCurrent(); assertEquals(0L, Session.hiddenUntil.value); assertTrue(repo.state.value.episodeHidden)
        repo.hide("30"); runCurrent(); assertEquals("", Session.hiddenEpisode.value); assertEquals(30, repo.state.value.remainingSeconds)
        try { repo.hide("forever"); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(30, repo.state.value.remainingSeconds)
    }
}
