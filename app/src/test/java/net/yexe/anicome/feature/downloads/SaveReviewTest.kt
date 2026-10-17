package net.yexe.anicome

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SaveReviewTest {
    private val work = AnimeInfo(1, "テスト作品 第2クール", "", "", null, 2026, 12, emptyList())
    @Test fun reviewPreservesLocalKeysAndChecksResolvedEpisodeAndSubtitle() = runTest {
        val correct = Candidate("so13", "テスト作品 第13話「新しい旅」", channelId = 10, ownerName = "公式チャンネル")
        val wrong = Candidate("so1", "テスト作品 第1話", channelId = 10, commentCount = 99999)
        val queries = mutableListOf<Episode>()
        val repo = SaveReviewRepository(resolve = { _, _ -> ResolvedEpisodeMetadata("新しい旅", Episode("テスト作品", 13)) },
            candidates = { episode, _ -> queries += episode; listOf(wrong, correct) })
        val review = repo.prepare(work, listOf(Episode(work.title, 1)), setOf(PlaybackSource.OFFICIAL), null, 1) { _, _ -> }
        assertEquals(listOf(Episode("テスト作品", 13)), queries)
        assertEquals(Episode(work.title, 1), review.episodes.single().episode)
        assertEquals(13, review.episodes.single().searchEpisode.number)
        assertEquals("so13", review.selected.single().candidate!!.id)
        assertTrue(review.selected.single().issue.isEmpty())
    }
    @Test fun wrongSeasonsAreNotPreselectedAndCanNeverBecomePreviewCandidates() = runTest {
        val second = work.copy(title = "テスト作品 第2期")
        val repo = SaveReviewRepository(candidates = { _, _ -> listOf(Candidate("so1", "テスト作品 第1話", channelId = 10)) })
        val row = repo.prepare(second, listOf(Episode(second.title, 1)), setOf(PlaybackSource.OFFICIAL), null, 1) { _, _ -> }.episodes.single()
        assertNull(row.candidate); assertFalse(row.selected)
        assertEquals("期・話数が一致する公式動画がありません", row.issue)
    }
    @Test fun specialEpisodesCanBeExcludedAndSelectedCapacityUpdates() = runTest {
        val repo = SaveReviewRepository(channelEpisode = { channel, n -> Candidate("so${n+10}", "第${n}話", channelId = channel) },
            storage = { SaveSpace(free = 10_000_000L, typicalEpisodeBytes = 1_000_000L) })
        val review = repo.prepare(work, listOf(Episode(work.title, 0), Episode(work.title, 13), Episode(work.title, 14)), setOf(PlaybackSource.OFFICIAL), 10L, 1) { _, _ -> }
        assertEquals(listOf(13, 14), review.selected.map { it.episode.number })
        assertEquals(2_000_000L, review.estimatedBytes)
        val fewer = review.copy(episodes = review.episodes.map { it.copy(selected = it.episode.number == 13) })
        assertEquals(1_000_000L, fewer.estimatedBytes)
        assertTrue(fewer.enoughSpace)
    }
    @Test fun lowDiskAndManualQuotaAreCheckedIndependentlyWithoutChangingFiles() {
        val rows = listOf(SaveEpisodeReview(Episode("作品", 1), Episode("作品", 1)))
        assertFalse(SaveReview(rows, 1_048_576L, SaveSpace(free = 5_242_880L)).enoughSpace)
        assertFalse(SaveReview(rows, 1_048_576L, SaveSpace(used = 100L, limit = 1_048_600L)).enoughSpace)
        assertTrue(SaveReview(rows, 1_048_576L, SaveSpace()).enoughSpace)
    }
    @Test fun networkFailuresAreDistinctFromNoResultsAndCancellationPropagates() = runTest {
        val errorRepo = SaveReviewRepository(candidates = { _, _ -> throw java.io.IOException("offline") })
        val row = errorRepo.prepare(work, listOf(Episode(work.title, 1)), setOf(PlaybackSource.OFFICIAL), null, 1) { _, _ -> }.episodes.single()
        assertEquals("通信に失敗しました。再確認してください", row.issue)
        val cancelRepo = SaveReviewRepository(resolve = { _, _ -> throw CancellationException() })
        try { cancelRepo.prepare(work, listOf(Episode(work.title, 1)), setOf(PlaybackSource.OFFICIAL), null, 1) { _, _ -> }; fail() }
        catch (_: CancellationException) { }
    }
    @Test fun candidateSummaryIncludesSeasonCourYearAndCount() {
        assertEquals("第2期 · 第2クール · 2026 · 全12話", animeWorkSummary(work.copy(title = "作品 第2期 第2クール")))
    }
    @Test fun retryRestoresTheReviewedEpisodeAndVideoInsteadOfRenumberingIt() {
        val tags = setOf("series:作品 第2クール", "episode:1", "reviewSeries:作品", "reviewNumber:13", "reviewSubtitle:新しい旅", "reviewVideo:so13")
        val row = reservationReview(tags)!!
        assertEquals(Episode("作品 第2クール", 1), row.episode)
        assertEquals(Episode("作品", 13), row.searchEpisode)
        assertEquals("so13", row.candidate!!.id)
        assertNull(reservationReview(setOf("series:作品", "episode:1")))
    }

    @Test fun reviewedArchiveCanBeReusedOfflineOnlyForTheSameVideoAndResolvedEpisode() {
        val saved = SavedComments(SavedEpisode(Episode("作品 第2クール", 1), "so13", "作品 第13話", 1, 1, 100), listOf(Comment(0, "cached")))
        assertTrue(canReuseReviewedArchive(saved, "so13", Episode("作品", 13), ""))
        assertFalse(canReuseReviewedArchive(saved.copy(info = saved.info.copy(sourceId = "invalid")), "invalid", Episode("作品", 13), ""))
        assertFalse(canReuseReviewedArchive(saved, "so14", Episode("作品", 13), ""))
        assertFalse(canReuseReviewedArchive(saved, "so13", Episode("作品", 1), ""))
        assertFalse(canReuseReviewedArchive(saved.copy(info = saved.info.copy(sources = "DANIME")), "so13", Episode("作品", 13), ""))
    }

    @Test fun duplicateOfficialVideosAreComparedByCanonicalIdAndOnlyAmongSelectedEpisodes() {
        val rows = listOf(
            SaveEpisodeReview(Episode("作品", 1), Episode("作品", 13), candidate = Candidate("so123", "第13話")),
            SaveEpisodeReview(Episode("作品", 2), Episode("作品", 13), candidate = Candidate("https://www.nicovideo.jp/watch/so123", "第13話")),
            SaveEpisodeReview(Episode("作品", 3), Episode("作品", 15), candidate = Candidate("so125", "第15話")))
        val review = SaveReview(rows, 1, SaveSpace())
        assertEquals(setOf("so123"), review.duplicateVideoIds)
        assertTrue(review.copy(episodes = rows.map { if (it.episode.number == 2) it.copy(selected = false) else it }).duplicateVideoIds.isEmpty())
        assertTrue(duplicateReviewVideoIds(listOf(rows.first(), rows.first())).isEmpty())
    }

}
