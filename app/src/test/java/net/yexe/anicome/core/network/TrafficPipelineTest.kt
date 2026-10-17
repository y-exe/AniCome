package net.yexe.anicome

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.RequestBody
import okhttp3.ResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class TrafficPipelineTest {
    @Test fun officialSearchStopsOnQuotaWithoutFallbackSearchesOrNegativeCaching() = runTest {
        val dir = Files.createTempDirectory("anicome-quota-search-").toFile()
        try {
            var calls = 0
            val repo = CommentRepository(CommentStore(dir), search = { calls++; throw TrafficLimitReached() })
            repeat(2) {
                try { repo.obtain(Episode("テスト作品", 1), amount = 1, episodeTitle = "各話のタイトル"); fail() }
                catch (_: TrafficLimitReached) { }
            }
            assertEquals(2, calls)
        } finally { dir.deleteRecursively() }
    }
    private val never = object : JsonApiService {
        override suspend fun get(url: String, headers: Map<String, String>): retrofit2.Response<ResponseBody> = throw AssertionError("unexpected API request")
        override suspend fun post(url: String, headers: Map<String, String>, body: RequestBody): retrofit2.Response<ResponseBody> = throw AssertionError("unexpected API request")
    }
    @Test fun quotaDuringExtraSourcesRetainsTheOfficialArchiveAndDoesNotRememberMissingResults() = runTest {
        val dir = Files.createTempDirectory("anicome-quota-").toFile()
        try {
            val store = CommentStore(dir)
            val episode = Episode("テスト作品", 1)
            val original = listOf(Comment(1000, "　■\n ■　"), Comment(2000, "公式"))
            store.save(episode, Candidate("so900001", "テスト作品 第1話", 100, 1400), original, 20)
            val file = dir.walkTopDown().first { it.isFile && it.extension == "json" }; val before = file.readBytes()
            var searches = 0; var quota = true
            val official = CommentRepository(store, search = { throw AssertionError("official cache was ignored") }, load = { _, _ -> throw AssertionError("official cache was ignored") })
            val repo = PlaybackSourceRepository(official, ApiClient(never), SettingsRepository(MemoryPreferences(), backgroundScope), videoSearch = {
                searches++; if (quota) throw TrafficLimitReached(); emptyList()
            })
            val partial = mutableListOf<PlaybackComments>()
            try { repo.obtain(episode, 1400, "", 20, {}, { _, _, _ -> }, requestedSources = setOf(PlaybackSource.OFFICIAL, PlaybackSource.DANIME), onPartial = { partial += it }); fail() }
            catch (_: TrafficLimitReached) { }
            assertTrue(partial.any { it.comments == original }); assertArrayEquals(before, file.readBytes())
            quota = false
            val result = repo.obtain(episode, 1400, "", 20, {}, { _, _, _ -> }, requestedSources = setOf(PlaybackSource.OFFICIAL, PlaybackSource.DANIME))
            assertEquals(original, result.comments); assertTrue(searches > 1)
        } finally { dir.deleteRecursively() }
    }
    @Test fun completedSourcesAreSavedBeforeQuotaFailureAndUsedOnTheNextAttempt() = runTest {
        val dir = Files.createTempDirectory("anicome-quota-partial-").toFile()
        try {
            val store = CommentStore(dir); val episode = Episode("テスト作品", 1)
            val official = CommentRepository(store)
            var danimeReads = 0
            val started = CompletableDeferred<Unit>()
            val repo = PlaybackSourceRepository(official, ApiClient(never), SettingsRepository(MemoryPreferences(), backgroundScope), videoSearch = {
                listOf(Candidate("so900002", "テスト作品 第1話", DANIME_CHANNEL, 1400), Candidate("so900003", "テスト作品 第1話", channelId = 100, tags = "コメント専用動画", duration = 1400))
            }, videoLoad = { id, _ ->
                if (id == "so900002") { danimeReads++; started.complete(Unit); listOf(Comment(1000, "dアニメ")) }
                else { started.await(); delay(100); throw TrafficLimitReached() }
            })
            try { repo.obtain(episode, 0, "", 1, {}, { _, _, _ -> }, offlineSave = true, requestedSources = setOf(PlaybackSource.DANIME, PlaybackSource.COMMENT_ONLY)); fail() }
            catch (_: TrafficLimitReached) { }
            assertEquals("dアニメ", store.read(episode)!!.comments.single().body)
            val cached = repo.obtain(episode, 0, "", 1, {}, { _, _, _ -> }, offlineSave = true, requestedSources = setOf(PlaybackSource.DANIME))
            assertEquals(1, danimeReads); assertTrue(cached.fromArchive)
        } finally { dir.deleteRecursively() }
    }
}
