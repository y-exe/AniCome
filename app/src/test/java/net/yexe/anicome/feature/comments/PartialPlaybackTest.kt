package net.yexe.anicome

import org.junit.Test
import org.junit.Assert.*

class PartialPlaybackTest {
    private val official = PlaybackCommentSlot("so123", "official", PlaybackSource.OFFICIAL, listOf(Comment(1000, "official")), true, 1400000, CommentSync.NATIVE)
    @Test fun liveCompletingBeforeTheArchiveReadKeepsExistingOfficialComments() {
        val live = PlaybackCommentSlot("live", "live", PlaybackSource.LIVE_ARCHIVE, listOf(Comment(2000, "live")), sync = CommentSync.CUT)
        val update = continuePlaybackComments(listOf(official), PlaybackComments(listOf(live)))
        assertSame(official, update.slots.first()); assertSame(official.comments[0], update.comments.first())
        assertEquals(listOf("official", "live"), update.comments.map { it.body }); assertTrue(update.fromArchive)
        val reloaded = continuePlaybackComments(update.slots, PlaybackComments(listOf(official)))
        assertEquals(2, reloaded.slots.size); assertEquals(2, reloaded.comments.size)
    }
    @Test fun emptyResultNeverClearsTheBoundArchive() {
        val update = continuePlaybackComments(listOf(official), PlaybackComments(emptyList(), listOf(PlaybackSource.JIKKYO to CommentSync.UNKNOWN)))
        assertEquals(official.comments, update.comments); assertSame(official, update.slots.single())
        assertEquals(listOf(PlaybackSource.JIKKYO to CommentSync.UNKNOWN), update.syncReports)
    }
    @Test fun updatedSlotReplacesTheSameSourceWithoutDuplicatingOtherSources() {
        val live = PlaybackCommentSlot("live", "live", PlaybackSource.JIKKYO, listOf(Comment(2000, "live")))
        val fresh = official.copy(comments = official.comments + Comment(3000, "new"))
        val update = continuePlaybackComments(listOf(official, live), PlaybackComments(listOf(fresh)))
        assertEquals(2, update.slots.size); assertEquals(3, update.comments.size)
        assertSame(live, update.slots[1]); assertSame(fresh, update.slots[0])
    }
    @Test fun newPlaybackDoesNotReuseThePreviousEpisode() {
        val incoming = PlaybackComments(listOf(official.copy(id = "so456")))
        assertSame(incoming, continuePlaybackComments(emptyList(), incoming))
    }
    @Test fun reconnectingWithASubsetPreservesExistingIdenticalCommentOccurrences() {
        val repeated = Comment(1000, "same")
        val old = official.copy(comments = listOf(repeated, repeated, Comment(2000, "old")))
        val fresh = official.copy(comments = listOf(repeated, Comment(3000, "new")), fromArchive = false)
        val first = continuePlaybackComments(listOf(old), PlaybackComments(listOf(fresh)))
        assertEquals(2, first.comments.count { it == repeated })
        assertEquals(listOf("same", "same", "old", "new"), first.comments.map { it.body })
        val second = continuePlaybackComments(first.slots, PlaybackComments(listOf(fresh)))
        assertEquals(first.comments, second.comments)
    }
    @Test fun reconnectingUsesSourceCommentIdentityRatherThanBodyOrUpdatedTiming() {
        fun comment(no: Int, at: Long) = Comment(at, "same", """{"id":"thread","no":$no,"postedAt":"time","source":"video"}""")
        val one = comment(1, 1000); val two = comment(2, 1000); val shifted = comment(1, 1100)
        val old = official.copy(comments = listOf(one, two))
        val fresh = official.copy(comments = listOf(shifted), fromArchive = false)
        val merged = continuePlaybackComments(listOf(old), PlaybackComments(listOf(fresh)))
        assertEquals(listOf(two, shifted), merged.comments)
        val again = continuePlaybackComments(merged.slots, PlaybackComments(listOf(fresh)))
        assertEquals(merged.comments, again.comments)
    }
    @Test fun equalBodiesFromDifferentSourcesRemainIndependentOnReconnect() {
        val same = Comment(1000, "same")
        val archive = official.copy(comments = listOf(same))
        val live = PlaybackCommentSlot("live", "live", PlaybackSource.LIVE_ARCHIVE, listOf(same))
        val result = continuePlaybackComments(listOf(archive, live), PlaybackComments(listOf(archive, live)))
        assertEquals(2, result.slots.size)
        assertEquals(2, result.comments.size)
    }
}
