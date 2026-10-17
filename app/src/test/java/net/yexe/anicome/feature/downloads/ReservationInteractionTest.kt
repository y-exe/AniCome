package net.yexe.anicome

import androidx.compose.runtime.*
import androidx.work.*
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class ReservationInteractionTest : InteractionFixture() {
    @Test fun libraryBulkControlsUseOnlyTheAppropriateVisibleReservations() {
        fun item(number: Int, state: WorkInfo.State, paused: Boolean = false) = WorkInfo(UUID.randomUUID(), state, setOf("series:作品", "episode:$number", "label:作品 第${number}話"), progress = workDataOf("reservationManaged" to true, "reservationPaused" to paused, "reservationOrder" to if (state == WorkInfo.State.RUNNING) 0 else number))
        val running = item(1, WorkInfo.State.RUNNING); val waiting = item(2, WorkInfo.State.ENQUEUED); val paused = item(3, WorkInfo.State.ENQUEUED, true)
        var pauseIds = emptySet<UUID>(); var resumeIds = emptySet<UUID>()
        val controller = AppCoordinator()
        val view = fixture { LibraryScreen(controller, emptyList(), Playback(), listOf(running, waiting, paused), pauseReservations = { pauseIds = it }, resumeReservations = { resumeIds = it }, cancelWork = {}) }
        var finished = false
        view.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewDetachedFromWindow(v: android.view.View) {}
            override fun onViewAttachedToWindow(v: android.view.View) {
                v.postDelayed({ click(view, "保存予約") }, 300)
                v.postDelayed({ click(view, "すべて一時停止"); assertEquals(setOf(running.id, waiting.id), pauseIds) }, 1000)
                v.postDelayed({ click(view, "停止中の予約を再開"); assertEquals(setOf(paused.id), resumeIds); finished = true }, 1400)
            }
        })
        try {
            paparazzi.gif(view, "library_queue_controls", end = 1700, fps = 10)
            assertTrue(finished)
        } finally { view.disposeComposition() }
    }
    @Test fun pausedRowOffersResumeAndCancelAsSeparateActions() {
        var resumed = false; var removed = false; var retried = false
        val work = WorkInfo(UUID.randomUUID(), WorkInfo.State.ENQUEUED, setOf("series:作品", "episode:13"), progress = workDataOf("reservationManaged" to true, "reservationPaused" to true))
        val view = fixture { ReservationRow(work, { retried = true }, { removed = true }, resume = { resumed = true }) }
        try {
            paparazzi.snapshot(view, "reservation_paused")
            assertTrue(texts(view).contains("一時停止中"))
            click(view, "再開"); assertTrue(resumed); assertFalse(removed); assertFalse(retried)
            click(view, "キャンセル"); assertTrue(removed)
        } finally { view.disposeComposition() }
    }
    @Test fun priorityButtonsIdentifyTheEpisodeAndMoveTheSelectedReservation() {
        fun item(number: Int, rank: Int) = WorkInfo(UUID.randomUUID(), WorkInfo.State.ENQUEUED, setOf("series:作品", "episode:$number"), progress = workDataOf("reservationManaged" to true, "reservationOrder" to rank + 1, "reservationRank" to rank.toLong()))
        val one = item(13, 0); val two = item(14, 1)
        var changed: Pair<UUID, String>? = null
        val view = fixture { ReservationOrderContent(listOf(one, two)) { id, direction -> changed = id to direction } }
        try {
            paparazzi.snapshot(view, "reservation_order")
            click(view, "1つ上へ移動: 作品 第14話")
            assertEquals(two.id to "up", changed)
        } finally { view.disposeComposition() }
    }
    @Test fun actualWorkRequestPreservesItsConstraintsVideoAndStableIdentity() {
        val episode = Episode("作品 第2クール", 1)
        val spec = ReservationSpec(episode, origin = "prefetch", wifi = true, charging = true, channel = 123L, amount = 20, estimatedBytes = 5_000_000L, sources = setOf(PlaybackSource.OFFICIAL, PlaybackSource.DANIME),
            reviewed = SaveEpisodeReview(episode, Episode("作品", 13), "新しい旅", Candidate("so13", "作品 第13話")))
        val id = UUID.randomUUID()
        val request = ReservationSpec.decode(spec.encode()).request(id, "logical-id")
        assertEquals(id, request.id)
        assertEquals("so13", request.workSpec.input.getString("reviewVideo"))
        assertEquals(13, request.workSpec.input.getInt("reviewNumber", 0))
        assertEquals(1, request.workSpec.input.getInt("number", 0))
        assertEquals("prefetch", request.workSpec.input.getString("origin"))
        assertEquals(20, request.workSpec.input.getInt("amount", 0))
        assertEquals(5_000_000L, request.workSpec.input.getLong("estimatedBytes", 0))
        assertEquals(NetworkType.UNMETERED, request.workSpec.constraints.requiredNetworkType)
        assertTrue(request.workSpec.constraints.requiresCharging())
        assertTrue("managed-reservation:logical-id" in request.tags)
    }
    @Test fun estimateRowShowsTheOverallTimeAndCapacityAndKeepsPauseAvailable() {
        var paused = false
        val work = WorkInfo(UUID.randomUUID(), WorkInfo.State.RUNNING, setOf("series:作品", "episode:1"), progress = workDataOf("reservationManaged" to true, "reservationBytes" to 5_242_880L, "reservationDurationMs" to 60_000L, "downloadElapsedMs" to 20_000L, "current" to 1000, "target" to 20000))
        val view = fixture { ReservationRow(work, {}, {}, pause = { paused = true }) }
        try {
            paparazzi.snapshot(view, "reservation_estimate")
            assertTrue(texts(view).contains("保存完了までの目安：約40秒"))
            assertTrue(texts(view).contains("保存容量の目安：5.0 MB"))
            click(view, "一時停止"); assertTrue(paused)
        } finally { view.disposeComposition() }
    }
}
