package net.yexe.anicome

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

class YouzitsuSaveRegressionTest {
    private fun fixture(name: String) = JSONObject(javaClass.classLoader!!.getResource("youzitsu3/$name.json")!!.readText())
    @Test fun realThirdSeasonMetadataFindsThirteenDistinctOfficialEpisodesOffTheCallerThread() = runBlocking {
        val caller = Thread.currentThread()
        val metadata = EpisodeMetadataRepository(request = { url, _ -> fixture(if (url.contains("TitleSearch")) "search" else "detail") })
        val work = AnimeInfo(6888, "ようこそ実力至上主義の教室へ 3rd Season", "", "", null, 2024, 13, emptyList(), "syoboi")
        val data = fixture("videos").getJSONArray("data")
        val videos = (0 until data.length()).map { data.getJSONObject(it).let { c -> Candidate(c.getString("contentId"), c.getString("title"), if (c.isNull("channelId")) null else c.getLong("channelId"), commentCount = c.optLong("commentCounter")) } }
        val seen = CopyOnWriteArrayList<String>()
        val directory = Files.createTempDirectory("youzitsu-review").toFile()
        try {
            val repository = CommentRepository(CommentStore(directory), search = { query -> seen += query; videos })
            val review = SaveReviewRepository(resolve = { selected, n -> metadata.resolve(selected, n) }, candidates = { episode, title ->
                assertNotSame("Room-backed archive lookup must not run on the UI caller", caller, Thread.currentThread())
                assertTrue(title.isNotBlank())
                repository.searchEpisodeCandidates(episode.series, episode.number, title)
            }).prepare(work, (1..13).map { Episode(work.title, it) }, setOf(PlaybackSource.OFFICIAL), null, 5) { _, _ -> }
            assertEquals(13, review.episodes.size)
            assertTrue(review.episodes.joinToString("\n") { "${it.episode.number}: ${it.subtitle} / ${it.issue} / ${it.candidate?.id}" }, review.episodes.all { it.issue.isBlank() && it.selected && it.candidate?.channelId == 2649237L })
            assertEquals(13, review.episodes.mapNotNull { it.candidate?.id }.distinct().size)
            assertTrue(seen.any { it.contains("愛は最も良い教師である") })
            assertEquals(13, seen.size)
        } finally { directory.deleteRecursively() }
    }
    @Test fun ordinalRomanAndWordSeasonMarkersDoNotAbortEpisodeExtraction() {
        for (marker in listOf("3rd Season", "Season III", "third season", "第3期", "第三期")) {
            val parsed = EpisodeMatcher.extract("テスト作品 $marker 第13話「最終話」")
            assertEquals(marker, 3, parsed.season?.number)
            assertEquals(marker, 13, parsed.episode?.number)
            assertEquals(marker, "最終話", parsed.subtitle)
        }
    }
    @Test fun shortAmbiguousSubtitlesDoNotTriggerTitleOnlySearchOrMatchOtherSeasons() = runBlocking {
        val directory = Files.createTempDirectory("short-subtitle").toFile()
        try {
            val queries = CopyOnWriteArrayList<String>()
            CommentRepository(CommentStore(directory), search = { queries += it; emptyList() }).searchEpisodeCandidates("作品 第3期", 1, "夢")
            assertFalse(queries.any { it.contains("夢") })
            assertFalse(savedEpisodeMatches(Episode("作品 第3期", 1), Candidate("so1", "作品 第1期 第1話「夢」", 123), "夢"))
        } finally { directory.deleteRecursively() }
    }
}
