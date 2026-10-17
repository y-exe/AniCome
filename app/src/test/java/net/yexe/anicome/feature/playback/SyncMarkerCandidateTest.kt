package net.yexe.anicome

import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SyncMarkerCandidateTest {
    private val source = PlaybackSource.JIKKYO
    @Before fun setup() {
        Session.playback.value = Playback(title = "作品", subtitle = "第1話", mediaId = "ep1", position = 120_000)
        Session.manualPlayback.value = null; Session.commentBinding.value = CommentBinding.AUTO
        Session.manualCommentKey.value = ""; Session.matchedKey = Session.playback.value.key
        Session.offset.value = 2000; Session.sourceOffsets.value = mapOf(PlaybackSource.OFFICIAL to 3000)
        val comments = (0..3).map { Comment(90_000L + it * 100, "OP", JSONObject().put("userId", "u$it").toString()) } + Comment(100_000, "　■\n ■　")
        Session.commentSlots.value = listOf(PlaybackCommentSlot("jk1", "実況", source, comments, durationMs = 1_440_000))
    }
    @After fun clear() {
        Session.playback.value = Playback(); Session.manualPlayback.value = null; Session.commentBinding.value = CommentBinding.AUTO
        Session.manualCommentKey.value = ""; Session.matchedKey = ""; Session.offset.value = 0
        Session.sourceOffsets.value = emptyMap(); Session.commentSlots.value = emptyList()
    }
    private fun candidate() = analyzeSyncMarkers(Session.commentSlots.value, currentSyncMarkerContext()).getValue(source).getValue("OP")
    @Test fun precomputedMarkerUsesCurrentVideoPositionAndCanBeUndoneWithoutChangingAa() = runTest {
        val repo = SyncAdjustmentRepository(backgroundScope); val marker = candidate(); val slots = Session.commentSlots.value
        Session.playback.value = Session.playback.value.copy(position = 125_000)
        assertTrue(repo.alignMarker(marker, 0))
        val correction = Session.sourceOffsets.value.getValue(source)
        assertEquals(125_000L + Session.offset.value, marker.at + correction)
        assertEquals(3000L, Session.sourceOffsets.value[PlaybackSource.OFFICIAL]); assertSame(slots, Session.commentSlots.value)
        assertEquals("　■\n ■　", Session.commentSlots.value.single().comments.last().body)
        assertTrue(repo.undo()); assertFalse(Session.sourceOffsets.value.containsKey(source))
    }
    @Test fun changedEpisodeOrBindingRejectsTheDisplayedMarker() = runTest {
        val repo = SyncAdjustmentRepository(backgroundScope); val marker = candidate(); val before = Session.sourceOffsets.value
        val playback = Session.playback.value
        Session.playback.value = playback.copy(mediaId = "ep2")
        assertFalse(repo.alignMarker(marker, 0)); assertEquals(before, Session.sourceOffsets.value)
        Session.playback.value = playback; Session.commentBinding.value = CommentBinding.MANUAL; Session.manualCommentKey.value = playback.key
        assertFalse(repo.alignMarker(marker, 0)); assertEquals(before, Session.sourceOffsets.value)
    }
    @Test fun replacingVideoOrAutomaticMappingRejectsTheOldMarkerEvenForTheSameSource() = runTest {
        val repo = SyncAdjustmentRepository(backgroundScope); val marker = candidate()
        Session.commentSlots.value = listOf(marker.slot.copy(id = "jk2"))
        assertFalse(repo.alignMarker(marker, 0))
        Session.commentSlots.value = listOf(marker.slot.copy(syncOffset = 30_000))
        assertFalse(repo.alignMarker(marker, 0)); assertFalse(Session.sourceOffsets.value.containsKey(source))
    }
    @Test fun commentUpdatesRequireFreshAnalysisWhileUnchangedOtherSourcesDoNot() = runTest {
        val repo = SyncAdjustmentRepository(backgroundScope); val marker = candidate()
        Session.commentSlots.value += PlaybackCommentSlot("so1", "公式", PlaybackSource.OFFICIAL, emptyList())
        assertTrue(repo.alignMarker(marker, 0)); repo.undo()
        Session.commentSlots.value = Session.commentSlots.value.map { if (it.source == source) it.copy(comments = it.comments + Comment(130_000, "追加")) else it }
        assertFalse(repo.alignMarker(marker, 0)); assertTrue(repo.alignMarker(candidate(), 0))
    }
    @Test fun missingPositionOrChangedMatchedVideoDoesNotApplyOrReplacePendingUndo() = runTest {
        val repo = SyncAdjustmentRepository(backgroundScope); val marker = candidate()
        repo.global(1000); runCurrent(); val undo = repo.undo.value!!.id
        Session.playback.value = Session.playback.value.copy(position = -1)
        assertFalse(repo.alignMarker(marker, 0))
        Session.playback.value = Session.playback.value.copy(position = 120_000); Session.matchedKey = "replacement"
        assertFalse(repo.alignMarker(marker, 0)); runCurrent(); assertEquals(undo, repo.undo.value?.id)
    }
}
