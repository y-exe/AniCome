package net.yexe.anicome

import java.nio.file.Files
import kotlinx.coroutines.*
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.RequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class PlaybackSourcesTest {
    private suspend fun allSourceSettings(scope: CoroutineScope): SettingsRepository {
        val store = MemoryPreferences()
        store.updateData { it.toMutablePreferences().apply {
            this[androidx.datastore.preferences.core.booleanPreferencesKey("syncOnlyConfirmed")] = false
            PlaybackSource.entries.mapNotNull { it.settingKey }.forEach { key -> this[androidx.datastore.preferences.core.booleanPreferencesKey(key)] = true }
        } }
        return SettingsRepository(store, scope)
    }

    @Test fun freshAutomaticPlaybackUsesOnlyOfficialAndDanimeFromTheSavedBundle() = runBlocking {
        val directory = Files.createTempDirectory("anicome-default-sources").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val episode = Episode("作品", 1)
            val store = CommentStore(directory)
            store.saveSources(episode, PlaybackSource.entries.filter { it.settingKey != null }.map {
                PlaybackCommentSlot(it.name, episode.label, it, listOf(Comment(1000, it.name)), true, durationMs = 1400000, amount = 5)
            })
            val preferences = MemoryPreferences()
            preferences.updateData { it.toMutablePreferences().apply { this[androidx.datastore.preferences.core.booleanPreferencesKey("syncOnlyConfirmed")] = false } }
            val repository = CommentRepository(store, search = { error("Cached sources must not search") }, load = { _, _ -> error("Cached sources must not fetch") })
            val api = object : JsonApiService {
                override suspend fun get(url: String, headers: Map<String, String>): Response<ResponseBody> = error("Disabled sources must not fetch")
                override suspend fun post(url: String, headers: Map<String, String>, body: RequestBody): Response<ResponseBody> = error("Disabled sources must not fetch")
            }
            val sources = PlaybackSourceRepository(repository, ApiClient(api), SettingsRepository(preferences, scope), videoSearch = { error("Cached sources must not search") })
            assertEquals(DefaultCommentSources, sources.cached(episode, 1400000)!!.slots.map { it.source }.toSet())
            assertEquals(DefaultCommentSources, sources.obtain(episode, 1400, "", 5, {}, { _, _, _ -> }).slots.map { it.source }.toSet())
            assertEquals(6, store.read(episode)!!.slots.size)
        } finally { scope.cancel(); directory.deleteRecursively() }
    }
    @Test fun savedOfficialAndDanimePlayTogetherWithoutDeviceDurationOrWithDifferentCredits() = runBlocking {
        val directory = Files.createTempDirectory("anicome-saved-dual-source").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val episode = Episode("ようこそ実力至上主義の教室へ 4th Season 2年生編1学期", 2)
            val store = CommentStore(directory)
            val official = PlaybackCommentSlot("so46115421", "${episode.label} 契約と代償", PlaybackSource.OFFICIAL,
                listOf(Comment(1000, "official")), durationMs = 1420000, amount = 5)
            val danime = PlaybackCommentSlot("so46115239", "${episode.label} 契約と代償", PlaybackSource.DANIME,
                listOf(Comment(2000, "danime")), durationMs = 1420000, amount = 5)
            store.saveSources(episode, listOf(official, danime))
            val before = store.exportJson(episode)
            val repository = CommentRepository(store, search = { error("Saved episode must not search") }, load = { _, _ -> error("Saved episode must not fetch") })
            val api = object : JsonApiService {
                override suspend fun get(url: String, headers: Map<String, String>): Response<ResponseBody> = error("Saved sources must not fetch")
                override suspend fun post(url: String, headers: Map<String, String>, body: RequestBody): Response<ResponseBody> = error("Saved sources must not fetch")
            }
            val sources = PlaybackSourceRepository(repository, ApiClient(api), SettingsRepository(MemoryPreferences(), scope), videoSearch = { error("Saved sources must not search") })
            for (duration in listOf(0L, 1420L, 1440L)) {
                val cached = sources.cached(episode, duration * 1000)!!
                val partials = mutableListOf<PlaybackComments>()
                val automatic = sources.obtain(episode, duration, "", 5, {}, { _, _, _ -> }, onPartial = { partials += it })
                for (result in listOf(cached, automatic) + partials) {
                    assertEquals("duration=$duration", DefaultCommentSources, result.slots.map { it.source }.toSet())
                    assertEquals(listOf("official", "danime"), result.comments.map { it.body })
                    assertTrue(result.slots.all { it.fromArchive && it.sync == CommentSync.NATIVE })
                }
            }
            assertEquals(before, store.exportJson(episode))
        } finally { scope.cancel(); directory.deleteRecursively() }
    }

    @Test fun joinsCompleteChaptersAtTheirActualDurationsAndRejectsGaps() {
        val episode = Episode("テスト作品", 1)
        val parts = listOf(Candidate("so900011", "テスト作品 第1話 Chapter.1", DANIME_CHANNEL, 700), Candidate("so900012", "テスト作品 第1話 Chapter.2", DANIME_CHANNEL, 700))
        assertEquals(PlaybackSource.CHAPTER, matchingPlaybackVideos(episode, parts, 1400).single().first)
        assertTrue(matchingPlaybackVideos(episode, parts.drop(1), 1400).isEmpty())
        assertTrue(matchingPlaybackVideos(episode, parts, 1000).isEmpty())
        val joined = joinChapterComments(listOf(700L to listOf(Comment(10, "part1")), 700L to listOf(Comment(20, "part2"))))
        assertEquals(listOf(10L, 700020L), joined.map { it.at })
    }
    @Test fun archivesPreserveCommandsAndAlignMicrosecondBroadcastTimes() {
        val xml = """<packet><chat thread="x" no="1" vpos="123" mail="ue red" premium="1" date="1000">A&amp;B</chat><chat deleted="1" vpos="5">deleted</chat><chat vpos="1">/gift</chat></packet>"""
        val c = parseNicologComments(xml).single()
        assertEquals(1230L, c.at); assertEquals("A&B", c.body)
        assertEquals("ue", JSONObject(c.raw!!).getJSONArray("commands").getString(0))
        val json = JSONObject("""{"packet":[{"chat":{"thread":"x","no":1,"date":1001,"date_usec":234000,"content":"hello"}},{"chat":{"date":999,"content":"before"}}]}""")
        assertEquals(1234L, parseJikkyoComments(json, 1000).single().at)
        try { parseRemoteXml("<!DOCTYPE x [<!ENTITY secret SYSTEM 'file:///test'>]><x>&secret;</x>"); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun automaticPlaybackCombinesSixSourcesButPersistsOnlyOfficial() = runBlocking {
        val directory = Files.createTempDirectory("anicome-sources").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = CommentStore(directory)
        val official = Candidate("so900001", "テスト作品 第1話", 123, 1400)
        val danime = Candidate("so900002", "テスト作品 第1話", DANIME_CHANNEL, 1400)
        val chapters = listOf(Candidate("so900003", "テスト作品 第1話 Chapter.1", DANIME_CHANNEL, 700), Candidate("so900004", "テスト作品 第1話 Chapter.2", DANIME_CHANNEL, 700))
        val szbh = Candidate("sm900005", "テスト作品 第1話", duration = 1400, userId = 123, tags = "コメント専用動画")
        val service = object : JsonApiService {
            override suspend fun get(url: String, headers: Map<String, String>): Response<ResponseBody> {
                val body = when {
                    url.contains("json.php") -> """{"Titles":{"1":{"Title":"テスト作品"}}}"""
                    url.contains("db.php") -> """<ProgLookupResponse><ProgItems><ProgItem><ChID>19</ChID><StTime>2020-01-01 00:00:00</StTime><EdTime>2020-01-01 00:30:00</EdTime></ProgItem></ProgItems></ProgLookupResponse>"""
                    url.contains("kakolog") -> """{"packet":[{"chat":{"thread":"x","no":1,"date":1577804401,"content":"jikkyo"}}]}"""
                    else -> """<packet><chat thread="live" no="1" vpos="100" date="1000">live</chat></packet>"""
                }
                return Response.success(body.toResponseBody())
            }
            override suspend fun post(url: String, headers: Map<String, String>, body: RequestBody): Response<ResponseBody> {
                val buffer = okio.Buffer(); body.writeTo(buffer); val path = JSONObject(buffer.readUtf8()).getString("path")
                val json = when {
                    url.endsWith("/get") -> """{"data":{"raw_url":"https://archive.example/one.xml"}}"""
                    path == "/nico-live-anime" -> """{"data":{"content":[{"name":"テスト作品","is_dir":true}]}}"""
                    else -> """{"data":{"content":[{"name":"第1話上映会.xml","is_dir":false}]}}"""
                }
                return Response.success(json.toResponseBody())
            }
        }
        try {
            val repository = CommentRepository(store, search = { listOf(official) }, load = { _, _ -> listOf(Comment(1000, "official")) })
            val sources = PlaybackSourceRepository(repository, ApiClient(service), allSourceSettings(scope),
                videoSearch = { listOf(official, danime, szbh) + chapters }, videoLoad = { id, _ -> listOf(Comment(1000, id)) })
            val partials = mutableListOf<Int>()
            val result = sources.obtain(Episode("テスト作品", 1), 1400, "", 1, {}, { _, _, _ -> }) { partials += it.comments.size }
            assertEquals(PlaybackSource.entries.filter { it.settingKey != null }.toSet(), result.slots.map { it.source }.toSet())
            assertTrue(partials.isNotEmpty())
            assertEquals(listOf("official"), store.read(Episode("テスト作品", 1))!!.comments.map { it.body })
            assertEquals(1, store.list().size)
            assertNull(store.readSource(danime.id))
            val episode = Episode("テスト作品", 1)
            val offline = OfflineCommentRepository(repository, sources).save(episode, selected = OfflineSources - PlaybackSource.VIDEO)
            assertEquals(6, offline.slots.size)
            assertEquals(1, store.list().size)
            assertEquals(offline.slots.sumOf { it.comments.size }, store.list().single().count)
            assertEquals(listOf(danime.id), store.readSource(danime.id)!!.comments.map { it.body })
            assertEquals(701000L, offline.slots.single { it.source == PlaybackSource.CHAPTER }.comments.last().at)
            val restored = sources.cached(episode, 1400000)!!
            assertEquals(6, restored.slots.size)
            assertTrue(restored.slots.all { it.fromArchive })
            assertTrue(restored.slots.single { it.source == PlaybackSource.JIKKYO }.comments.first().raw!!.contains("jikkyo"))
            val backup = CommentStore(Files.createTempDirectory("anicome-offline-import").toFile())
            backup.importJson(store.exportJson(episode)!!)
            assertEquals(offline.slots.map { it.comments }, backup.read(episode)!!.slots.map { it.comments })
            store.save(episode, official, listOf(Comment(2000, "new official")))
            assertEquals(6, store.read(episode)!!.slots.size)
            assertEquals(listOf(danime.id), store.readSource(danime.id)!!.comments.map { it.body })
            val noNetwork = object : JsonApiService {
                override suspend fun get(url: String, headers: Map<String, String>): Response<ResponseBody> { fail("Offline playback requested $url"); error("unreachable") }
                override suspend fun post(url: String, headers: Map<String, String>, body: RequestBody): Response<ResponseBody> { fail("Offline playback requested $url"); error("unreachable") }
            }
            val offlineSettings = allSourceSettings(scope)
            val cachedSources = PlaybackSourceRepository(repository, ApiClient(noNetwork), offlineSettings,
                videoSearch = { fail("Offline playback searched videos"); emptyList() }, videoLoad = { _, _ -> fail("Offline playback fetched video comments"); emptyList() })
            val cachedPlayback = cachedSources.obtain(episode, 1400, "", 1, {}, { _, _, _ -> })
            assertEquals(6, cachedPlayback.slots.size)
            assertTrue(cachedPlayback.slots.all { it.fromArchive })
            val second = Episode("テスト作品", 2)
            val onlyDanime = PlaybackSourceRepository(repository, ApiClient(noNetwork), offlineSettings,
                videoSearch = { listOf(danime.copy(id = "so900020", title = second.label)) }, videoLoad = { _, _ -> listOf(Comment(1000, "danime only")) })
            val savedDanime = OfflineCommentRepository(repository, onlyDanime).save(second, selected = setOf(PlaybackSource.DANIME))
            assertEquals("DANIME", savedDanime.info.sources)
            assertEquals(listOf("danime only"), onlyDanime.cached(second, 1400000)!!.comments.map { it.body })
            val onlyVideo = PlaybackSourceRepository(repository, ApiClient(noNetwork), offlineSettings,
                videoSearch = { listOf(Candidate("sm900021", "テスト作品 第3話", duration = 1400, userId = 42)) }, videoLoad = { _, _ -> listOf(Comment(1000, "video only")) })
            val savedVideo = OfflineCommentRepository(repository, onlyVideo).save(Episode("テスト作品", 3), selected = setOf(PlaybackSource.VIDEO))
            assertEquals("VIDEO", savedVideo.info.sources)
        } finally { scope.cancel(); directory.deleteRecursively() }
    }
    @Test fun automaticPlaybackPersistsEverySourceInSeparateExpiringCache() = runBlocking {
        val directory = Files.createTempDirectory("anicome-sources").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manual = CommentStore(java.io.File(directory, "manual"))
        var now = 1000L
        val store = CommentStore(java.io.File(directory, "cache"), fallback = manual, expiresAfter = { 100L }, clock = { now })
        val official = Candidate("so900001", "テスト作品 第1話", 123, 1400)
        val danime = Candidate("so900002", "テスト作品 第1話", DANIME_CHANNEL, 1400)
        val chapters = listOf(Candidate("so900003", "テスト作品 第1話 Chapter.1", DANIME_CHANNEL, 700), Candidate("so900004", "テスト作品 第1話 Chapter.2", DANIME_CHANNEL, 700))
        val szbh = Candidate("sm900005", "テスト作品 第1話", duration = 1400, userId = 123, tags = "コメント専用動画")
        val service = object : JsonApiService {
            override suspend fun get(url: String, headers: Map<String, String>): Response<ResponseBody> {
                val body = when {
                    url.contains("json.php") -> """{"Titles":{"1":{"Title":"テスト作品"}}}"""
                    url.contains("db.php") -> """<ProgLookupResponse><ProgItems><ProgItem><ChID>19</ChID><StTime>2020-01-01 00:00:00</StTime><EdTime>2020-01-01 00:30:00</EdTime></ProgItem></ProgItems></ProgLookupResponse>"""
                    url.contains("kakolog") -> """{"packet":[{"chat":{"thread":"x","no":1,"date":1577804401,"content":"jikkyo"}}]}"""
                    else -> """<packet><chat thread="live" no="1" vpos="100" date="1000">live</chat></packet>"""
                }
                return Response.success(body.toResponseBody())
            }
            override suspend fun post(url: String, headers: Map<String, String>, body: RequestBody): Response<ResponseBody> {
                val buffer = okio.Buffer(); body.writeTo(buffer); val path = JSONObject(buffer.readUtf8()).getString("path")
                val json = when {
                    url.endsWith("/get") -> """{"data":{"raw_url":"https://archive.example/one.xml"}}"""
                    path == "/nico-live-anime" -> """{"data":{"content":[{"name":"テスト作品","is_dir":true}]}}"""
                    else -> """{"data":{"content":[{"name":"第1話上映会.xml","is_dir":false}]}}"""
                }
                return Response.success(json.toResponseBody())
            }
        }
        try {
            val repository = CommentRepository(store, search = { listOf(official) }, load = { _, _ -> listOf(Comment(1000, "official")) })
            val sources = PlaybackSourceRepository(repository, ApiClient(service), allSourceSettings(scope),
                videoSearch = { listOf(official, danime, szbh) + chapters }, videoLoad = { id, _ -> listOf(Comment(1000, id)) })
            val partials = mutableListOf<Int>()
            val result = sources.obtain(Episode("テスト作品", 1), 1400, "", 1, {}, { _, _, _ -> }) { partials += it.comments.size }
            assertEquals(PlaybackSource.entries.filter { it.settingKey != null }.toSet(), result.slots.map { it.source }.toSet())
            assertTrue(partials.isNotEmpty())
            assertEquals(listOf("official"), store.read(Episode("テスト作品", 1))!!.comments.map { it.body })
            assertEquals(1, store.list().size)
            assertNotNull(store.readSource(danime.id))
            assertTrue(manual.list().isEmpty())
            assertEquals(6, store.readOwn(Episode("テスト作品", 1))!!.slots.size)
            val episode = Episode("テスト作品", 1)
            val original = store.exportJson(episode)
            val reused = sources.obtain(episode, 1400, "", 1, {}, { _, _, _ -> })
            assertTrue(reused.slots.all { it.fromArchive })
            assertEquals(original, store.exportJson(episode))
            now += 101
            store.purgeExpired()
            assertTrue(store.ownList().isEmpty())
        } finally { scope.cancel(); directory.deleteRecursively() }
    }
    @Test fun downloadsRejectDanimeAndCommentOnlyWithoutCreatingFiles() = runBlocking {
        val directory = Files.createTempDirectory("anicome-official").toFile()
        try {
            val store = CommentStore(directory)
            val repository = CommentRepository(store, load = { _, _ -> fail("Must not fetch unsupported downloads"); emptyList() })
            for (candidate in listOf(Candidate("so9", "作品 第1話", DANIME_CHANNEL), Candidate("sm9", "作品 第1話", userId = 1, tags = "コメント専用動画"))) {
                try { repository.obtain(Episode("作品", 1), source = candidate); fail() } catch (_: IllegalArgumentException) { }
            }
            assertTrue(store.list().isEmpty())
        } finally { directory.deleteRecursively() }
    }
    @Test fun manualAndAutomaticReuseSavedCommentsWithoutNetworkFetch() = runBlocking {
        val directory = Files.createTempDirectory("anicome-cache").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val episode = Episode("テスト作品", 1)
            val candidate = Candidate("so900001", "テスト作品 第1話", 123, 1400)
            val store = CommentStore(directory)
            store.save(episode, candidate, listOf(Comment(1000, "saved")))
            val repository = CommentRepository(store, search = { error("Cached episode must not search") }, load = { _, _ -> error("Cached episode must not fetch") })
            assertTrue(repository.obtain(episode).fromArchive)
            val service = object : JsonApiService {
                override suspend fun get(url: String, headers: Map<String, String>): Response<ResponseBody> = error("Disabled source must not fetch")
                override suspend fun post(url: String, headers: Map<String, String>, body: RequestBody): Response<ResponseBody> = error("Disabled source must not fetch")
            }
            val settings = SettingsRepository(MemoryPreferences(), scope)
            settings.putBoolean("sourceJikkyo", false); settings.putBoolean("sourceLive", false)
            settings.load()
            val sources = PlaybackSourceRepository(repository, ApiClient(service), settings, videoSearch = { emptyList() }, videoLoad = { _, _ -> error("Must reuse archive") })
            val progress = mutableListOf<String>()
            val manual = sources.manual(episode, candidate, 1) { label, _, _ -> progress += label }
            assertTrue(manual.fromArchive)
            assertEquals(listOf("saved"), manual.comments.map { it.body })
            assertTrue(progress.all { it == "保存済みコメントを使用" })
            val partials = mutableListOf<PlaybackComments>()
            val automatic = sources.obtain(episode, 1400, "", 1, {}, { _, _, _ -> }) { partials += it }
            assertTrue(automatic.fromArchive)
            assertTrue(partials.any { it.fromArchive })
        } finally { scope.cancel(); directory.deleteRecursively() }
    }

    @Test fun automaticPipelineCorrectsBroadcastBeforePublishingAndKeepsSavedOriginal() = runBlocking {
        val directory = Files.createTempDirectory("anicome-sync").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val times = listOf(0L, 90_000L, 180_000L, 660_000L, 1_350_000L)
        val bodies = listOf("始まった", "OP", "A", "B", "ED")
        fun markers(broadcast: Boolean) = times.flatMapIndexed { index, at -> (0..3).map { user ->
            Comment(at + 1_000 + user * 100 + (if (broadcast) 30_000 else 0) + (if (broadcast && index >= 3) 120_000 else 0), bodies[index], JSONObject().put("userId", "u$user").toString())
        } }
        val original = markers(false)
        val broadcast = markers(true) + listOf(Comment(740_000, "commercial"), Comment(900_000, "after"))
        val packet = org.json.JSONArray().apply { broadcast.forEach { comment -> put(JSONObject().put("chat", JSONObject().put("date", 1577804400L + comment.at / 1000).put("date_usec", comment.at % 1000 * 1000).put("content", comment.body).put("user_id", comment.raw?.let { JSONObject(it).optString("userId") }.orEmpty()))) } }
        val service = object : JsonApiService {
            override suspend fun get(url: String, headers: Map<String, String>): Response<ResponseBody> {
                val body = when {
                    url.contains("json.php") -> """{"Titles":{"1":{"Title":"テスト作品"}}}"""
                    url.contains("db.php") -> """<ProgLookupResponse><ProgItems><ProgItem><ChID>19</ChID><StTime>2020-01-01 00:00:00</StTime><EdTime>2020-01-01 00:30:00</EdTime></ProgItem></ProgItems></ProgLookupResponse>"""
                    else -> JSONObject().put("packet", packet).toString()
                }
                return Response.success(body.toResponseBody())
            }
            override suspend fun post(url: String, headers: Map<String, String>, body: RequestBody): Response<ResponseBody> = error("Live disabled")
        }
        try {
            val store = CommentStore(directory)
            val candidate = Candidate("so900001", "テスト作品 第1話", 123, 1440)
            val settings = SettingsRepository(MemoryPreferences().apply { updateData { it.toMutablePreferences().apply { this[androidx.datastore.preferences.core.booleanPreferencesKey("sourceLive")] = false; this[androidx.datastore.preferences.core.booleanPreferencesKey("sourceJikkyo")] = true } } }, scope)
            val official = CommentRepository(store, search = { listOf(candidate) }, load = { _, _ -> original })
            val sources = PlaybackSourceRepository(official, ApiClient(service), settings, videoSearch = { listOf(candidate) })
            val partials = mutableListOf<PlaybackComments>()
            val result = sources.obtain(Episode("テスト作品", 1), 1440, "", 1, {}, { _, _, _ -> }) { partials += it }
            val corrected = result.slots.single { it.source == PlaybackSource.JIKKYO }
            assertEquals(CommentSync.CUT, corrected.sync)
            assertFalse(corrected.comments.any { it.body == "commercial" })
            assertEquals(750_000L, corrected.comments.single { it.body == "after" }.at)
            assertTrue(partials.flatMap { it.slots }.filter { it.source == PlaybackSource.JIKKYO }.all { it.sync == CommentSync.CUT })
            assertEquals(original, store.read(Episode("テスト作品", 1))!!.comments)
        } finally { scope.cancel(); directory.deleteRecursively() }
    }

    @Test fun asciiArtStartingWithASlashSurvivesBothBroadcastParsers() {
        val art = "/＼＿／＼\n （・ω・）"
        val xml = """<packet><chat vpos="100" date="1600000000" premium="1" mail="ue full gothic">$art</chat><chat vpos="100">/gift 1</chat></packet>"""
        val archive = parseNicologComments(xml).single()
        assertEquals(art, archive.body)
        assertEquals(listOf("ue", "full", "gothic"), JSONObject(archive.raw!!).getJSONArray("commands").let { a -> (0 until a.length()).map { a.getString(it) } })
        val packet = JSONObject().put("packet", org.json.JSONArray().put(JSONObject().put("chat", JSONObject().put("date", 1001).put("content", art).put("mail", "ue full gothic"))))
        val broadcast = parseJikkyoComments(packet, 1000).single()
        assertEquals(art, broadcast.body)
        assertTrue(renderableComment(broadcast, emptyList()))
        assertEquals(1000L, broadcast.at)
    }

    @Test fun unavailableExtraSourcesBackOffButNetworkErrorsAndExplicitSavesCanRetry() = runBlocking {
        val directory = Files.createTempDirectory("anicome-missing-source").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = object : JsonApiService {
            override suspend fun get(url: String, headers: Map<String, String>): Response<ResponseBody> = error("unexpected API")
            override suspend fun post(url: String, headers: Map<String, String>, body: RequestBody): Response<ResponseBody> = error("unexpected API")
        }
        try {
            val official = CommentRepository(CommentStore(directory), search = { error("official disabled") })
            var searches = 0
            var failure = false
            val sources = PlaybackSourceRepository(official, ApiClient(service), SettingsRepository(MemoryPreferences(), scope), videoSearch = {
                searches++
                if (failure) throw java.io.IOException("offline")
                emptyList()
            })
            suspend fun request(offline: Boolean = false) {
                try { sources.obtain(Episode("作品", 1), 1400, "", 1, {}, { _, _, _ -> }, offlineSave = offline, requestedSources = setOf(PlaybackSource.DANIME)); fail("must be unavailable") }
                catch (_: MatchUnavailable) { assertFalse(failure) }
                catch (_: java.io.IOException) { assertTrue(failure) }
            }
            request(); assertEquals(2, searches)
            request(); assertEquals(2, searches)
            sources.clearMissing(); request(); assertEquals(4, searches)
            request(offline = true); assertEquals(6, searches)
            sources.clearMissing(); failure = true
            request(); request(); assertEquals(10, searches)
        } finally { scope.cancel(); directory.deleteRecursively() }
    }

}
