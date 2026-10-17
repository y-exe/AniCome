package net.yexe.anicome

import androidx.work.WorkInfo
import androidx.work.workDataOf
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class FailureActionUiTest : InteractionFixture() {
    @Test fun authenticationFailureOpensNiconicoSettingsAndKeepsRetryAndDelete() {
        var page = ""; var retries = 0; var deletions = 0
        val work = WorkInfo(UUID.randomUUID(), WorkInfo.State.FAILED, setOf("series:作品", "episode:1"), outputData = workDataOf("failureKind" to "ACCESS", "message" to "取得失敗（HTTP 403）"))
        val view = fixture { ReservationRow(work, { retries++ }, { deletions++ }, { page = it }) }
        try {
            paparazzi.snapshot(view, "failure_access")
            click(view, "設定を確認"); assertEquals("コメント取得", page)
            click(view, "再試行"); click(view, "削除")
            assertEquals(1, retries); assertEquals(1, deletions)
        } finally { view.disposeComposition() }
    }
    @Test fun missingVideosOfferDirectChannelSelection() {
        var channels = 0
        val work = WorkInfo(UUID.randomUUID(), WorkInfo.State.FAILED, setOf("series:作品", "episode:1"), outputData = workDataOf("failureKind" to "NOT_FOUND", "message" to "動画が見つかりません"))
        val view = fixture { ReservationRow(work, {}, {}, channel = { channels++ }) }
        try { paparazzi.snapshot(view, "failure_missing"); click(view, "チャンネルを指定"); assertEquals(1, channels) }
        finally { view.disposeComposition() }
    }
}
