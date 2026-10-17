package net.yexe.anicome

import org.junit.Assert.*
import org.junit.Test

class EpisodeMatcherTest {
    @Test fun allTwelveRealUraraVideoTitlesResolveToTheirActualEpisode() {
        val data = org.json.JSONArray(javaClass.getResource("/episode-metadata/urara-video-titles.json")!!.readText())
        val videos = List(data.length()) { index -> data.getJSONObject(index).let { Candidate(it.getString("contentId"), it.getString("title"), channelId = it.getLong("channelId")) } }
        val found = mutableSetOf<String>()
        (1..12).forEach { number ->
            val choices = videos.filter { savedEpisodeMatches(Episode("うらら迷路帖", number), it) }
            assertEquals("第${number}話", 1, choices.size)
            assertEquals(number, EpisodeMatcher.parse(choices.single().title)!!.number)
            found.add(choices.single().id)
        }
        assertEquals(12, found.size)
    }
    @Test fun refusesEpisodeWordsEmbeddedInTheAnimeTitle() {
        assertNull(EpisodeMatcher.parse("Lv2からチートだった元勇者候補のまったり異世界ライフ"))
        assertNull(EpisodeMatcher.parse("Level2からチートだった元勇者候補のまったり異世界ライフ"))
    }

    @Test fun acceptsUraraFortuneNumbersAndKeepsEpisodeBoundaries() {
        assertEquals(Episode("うらら迷路帖", 1), EpisodeMatcher.parse("うらら迷路帖 一占『少女と占い、時々おなか』"))
        assertEquals(Episode("うらら迷路帖", 12), EpisodeMatcher.parse("うらら迷路帖 十二占『お風呂とお祝い、時々笑顔』"))
        assertTrue(savedEpisodeMatches(Episode("うらら迷路帖", 12), Candidate("so1", "うらら迷路帖 十二占『お風呂とお祝い、時々笑顔』")))
        assertFalse(savedEpisodeMatches(Episode("うらら迷路帖", 2), Candidate("so2", "うらら迷路帖 十二占『お風呂とお祝い、時々笑顔』")))
    }
    @Test fun acceptsOtherEpisodeUnitsAndFormalKanji() {
        listOf("二曲", "第二夜", "弐占", "二皿", "二限目", "二服", "二譚", "Case.2", "Page.2").forEach { label ->
            assertEquals(label, Episode("作品", 2), EpisodeMatcher.parse("作品 $label「サブタイトル」"))
        }
        assertNull(EpisodeMatcher.parse("作品 第2期 第2クール"))
        assertNull(EpisodeMatcher.parse("一週間フレンズ。"))
    }
    @Test fun generatedSearchIncludesKanjiAndNumericNonstandardUnits() {
        val query = Comments.buildSearchQuery(EpisodeMatcher.extract("うらら迷路帖 第12話")).first
        assertTrue(query.contains("十二占")); assertTrue(query.contains("12占"))
    }

    @Test fun parsesAbemaEpisodeFirstMetadata() {
        assertEquals(Episode("無職転生", 1), EpisodeMatcher.parse("第1話 無職転生"))
    }
    @Test fun joinsDmmSeriesAndEpisode() {
        assertEquals(Episode("葬送のフリーレン", 1), EpisodeMatcher.parse("葬送のフリーレン", "第１話 冒険の終わり"))
    }
    @Test fun acceptsHashAndZeroPadding() {
        assertEquals(Episode("作品", 2), EpisodeMatcher.parse("作品 ＃０２「サブタイトル」"))
    }
    @Test fun refusesUnknownEpisode() { assertNull(EpisodeMatcher.parse("作品", "サブタイトル")) }
    @Test fun acceptsKanjiEpisodeNumbers() { assertEquals(Episode("作品", 12), EpisodeMatcher.parse("作品 第十二話")) }
    @Test fun doesNotTreatEpInsideStepAsAnEpisodePrefix() { assertNull(EpisodeMatcher.parse("Step9 ゴリラのみずき")) }
    @Test fun resolvesDmmStepMetadataWithSeriesFromArtist() {
        assertEquals("スロウスタート" to "第9話 ゴリラのみずき", PlaybackMetadata.resolve(listOf(
            "TITLE" to "Step9 ゴリラのみずき",
            "DISPLAY_TITLE" to "Step9 ゴリラのみずき",
            "DISPLAY_SUBTITLE" to "スロウスタート",
            "ARTIST" to "スロウスタート"
        )))
    }
    @Test fun refusesFractionalSpecialEpisode() { assertNull(EpisodeMatcher.parse("作品 #12.5")) }
    @Test fun matchesOfficialEpisodeWithoutSelectingOtherSeasonsOrClips() {
        val right = Candidate("so123", "作品 第2話「はじまり」")
        assertEquals(right, EpisodeMatcher.choose(Episode("作品", 2), listOf(
            Candidate("so999", "作品 第12話"), Candidate("so888", "作品 第2期 第2話"),
            Candidate("sm123", "作品 第2話"), Candidate("so777", "作品 第2話 予告"), right)))
    }
    @Test fun choosesTheHighestCommentOfficialEditionLikeNcOverlay() {
        assertEquals("so2", EpisodeMatcher.choose(Episode("作品", 1), listOf(Candidate("so1", "作品 第1話", commentCount = 10), Candidate("so2", "作品 第1話「別版」", commentCount = 20)))?.id)
    }
    @Test fun refusesEpisodeBundles() {
        assertNull(EpisodeMatcher.choose(Episode("作品", 1), listOf(Candidate("so1", "作品 第1話〜第3話"))))
    }
    @Test fun matchesTitleVariantsLikeSteinsGate() {
        assertTrue(EpisodeMatcher.compare("STEINS;GATE 第1話", "シュタインズ・ゲート 第1話「始まりと終わりのプロローグ」"))
    }
    @Test fun matchesWithLevenshteinSimilarity() {
        assertTrue(EpisodeMatcher.compare("その着せ替え人形は恋をする 第1話", "その着せ替え人形(ビスク・ドール)は恋をする 第1話「自分とは住む世界が違う」"))
    }
    @Test fun separatesDifferentSeasons() {
        assertFalse(EpisodeMatcher.compare("無職転生 第2期 第1話", "無職転生 第1話"))
        assertTrue(EpisodeMatcher.compare("無職転生 第2期 第1話", "無職転生 II 第1話"))
    }
    @Test fun extractsCoreTitleCorrectly() {
        assertEquals("ダンジョンに出会いを求めるのは間違っているだろうか", EpisodeMatcher.extractCoreTitle("ダンジョンに出会いを求めるのは間違っているだろうかIV 新章 迷宮篇"))
        assertEquals("Re:ゼロから始める異世界生活", EpisodeMatcher.extractCoreTitle("Re:ゼロから始める異世界生活 3rd season"))
    }
    @Test fun fuzzyMatchingMatchesEvenWithSignificantTitleDifferences() {
         
        assertFalse(EpisodeMatcher.compare("やはり俺の青春ラブコメはまちがっている。 第1話", "俺ガイル 完 第1話"))
        assertTrue(EpisodeMatcher.compare("やはり俺の青春ラブコメはまちがっている。 第1話", "やはり俺の青春ラブコメはまちがっている。完 -新章- 第1話", allowFuzzy = true))
    }
    @Test fun matchesFallbackToGeneralVideosWhenAllowed() {
        val general = Candidate("sm99999", "作品 第1話", commentCount = 50)
         
        assertTrue(EpisodeMatcher.matches(Episode("作品", 1), listOf(general)).isEmpty())
         
        val matches = EpisodeMatcher.matches(Episode("作品", 1), listOf(general), allowGeneralVideos = true)
        assertEquals(1, matches.size)
        assertEquals("sm99999", matches.first().id)
    }
}
