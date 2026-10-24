package net.yexe.anicome

import java.io.IOException
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AnimeChannelRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private val data get() = JSONObject("""{"items":[
      {"title":"ご注文はうさぎですか？","begin":"2014-04-10","sites":[{"site":"nicovideo","id":"gochiusa"},{"site":"danime","id":"11244"}]},
      {"title":"ご注文はうさぎですか??","begin":"2015-10-10","sites":[{"site":"nicovideo","id":"gochiusa2"}]},
      {"title":"無職転生～異世界行ったら本気だす～","sites":[{"site":"nicovideo","id":"mushokutensei"}]},
      {"title":"無職転生～異世界行ったら本気だす～(第2クール)","sites":[{"site":"nicovideo","id":"mushokutensei"}]},
      {"title":"本店だけ","sites":[{"site":"danime","id":"123"}]}
    ]}""")
    private val konosuba get() = JSONObject("""{"items":[{"title":"この素晴らしい世界に祝福を！3","begin":"2024-04-10","sites":[{"site":"nicovideo","id":"konosuba_3rd"}]}]}""")
    @Test fun directBangumiSearchKeepsChannelBindingEvenWhenExternalTitlesDiffer() = runTest {
        val channelCatalog = AnimeChannelRepository(folder.newFile(), { konosuba }, { reference ->
            assertEquals("konosuba_3rd", reference); 2649354L
        })
        val external = AnimeInfo(123, "この素晴らしい世界に祝福を！ 第3期", "image", "", null, 2024, 11, emptyList(), "kitsu")
        val repository = SaveCommentRepository(catalogSearch = { listOf(external) }, channelCatalog = channelCatalog)
        val selected = repository.search("この素晴らしい世界に祝福を").single()
        assertEquals("この素晴らしい世界に祝福を！3", selected.title)
        assertEquals("konosuba_3rd", selected.niconicoChannel)
        assertEquals(11, selected.episodes)
        assertEquals("image", selected.imageUrl)
        assertEquals(2649354L, channelCatalog.official(selected.copy(title = "表示用に書き換えたタイトル")))
        val reservation = ReservationSpec(Episode(selected.title, 1), catalog = selected)
        assertEquals(selected.niconicoChannel, ReservationSpec.decode(reservation.encode()).catalog!!.niconicoChannel)
        assertEquals("konosuba_3rd", reservation.input().getString("catalogChannel"))
        assertEquals(animeWorkIdentity(external.title).season, animeWorkIdentity("この素晴らしい世界に祝福を！３").season)
    }
    @Test fun directCandidatesRemainAvailableIfOptionalArtworkProviderFails() = runTest {
        val channels = AnimeChannelRepository(folder.newFile(), { konosuba }, { 2649354L })
        val repository = SaveCommentRepository(channelCatalog = channels, catalogSearch = { throw IOException() })
        val selected = repository.search("この素晴らしい世界に祝福を").single()
        assertEquals("bangumi-data", selected.provider)
        assertEquals("konosuba_3rd", selected.niconicoChannel)
        val restored = ReservationSpec.decode(ReservationSpec(Episode(selected.title, 1), catalog = selected).encode())
        assertEquals(selected, restored.catalog)
    }
    @Test fun calendarEpisodeCountCanCompleteBangumiOnlyCandidates() = runTest {
        val entries = (1..11).joinToString("\n") { "*$it*各話タイトル$it" }
        val metadata = EpisodeMetadataRepository(request = { url, _ ->
            if ("TitleSearch" in url) JSONObject("""{"Titles":{"123":{"TID":123,"Title":"この素晴らしい世界に祝福を！3","FirstYear":2024}}}""")
            else JSONObject().put("Titles", JSONObject().put("123", JSONObject().put("SubTitles", entries)))
        })
        val work = AnimeInfo(1, "この素晴らしい世界に祝福を！3", "", "", null, 2024, null, emptyList(), "bangumi-data", "konosuba_3rd")
        val complete = metadata.withEpisodeCount(work)
        assertEquals(11, complete.episodes)
        assertEquals("konosuba_3rd", complete.niconicoChannel)
    }
    @Test fun completeOfficialFeedLoadsAllElevenEpisodesWithoutSearchingTitlesAgain() = runTest {
        val feed = "<rss><channel><link>https://ch.nicovideo.jp/ch2649354/video</link>" + (1..11).joinToString("") { n ->
            "<item><title>この素晴らしい世界に祝福を！３ 第${n}話「各話タイトル」</title><link>https://www.nicovideo.jp/watch/${1712629209L + n}</link></item>"
        } + "</channel></rss>"
        val requests = mutableListOf<String>()
        val service = object : JsonApiService {
            override suspend fun get(url: String, headers: Map<String, String>): retrofit2.Response<okhttp3.ResponseBody> {
                requests += url
                check(url.startsWith("https://ch.nicovideo.jp/")) { "Unexpected title search: $url" }
                return retrofit2.Response.success(feed.toResponseBody())
            }
            override suspend fun post(url: String, headers: Map<String, String>, body: okhttp3.RequestBody): retrofit2.Response<okhttp3.ResponseBody> = error("Unexpected POST")
        }
        val channels = ChannelCommentRepository(ApiClient(service))
        val listed = channels.input("konosuba_3rd")
        assertEquals((1..11).toList(), listed.numbers)
        assertEquals(2649354L, listed.id)
        assertEquals(2, requests.size)
        assertEquals("1712629210", channels.episode(listed.id, 1).id)
        assertEquals("1712629220", channels.episode(listed.id, 11).id)
        assertEquals(2, requests.size)
    }
    @Test fun registeredOfficialLinksKeepSeasonsSeparateAndIgnoreDanimeMainStore() {
        val entries = bangumiChannelEntries(data)
        assertEquals("gochiusa", selectAnimeChannel(entries, "ご注文はうさぎですか？"))
        assertEquals("gochiusa2", selectAnimeChannel(entries, "ご注文はうさぎですか??", 2015))
        assertNull(selectAnimeChannel(entries, "ご注文はうさぎですか??", 2014))
        assertNull(selectAnimeChannel(entries, "本店だけ"))
        assertEquals("mushokutensei", selectAnimeChannel(entries, "無職転生～異世界行ったら本気だす～(第2クール)"))
        assertNull(selectAnimeChannel(entries + AnimeChannelEntry("ご注文はうさぎですか？", emptyList(), 2014, "different"), "ご注文はうさぎですか？"))
    }
    @Test fun calendarLinksRejectDanimeAndAmbiguousChannels() {
        fun item(comment: String) = JSONObject().put("Title", "作品").put("Comment", comment)
        assertEquals("official", calendarChannelEntry(item("-[[ニコニコ https://ch.nicovideo.jp/official]]"))!!.channel)
        assertNull(calendarChannelEntry(item("https://ch.nicovideo.jp/ch$DANIME_CHANNEL")))
        assertNull(calendarChannelEntry(item("https://ch.nicovideo.jp/first https://ch.nicovideo.jp/second")))
        assertNull(calendarChannelEntry(item("https://example.com/fake")))
    }
    @Test fun mappingsAreCachedOnDiskAndReusedAfterRecreation() = runTest {
        val file = folder.newFile()
        var calls = 0
        val repository = AnimeChannelRepository(file, { calls++; data }, { name -> assertEquals("gochiusa2", name); 20L })
        assertEquals(20L, repository.official("ご注文はうさぎですか??"))
        assertEquals(20L, repository.official("ご注文はうさぎですか??"))
        assertEquals(1, calls)
        val reopened = AnimeChannelRepository(file, { error("Cached dataset expected") }, { 20L })
        assertEquals(20L, reopened.official("ご注文はうさぎですか??"))
    }
    @Test fun missingMappingUsesCalendarAndFailuresAllowLegacySearch() = runTest {
        val requests = mutableListOf<String>()
        val repository = AnimeChannelRepository(folder.newFile(), { url ->
            requests += url
            when {
                "raw.githubusercontent" in url -> data
                "TitleSearch" in url -> JSONObject("""{"Titles":{"123":{"TID":123,"Title":"新しい作品 第2期","FirstYear":2026}}}""")
                else -> JSONObject("""{"Titles":{"123":{"Title":"新しい作品 第2期","Comment":"https://ch.nicovideo.jp/new-work-2"}}}""")
            }
        }, { name -> assertEquals("new-work-2", name); 123L })
        assertEquals(123L, repository.official("新しい作品 第2期", 2026))
        assertEquals(3, requests.size)
        val offline = AnimeChannelRepository(folder.newFile(), { throw IOException() }, { error("No link") })
        assertNull(offline.official("未登録"))
    }
    @Test fun registeredChannelsArePreferredByPreviewAndPlaybackRepositories() = runTest {
        val correct = Candidate("so13", "作品 第13話", channelId = 42)
        val repository = CommentRepository(CommentStore(folder.newFolder()), search = { error("Registered channel should win") },
            officialLookup = { episode -> assertEquals(13, episode.number); listOf(correct) })
        assertEquals(listOf(correct), repository.searchEpisodeCandidates("作品", 13))
        val playback = repository.forPlayback(CommentStore(folder.newFolder()))
        assertEquals(listOf(correct), playback.searchEpisodeCandidates("作品", 13))
    }
    @Test fun failedChannelLookupsKeepNormalSearchAvailable() = runTest {
        val correct = Candidate("so1", "作品 第1話", channelId = 42)
        var searched = false
        val repository = CommentRepository(CommentStore(folder.newFolder()), search = { searched = true; listOf(correct) },
            officialLookup = { throw IOException() })
        assertTrue(repository.searchEpisodeCandidates("作品", 1).contains(correct))
        assertTrue(searched)
    }
    @Test fun automaticChannelUsesResolvedCourNumbersAndFallsBackWhenUnavailable() = runTest {
        val work = AnimeInfo(1, "作品 第2クール", "", "", null, 2026, 12, emptyList())
        var searched = false
        val correct = Candidate("so13", "作品 第13話", channelId = 42)
        val repository = SaveReviewRepository(resolve = { _, _ -> ResolvedEpisodeMetadata("新しい話", Episode("作品", 13)) },
            officialChannel = { 42L }, channelEpisode = { id, number -> assertEquals(42L, id); assertEquals(13, number); correct },
            candidates = { _, _ -> searched = true; emptyList() })
        val row = repository.prepare(work, listOf(Episode(work.title, 1)), setOf(PlaybackSource.OFFICIAL), null, 1) { _, _ -> }.episodes.single()
        assertEquals("so13", row.candidate!!.id)
        assertEquals(13, row.searchEpisode.number)
        assertFalse(searched)
        val fallback = SaveReviewRepository(officialChannel = { 42L }, channelEpisode = { _, _ -> throw IOException() }, candidates = { _, _ -> searched = true; emptyList() })
        fallback.prepare(work.copy(title = "作品"), listOf(Episode("作品", 1)), setOf(PlaybackSource.OFFICIAL), null, 1) { _, _ -> }
        assertTrue(searched)
    }
}
