package net.yexe.anicome

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import retrofit2.Response

class LibraryAcquisitionTest {
    @get:Rule val folder = TemporaryFolder()
    private fun text(name: String) = javaClass.getResource("/episode-metadata/$name")!!.readText()
    private fun metadata(name: String) = EpisodeMetadataRepository(request = { url, _ -> JSONObject(text("$name-${if (url.contains("TitleSearch")) "search" else "detail"}.json")) })
    @Test fun MachikadoIdenticalCatalogNamesAreSeparatedUsingTheReleaseYear() = runBlocking {
        val first = AnimeInfo(42137, "まちカドまぞく", "", "", null, 2019, 12, emptyList(), "kitsu")
        val second = first.copy(id = 43547, year = 2022)
        val repo = SaveCommentRepository(catalogSearch = { listOf(first, second) }, metadata = metadata("machi"))
        val works = repo.search("まちカドまぞく")
        assertEquals("まちカドまぞく", works[0].title)
        assertEquals("まちカドまぞく 2丁目", works[1].title)
        val source = metadata("machi").resolve(second, 1)!!
        assertEquals("まちカドまぞく 2丁目", source.episode.series)
        assertFalse(savedEpisodeMatches(source.episode, Candidate("so35384944", "まちカドまぞく 第1話「優子の目覚め!!」")))
    }
    @Test fun DropkickDashPunctuationIsNeverTreatedAsTheFirstSeason() = runBlocking {
        for (suffix in listOf("'", "’", "`")) {
            val selected = AnimeInfo(42126, "邪神ちゃんドロップキック$suffix", "", "", null, 2020, 12, emptyList(), "kitsu")
            val canonical = metadata("jashin").canonical(selected)
            assertEquals(2, animeWorkIdentity(canonical.title).season)
            assertFalse(savedEpisodeMatches(Episode(canonical.title, 1), Candidate("so1", "邪神ちゃんドロップキック 第1話")))
            assertTrue(savedEpisodeMatches(Episode(canonical.title, 1), Candidate("so2", "邪神ちゃんドロップキック’ 第1話")))
        }
    }
    @Test fun LessonNumbersAndTheOnAirEditionMatchTheOfficialSeries() {
        val candidate = Candidate("so46162454", "よわよわ先生 オンエアver. Lesson.1「こわこわ先生」", channelId = 2650096)
        assertEquals(1, EpisodeMatcher.extract(candidate.title).episode!!.number)
        assertTrue(savedEpisodeMatches(Episode("よわよわ先生", 1), candidate, "こわこわ先生"))
    }
    @Test fun SavedRowsDisplayTheVideoNumberInsteadOfTheLocalReservationIndex() {
        val saved = SavedEpisode(Episode("無職転生 第2クール", 2), "so1", "無職転生 第13話「すれ違い」", 0, 1, 1)
        assertEquals(13, saved.videoEpisodeNumber())
        assertEquals(2, saved.episode.number)
    }
    @Test fun ChannelIdValidationRejectsOtherSitesAndTheDanimeChannel() {
        assertEquals(2650096L, niconicoChannelId("ch2650096"))
        assertEquals(2650096L, niconicoChannelId("https://ch.nicovideo.jp/ch2650096"))
        assertNull(niconicoChannelId("https://example.com/ch2650096"))
        assertNull(niconicoChannelId("ch2632720"))
    }
    @Test fun ChannelNamesUrlsAndNumericIdsResolveToTheSameOfficialChannel() = runBlocking {
        assertEquals("mushokutensei", niconicoChannelName("https://ch.nicovideo.jp/mushokutensei/video"))
        assertEquals("mushokutensei", niconicoChannelName("mushokutensei"))
        assertNull(niconicoChannelName("https://ch.nicovideo.jp.example.com/mushokutensei"))
        assertNull(niconicoChannelName("https://ch.nicovideo.jp@evil.example/mushokutensei"))
        val repo = ChannelCommentRepository(ApiClient(FixtureService(2650096)))
        for (value in listOf("2650096", "ch2650096", "yowayowasensei", "https://ch.nicovideo.jp/yowayowasensei")) {
            assertEquals(2650096L, repo.input(value).id)
        }
    }
    @Test fun ProvidedMushokutenseiUrlHasTheExpectedNumericChannelId() {
        assertEquals(2646899L, channelFeedId(text("mushokutensei.xml")))
        assertEquals(2646899L, niconicoChannelId("https://ch.nicovideo.jp/ch2646899/video"))
        assertEquals((6..23).toSet(), channelFeedNumbers(text("mushokutensei.xml")))
        assertEquals("無職転生", channelSearchTitle(text("mushokutensei.xml")).substringBefore('〜').trim())
    }
    @Test fun ProvidedMushokutenseiNameAndUrlLoadThe23OfficialEpisodes() = runBlocking {
        val repo = ChannelCommentRepository(ApiClient(FixtureService(2646899)))
        val work = repo.input("mushokutensei")
        assertEquals((1..23).toList(), work.numbers)
        assertTrue(work.videos.all { it.channelId == 2646899L })
        assertEquals(work.id, repo.input("https://ch.nicovideo.jp/mushokutensei").id)
    }
    @Test fun SelectedSecondCourRestrictsTheSharedChannelToTheActual12Through23Episodes() = runBlocking {
        val channels = ChannelCommentRepository(ApiClient(FixtureService(2646899)))
        val repo = SaveCommentRepository(metadata = metadata("mushoku"), channels = channels)
        val selected = AnimeInfo(0, "無職転生 ～異世界行ったら本気だす～ 第2クール", "", "", null, 2021, 12, emptyList(), "syoboi")
        val result = repo.channel("mushokutensei", selected)
        assertEquals((12..23).toList(), result.numbers)
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun ChannelRecoveryClearsTheFailedCatalogSearchAndEnablesSaving() = runBlocking {
        val dispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            val repo = SaveCommentRepository(catalogSearch = { throw java.io.IOException("offline") }, channels = ChannelCommentRepository(ApiClient(FixtureService(2650096))))
            val model = SaveCommentViewModel(repo)
            model.query("よわよわ先生")
            dispatcher.scheduler.advanceUntilIdle()
            assertTrue(model.state.value.failure)
            model.channelInput("yowayowasensei")
            model.connectChannel()
            val state = withTimeout(5000) { model.state.first { it.channel != null } }
            assertFalse(state.failure); assertFalse(state.loading); assertTrue(state.canSave)
            model.start("")
        } finally { Dispatchers.resetMain() }
    }
    @Test fun OfficialChannelSelectionLoadsAllPagesAndNeverIncludesTheFirstSeason() = runBlocking {
        val repo = ChannelCommentRepository(ApiClient(FixtureService(2648350)))
        val series = repo.load(2648350)
        assertEquals((1..12).toList(), series.numbers)
        assertTrue(series.videos.all { it.channelId == 2648350L && it.title.contains("2丁目") })
        assertEquals("so40277014", repo.episode(2648350, 1).id)
        val state = SaveCommentState(query = series.title, work = AnimeInfo(2648350, series.title, "", "", null, null, 12, emptyList(), "channel"), allEpisodes = true, channel = series)
        assertEquals((1..12).toList(), state.episodesToSave().map { it.number })
    }
    @Test fun YowayowaOfficialChannelContainsAll12Lessons() = runBlocking {
        val series = ChannelCommentRepository(ApiClient(FixtureService(2650096))).load(2650096)
        assertEquals((1..12).toList(), series.numbers)
        assertTrue(series.videos.all { it.channelId == 2650096L })
    }
    @Test fun ChannelReservationsKeepContinuousEpisodeNumbers() {
        val videos = (13..24).map { Candidate("so$it", "作品 第${it}話", channelId = 111) }
        val channel = ChannelAnime(111, "作品", videos)
        val work = AnimeInfo(111, "作品", "", "", null, null, 12, emptyList(), "channel")
        val state = SaveCommentState(query = work.title, work = work, channel = channel, allEpisodes = true)
        assertEquals((13..24).toList(), state.episodesToSave().map { it.number })
        assertTrue(state.copy(allEpisodes = false, episode = "13").canSave)
        assertFalse(state.copy(allEpisodes = false, episode = "1").canSave)
    }
    @Test fun DismissedFailedReservationsRemainHiddenAfterRepositoryRecreation() = runBlocking {
        val state = MutableStateFlow<Preferences>(emptyPreferences())
        val mutex = Mutex()
        val store = object : DataStore<Preferences> {
            override val data = state
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
                transform(state.value).also { state.value = it }
            }
        }
        val visibility = ReservationVisibility(store)
        coroutineScope { awaitAll(async { visibility.dismiss(setOf("failed")) }, async { visibility.dismiss(setOf("running", "waiting")) }) }
        assertEquals(setOf("failed", "running", "waiting"), ReservationVisibility(store).hidden.first())
        assertFalse("retried-new-id" in ReservationVisibility(store).hidden.first())
    }

    private inner class FixtureService(private val channel: Long) : JsonApiService {
        override suspend fun get(url: String, headers: Map<String, String>): Response<ResponseBody> {
            assertFalse(headers.containsKey("Cookie"))
            val name = if (url.contains("rss=2.0")) {
                assertFalse(headers.getValue("User-Agent").contains("Android"))
                assertTrue(headers.getValue("Accept").contains("rss+xml"))
                "channel-$channel.xml"
            } else {
                assertFalse(url.contains("jsonFilter"))
                assertEquals("*/*", headers["Accept"])
                val offset = Regex("_offset=(\\d+)").find(url)!!.groupValues[1].toInt()
                "channel-$channel-${offset / 100}.json"
            }
            return Response.success(text(name).toResponseBody())
        }
        override suspend fun post(url: String, headers: Map<String, String>, body: RequestBody): Response<ResponseBody> = error("Unexpected POST")
    }
}
