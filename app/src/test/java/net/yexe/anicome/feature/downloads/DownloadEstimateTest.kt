package net.yexe.anicome

import androidx.work.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import android.content.ContextWrapper

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DownloadEstimateTest {
    private val spec = ReservationSpec(Episode("作品", 1), amount = 20, sources = setOf(PlaybackSource.OFFICIAL))
    private fun history(milliseconds: Long, series: String = "作品", amount: Int = 20, fresh: Boolean = true, state: String = "SUCCEEDED", sources: Set<PlaybackSource> = setOf(PlaybackSource.OFFICIAL), time: Long = milliseconds) =
        ReservationEntry(UUID.randomUUID().toString(), "history", series, 1, spec.copy(episode = Episode(series, 1), sources = sources, amount = amount).encode(), state, time, time,
            output = reservationOutput(workDataOf("elapsedMs" to milliseconds, "amount" to amount, "downloadedNew" to fresh)))
    @Test fun progressNeedsEnoughSamplesAndNeverClaimsZeroOrAnUnknownTarget() {
        val estimate = DownloadProgressEstimator()
        assertNull(estimate.remaining("official", 0, 1000, 0))
        assertNull(estimate.remaining("official", 10, 1000, 500))
        assertEquals(18_000L, estimate.remaining("official", 100, 1000, 2000))
        assertNull(estimate.remaining("official", 1000, 1000, 4000))
        assertNull(estimate.remaining("unknown", 10, 0, 4000))
    }
    @Test fun switchingSourceTargetOrPositionRestartsTheMeasurement() {
        val estimate = DownloadProgressEstimator()
        estimate.remaining("official", 0, 1000, 0)
        assertEquals(18_000L, estimate.remaining("official", 100, 1000, 2000))
        assertNull(estimate.remaining("danime", 100, 1000, 2000))
        assertNull(estimate.remaining("official", 100, 2000, 3000))
        assertEquals(36_000L, estimate.remaining("official", 200, 2000, 5000))
        assertNull(estimate.remaining("official", 50, 2000, 6000))
        assertNull(estimate.remaining("official", 100, 2000, 1000))
    }
    @Test fun cachedFailuresOtherAmountsAndDifferentSourcesDoNotDistortTheEstimate() {
        val rows = listOf(history(10_000), history(11_000), history(90_000), history(1000, fresh = false), history(1000, state = "FAILED"), history(1000, amount = 1), history(1000, sources = setOf(PlaybackSource.DANIME)))
        assertEquals(11_000L, ReservationDurationHistory(rows).estimate(spec))
        assertNull(ReservationDurationHistory(rows).estimate(spec.copy(amount = 0)))
        assertNull(ReservationDurationHistory(rows).estimate(spec.copy(sources = setOf(PlaybackSource.LIVE_ARCHIVE))))
        assertNull(ReservationDurationHistory(listOf(history(0), history(100, fresh = false))).estimate(spec))
    }
    @Test fun sameWorkHistoryIsPreferredAndOldMeasurementsAreBounded() {
        val rows = (1..40).map { history(it * 1000L, series = "別作品", time = it.toLong()) } + history(55_000, time = 100)
        assertEquals(55_000L, ReservationDurationHistory(rows).estimate(spec))
        assertEquals(26_000L, ReservationDurationHistory(rows.dropLast(1)).estimate(spec.copy(episode = Episode("未取得の作品", 1))))
    }
    @Test fun sizeUsesSavedAmountAndSourceCountAndHasASensibleInitialEstimate() {
        val samples = listOf(SavedEpisode(Episode("作品", 1), "so1", "title", 0, 1000, 8_388_608L, amount = 4, sources = "OFFICIAL,DANIME"))
        assertEquals(6_291_456L, estimatedCommentBytes(samples, 2, 3))
        assertEquals(1_048_576L, estimatedCommentBytes(emptyList(), 1, 1))
        assertEquals(MAX_DOWNLOAD_ESTIMATE, estimatedCommentBytes(listOf(samples.single().copy(bytes = Long.MAX_VALUE, amount = 1, sources = "OFFICIAL")), 20, 7))
    }
    @Test fun progressAndHistoryLabelsDistinguishOverallPhaseWaitingAndPausedStates() {
        fun work(state: WorkInfo.State = WorkInfo.State.RUNNING, paused: Boolean = false, elapsed: Long = 20_000, duration: Long = 60_000, phase: Long = 10_000) = WorkInfo(UUID.randomUUID(), state, emptySet(), progress = workDataOf("reservationDurationMs" to duration, "downloadElapsedMs" to elapsed, "phaseRemainingMs" to phase, "reservationPaused" to paused))
        assertEquals("保存完了までの目安：約40秒", reservationTimeLabel(work()))
        assertEquals("現在の取得元の残り目安：約10秒", reservationTimeLabel(work(elapsed = 80_000)))
        assertEquals("残り時間を計測中", reservationTimeLabel(work(duration = 0, phase = 0)))
        assertEquals("保存時間の目安：約1分", reservationTimeLabel(work(state = WorkInfo.State.ENQUEUED)))
        assertNull(reservationTimeLabel(work(paused = true)))
        assertNull(reservationTimeLabel(work(state = WorkInfo.State.FAILED)))
    }
    @Test fun newAndLegacyReservationsAndResultsRemainReadable() {
        val legacy = ReservationSpec.fromTags(setOf("series:作品", "episode:13", "manual"), true, false)!!
        assertEquals(0, ReservationSpec.decode(legacy.encode()).amount)
        assertEquals(0L, ReservationSpec.decode(legacy.encode()).estimatedBytes)
        val prepared = spec.copy(estimatedBytes = 5_000_000L)
        assertEquals(prepared.input(), ReservationSpec.decode(prepared.encode()).input())
        val output = workDataOf("elapsedMs" to 30_000L, "bytes" to 5_000_000L, "amount" to 20, "downloadedNew" to true, "message" to "saved")
        assertEquals(output, readReservationOutput(reservationOutput(output)))
        assertEquals(Data.EMPTY, readReservationOutput(""))
    }
    @Test fun preparedAmountAndSizeSurviveChangingTheDefaultAndPausing() = runTest {
        val index = MemoryReservations(); val queue = ReservationTransport()
        val settings = SettingsRepository(MemoryPreferences(), backgroundScope)
        settings.putInt("commentAmount", 20); runCurrent()
        val scheduler = ReservationScheduler(index, queue, backgroundScope, estimateBytes = { 5_000_000L })
        val repository = DownloadRepository(ContextWrapper(null), settings, queue, scheduler, backgroundScope)
        repository.reserve(spec.copy(amount = 0))
        val id = index.all().single().id
        scheduler.pause(setOf(id))
        settings.putInt("commentAmount", 1); runCurrent()
        scheduler.resume(setOf(id))
        val request = queue.enqueued.last().first
        assertEquals(20, request.amount); assertEquals(5_000_000L, request.estimatedBytes)
        val displayed = repository.work.first().single()
        assertEquals(5_000_000L, displayed.progress.getLong("reservationBytes", 0))
    }
    @Test fun completedOutputFeedsTheNextRequestAndIgnoresArchiveOnlySaves() = runTest {
        val index = MemoryReservations(); val queue = ReservationTransport()
        val scheduler = ReservationScheduler(index, queue, backgroundScope, parallelism = 1)
        scheduler.submit(spec)
        scheduler.finished(queue.enqueued.last().second, WorkInfo.State.SUCCEEDED, workDataOf("elapsedMs" to 30_000L, "amount" to 20, "downloadedNew" to true))
        scheduler.submit(spec.copy(episode = Episode("作品", 2)))
        assertEquals(30_000L, scheduler.present(index.all(), queue.all()).single { !it.state.isFinished }.progress.getLong("reservationDurationMs", 0))
        scheduler.finished(queue.enqueued.last().second, WorkInfo.State.SUCCEEDED, workDataOf("elapsedMs" to 1000L, "amount" to 20, "downloadedNew" to false))
        scheduler.submit(spec.copy(episode = Episode("作品", 3)))
        assertEquals(30_000L, scheduler.present(index.all(), queue.all()).single { !it.state.isFinished }.progress.getLong("reservationDurationMs", 0))
    }
}
