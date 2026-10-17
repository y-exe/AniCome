package net.yexe.anicome

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import org.junit.Assert.*
import org.junit.Test

class SyncStatusInteractionTest : InteractionFixture() {
    @Test fun allStatesHaveReadableExplanationsAndExcludedCommentsAreIdentified() {
        val reports = CommentSync.entries.map { PlaybackSource.JIKKYO to it }
        val view = fixture { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { CommentSyncStatus(reports) } }
        try {
            paparazzi.snapshot(view, "sync_status_all")
            val shown = texts(view)
            CommentSync.entries.forEach { sync ->
                assertTrue(shown.any { it.contains(sync.label) })
                assertTrue(shown.contains(commentSyncExplanation(sync)))
            }
            assertTrue(commentSyncExplanation(CommentSync.CUT).contains("推定"))
            assertTrue(commentSyncExplanation(CommentSync.START_ONLY).contains("未補正"))
            assertFalse(commentSyncExplanation(CommentSync.DISABLED).contains("同期済"))
        } finally { view.disposeComposition() }
        val missing = fixture { CommentSyncStatus(listOf(PlaybackSource.LIVE_ARCHIVE to CommentSync.UNKNOWN), unavailable = true) }
        try {
            paparazzi.snapshot(missing, "sync_status_excluded")
            assertTrue(texts(missing).contains("この同期状態のコメントは、現在の表示に含まれていません。"))
            assertTrue(texts(missing).any { it.contains(PlaybackSource.LIVE_ARCHIVE.label) })
        } finally { missing.disposeComposition() }
        var section = ""
        val attention = fixture { FloatingPanelContent(FloatingPanelState(syncReports = listOf(PlaybackSource.JIKKYO to CommentSync.UNKNOWN)), FloatingPanelOptions(compact = true), false,
            {}, {}, {}, {}, tools = { section = it }) }
        try {
            paparazzi.snapshot(attention, "floating_sync_attention")
            click(attention, "コメント表示の設定"); assertEquals("settings", section)
        } finally { attention.disposeComposition() }
        val locked = fixture { FloatingPanelContent(FloatingPanelState(locked = true, syncReports = listOf(PlaybackSource.JIKKYO to CommentSync.UNKNOWN)), FloatingPanelOptions(compact = true), false,
            {}, {}, {}, {}, tools = {}) }
        try {
            paparazzi.snapshot(locked, "floating_sync_locked")
            assertFalse(nodes(locked).any { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("同期状態を確認") == true })
        } finally { locked.disposeComposition() }
    }
}
