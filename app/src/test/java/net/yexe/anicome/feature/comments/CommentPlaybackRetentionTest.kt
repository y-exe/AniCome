package net.yexe.anicome

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class CommentPlaybackRetentionTest {
    private val playback = Playback(connected = true, title = "作品 第1話", mediaId = "episode-1", playing = true)
    private val saved = listOf(Comment(29_000, "保存済みコメント"), Comment(31_000, "続き"))
    private fun bind() {
        Session.comments.value = saved
        Session.matchedKey = playback.key
        Session.commentsFromArchive.value = true
        Session.commentSources.value = listOf(PlaybackSource.OFFICIAL)
        Session.commentSyncReports.value = listOf(PlaybackSource.JIKKYO to CommentSync.CUT)
        Session.offset.value = 500
    }
    @After fun reset() {
        Session.matchedKey = ""; Session.comments.value = emptyList(); Session.commentSources.value = emptyList()
        Session.commentSyncReports.value = emptyList(); Session.commentsFromArchive.value = false; Session.offset.value = 0
    }
    @Test fun durationArrivingAfterThirtySecondsDoesNotClearSavedPlayback() {
        bind()
        prepareAutomaticComments(playback.copy(position = 30_000, duration = 1_440_000), true, PlaybackSource.entries.toSet())
        assertSame(saved, Session.comments.value)
        assertTrue(Session.commentsFromArchive.value)
        assertEquals(listOf(PlaybackSource.OFFICIAL), Session.commentSources.value)
        assertEquals(listOf(PlaybackSource.JIKKYO to CommentSync.CUT), Session.commentSyncReports.value)
        assertEquals(500L, Session.offset.value)
    }
    @Test fun repeatedFetchAndSeekKeepExistingCommentsUntilReplacementIsReady() {
        bind()
        repeat(3) { prepareAutomaticComments(playback.copy(position = it * 60_000L), true, setOf(PlaybackSource.OFFICIAL, PlaybackSource.JIKKYO, PlaybackSource.LIVE_ARCHIVE)) }
        assertSame(saved, Session.comments.value)
        assertEquals(playback.key, Session.matchedKey)
    }
    @Test fun nextEpisodeDoesNotReuseThePreviousEpisodesComments() {
        bind()
        prepareAutomaticComments(playback.copy(mediaId = "episode-2", title = "作品 第2話"), true, null)
        assertTrue(Session.comments.value.isEmpty())
        assertTrue(Session.matchedKey.isEmpty())
        assertFalse(Session.commentsFromArchive.value)
    }
    @Test fun stoppingOrDisablingTheSourceClearsTheBinding() {
        bind(); prepareAutomaticComments(playback, false, null); assertTrue(Session.comments.value.isEmpty())
        bind(); prepareAutomaticComments(playback, true, setOf(PlaybackSource.JIKKYO)); assertTrue(Session.comments.value.isEmpty())
        bind(); prepareAutomaticComments(playback.copy(connected = false), true, null); assertTrue(Session.comments.value.isEmpty())
    }
}
