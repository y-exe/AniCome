package net.yexe.anicome

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CommentTimelineTest {
    private val duration = 1_440_000L
    private val labels = listOf("始まった", "OP", "A", "B", "ED")
    private val times = listOf(0L, 90_000L, 180_000L, 660_000L, 1_350_000L)
    private fun markers(shift: Long = 0, cm: Long = 0): List<Comment> = times.flatMapIndexed { i, at ->
        (0..3).map { n -> Comment(at + shift + (if (i >= 3) cm else 0) + 1_000 + n * 100, labels[i], JSONObject().put("userId", "u$n").toString()) }
    }
    private fun official() = PlaybackCommentSlot("official", "episode", PlaybackSource.OFFICIAL, markers(), durationMs = duration)
    private fun broadcast(comments: List<Comment>) = PlaybackCommentSlot("jk4:one", "episode", PlaybackSource.JIKKYO, comments, durationMs = duration + 150_000)
    @Test fun removesMidEpisodeCommercialAndAlignsBothPartsWithoutChangingOriginals() {
        val before = Comment(500_000, "before")
        val ad = Comment(740_000, "commercial")
        val after = Comment(900_000, "after")
        val raw = markers(30_000, 120_000) + listOf(before, ad, after)
        val result = synchronizeCommentSlots(listOf(official(), broadcast(raw)), duration, true, true).last()
        assertEquals(CommentSync.CUT, result.sync)
        assertEquals(470_000L, result.comments.single { it.body == "before" }.at)
        assertEquals(750_000L, result.comments.single { it.body == "after" }.at)
        assertFalse(result.comments.any { it.body == "commercial" })
        assertEquals(900_000L, after.at)
        assertEquals(raw, broadcast(raw).comments)
        assertEquals(result.comments.sortedBy { it.at }, result.comments)
    }
    @Test fun alignsConstantWaitingTimeWithoutRemovingCommercials() {
        val result = synchronizeCommentSlots(listOf(official(), broadcast(markers(30_000) + Comment(600_000, "main"))), duration, true, true).last()
        assertEquals(CommentSync.OFFSET, result.sync)
        assertEquals(570_000L, result.comments.single { it.body == "main" }.at)
    }
    @Test fun missingOrConflictingLandmarksNeverCutTheEpisode() {
        val incomplete = markers(30_000, 120_000).filter { it.body != "B" }
        val relaxed = synchronizeCommentSlots(listOf(official(), broadcast(incomplete)), duration, true, false).last()
        assertEquals(CommentSync.START_ONLY, relaxed.sync)
        assertTrue(relaxed.comments.any { it.body == "ED" })
        assertEquals(1, synchronizeCommentSlots(listOf(official(), broadcast(incomplete)), duration, true, true).size)
        val conflicting = markers(30_000, 120_000) + markers(200_000, 120_000)
        assertEquals(CommentSync.UNKNOWN, synchronizeCommentSlots(listOf(official(), broadcast(conflicting)), duration, true, false).last().sync)
    }
    @Test fun oneUserRepeatingMarkersCannotTriggerAutomaticSynchronization() {
        val repeated = markers(30_000, 120_000).map { it.copy(raw = JSONObject().put("userId", "same").toString()) }
        assertEquals(CommentSync.UNKNOWN, synchronizeCommentSlots(listOf(broadcast(repeated)), duration, true, false).single().sync)
    }
    @Test fun editingDifferencesAndCommercialsInsideUnknownSectionsAreRejected() {
        val wrongEdition = official().copy(durationMs = duration + 100_000)
        assertNotEquals(CommentSync.CUT, synchronizeCommentSlots(listOf(wrongEdition, broadcast(markers(30_000, 120_000))), duration, true, false).last().sync)
        val extraInB = markers(30_000).map { if (it.body == "ED") it.copy(at = it.at + 120_000) else it }
        assertNotEquals(CommentSync.CUT, synchronizeCommentSlots(listOf(official(), broadcast(extraInB)), duration, true, false).last().sync)
    }
    @Test fun settingsAndRepeatedEvaluationPreserveRawDataAndDoNotApplyOffsetsTwice() {
        val raw = listOf(official(), broadcast(markers(30_000, 120_000)))
        assertEquals(raw.map { it.comments }, synchronizeCommentSlots(raw, duration, false, true).map { it.comments })
        assertEquals(synchronizeCommentSlots(raw, duration, true, true), synchronizeCommentSlots(raw, duration, true, true))
    }
    @Test fun boundariesAreHalfOpenAndMalformedMapsAreRejected() {
        val comments = listOf(Comment(9, "before"), Comment(10, "start"), Comment(19, "last"), Comment(20, "ad"), Comment(30, "resume"), Comment(40, "after"))
        assertEquals(listOf(0L, 9L, 10L), remapCommentTimeline(comments, listOf(CommentTimeSpan(10, 20, 0), CommentTimeSpan(30, 40, 10)), 20).map { it.at })
        assertThrows(IllegalArgumentException::class.java) { remapCommentTimeline(comments, listOf(CommentTimeSpan(10, 30, 0), CommentTimeSpan(20, 40, 20)), 40) }
    }
    @Test fun unknownTimingAndChapterDurationAreHandledPerSource() {
        val chapter = PlaybackCommentSlot("parts", "episode", PlaybackSource.CHAPTER, listOf(Comment(700_020, "part2")), durationMs = duration)
        assertEquals(CommentSync.NATIVE, synchronizeCommentSlots(listOf(chapter), duration, true, true).single().sync)
        assertTrue(synchronizeCommentSlots(listOf(broadcast(markers())), 0, true, true).isEmpty())
        val live = broadcast(markers(30_000, 120_000)).copy(source = PlaybackSource.LIVE_ARCHIVE, durationMs = 0)
        assertEquals(CommentSync.CUT, synchronizeCommentSlots(listOf(official(), live), duration, true, true).last().sync)
    }
    @Test fun twoCommercialBreaksAreRemovedIndependently() {
        val source = markers(30_000, 120_000).map { if (it.body in listOf("A", "B", "ED")) it.copy(at = it.at + 30_000) else it } + listOf(Comment(230_000, "first ad"), Comment(780_000, "second ad"), Comment(950_000, "later"))
        val result = synchronizeCommentSlots(listOf(official(), broadcast(source)), duration, true, true).last()
        assertEquals(CommentSync.CUT, result.sync)
        assertFalse(result.comments.any { it.body.contains("ad") })
        assertEquals(770_000L, result.comments.single { it.body == "later" }.at)
    }

    @Test fun infersBPartFromConfirmedMainDurationLikeTheReferenceImplementation() {
        val reference = official().copy(comments = markers().filter { it.body != "B" })
        val source = markers(30_000, 120_000) + listOf(Comment(740_000, "ad"), Comment(900_000, "after"))
        val result = synchronizeCommentSlots(listOf(reference, broadcast(source)), duration, true, true).last()
        assertEquals(CommentSync.CUT, result.sync)
        assertFalse(result.comments.any { it.body == "ad" })
        assertEquals(750_000L, result.comments.single { it.body == "after" }.at)
    }

}
