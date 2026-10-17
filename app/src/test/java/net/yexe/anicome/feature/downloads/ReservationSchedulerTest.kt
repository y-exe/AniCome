package net.yexe.anicome

import android.content.ContextWrapper
import androidx.work.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.nio.file.Files

internal class MemoryReservations : ReservationIndex {
    val rows = MutableStateFlow<List<ReservationEntry>>(emptyList())
    override fun all() = rows.value.sortedWith(compareBy<ReservationEntry> { it.rank }.thenBy { it.createdAt }.thenBy { it.id })
    override fun observe() = rows
    override fun put(entries: List<ReservationEntry>) { rows.value = rows.value.associateByTo(linkedMapOf()) { it.id }.apply { entries.forEach { put(it.id, it) } }.values.toList() }
}
internal class ReservationTransport : DownloadQueue {
    val works = MutableStateFlow<List<WorkInfo>>(emptyList())
    val environment = MutableStateFlow(DownloadConditions())
    val enqueued = mutableListOf<Pair<ReservationSpec, UUID>>()
    val cancelled = mutableListOf<UUID>()
    var watched = emptyList<ReservationSpec>()
    var budgetResetAt = 0L
    override suspend fun watchBudget(resetsAt: Long) { budgetResetAt = resetsAt }
    override fun observe() = works
    override suspend fun all() = works.value
    override fun conditions() = environment
    override suspend fun currentConditions() = environment.value
    override suspend fun watchConditions(specs: List<ReservationSpec>) { watched = specs }
    override suspend fun cancel(id: UUID) { cancelled += id; works.value = works.value.map { if (it.id == id) WorkInfo(it.id, WorkInfo.State.CANCELLED, it.tags, constraints = it.constraints) else it } }
    override suspend fun cancelAll() { works.value.forEach { cancel(it.id) } }
    override suspend fun enqueue(spec: ReservationSpec, workId: UUID, reservation: String) {
        enqueued += spec to workId
        works.value += WorkInfo(workId, WorkInfo.State.ENQUEUED, spec.tags() + "managed-reservation:$reservation", constraints = Constraints.Builder().setRequiredNetworkType(if (spec.wifi) NetworkType.UNMETERED else NetworkType.CONNECTED).setRequiresCharging(spec.charging).build())
    }
}
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReservationSchedulerTest {
    @Test fun trafficLimitKeepsReservationsQueuedAndRestartsThemInOrderAfterReset() = runTest {
        val index = MemoryReservations(); val queue = ReservationTransport()
        var allowed = false
        val scheduler = ReservationScheduler(index, queue, backgroundScope, parallelism = 1, transferAllowance = { TransferAllowance(allowed, 123456L) })
        val one = scheduler.submit(spec(1)); val two = scheduler.submit(spec(2))
        assertTrue(queue.enqueued.isEmpty()); assertEquals(123456L, queue.budgetResetAt)
        assertTrue(scheduler.present(index.all(), queue.all()).all { it.progress.getBoolean("reservationTrafficBlocked", false) })
        scheduler.pause(setOf(one)); scheduler.reorder(two, "first")
        allowed = true; scheduler.pump()
        assertEquals(spec(2).input(), queue.enqueued.single().first.input())
        assertEquals(0L, queue.budgetResetAt)
        val run = queue.enqueued.single().second
        allowed = false
        scheduler.finished(run, WorkInfo.State.FAILED, acquisitionFailureOutput(TrafficLimitReached()))
        assertEquals("QUEUED", index.all().single { it.id == two }.state)
        assertEquals("PAUSED", index.all().single { it.id == one }.state)
        scheduler.finished(run, WorkInfo.State.SUCCEEDED, androidx.work.Data.EMPTY)
        assertEquals("QUEUED", index.all().single { it.id == two }.state)
        val restarted = ReservationScheduler(index, queue, backgroundScope, parallelism = 1, transferAllowance = { TransferAllowance(allowed, 123456L) })
        restarted.pump(); assertEquals(1, queue.enqueued.size)
        allowed = true; restarted.pump()
        assertEquals(2, queue.enqueued.size); assertNotEquals(run, queue.enqueued.last().second)
        assertEquals(spec(2).input(), queue.enqueued.last().first.input())
    }
    private fun spec(number: Int, wifi: Boolean = false, charging: Boolean = false) = ReservationSpec(Episode("作品 第2クール", number), wifi = wifi, charging = charging,
        catalog = AnimeInfo(10, "作品 第2クール", "https://example.com/poster.jpg", "", null, 2026, 12, emptyList(), "jikan"), channel = 123L,
        sources = setOf(PlaybackSource.OFFICIAL, PlaybackSource.DANIME), reviewed = SaveEpisodeReview(Episode("作品 第2クール", number), Episode("作品", number + 12), "各話のタイトル", Candidate("so${number+12}", "作品 第${number+12}話")))

    @Test fun pausedReservationSurvivesRestartAndRetainsTheConfirmedVideoAndConstraints() = runTest {
        val index = MemoryReservations(); val queue = ReservationTransport()
        val scheduler = ReservationScheduler(index, queue, backgroundScope, parallelism = 1)
        val original = spec(1, wifi = true, charging = true)
        val id = scheduler.submit(original); val firstRun = queue.enqueued.single().second
        scheduler.pause(setOf(id))
        assertTrue(firstRun in queue.cancelled); assertEquals("PAUSED", index.all().single().state)
        assertFalse(scheduler.canRun(firstRun, original.name))
        val restored = ReservationScheduler(index, queue, backgroundScope, parallelism = 1)
        restored.pump(); assertEquals(1, queue.enqueued.size)
        val paused = restored.present(index.all(), queue.all()).single()
        assertTrue(reservationPaused(paused)); assertEquals(13, paused.progress.getInt("sourceNumber", 0))
        restored.resume(setOf(id))
        val next = queue.enqueued.last()
        assertNotEquals(firstRun, next.second)
        assertEquals(original.input(), next.first.input())
        assertEquals(original.tags(), next.first.tags()); assertTrue(next.first.wifi); assertTrue(next.first.charging)
        assertTrue(restored.canRun(next.second, original.name)); assertFalse(restored.canRun(firstRun, original.name))
        restored.finished(firstRun, WorkInfo.State.SUCCEEDED, workDataOf("message" to "old result"))
        assertEquals("ACTIVE", index.all().single().state)
        restored.resume(setOf(id)); assertEquals(2, queue.enqueued.size)
    }
    @Test fun reorderingChangesTheNextActualDownloadAndNeverInterruptsTheActiveOne() = runTest {
        val index = MemoryReservations(); val queue = ReservationTransport()
        val scheduler = ReservationScheduler(index, queue, backgroundScope, parallelism = 1)
        val first = scheduler.submit(spec(1)); val second = scheduler.submit(spec(2)); val third = scheduler.submit(spec(3))
        val active = queue.enqueued.single().second
        scheduler.reorder(third, "first")
        assertEquals(1, queue.enqueued.size); assertFalse(active in queue.cancelled)
        val displayed = scheduler.present(index.all(), queue.all())
        assertEquals(1, displayed.single { it.id.toString() == third }.progress.getInt("reservationOrder", 0))
        scheduler.finished(active, WorkInfo.State.SUCCEEDED, workDataOf("message" to "saved"))
        assertEquals(spec(3).episode, queue.enqueued.last().first.episode)
        val restarted = ReservationScheduler(index, queue, backgroundScope, parallelism = 1)
        restarted.finished(queue.enqueued.last().second, WorkInfo.State.SUCCEEDED, Data.EMPTY)
        assertEquals(spec(2).episode, queue.enqueued.last().first.episode)
        assertEquals("SUCCEEDED", index.all().single { it.id == first }.state)
        assertEquals("ACTIVE", index.all().single { it.id == second }.state)
    }
    @Test fun pauseAndCancelOnlyApplyToTheCapturedIdsAndNeverModifySavedComments() = runTest {
        val directory = Files.createTempDirectory("anicome-queue-preserve").toFile()
        try {
            val comments = CommentStore(directory)
            comments.save(spec(1).episode, Candidate("so13", "作品 第13話"), listOf(Comment(100, " /\\\n / \\", """{"commands":["ue","gothic"]}""", "owner")))
            val bytes = directory.listFiles()!!.single().readBytes()
            val index = MemoryReservations(); val queue = ReservationTransport()
            val scheduler = ReservationScheduler(index, queue, backgroundScope, parallelism = 1)
            val first = scheduler.submit(spec(1)); val second = scheduler.submit(spec(2))
            scheduler.pause(setOf(first, second))
            val later = scheduler.submit(spec(3))
            scheduler.cancel(setOf(first, second))
            assertEquals("ACTIVE", index.all().single { it.id == later }.state)
            assertTrue(index.all().filter { it.id in setOf(first, second) }.all { it.state == "CANCELLED" })
            assertArrayEquals(bytes, directory.listFiles()!!.single().readBytes())
        } finally { directory.deleteRecursively() }
    }
    @Test fun duplicateSubmissionKeepsAPausedReservationAndManualAndCacheDestinationsAreSeparate() = runTest {
        val index = MemoryReservations(); val queue = ReservationTransport()
        val scheduler = ReservationScheduler(index, queue, backgroundScope)
        val manual = scheduler.submit(spec(1)); scheduler.pause(setOf(manual))
        assertEquals(manual, scheduler.submit(spec(1).copy(sources = setOf(PlaybackSource.OFFICIAL))))
        assertEquals("PAUSED", index.all().single().state)
        val cache = scheduler.submit(spec(1).copy(origin = "prefetch"))
        assertNotEquals(manual, cache); assertEquals(2, index.all().size)
    }
    @Test fun networkAndChargingRequirementsDoNotBlockOtherEligibleDownloads() = runTest {
        val index = MemoryReservations(); val queue = ReservationTransport()
        queue.environment.value = DownloadConditions(connected = true, metered = true, charging = false)
        val scheduler = ReservationScheduler(index, queue, backgroundScope, parallelism = 1)
        val wifi = scheduler.submit(spec(1, wifi = true)); val charging = scheduler.submit(spec(2, charging = true)); scheduler.submit(spec(3))
        assertEquals(spec(3).episode, queue.enqueued.single().first.episode)
        assertEquals(setOf(spec(1, wifi = true), spec(2, charging = true)).map { it.episode }.toSet(), queue.watched.map { it.episode }.toSet())
        queue.environment.value = DownloadConditions()
        scheduler.finished(queue.enqueued.single().second, WorkInfo.State.SUCCEEDED, Data.EMPTY)
        assertEquals("ACTIVE", index.all().single { it.id == wifi }.state)
        assertEquals("QUEUED", index.all().single { it.id == charging }.state)
    }
    @Test fun lossOfARequiredConnectionReleasesTheSlotAndRestartsWithTheSameRequest() = runTest {
        val index = MemoryReservations(); val queue = ReservationTransport()
        val scheduler = ReservationScheduler(index, queue, backgroundScope, parallelism = 1)
        val wifi = scheduler.submit(spec(1, wifi = true)); val run = queue.enqueued.single().second
        scheduler.submit(spec(2))
        queue.environment.value = DownloadConditions(metered = true)
        scheduler.pump()
        assertTrue(run in queue.cancelled)
        assertEquals("QUEUED", index.all().single { it.id == wifi }.state)
        assertEquals(spec(2).episode, queue.enqueued.last().first.episode)
        queue.environment.value = DownloadConditions()
        scheduler.finished(queue.enqueued.last().second, WorkInfo.State.SUCCEEDED, Data.EMPTY)
        assertEquals(spec(1, wifi = true).input(), queue.enqueued.last().first.input())
    }
    @Test fun legacyWorkIsAdoptedWithItsReviewedEpisodeAndNeverAppearsTwice() = runTest {
        val index = MemoryReservations(); val queue = ReservationTransport()
        val legacy = spec(1, wifi = true, charging = true)
        val old = UUID.randomUUID()
        queue.works.value = listOf(WorkInfo(old, WorkInfo.State.ENQUEUED, legacy.tags(), constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresCharging(true).build()))
        val scheduler = ReservationScheduler(index, queue, backgroundScope, parallelism = 1)
        scheduler.pump()
        assertTrue(old in queue.cancelled)
        assertEquals(legacy.input(), queue.enqueued.single().first.input())
        assertEquals(1, scheduler.present(index.all(), queue.all()).size)
        scheduler.pause(setOf(old.toString())); scheduler.resume(setOf(old.toString()))
        assertEquals(1, scheduler.present(index.all(), queue.all()).size)
        assertFalse(scheduler.canRun(old, legacy.name))
    }
    @Test fun missingWorkManagerRequestIsRecoveredAndFailureRetryKeepsItsErrorAndSelection() = runTest {
        val index = MemoryReservations(); val queue = ReservationTransport()
        val scheduler = ReservationScheduler(index, queue, backgroundScope, parallelism = 1)
        val id = scheduler.submit(spec(1)); val lost = queue.enqueued.single().second
        queue.works.value = emptyList()
        ReservationScheduler(index, queue, backgroundScope, parallelism = 1).pump()
        val actual = queue.enqueued.last().second
        assertNotEquals(lost, actual)
        scheduler.finished(actual, WorkInfo.State.FAILED, workDataOf("message" to "HTTP 403", "failureKind" to "ACCESS"))
        val failure = scheduler.present(index.all(), queue.all()).single()
        assertEquals(WorkInfo.State.FAILED, failure.state); assertEquals("ACCESS", failure.outputData.getString("failureKind"))
        scheduler.retry(id)
        assertEquals(spec(1).input(), queue.enqueued.last().first.input())
        assertEquals("ACTIVE", index.all().single().state)
        assertEquals(1, scheduler.present(index.all(), queue.all()).size)
    }
    @Test fun bulkCancellationIncludesPausedAndFailedRecordsAndPreservesSucceededOnes() = runTest {
        val index = MemoryReservations(); val queue = ReservationTransport()
        val scheduler = ReservationScheduler(index, queue, backgroundScope, parallelism = 1)
        val done = scheduler.submit(spec(1)); scheduler.finished(queue.enqueued.last().second, WorkInfo.State.SUCCEEDED, Data.EMPTY)
        val failed = scheduler.submit(spec(2)); scheduler.finished(queue.enqueued.last().second, WorkInfo.State.FAILED, Data.EMPTY)
        val paused = scheduler.submit(spec(3)); scheduler.pause(setOf(paused))
        val repository = DownloadRepository(ContextWrapper(null), SettingsRepository(MemoryPreferences(), backgroundScope), queue, scheduler, backgroundScope)
        repository.cancelAll()
        assertEquals("SUCCEEDED", index.all().single { it.id == done }.state)
        assertTrue(index.all().filter { it.id in setOf(failed, paused) }.all { it.state == "CANCELLED" })
        assertTrue(repository.work.first().none { it.id.toString() in setOf(failed, paused) })
    }
    @Test fun monotonicInsertionKeepsTheLatestReservationVisibleEvenWhenTheClockMovesBackwards() = runTest {
        val index = MemoryReservations(); val queue = ReservationTransport()
        var now = 1000L
        val scheduler = ReservationScheduler(index, queue, backgroundScope, clock = { now })
        val first = scheduler.submit(spec(1)); scheduler.finished(queue.enqueued.last().second, WorkInfo.State.SUCCEEDED, Data.EMPTY)
        now = 0L
        val second = scheduler.submit(spec(1))
        assertNotEquals(first, second)
        assertEquals(second, scheduler.present(index.all(), queue.all()).single().id.toString())
    }
    @Test fun upDownAndLastMovesPersistTheCompleteWaitingOrder() = runTest {
        val index = MemoryReservations(); val queue = ReservationTransport()
        queue.environment.value = DownloadConditions(connected = false)
        val scheduler = ReservationScheduler(index, queue, backgroundScope)
        val ids = (1..4).map { scheduler.submit(spec(it)) }
        suspend fun order() = scheduler.present(index.all(), queue.all()).filter(::reservationWaiting).map { it.id.toString() }
        scheduler.reorder(ids[2], "up"); assertEquals(listOf(ids[0], ids[2], ids[1], ids[3]), order())
        scheduler.reorder(ids[0], "down"); assertEquals(listOf(ids[2], ids[0], ids[1], ids[3]), order())
        scheduler.reorder(ids[2], "last"); assertEquals(listOf(ids[0], ids[1], ids[3], ids[2]), order())
        scheduler.pause(setOf(ids[1]))
        ReservationScheduler(index, queue, backgroundScope).pump()
        assertEquals(listOf(ids[0], ids[1], ids[3], ids[2]), order())
        assertTrue(queue.enqueued.isEmpty())
    }
}
