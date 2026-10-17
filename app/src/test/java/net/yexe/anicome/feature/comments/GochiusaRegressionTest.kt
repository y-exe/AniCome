package net.yexe.anicome

import org.junit.Assert.*
import org.junit.Test

class GochiusaRegressionTest {
    @Test fun firstSeasonDoesNotAcquireTheSecondSeasonWithTheSameRuntime() {
        val first = Candidate("so23335421", "ご注文はうさぎですか？ 第1羽「ひと目で、尋常でないもふもふだと見抜いたよ」", 2591203, 1417, 2222624)
        val second = Candidate("so27369471", "ご注文はうさぎですか？？ 第1羽", 2614174, 1417, 230949)
        val matched = matchingPlaybackVideos(Episode("ご注文はうさぎですか？", 1), listOf(first, second), 1417)
        assertEquals(listOf(first.id), matched.flatMap { it.second }.map { it.id })
        assertEquals(listOf(second.id), matchingPlaybackVideos(Episode("ご注文はうさぎですか？？", 1), listOf(first, second), 1417).flatMap { it.second }.map { it.id })
    }

    @Test fun aShorterRefreshDoesNotRemoveAlreadyAcquiredCommentsFromAnySource() {
        for (source in listOf(PlaybackSource.OFFICIAL, PlaybackSource.DANIME, PlaybackSource.CHAPTER, PlaybackSource.COMMENT_ONLY, PlaybackSource.JIKKYO, PlaybackSource.LIVE_ARCHIVE)) {
            val comments = (0 until 20000).map { Comment(it * 70L, "comment-$it") }
            val previous = PlaybackCommentSlot("same-source", "episode", source, comments, sync = CommentSync.NATIVE)
            val refreshed = previous.copy(comments = comments.takeLast(1000) + Comment(1400000, "new"))
            val result = continuePlaybackComments(listOf(previous), PlaybackComments(listOf(refreshed)))
            assertEquals(source.name, 20001, result.comments.size)
            assertTrue(result.comments.contains(comments[5000]))
        }
    }

    @Test fun refreshedPostMetadataDoesNotDuplicateTheSamePost() {
        val old = Comment(1000, "same", """{"no":1,"postedAt":"2026-01-01T00:00:00Z","nicoruCount":0}""")
        val updated = old.copy(raw = """{"no":1,"postedAt":"2026-01-01T00:00:00Z","nicoruCount":1}""")
        val slot = PlaybackCommentSlot("so1", "episode", PlaybackSource.OFFICIAL, listOf(old), sync = CommentSync.NATIVE)
        assertEquals(listOf(updated), continuePlaybackComments(listOf(slot), PlaybackComments(listOf(slot.copy(comments = listOf(updated))))).comments)
    }

    @Test fun aNewSynchronizationMappingReplacesTheOldTimes() {
        val old = PlaybackCommentSlot("live", "episode", PlaybackSource.LIVE_ARCHIVE, listOf(Comment(1000, "same")), sync = CommentSync.START_ONLY)
        val aligned = old.copy(comments = listOf(Comment(500, "same")), sync = CommentSync.OFFSET, syncOffset = -500)
        assertEquals(listOf(aligned), continuePlaybackComments(listOf(old), PlaybackComments(listOf(aligned))).slots)
    }

    @Test fun confirmingNativeTimesDoesNotDiscardTheOfflineArchive() {
        val stored = PlaybackCommentSlot("so1", "episode", PlaybackSource.OFFICIAL, listOf(Comment(1000, "old"), Comment(2000, "recent")), true)
        val confirmed = stored.copy(comments = stored.comments.takeLast(1), fromArchive = false, sync = CommentSync.NATIVE)
        val merged = continuePlaybackComments(listOf(stored), PlaybackComments(listOf(confirmed)))
        assertEquals(stored.comments, merged.comments)
        assertEquals(CommentSync.NATIVE, merged.slots.single().sync)
        assertTrue(merged.fromArchive)
    }

    @Test fun legacyPostsWithoutNumbersDoNotShareAnIdentity() {
        val raw = """{"id":":","no":0,"postedAt":"1970-01-01T00:00:00Z"}"""
        val comments = listOf(Comment(1000, "one", raw), Comment(1000, "two", raw))
        val old = PlaybackCommentSlot("legacy", "episode", PlaybackSource.LIVE_ARCHIVE, comments)
        assertEquals(comments, continuePlaybackComments(listOf(old), PlaybackComments(listOf(old.copy(comments = comments.takeLast(1))))).comments)
    }
}
