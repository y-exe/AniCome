package net.yexe.anicome

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SeasonCourSaveTest {
    @get:Rule val folder = TemporaryFolder()
    private val base = "無職転生 ～異世界行ったら本気だす～"
    private fun fixture(name: String) = JSONObject(javaClass.getResource("/episode-metadata/mushoku-$name.json")!!.readText())
    private fun repository() = EpisodeMetadataRepository(request = { url, _ -> if (url.contains("TitleSearch")) fixture("search") else fixture("detail") })
    private fun work(title: String, year: Int, count: Int) = AnimeInfo(0, title, "", "", null, year, count, emptyList(), "syoboi")
    @Test fun JapaneseSeasonSuffixAndCompactRomanNumeralsAreEquivalent() {
        val a = animeWorkIdentity("$base 第3期")
        val b = animeWorkIdentity("無職転生Ⅲ ～異世界行ったら本気だす～")
        assertEquals(a.base, b.base); assertEquals(3, b.season)
        assertTrue(savedEpisodeMatches(Episode("$base 第3期", 14), Candidate("so46858067", "無職転生Ⅲ ～異世界行ったら本気だす～ 第14話「覚悟」"), "覚悟"))
    }
    @Test fun ASubTitleNeverMakesTheWrongSeasonAcceptable() {
        assertFalse(savedEpisodeMatches(Episode("$base 第3期", 14), Candidate("so1", "無職転生Ⅱ ～異世界行ったら本気だす～ 第14話「覚悟」"), "覚悟"))
    }
    @Test fun SeasonAndCourRemainSeparate() {
        val identity = animeWorkIdentity("$base 第2期 第2クール")
        assertEquals(2, identity.season); assertEquals(2, identity.cour)
        assertFalse(savedEpisodeMatches(Episode("$base 第2クール", 1), Candidate("so1", "$base 第1話")))
    }
    @Test fun CompactArabicSeasonNumbersMatchWithoutRemovingYears() {
        assertEquals(animeWorkIdentity("響け！ユーフォニアム 第2期").base, animeWorkIdentity("響け！ユーフォニアム2").base)
        assertEquals(2, animeWorkIdentity("響け！ユーフォニアム2").season)
        assertEquals(1, animeWorkIdentity("宇宙戦艦ヤマト2202").season)
        assertTrue(savedEpisodeMatches(Episode("響け！ユーフォニアム 第2期", 1), Candidate("so1", "響け！ユーフォニアム2 第1話「まなつのファンファーレ」")))
    }
    @Test fun RepeatedSubtitlesKeepTheCourNumberMappingButCannotIdentifyAnUnnumberedVideo() = runBlocking {
        val repo = EpisodeMetadataRepository(request = { url, _ -> if (url.contains("TitleSearch")) fixture("search") else JSONObject("""{"Titles":{"6982":{"SubTitles":"*13*同じタイトル\n*14*同じタイトル"}}}""") })
        val first = repo.resolve(work("$base 第2期 第2クール", 2024, 12), 1)!!
        assertEquals("", first.title); assertEquals(13, first.episode.number)
        assertFalse(savedEpisodeMatches(first.episode, Candidate("so1", "無職転生Ⅱ ～異世界行ったら本気だす～「同じタイトル」"), first.title))
    }
    @Test fun ThirdSeasonFindsAll14RealJapaneseTitles() = runBlocking {
        val repo = repository()
        val selected = work("$base 第3期", 2026, 14)
        assertEquals("吠えよ狂犬", repo.resolve(selected, 2)!!.title)
        assertEquals("不死魔王との謁見", repo.resolve(selected, 10)!!.title)
        val last = repo.resolve(selected, 14)!!
        assertEquals("覚悟", last.title); assertEquals(14, last.episode.number)
        assertEquals(3, animeWorkIdentity(last.episode.series).season)
    }
    @Test fun SecondCourMapsLocalEpisodesToTheRegisteredContinuousNumbers() = runBlocking {
        val repo = repository()
        val selected = work("$base 第2期 第2クール", 2024, 12)
        val first = repo.resolve(selected, 1)!!
        assertEquals("夢のマイホーム", first.title); assertEquals(13, first.episode.number)
        assertEquals(24, repo.resolve(selected, 12)!!.episode.number)
        assertEquals("嗣ぐ", repo.resolve(selected, 12)!!.title)
    }
    @Test fun FirstSeasonSecondCourStartsAtEpisode12InsteadOfAssuming13() = runBlocking {
        val first = repository().resolve(work("$base 第2クール", 2021, 12), 1)!!
        assertEquals(12, first.episode.number); assertEquals("魔眼を持つ女", first.title)
    }
    @Test fun ContinuousVideoNumberIsStoredAsTheSelectedLocalEpisode() = runBlocking {
        val selected = work("$base 第2期 第2クール", 2024, 12)
        val metadata = repository().resolve(selected, 1)!!
        val source = Candidate("so889930", "無職転生Ⅱ ～異世界行ったら本気だす～ 第13話「夢のマイホーム」", channelId = 111)
        val comments = CommentRepository(CommentStore(folder.newFolder()), search = { listOf(source) }, load = { _, _ -> listOf(Comment(0, "test")) })
        val local = Episode(selected.title, 1)
        assertEquals(local, comments.obtain(local, episodeTitle = metadata.title, sourceEpisode = metadata.episode, strictMatch = true).info.episode)
    }
    @Test fun ReportedThirdSeasonEpisode14UsesTheActualOfficialVideoTitle() = runBlocking {
        val selected = work("$base 第3期", 2026, 14)
        val metadata = repository().resolve(selected, 14)!!
        val query = mutableListOf<String>()
        val source = Candidate("so46858067", "無職転生Ⅲ ～異世界行ったら本気だす～ 第14話「覚悟」", channelId = 2650205)
        val comments = CommentRepository(CommentStore(folder.newFolder()), search = { query.add(it); listOf(source) }, load = { _, _ -> listOf(Comment(0, "test")) })
        assertEquals(source.id, comments.obtain(Episode(selected.title, 14), episodeTitle = metadata.title, sourceEpisode = metadata.episode, strictMatch = true).info.sourceId)
        assertTrue(query.single().contains("覚悟")); assertFalse(query.single().contains("第3期"))
    }
    @Test fun MissingSubtitleStillFindsTheCorrectSeasonWithRomanNumerals() = runBlocking {
        val source = Candidate("so46858067", "無職転生Ⅲ ～異世界行ったら本気だす～ 第14話「覚悟」", channelId = 2650205)
        val wrong = source.copy(id = "so889999", title = "無職転生Ⅱ ～異世界行ったら本気だす～ 第14話「披露宴」", commentCount = 99999)
        val comments = CommentRepository(CommentStore(folder.newFolder()), search = { listOf(wrong, source) }, load = { id, _ -> assertEquals(source.id, id); listOf(Comment(0, "test")) })
        assertEquals(source.id, comments.obtain(Episode("$base 第3期", 14), strictMatch = true).info.sourceId)
    }
}
