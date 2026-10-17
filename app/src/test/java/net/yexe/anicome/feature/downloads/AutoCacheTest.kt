package net.yexe.anicome

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.test.runTest

class AutoCacheTest {
    @Test fun automaticReservationsDoNotReplaceManualDownloadRequests() {
        val episode = Episode("作品", 1)
        assertFalse(DownloadWorker.automatic(null))
        assertFalse(DownloadWorker.automatic("manual"))
        listOf("playback", "notification", "prefetch").forEach { origin ->
            assertTrue(DownloadWorker.automatic(origin))
            assertNotEquals(DownloadWorker.name(episode), DownloadWorker.name(episode, origin))
        }
    }

    @Test fun expiryDeletesOnlyAutomaticSourcesAndStillReadsManualComments() {
        val root = Files.createTempDirectory("anicome-cache-expiry").toFile()
        try {
            var now = 1000L
            var ttl = 48 * 3_600_000L
            val manual = CommentStore(java.io.File(root, "manual"), clock = { now })
            val cache = CommentStore(java.io.File(root, "cache"), fallback = manual, expiresAfter = { ttl }, clock = { now })
            val episode = Episode("作品", 1)
            manual.save(episode, Candidate("so1", "作品 第1話"), listOf(Comment(1000, "official")))
            cache.saveSources(episode, listOf(PlaybackCommentSlot("so2", "作品 第1話", PlaybackSource.DANIME, listOf(Comment(2000, "danime")))))
            assertEquals(setOf("so1", "so2"), cache.read(episode)!!.slots.map { it.id }.toSet())
            assertEquals("DANIME", cache.ownList().single().sources)
            assertEquals("OFFICIAL", manual.list().single().sources)
            now += ttl - 1
            assertNotNull(cache.readOwn(episode))
            now++
            assertNull(cache.readOwn(episode))
            assertEquals("official", cache.read(episode)!!.comments.single().body)
            assertTrue(cache.ownList().isEmpty())
            assertEquals(1, manual.list().size)
            assertEquals("official", manual.read(episode)!!.comments.single().body)
            cache.save(episode, Candidate("so3", "作品 第1話"), listOf(Comment(3000, "cache")))
            ttl = 1
            now++
            cache.purgeExpired()
            assertNull(cache.readSource("so3"))
            assertNotNull(manual.readSource("so1"))
        } finally { root.deleteRecursively() }
    }
    @Test fun archivedManualSlotsAreNeverCopiedToAutomaticCache() {
        val root = Files.createTempDirectory("anicome-cache-manual").toFile()
        try {
            val manual = CommentStore(java.io.File(root, "manual"))
            val cache = CommentStore(java.io.File(root, "cache"), fallback = manual)
            val episode = Episode("作品", 1)
            manual.save(episode, Candidate("so1", "作品 第1話"), listOf(Comment(0, "official")))
            val slot = PlaybackCommentSlot("so1", "作品 第1話", PlaybackSource.OFFICIAL, listOf(Comment(0, "official")), fromArchive = true)
            cache.saveSources(episode, listOf(slot))
            assertTrue(cache.ownList().isEmpty())
            cache.saveSources(episode, listOf(slot, PlaybackCommentSlot("jk9:1-2", "作品 第1話", PlaybackSource.JIKKYO, listOf(Comment(0, "live")))))
            assertEquals(listOf("jk9:1-2"), cache.readOwn(episode)!!.slots.map { it.id })
            assertEquals(setOf("so1", "jk9:1-2"), cache.read(episode)!!.slots.map { it.id }.toSet())
            cache.delete(episode)
            assertNotNull(manual.read(episode))
        } finally { root.deleteRecursively() }
    }
    @Test fun manualDownloadReusesCacheWithoutNetworkAndNeverExpiresAfterPromotion() = runTest {
        val root = Files.createTempDirectory("anicome-cache-promote").toFile()
        try {
            var now = 1000L
            val manual = CommentStore(java.io.File(root, "manual"), clock = { now })
            val cache = CommentStore(java.io.File(root, "cache"), fallback = manual, expiresAfter = { 100L }, clock = { now })
            val episode = Episode("作品", 1)
            cache.save(episode, Candidate("so123", "作品 第1話"), listOf(Comment(1000, "cached")))
            cache.saveSources(episode, listOf(PlaybackCommentSlot("so456", "作品 第1話", PlaybackSource.DANIME, listOf(Comment(2000, "danime")))))
            val repo = CommentRepository(manual, search = { error("network search") }, load = { _, _ -> error("network load") }, reuse = cache)
            repo.promoteCached(episode)
            assertTrue(repo.obtain(episode).fromArchive)
            now += 101
            cache.purgeExpired()
            assertEquals(setOf("so123", "so456"), manual.read(episode)!!.slots.map { it.id }.toSet())
            assertEquals("cached", manual.read(episode)!!.comments.single().body)
        } finally { root.deleteRecursively() }
    }
    @Test fun manualSaveCanReuseAnAliasOfAnExistingManualArchiveWithoutCacheFile() = runTest {
        val root = Files.createTempDirectory("anicome-cache-alias").toFile()
        try {
            val manual = CommentStore(java.io.File(root, "manual"))
            val cache = CommentStore(java.io.File(root, "cache"), fallback = manual)
            val old = Episode("作品 第2クール", 2)
            manual.save(old, Candidate("so123", "作品 第13話"), listOf(Comment(1000, "manual")))
            val repo = CommentRepository(manual, search = { error("network search") }, load = { _, _ -> error("network load") }, reuse = cache)
            assertTrue(repo.obtain(Episode("作品", 13)).fromArchive)
            assertEquals("manual", manual.read(Episode("作品", 13))!!.comments.single().body)
            assertNotNull(manual.read(old))
            assertTrue(cache.ownList().isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun promotionRespectsSelectedSourcesAndUsesTheRicherCachedOfficialArchive() = runTest {
        val root = Files.createTempDirectory("anicome-cache-selected").toFile()
        try {
            val manual = CommentStore(java.io.File(root, "manual"))
            val cache = CommentStore(java.io.File(root, "cache"), fallback = manual)
            val episode = Episode("作品", 1)
            manual.save(episode, Candidate("so1", "作品 第1話"), listOf(Comment(0, "old")), amount = 1)
            val better = listOf(Comment(1000, "cached one"), Comment(2000, "cached two"))
            cache.save(episode, Candidate("so1", "作品 第1話"), better, amount = 2)
            cache.saveSources(episode, listOf(PlaybackCommentSlot("so2", "作品 第1話", PlaybackSource.DANIME, listOf(Comment(1000, "danime")))))
            val repository = CommentRepository(manual, search = { error("network search") }, load = { _, _ -> error("network fetch") }, reuse = cache)
            val result = repository.obtain(episode)
            assertTrue(result.fromArchive)
            assertEquals(2, result.info.amount)
            assertEquals(better, manual.read(episode)!!.comments)
            assertNull(manual.readSource("so2"))
            repository.promoteCached(episode, setOf(PlaybackSource.DANIME))
            assertNotNull(manual.readSource("so2"))
            assertEquals(better, manual.readSource("so1")!!.comments)
        } finally { root.deleteRecursively() }
    }

    @Test fun keepingCacheMovesAllSourcesAndNeverLosesManualSources() {
        val root = Files.createTempDirectory("anicome-cache-keep").toFile()
        try {
            var now = 1000L
            val manual = CommentStore(java.io.File(root, "manual"), clock = { now })
            val cache = CommentStore(java.io.File(root, "cache"), fallback = manual, expiresAfter = { 100L }, clock = { now })
            val episode = Episode("作品", 13)
            manual.save(episode, Candidate("so1", "作品 第13話"), listOf(Comment(0, "manual")))
            cache.saveSources(episode, listOf(PlaybackCommentSlot("so2", "作品 第13話", PlaybackSource.DANIME, listOf(Comment(0, "danime"))), PlaybackCommentSlot("lv1", "作品", PlaybackSource.LIVE_ARCHIVE, listOf(Comment(0, "live")))))
            cache.keepCached(episode)
            assertNull(cache.readOwn(episode))
            now += 1000
            assertEquals(setOf("so1", "so2", "lv1"), manual.read(episode)!!.slots.map { it.id }.toSet())
        } finally { root.deleteRecursively() }
    }
    @Test fun failedKeepLeavesCacheIntact() {
        val root = Files.createTempDirectory("anicome-cache-keep-failure").toFile()
        try {
            val manual = CommentStore(java.io.File(root, "manual"), capacity = { 1L })
            val cache = CommentStore(java.io.File(root, "cache"), fallback = manual)
            val episode = Episode("作品", 1)
            cache.save(episode, Candidate("so1", "作品 第1話"), listOf(Comment(0, "cached")))
            assertTrue(runCatching { cache.keepCached(episode) }.isFailure)
            assertNotNull(cache.readOwn(episode))
            assertTrue(manual.list().isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun automaticQuotaEvictsOldestCacheAndNeverManual() {
        val root = Files.createTempDirectory("anicome-cache-quota").toFile()
        try {
            var now = 1000L
            var limit = 0L
            val manual = CommentStore(java.io.File(root, "manual"), clock = { now })
            val cache = CommentStore(java.io.File(root, "cache"), fallback = manual, clock = { now }, automaticLimit = { limit })
            val first = Episode("作品", 1); val second = Episode("作品", 2)
            manual.save(first, Candidate("so9", "作品 第1話"), listOf(Comment(0, "manual")))
            cache.save(first, Candidate("so1", "作品 第1話"), listOf(Comment(0, "cache1")))
            val bytes = cache.ownList().single().bytes
            limit = bytes + 20L
            now++
            cache.save(second, Candidate("so2", "作品 第2話"), listOf(Comment(0, "cache2")))
            assertNull(cache.readOwn(first))
            assertNotNull(cache.readOwn(second))
            assertNotNull(manual.read(first))
            limit = 1L
            cache.enforceAutomaticLimit()
            assertTrue(cache.ownList().isEmpty())
            assertNotNull(manual.read(first))
        } finally { root.deleteRecursively() }
    }
    @Test fun oversizedCacheDoesNotEvictExistingCache() {
        val root = Files.createTempDirectory("anicome-cache-oversize").toFile()
        try {
            var limit = 0L
            val manual = CommentStore(java.io.File(root, "manual"))
            val cache = CommentStore(java.io.File(root, "cache"), fallback = manual, automaticLimit = { limit })
            val first = Episode("作品", 1)
            cache.save(first, Candidate("so1", "作品 第1話"), listOf(Comment(0, "small")))
            limit = cache.ownList().single().bytes + 10
            assertTrue(runCatching { cache.save(Episode("作品", 2), Candidate("so2", "作品 第2話"), listOf(Comment(0, "large".repeat(1000)))) }.isFailure)
            assertNotNull(cache.readOwn(first))
            assertEquals(1, cache.ownList().size)
        } finally { root.deleteRecursively() }
    }
    @Test fun interruptedCacheCleanupPreservesRecentTemporaryFilesAndManualFiles() {
        val root = Files.createTempDirectory("anicome-cache-cleanup").toFile()
        try {
            val manualDir = java.io.File(root, "manual").apply { mkdirs() }
            val cacheDir = java.io.File(root, "cache").apply { mkdirs() }
            val manual = CommentStore(manualDir)
            val cache = CommentStore(cacheDir, fallback = manual, clock = { 10_000_000L })
            val stale = java.io.File(cacheDir, "old.json.tmp").apply { writeText("partial"); setLastModified(1L) }
            val recent = java.io.File(cacheDir, "recent.json.tmp").apply { writeText("active"); setLastModified(9_999_999L) }
            val corrupt = java.io.File(cacheDir, "broken.json").apply { writeText("{") }
            val manualTemp = java.io.File(manualDir, "old.json.tmp").apply { writeText("manual"); setLastModified(1L) }
            cache.cleanupInterruptedData()
            assertFalse(stale.exists()); assertFalse(corrupt.exists()); assertTrue(recent.exists()); assertTrue(manualTemp.exists())
            val damagedManual = java.io.File(manualDir, "broken.json").apply { writeText("{") }
            manual.cleanupInterruptedData()
            assertFalse(manualTemp.exists()); assertTrue(damagedManual.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun completionRequestsOnlyMissingSources() {
        val saved = SavedEpisode(Episode("作品", 1), "so1", "作品", 1, 1, 1, sources = "OFFICIAL,DANIME")
        assertEquals(OfflineSources - setOf(PlaybackSource.OFFICIAL, PlaybackSource.DANIME), missingOfflineSources(saved))
        assertTrue(missingOfflineSources(saved.copy(sources = OfflineSources.joinToString(",") { it.name })).isEmpty())
    }

    @Test fun keepingSameSizeArchiveRetainsBothOldAndNewCommentsAndDuplicates() {
        val root = Files.createTempDirectory("anicome-cache-union").toFile()
        try {
            val manual = CommentStore(java.io.File(root, "manual"))
            val cache = CommentStore(java.io.File(root, "cache"), fallback = manual)
            val episode = Episode("作品", 1)
            val old = Comment(0, "old"); val fresh = Comment(1000, "new")
            manual.save(episode, Candidate("so1", "作品 第1話"), listOf(old, old))
            cache.save(episode, Candidate("so1", "作品 第1話"), listOf(old, fresh))
            cache.keepCached(episode)
            assertEquals(listOf(old, old, fresh), manual.read(episode)!!.comments)
            assertNull(cache.readOwn(episode))
        } finally { root.deleteRecursively() }
    }
    @Test fun prefetchWorkPreferencesStaySeparateAcrossSeasonsAndCours() {
        assertEquals(prefetchWorkKey("作品 第2期"), prefetchWorkKey("作品 2期"))
        assertNotEquals(prefetchWorkKey("作品"), prefetchWorkKey("作品 第2期"))
        assertNotEquals(prefetchWorkKey("作品"), prefetchWorkKey("作品 第2クール"))
    }

}
