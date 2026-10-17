package net.yexe.anicome

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SavedEpisodeMatcherTest {
    @Test fun CalendarAndNiconicoAgreeForAllUraraEpisodesDespiteFourthEpisodeParticle() = runBlocking {
        val work = AnimeInfo(4419, "うらら迷路帖", "", "", null, 2017, 12, emptyList(), "syoboi")
        val metadata = EpisodeMetadataRepository(request = { url, _ -> org.json.JSONObject(javaClass.getResource("/episode-metadata/urara-calendar-${if (url.contains("TitleSearch")) "search" else "detail"}.json")!!.readText()) })
        val rows = org.json.JSONArray(javaClass.getResource("/episode-metadata/urara-video-titles.json")!!.readText())
        val candidates = List(rows.length()) { index -> rows.getJSONObject(index).let { Candidate(it.getString("contentId"), it.getString("title"), channelId = it.getLong("channelId")) } }
        (1..12).forEach { number ->
            val resolved = metadata.resolve(work, number)!!
            assertEquals(1, candidates.count { savedEpisodeMatches(resolved.episode, it, resolved.title) })
        }
        val fourth = metadata.resolve(work, 4)!!
        assertEquals("良いことと悪いこと、時々くすぐったい", fourth.title)
        val repo = CommentRepository(CommentStore(folder.newFolder()), search = { query ->
            if (query.contains(fourth.title)) emptyList() else candidates
        }, load = { id, _ -> assertEquals("so30539821", id); listOf(Comment(0, "test")) }, snapshotLoader = { _, _, _, _, _ -> emptyList() })
        val saved = repo.obtain(fourth.episode, strictMatch = true, episodeTitle = fourth.title)
        assertEquals("so30539821", saved.info.sourceId)
    }
    @Test fun MinorSubtitleDifferencesNeverOverrideMissingNumbersOrDifferentWorks() {
        val episode = Episode("うらら迷路帖", 4)
        val title = "良いことと悪いこと、時々くすぐったい"
        val subtitle = "良いこと悪いこと、時々くすぐったい"
        assertTrue(savedEpisodeMatches(episode, Candidate("so1", "うらら迷路帖 四占『$subtitle』"), title))
        listOf("うらら迷路帖 五占『$subtitle』", "うらら迷路帖 第2期 四占『$subtitle』", "別の作品 四占『$subtitle』", "うらら迷路帖『$subtitle』").forEach {
            assertFalse(it, savedEpisodeMatches(episode, Candidate("so2", it), title))
        }
        assertFalse(savedEpisodeMatches(episode, Candidate("so3", "うらら迷路帖 四占『良いこと悪いこと、時々痛い』"), title))
    }

    @get:Rule val folder = TemporaryFolder()
    private val episode = Episode("テスト作品", 1)
    private fun candidate(title: String, id: String = "so889001") = Candidate(id, title, channelId = 111)
    @Test fun NativeSubtitleIdentifiesAnEpisodeWithoutAnEpisodeNumber() {
        assertTrue(savedEpisodeMatches(episode, candidate("テスト作品「再会」"), "再会"))
        assertFalse(savedEpisodeMatches(episode, candidate("別の作品「再会」"), "再会"))
    }
    @Test fun WrongEpisodeOrSeasonNeverPassesEvenWithTheSameSubtitle() {
        assertFalse(savedEpisodeMatches(episode, candidate("テスト作品 第2話「再会」"), "再会"))
        assertFalse(savedEpisodeMatches(episode, candidate("テスト作品 第2期 第1話「再会」"), "再会"))
    }
    @Test fun RegisteredSubtitleRejectsAContradictingVideoSubtitle() {
        assertFalse(savedEpisodeMatches(episode, candidate("テスト作品 第1話「別れ」"), "再会"))
        assertTrue(savedEpisodeMatches(episode, candidate("テスト作品 第1話"), "再会"))
    }
    @Test fun MissingMetadataStillRequiresTheSeriesAndEpisodeNumber() {
        assertTrue(savedEpisodeMatches(episode, candidate("テスト作品 第０１話")))
        assertFalse(savedEpisodeMatches(episode, candidate("テスト作品")))
        assertFalse(savedEpisodeMatches(episode, candidate("別の作品 第1話")))
    }
    @Test fun AWordInTheSeriesNameCannotSatisfyTheEpisodeSubtitle() {
        assertFalse(savedEpisodeMatches(Episode("再会", 1), candidate("再会 第1話「別れ」"), "再会"))
    }
    @Test fun KnownJapaneseAndEnglishTitleAliasesRemainCompatible() {
        assertTrue(savedEpisodeMatches(Episode("STEINS;GATE", 1), candidate("シュタインズ・ゲート 第1話「始まり」"), "始まり"))
    }
    @Test fun ACloseButDifferentSeriesNameDoesNotPassTheSaveCheck() {
        assertFalse(savedEpisodeMatches(Episode("とても長い作品の正式なタイトルA", 1), candidate("とても長い作品の正式なタイトルB 第1話「再会」"), "再会"))
    }
    @Test fun SubtitleSearchIgnoresTheMorePopularWrongEpisode() = runBlocking {
        val correct = candidate("テスト作品「再会」")
        val wrong = candidate("テスト作品 第2話「再会」", "so889002").copy(commentCount = 10000)
        val queries = mutableListOf<String>()
        val repository = CommentRepository(CommentStore(folder.newFolder()), search = { queries.add(it); listOf(wrong, correct) }, load = { id, _ -> assertEquals(correct.id, id); listOf(Comment(0, "test")) })
        val saved = repository.obtain(episode, episodeTitle = "再会", strictMatch = true)
        assertEquals(correct.id, saved.info.sourceId)
        assertTrue(queries.single().contains("再会"))
    }
    @Test fun StrictSaveNeverUsesAnUnrelatedOfficialChannelFallback() = runBlocking {
        val repository = CommentRepository(CommentStore(folder.newFolder()), search = { listOf(candidate("別の作品 第1話")) }, load = { _, _ -> error("取得してはいけない") }, snapshotLoader = { _, _, _, _, _ -> emptyList() })
        try { repository.obtain(episode, strictMatch = true); fail("一致しない公式動画を保存してはいけない") }
        catch (_: MatchUnavailable) { assertTrue(repository.store.list().isEmpty()) }
    }
    @Test fun SourceArchiveReuseAlsoRegistersTheCorrectRequestedEpisode() = runBlocking {
        val store = CommentStore(folder.newFolder())
        val source = candidate("テスト作品 第1話「再会」")
        val oldEpisode = Episode(episode.series, 2)
        store.save(oldEpisode, source, listOf(Comment(0, "test")))
        val repository = CommentRepository(store, search = { listOf(source) }, load = { _, _ -> error("再取得してはいけない") })
        val result = repository.obtain(episode, episodeTitle = "再会", strictMatch = true)
        assertEquals(episode, result.info.episode)
        assertNotNull(store.read(episode)); assertNotNull(store.read(oldEpisode))
    }
}
