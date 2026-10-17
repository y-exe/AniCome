package net.yexe.anicome

import org.junit.Assert.*
import org.junit.Test

class MissingSourceBackoffTest {
    @Test fun noResultsBackOffPerEpisodeAndSourceAndExplicitRetryClearsTheDelay() {
        var now = 1000L
        val policy = MissingSourceBackoff({ now }, 100L)
        policy.record("work:1", PlaybackSource.DANIME)
        assertTrue(policy.blocked("work:1", PlaybackSource.DANIME))
        assertFalse(policy.blocked("work:2", PlaybackSource.DANIME))
        assertFalse(policy.blocked("work:1", PlaybackSource.LIVE_ARCHIVE))
        now += 99L; assertTrue(policy.blocked("work:1", PlaybackSource.DANIME))
        now++; assertFalse(policy.blocked("work:1", PlaybackSource.DANIME))
        policy.record("work:1", PlaybackSource.DANIME); policy.clear()
        assertFalse(policy.blocked("work:1", PlaybackSource.DANIME))
    }
    @Test fun recordsAreBoundedAndClockResetDoesNotBlockRequests() {
        var now = 1000L
        val policy = MissingSourceBackoff({ now })
        repeat(257) { policy.record(it.toString(), PlaybackSource.JIKKYO) }
        assertFalse(policy.blocked("0", PlaybackSource.JIKKYO))
        assertTrue(policy.blocked("256", PlaybackSource.JIKKYO))
        now = 1L
        assertFalse(policy.blocked("256", PlaybackSource.JIKKYO))
    }
    @Test fun officialNoResultsBackOffButManualRetryAndArchivesBypassTheDelay() = kotlinx.coroutines.test.runTest {
        val root = java.nio.file.Files.createTempDirectory("anicome-official-backoff").toFile()
        try {
            var requests = 0
            val store = CommentStore(root)
            val repository = CommentRepository(store, search = { requests++; emptyList() }, snapshotLoader = { _, _, _, _, _ -> requests++; emptyList() })
            val episode = Episode("作品", 1)
            suspend fun unavailable(force: Boolean = false) {
                try { repository.obtain(episode, forceSearch = force); fail() } catch (_: OfficialMatchUnavailable) { }
            }
            unavailable(); val first = requests; assertTrue(first > 1)
            unavailable(); assertEquals(first, requests)
            unavailable(true); assertTrue(requests > first)
            store.save(episode, Candidate("so970001", "作品 第1話", channelId = 10), listOf(Comment(0, "cached")))
            assertTrue(repository.obtain(episode).fromArchive)
            repository.clearMissing(); assertNotNull(repository.cached(episode))
        } finally { root.deleteRecursively() }
    }
    @Test fun failedOfficialRequestsAreNotRecordedAsMissingAndCancellationIsPreserved() = kotlinx.coroutines.test.runTest {
        val root = java.nio.file.Files.createTempDirectory("anicome-official-network").toFile()
        try {
            var requests = 0
            val repository = CommentRepository(CommentStore(root), search = { requests++; throw java.io.IOException("offline") },
                snapshotLoader = { _, _, _, _, _ -> requests++; throw java.io.IOException("offline") })
            suspend fun attempt() { try { repository.obtain(Episode("作品", 1)); fail() } catch (_: java.io.IOException) { } }
            attempt(); val first = requests; attempt(); assertTrue(requests > first)
            val cancelled = CommentRepository(CommentStore(root), search = { throw kotlinx.coroutines.CancellationException() })
            try { cancelled.obtain(Episode("別作品", 1)); fail() } catch (_: kotlinx.coroutines.CancellationException) { }
        } finally { root.deleteRecursively() }
    }

}
