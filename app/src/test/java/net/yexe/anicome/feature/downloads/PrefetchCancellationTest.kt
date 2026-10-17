package net.yexe.anicome

import android.content.ContextWrapper
import androidx.work.WorkInfo
import java.util.UUID
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PrefetchCancellationTest {
    @Test fun disablingATitleCancelsAndHidesOnlyItsActivePrefetchReservations() = runTest {
        fun work(title: String, origin: String, state: WorkInfo.State = WorkInfo.State.ENQUEUED) = WorkInfo(UUID.randomUUID(), state, setOf("series:$title", "episode:1", origin))
        val selected = work("作品 第2期", "prefetch")
        val running = work("作品 第2期", "prefetch", WorkInfo.State.RUNNING)
        val manual = work("作品 第2期", "manual")
        val currentPlayback = work("作品 第2期", "playback")
        val otherSeason = work("作品 第1期", "prefetch")
        val otherCour = work("作品 第2期 第2クール", "prefetch")
        val completed = work("作品 第2期", "prefetch", WorkInfo.State.SUCCEEDED)
        val items = listOf(selected, running, manual, currentPlayback, otherSeason, otherCour, completed)
        val cancelled = mutableListOf<UUID>()
        val queue = object : DownloadQueue {
            override fun observe() = flowOf(items)
            override suspend fun all() = items
            override suspend fun cancel(id: UUID) { cancelled += id }
            override suspend fun cancelAll() = error("must not cancel everything")
        }
        val repository = DownloadRepository(ContextWrapper(null), SettingsRepository(MemoryPreferences(), backgroundScope), queue)
        repository.cancelPrefetch("作品 2期")
        assertEquals(setOf(selected.id, running.id), cancelled.toSet())
        val remaining = repository.work.first().map { it.id }.toSet()
        assertFalse(selected.id in remaining); assertFalse(running.id in remaining)
        assertTrue(remaining.containsAll(listOf(manual, currentPlayback, otherSeason, otherCour, completed).map { it.id }))
    }
    @Test fun workersSkipOnlyPrefetchForTheDisabledSeason() = runTest {
        val settings = SettingsRepository(MemoryPreferences(), backgroundScope)
        settings.putBoolean(prefetchWorkKey("作品 第2期"), true)
        runCurrent()
        val prefs = settings.load()
        assertTrue(DownloadWorker.skipPrefetch("prefetch", "作品 2期", prefs))
        assertFalse(DownloadWorker.skipPrefetch("manual", "作品 第2期", prefs))
        assertFalse(DownloadWorker.skipPrefetch("playback", "作品 第2期", prefs))
        assertFalse(DownloadWorker.skipPrefetch("prefetch", "作品 第1期", prefs))
    }
}
