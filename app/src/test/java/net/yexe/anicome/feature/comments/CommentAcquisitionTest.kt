package net.yexe.anicome

import kotlinx.coroutines.*
import okhttp3.RequestBody
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Response

class CommentAcquisitionTest {
    @get:Rule val folder = TemporaryFolder()
    private fun raw(no: Int, body: String = "comment $no", at: Long = no * 100L) = JSONObject()
        .put("no", no).put("postedAt", java.time.Instant.ofEpochSecond(no.toLong()).toString())
        .put("vposMs", at).put("body", body).put("commands", JSONArray(listOf("ue", "big", "red")))
    private fun comments(from: Int, to: Int) = (from..to).map { Comment(it * 100L, "comment $it", raw(it).toString()) }
    private fun threads(vararg rows: JSONObject) = JSONArray().put(JSONObject().put("id", "123").put("fork", "main")
        .put("commentCount", 1000).put("comments", JSONArray(rows.toList())))
    private fun response(vararg rows: JSONObject) = JSONObject().put("data", JSONObject().put("threads", threads(*rows)))

    @Test fun countFetchDeduplicatesPagesAndKeepsNewestWithCommands() = runBlocking {
        var calls = 0
        val result = Comments.collectPages(threads(raw(8), raw(9), raw(10)), Int.MAX_VALUE, { _, _ -> }, wanted = 5) {
            calls++
            response(raw(5), raw(6), raw(7), raw(8))
        }
        assertEquals(1, calls)
        assertEquals((6..10).map { "comment $it" }, result.map { it.body })
        assertTrue(result.all { JSONObject(it.raw!!).getJSONArray("commands").getString(0) == "ue" })
    }

    @Test fun countFetchDoesNotRequestHistoryWhenInitialPageIsEnough() = runBlocking {
        val result = Comments.collectPages(threads(raw(8), raw(9), raw(10)), Int.MAX_VALUE, { _, _ -> }, wanted = 1) {
            error("Must not fetch another page")
        }
        assertEquals(listOf("comment 10"), result.map { it.body })
    }

    @Test fun countFetchStopsWhenServerRepeatsTheSamePage() = runBlocking {
        var calls = 0
        val result = Comments.collectPages(threads(raw(8), raw(9)), Int.MAX_VALUE, { _, _ -> }, wanted = 100) {
            calls++
            response(raw(8), raw(9))
        }
        assertEquals(1, calls)
        assertEquals(2, result.size)
    }

    @Test fun deletedPageDoesNotHideOlderAvailableComments() = runBlocking {
        var calls = 0
        val result = Comments.collectPages(threads(raw(10)), Int.MAX_VALUE, { _, _ -> }, wanted = 3) {
            calls++
            if (calls == 1) response(raw(8, ""), raw(9, "")) else response(raw(1), raw(2))
        }
        assertEquals(2, calls)
        assertEquals(listOf("comment 1", "comment 2", "comment 10"), result.map { it.body })
    }

    @Test fun multiplierAboveTwentyReallyFetchesMorePages() = runBlocking {
        var calls = 0
        val result = Comments.collectPages(threads(raw(100)), 25, { _, _ -> }) {
            calls++
            response(raw(100 - calls))
        }
        assertEquals(24, calls)
        assertEquals(25, result.size)
        assertEquals(Int.MAX_VALUE, commentTarget(Int.MAX_VALUE))
    }

    @Test fun combinedCountRedistributesUnusedQuotaWithoutMutatingArchive() {
        val original = listOf(PlaybackCommentSlot("so1", "official", PlaybackSource.OFFICIAL, comments(1, 2)),
            PlaybackCommentSlot("so2", "danime", PlaybackSource.DANIME, comments(3, 20)))
        val limited = limitCommentSlots(original, 7)
        assertEquals(listOf(2, 5), limited.map { it.comments.size })
        assertEquals((16..20).map { "comment $it" }, limited[1].comments.map { it.body })
        assertEquals(18, original[1].comments.size)
        assertEquals(1, limitCommentSlots(original, 1).sumOf { it.comments.size })
    }

    @Test fun countArchiveDoesNotPretendToContainFullMultiplierHistory() = runBlocking {
        val store = CommentStore(folder.newFolder())
        val episode = Episode("テスト作品", 1)
        val source = Candidate("so123", episode.label, channelId = 123)
        val saved = store.save(episode, source, comments(1, 3), amount = 99, countLimit = 5)
        assertEquals(5, store.read(episode)!!.countLimit)
        assertTrue(saved.satisfiesCommentTarget(1, 5))
        assertFalse(saved.satisfiesCommentTarget(1, 6))
        assertFalse(saved.satisfiesCommentTarget(1, 0))
        val repository = CommentRepository(store, load = { _, _ -> error("No multiplier fetch") },
            countLoad = { _, _, target, _ -> assertEquals(6, target); comments(1, 6) })
        assertEquals(3, repository.obtain(episode, source, maxComments = 5).comments.size)
        assertEquals(6, repository.obtain(episode, source, maxComments = 6).comments.size)
    }

    @Test fun offlineMultiSourceSaveAndReplayObeyCombinedLimit() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = CommentStore(folder.newFolder())
            val episode = Episode("テスト作品", 1)
            val official = Candidate("so123", episode.label, channelId = 123, duration = 1400)
            val danime = Candidate("so456", episode.label, channelId = DANIME_CHANNEL, duration = 1400)
            var fetched = 0
            val repository = CommentRepository(store, search = { listOf(official) }, countLoad = { _, _, _, _ -> fetched++; comments(1, 10) })
            val noNetwork = object : JsonApiService {
                override suspend fun get(url: String, headers: Map<String, String>): Response<ResponseBody> = error("Unexpected request $url")
                override suspend fun post(url: String, headers: Map<String, String>, body: RequestBody): Response<ResponseBody> = error("Unexpected request $url")
            }
            val sources = PlaybackSourceRepository(repository, ApiClient(noNetwork), SettingsRepository(MemoryPreferences(), scope),
                videoSearch = { listOf(danime) }, countVideoLoad = { _, _, _, _ -> fetched++; comments(11, 20) })
            val selected = setOf(PlaybackSource.OFFICIAL, PlaybackSource.DANIME)
            val saved = OfflineCommentRepository(repository, sources).save(episode, selected = selected, maxComments = 7)
            assertEquals(7, saved.slots.sumOf { it.comments.size })
            assertEquals(selected, saved.slots.map { it.source }.toSet())
            assertEquals(7, store.read(episode)!!.countLimit)
            assertEquals(2, fetched)
            assertEquals(7, OfflineCommentRepository(repository, sources).save(episode, selected = selected, maxComments = 7).info.count)
            assertEquals(2, fetched)
            val replay = sources.obtain(episode, 0, "", 1, {}, { _, _, _ -> }, offlineSave = true, requestedSources = selected, maxComments = 7)
            assertEquals(7, replay.comments.size)
            assertEquals(2, fetched)
        } finally { scope.cancel() }
    }

    @Test fun settingsAndReservationPreserveLargeAmountAndCountAcrossRestart() {
        val profile = CommentDisplayProfile(amount = 100, countMode = true, countLimit = 50000)
        assertEquals(profile, CommentDisplayProfile.parse(profile.json()))
        val prefs = androidx.datastore.preferences.core.mutablePreferencesOf()
        profile.write(prefs)
        assertEquals(profile, CommentDisplayProfile.read(prefs))
        val reservation = ReservationSpec(Episode("テスト作品", 1), amount = 100, maxComments = 50000)
        val restored = ReservationSpec.decode(reservation.encode())
        assertEquals(100, restored.amount)
        assertEquals(50000, restored.maxComments)
        assertEquals(50000, restored.input().getInt("maxComments", 0))
        assertEquals(0, CommentDisplayProfile.parse("{\"amount\":99}")!!.let { if (it.countMode) it.countLimit else 0 })
    }

    @Test fun replacingVideoPreservesCountBasedAcquisition() = runBlocking {
        val store = CommentStore(folder.newFolder())
        val episode = Episode("テスト作品", 1)
        val original = store.save(episode, Candidate("so123", episode.label, channelId = 123), comments(1, 3), countLimit = 3)
        var requested = 0
        val repository = CommentRepository(store, load = { _, _ -> error("Must retain count mode") }, countLoad = { _, _, target, _ ->
            requested = target
            comments(4, 6)
        })
        val replacement = repository.replaceOfficial(original.info, Candidate("so456", episode.label, channelId = 123))
        assertEquals(3, requested)
        assertEquals(3, replacement.countLimit)
        assertEquals("so456", replacement.info.sourceId)
    }
}
