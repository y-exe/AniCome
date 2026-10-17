package net.yexe.anicome

import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SyncAdjustmentTest {
    private val official = PlaybackSource.OFFICIAL
    private val live = PlaybackSource.JIKKYO
    @Before fun setup() {
        Session.playback.value = Playback(title = "作品", subtitle = "第1話", mediaId = "ep1")
        Session.manualPlayback.value = null; Session.commentBinding.value = CommentBinding.AUTO; Session.matchedKey = Session.playback.value.key
        Session.offset.value = 0; Session.sourceOffsets.value = emptyMap()
        Session.commentSlots.value = listOf(PlaybackCommentSlot("so1", "作品 第1話", official, listOf(Comment(1000, "　■\n ■　")), sync = CommentSync.NATIVE),
            PlaybackCommentSlot("jk1", "実況", live, listOf(Comment(2000, "実況")), sync = CommentSync.OFFSET))
    }
    @After fun clear() { Session.playback.value = Playback(); Session.manualPlayback.value = null; Session.offset.value = 0; Session.sourceOffsets.value = emptyMap(); Session.commentSlots.value = emptyList(); Session.matchedKey = ""; Session.commentBinding.value = CommentBinding.AUTO; Session.manualCommentKey.value = "" }
    @Test fun sourceUndoRestoresOnlyTheChangedSourceAndKeepsAaAndOtherAdjustments() = runTest {
        val repo = SyncAdjustmentRepository(backgroundScope); val slots = Session.commentSlots.value
        repo.source(official, 1000); runCurrent(); assertEquals(1000L, Session.sourceOffsets.value[official])
        Session.sourceOffsets.value += live to 5000L; Session.offset.value = 2000L
        assertTrue(repo.undo()); assertFalse(Session.sourceOffsets.value.containsKey(official))
        assertEquals(5000L, Session.sourceOffsets.value[live]); assertEquals(2000L, Session.offset.value); assertSame(slots, Session.commentSlots.value)
        assertEquals("　■\n ■　", Session.commentSlots.value.first().comments.single().body)
        assertFalse(repo.undo())
    }
    @Test fun sourceResetCanBeUndoneAndNoOpChangesDoNotCreateAnUndo() = runTest {
        val repo = SyncAdjustmentRepository(backgroundScope)
        assertTrue(repo.source(official, null)); runCurrent(); assertNull(repo.undo.value)
        repo.source(official, -2000); repo.source(official, null); runCurrent()
        assertEquals(0L, Session.sourceOffsets.value[official]); assertEquals(-2000L, repo.undo.value!!.before)
        assertTrue(repo.undo()); assertEquals(-2000L, Session.sourceOffsets.value[official])
    }
    @Test fun aStaleButtonCannotUndoANewerChangeOrConsumeItsUndo() = runTest {
        val repo = SyncAdjustmentRepository(backgroundScope)
        repo.source(official, 1000); runCurrent(); val old = repo.undo.value!!
        repo.global(2000); runCurrent(); val newer = repo.undo.value!!
        assertNotEquals(old.id, newer.id)
        assertFalse(repo.undo(old.id)); runCurrent(); assertEquals(newer.id, repo.undo.value!!.id)
        assertEquals(2000L, Session.offset.value); assertEquals(1000L, Session.sourceOffsets.value[official])
        assertTrue(repo.undo(newer.id)); assertEquals(0L, Session.offset.value); assertEquals(1000L, Session.sourceOffsets.value[official])
    }
    @Test fun episodeOrVideoReplacementCannotApplyAnOldUndo() = runTest {
        val repo = SyncAdjustmentRepository(backgroundScope)
        repo.source(official, 1000)
        Session.commentSlots.value = Session.commentSlots.value.map { if (it.source == official) it.copy(id = "so2") else it }
        runCurrent(); assertNull(repo.undo.value); assertFalse(repo.undo()); assertEquals(1000L, Session.sourceOffsets.value[official])
        repo.global(1000); Session.playback.value = Session.playback.value.copy(mediaId = "ep2"); runCurrent()
        assertNull(repo.undo.value); assertFalse(repo.undo()); assertEquals(1000L, Session.offset.value)
    }
    @Test fun externalChangesAndNewAutomaticTransformsInvalidateTheRelevantUndo() = runTest {
        val repo = SyncAdjustmentRepository(backgroundScope)
        repo.global(1000); Session.offset.value = 3000; runCurrent(); assertNull(repo.undo.value); assertFalse(repo.undo()); assertEquals(3000L, Session.offset.value)
        repo.source(live, 1000)
        Session.commentSlots.value = Session.commentSlots.value.map { if (it.source == live) it.copy(syncOffset = 10_000) else it }
        assertFalse(repo.undo()); assertEquals(1000L, Session.sourceOffsets.value[live])
    }
    @Test fun addedCommentsDoNotInvalidateUndoButManualBindingChangesDo() = runTest {
        val repo = SyncAdjustmentRepository(backgroundScope)
        repo.source(official, 1000)
        Session.commentSlots.value = Session.commentSlots.value.map { it.copy(comments = it.comments + Comment(9000, "追加")) }
        assertTrue(repo.undo())
        repo.global(1000); Session.commentBinding.value = CommentBinding.MANUAL; Session.manualCommentKey.value = Session.playback.value.key
        assertFalse(repo.undo()); assertEquals(1000L, Session.offset.value)
        Session.manualCommentKey.value = ""
    }
    @Test fun sliderPreviewIsOneChangeAndUndoesToTheValueBeforeDragging() = runTest {
        val repo = SyncAdjustmentRepository(backgroundScope)
        repo.setGlobal(2000)
        for (value in 2500L..9000L step 500) repo.previewGlobal(value)
        runCurrent(); assertNull(repo.undo.value)
        repo.finishGlobalPreview(); runCurrent(); assertEquals(2000L, repo.undo.value!!.before); assertEquals(9000L, repo.undo.value!!.after)
        assertTrue(repo.undo()); assertEquals(2000L, Session.offset.value)
        repo.previewGlobal(3000); assertTrue(repo.undo()); assertEquals(2000L, Session.offset.value)
    }
    @Test fun leavingTheEpisodeDuringASliderGestureDoesNotCreateAnOldUndo() = runTest {
        val repo = SyncAdjustmentRepository(backgroundScope)
        repo.previewGlobal(5000)
        Session.playback.value = Session.playback.value.copy(mediaId = "ep2")
        repo.finishGlobalPreview(); runCurrent(); assertNull(repo.undo.value); assertFalse(repo.undo())
    }
    @Test fun theLastManualOperationWinsAndAResetUndoKeepsSourceCorrections() = runTest {
        val repo = SyncAdjustmentRepository(backgroundScope)
        repo.source(official, 5000); repo.global(2000); repo.global(null); runCurrent()
        assertNull(repo.undo.value!!.source); assertTrue(repo.undo()); assertEquals(2000L, Session.offset.value)
        assertEquals(5000L, Session.sourceOffsets.value[official])
        repo.previewGlobal(0); repo.setGlobal(0); runCurrent(); assertEquals(2000L, repo.undo.value!!.before)
    }
    @Test fun alignmentUsesTheActualRendererClockForBothPositiveAndNegativeGlobalOffsets() {
        val video = 40_000L; val marker = 35_000L
        for (global in listOf(-3000L, 0L, 3000L)) {
            val source = alignedSourceCorrection(video, global, marker)
            assertEquals(video + global, marker + source)
        }
        assertEquals(8000L, alignedSourceCorrection(video, 3000, marker))
        assertEquals(3_600_000L, alignedSourceCorrection(Long.MAX_VALUE, 0, 0))
        assertEquals(-3_600_000L, alignedSourceCorrection(0, 0, Long.MAX_VALUE))
    }
    @Test fun globalAndSourceDirectionsMatchTheirDisplayedDelayWithoutOverflow() = runTest {
        val repo = SyncAdjustmentRepository(backgroundScope)
        repo.global(1000); assertEquals(-1000L, globalDisplayDelay(Session.offset.value))
        assertEquals(1000L, Session.offset.value)
        repo.global(Long.MAX_VALUE); assertEquals(3_600_000L, Session.offset.value)
        repo.global(Long.MIN_VALUE); assertEquals(-3_600_000L, Session.offset.value)
        repo.source(official, Long.MAX_VALUE); assertEquals(3_600_000L, Session.sourceOffsets.value[official])
        repo.source(official, Long.MIN_VALUE); assertEquals(-3_600_000L, Session.sourceOffsets.value[official])
    }
}
