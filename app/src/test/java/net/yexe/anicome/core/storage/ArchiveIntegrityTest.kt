package net.yexe.anicome

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import okhttp3.RequestBody
import okhttp3.ResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

private class RecoveryIndex : SavedEpisodeIndex {
    val rows = linkedMapOf<String, SavedEpisode>()
    override fun all() = rows.values.toList()
    override fun bySource(id: String) = all().filter { it.sourceId == id }
    override fun upsert(info: SavedEpisode) { rows[info.episode.key] = info }
    override fun delete(key: String) { rows.remove(key) }
    override fun replace(items: List<SavedEpisode>) { rows.clear(); items.forEach(::upsert) }
}
class ArchiveIntegrityTest {
    private val ep = Episode("作品", 1)
    private fun file(root: File) = File(root, "${CommentStore.fileKey(ep.key)}.json")
    private fun offline(store: CommentStore, scope: kotlinx.coroutines.CoroutineScope): OfflineCommentRepository {
        val api = object : JsonApiService {
            override suspend fun get(url: String, headers: Map<String, String>): Response<ResponseBody> = error("unexpected network")
            override suspend fun post(url: String, headers: Map<String, String>, body: RequestBody): Response<ResponseBody> = error("unexpected network")
        }
        val comments = CommentRepository(store, search = { error("unexpected search") })
        return OfflineCommentRepository(comments, PlaybackSourceRepository(comments, ApiClient(api), SettingsRepository(MemoryPreferences(), scope), videoSearch = { error("unexpected search") }))
    }
    private fun damaged(root: File, store: CommentStore, artist: Comment = Comment(0, "official")): String {
        store.save(ep, Candidate("so82001", "作品 第1話", channelId = 10), listOf(artist))
        store.saveSources(ep, listOf(PlaybackCommentSlot("so82002", "作品 第1話", PlaybackSource.DANIME, listOf(Comment(1000, "danime")))))
        val json = JSONObject(file(root).readText())
        json.getJSONArray("sources").getJSONObject(1).getJSONArray("comments").getJSONObject(0).put("at", -1L)
        return json.toString().also { file(root).writeText(it) }
    }
    @Test fun repairsOnlyBrokenSourceAndPreservesHealthyCommentsAndOriginalBytes() = runTest {
        val root = Files.createTempDirectory("anicome-recovery-source").toFile()
        try {
            val store = CommentStore(root); val original = damaged(root, store)
            val before = store.inspectArchives().single()
            assertEquals(setOf(PlaybackSource.DANIME), before.damaged)
            val fetched = mutableListOf<String>()
            val repo = ArchiveIntegrityRepository(store, offline(store, backgroundScope), metadata = { Candidate(it, "作品 第1話", channelId = DANIME_CHANNEL) }, load = { id, _ -> fetched += id; listOf(Comment(1000, "repaired")) })
            repo.repair(before)
            assertEquals(listOf("so82002"), fetched)
            assertTrue(store.inspectArchives().single().healthy)
            assertEquals("official", store.read(ep)!!.comments.single().body)
            assertEquals("repaired", store.readSource("so82002")!!.comments.single().body)
            assertEquals(original, File(root, "recovery").listFiles()!!.single().readText())
            assertTrue(store.recoveryBytes() > 0L)
            store.delete(ep); assertEquals(0L, store.recoveryBytes())
        } finally { root.deleteRecursively() }
    }
    @Test fun failedRepairKeepsHealthySourcesAndPersistentMissingSourceInformation() = runTest {
        val root = Files.createTempDirectory("anicome-recovery-fail").toFile()
        try {
            val store = CommentStore(root); val original = damaged(root, store)
            val repo = ArchiveIntegrityRepository(store, offline(store, backgroundScope), metadata = { throw java.io.IOException("offline") })
            try { repo.repair(store.inspectArchives().single()); fail() } catch (_: java.io.IOException) { }
            assertEquals("official", store.read(ep)!!.comments.single().body)
            val afterRestart = CommentStore(root).inspectArchives().single()
            assertEquals("partial", afterRestart.status)
            assertEquals("so82002", afterRestart.broken.single().id)
            assertEquals(original, File(root, "recovery").listFiles()!!.single().readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun missingAndCorruptEntriesRemainInTheIndexAndCanBeRepaired() = runTest {
        val root = Files.createTempDirectory("anicome-recovery-index").toFile()
        try {
            val index = RecoveryIndex(); val store = CommentStore(root, index = index)
            store.save(ep, Candidate("so82003", "作品 第1話", channelId = 10), listOf(Comment(0, "old")))
            file(root).delete(); store.reconcileIndex()
            assertEquals(1, index.all().size); assertEquals("missing", store.inspectArchives().single().status)
            val repo = ArchiveIntegrityRepository(store, offline(store, backgroundScope), metadata = { Candidate(it, "作品 第1話", channelId = 10) }, load = { _, _ -> listOf(Comment(0, "new")) })
            repo.repair(store.inspectArchives().single()); assertTrue(store.inspectArchives().single().healthy)
            file(root).writeText("{"); store.reconcileIndex()
            assertEquals(1, index.all().size); assertEquals("corrupt", store.inspectArchives().single().status)
            repo.repair(store.inspectArchives().single()); assertEquals("new", store.read(ep)!!.comments.single().body)
            assertEquals("{", File(root, "recovery").listFiles()!!.single().readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun matchingSourceArchiveIsReusedWithoutMetadataOrCommentNetworkCalls() = runTest {
        val root = Files.createTempDirectory("anicome-recovery-reuse").toFile()
        try {
            val store = CommentStore(root); damaged(root, store)
            val valid = SavedComments(SavedEpisode(ep, "so82002", "作品 第1話", 0L, 1, 1, sources = "DANIME"), listOf(Comment(1000, "archive")))
            val repo = ArchiveIntegrityRepository(store, offline(store, backgroundScope), metadata = { error("metadata must not load") }, load = { _, _ -> error("comments must not load") }, cached = { _, _ -> valid })
            repo.repair(store.inspectArchives().single())
            assertEquals("archive", store.readSource("so82002")!!.comments.single().body)
        } finally { root.deleteRecursively() }
    }
    @Test fun unknownFilesCannotBeGuessedAndInvalidUtf8IsKeptBeforeOverwrite() {
        val root = Files.createTempDirectory("anicome-recovery-bytes").toFile()
        try {
            val index = RecoveryIndex(); val store = CommentStore(root, index = index)
            File(root, "unknown.json").writeText("{")
            assertFalse(store.inspectArchives().single().repairable)
            store.save(ep, Candidate("so82004", "作品 第1話"), listOf(Comment(0, "old")))
            val bytes = byteArrayOf(0xc3.toByte(), 0x28)
            file(root).writeBytes(bytes)
            assertEquals("corrupt", store.inspectEpisode(ep).status)
            store.save(ep, Candidate("so82004", "作品 第1話"), listOf(Comment(0, "new")))
            assertArrayEquals(bytes, File(root, "recovery").listFiles()!!.single().readBytes())
        } finally { root.deleteRecursively() }
    }
    @Test fun anotherHealthySourceOfTheSameKindDoesNotHideTheBrokenSource() = runTest {
        val root = Files.createTempDirectory("anicome-recovery-same-kind").toFile()
        try {
            val store = CommentStore(root); damaged(root, store)
            val json = JSONObject(file(root).readText())
            json.getJSONArray("sources").put(JSONObject().put("id", "so82005").put("title", "作品 第1話").put("source", "DANIME").put("amount", 1).put("durationMs", 0).put("comments", org.json.JSONArray().put(JSONObject().put("at", 2000).put("body", "healthy"))))
            file(root).writeText(json.toString())
            val repo = ArchiveIntegrityRepository(store, offline(store, backgroundScope), metadata = { throw java.io.IOException("offline") })
            try { repo.repair(store.inspectArchives().single()); fail() } catch (_: java.io.IOException) { }
            assertEquals("healthy", store.readSource("so82005")!!.comments.single().body)
            val pending = store.inspectArchives().single()
            assertFalse(pending.healthy); assertEquals("so82002", pending.broken.single().id)
            val fixed = ArchiveIntegrityRepository(store, offline(store, backgroundScope), metadata = { Candidate(it, "作品 第1話", channelId = DANIME_CHANNEL) }, load = { _, _ -> listOf(Comment(1000, "fixed")) })
            fixed.repair(pending)
            assertTrue(store.inspectArchives().single().healthy)
            assertEquals("healthy", store.readSource("so82005")!!.comments.single().body)
        } finally { root.deleteRecursively() }
    }

    @Test fun repairingAnotherSourcePreservesArtistWhitespaceCommandsAndFork() = runTest {
        val root = Files.createTempDirectory("anicome-recovery-aa").toFile()
        try {
            val store = CommentStore(root)
            val artist = Comment(0, " /\\\n　 / \\", """{"commands":["ue","gothic","full"]}""", "owner")
            damaged(root, store, artist)
            val repo = ArchiveIntegrityRepository(store, offline(store, backgroundScope), metadata = { Candidate(it, "作品 第1話", channelId = DANIME_CHANNEL) }, load = { _, _ -> listOf(Comment(1000, "fixed")) })
            repo.repair(store.inspectArchives().single())
            assertEquals(listOf(artist), store.read(ep)!!.comments)
        } finally { root.deleteRecursively() }
    }

    @Test fun aTwentyTimesArchiveCanRepairTheSameSourceWithoutLoginOrNetwork() = runTest {
        val root = Files.createTempDirectory("anicome-recovery-amount").toFile()
        try {
            val store = CommentStore(root); damaged(root, store)
            val json = JSONObject(file(root).readText())
            json.getJSONArray("sources").getJSONObject(1).put("amount", 20)
            file(root).writeText(json.toString())
            val valid = SavedComments(SavedEpisode(ep, "so82002", "作品 第1話", 0L, 1, 1, amount = 20, sources = "DANIME"), listOf(Comment(1000, "archive")))
            val repo = ArchiveIntegrityRepository(store, offline(store, backgroundScope), metadata = { error("metadata must not load") }, load = { _, _ -> error("network must not run") }, cached = { _, amount -> assertEquals(20, amount); valid })
            repo.repair(store.inspectArchives().single())
            assertEquals(20, store.readSource("so82002")!!.info.amount)
        } finally { root.deleteRecursively() }
    }

    @Test fun healthyCommentsInsideABrokenSourceSurviveFailedRepairWithoutBecomingACompleteCache() = runTest {
        val root = Files.createTempDirectory("anicome-recovery-partial-body").toFile()
        try {
            val store = CommentStore(root)
            store.save(ep, Candidate("so82006", "作品 第1話", channelId = 10), listOf(Comment(0, "intact"), Comment(1000, "broken")))
            val json = JSONObject(file(root).readText())
            json.getJSONArray("comments").getJSONObject(1).put("at", -1)
            file(root).writeText(json.toString())
            val repo = ArchiveIntegrityRepository(store, offline(store, backgroundScope), metadata = { throw java.io.IOException("offline") })
            try { repo.repair(store.inspectArchives().single()); fail() } catch (_: java.io.IOException) { }
            assertFalse(store.inspectArchives().single().healthy)
            assertEquals(listOf("intact"), store.read(ep)!!.comments.map { it.body })
            assertNull(store.readSource("so82006"))
            assertNull(CommentRepository(store).cached(ep))
            val fixed = ArchiveIntegrityRepository(store, offline(store, backgroundScope), metadata = { Candidate(it, "作品 第1話", channelId = 10) }, load = { _, _ -> listOf(Comment(1000, "restored")) })
            fixed.repair(store.inspectArchives().single())
            assertTrue(store.inspectArchives().single().healthy)
            assertEquals(listOf("intact", "restored"), store.read(ep)!!.comments.map { it.body })
        } finally { root.deleteRecursively() }
    }

    @Test fun playbackShowsIntactPartialCommentsImmediatelyAndRefetchesTheDamagedSource() = runTest {
        val root = Files.createTempDirectory("anicome-recovery-playback").toFile()
        try {
            val store = CommentStore(root)
            store.saveSources(ep, listOf(PlaybackCommentSlot("so82007", "作品 第1話", PlaybackSource.DANIME, listOf(Comment(0, "intact"), Comment(1000, "broken")), durationMs = 1400000)))
            val json = JSONObject(file(root).readText())
            json.getJSONArray("sources").getJSONObject(0).getJSONArray("comments").getJSONObject(1).put("at", -1)
            file(root).writeText(json.toString())
            store.salvageArchive(store.inspectArchives().single())
            val api = object : JsonApiService {
                override suspend fun get(url: String, headers: Map<String, String>): Response<ResponseBody> = error("unexpected network")
                override suspend fun post(url: String, headers: Map<String, String>, body: RequestBody): Response<ResponseBody> = error("unexpected network")
            }
            var loads = 0
            val prefs = MemoryPreferences()
            prefs.updateData { it.toMutablePreferences().apply { this[androidx.datastore.preferences.core.booleanPreferencesKey("syncOnlyConfirmed")] = false } }
            val repository = PlaybackSourceRepository(CommentRepository(store), ApiClient(api), SettingsRepository(prefs, backgroundScope),
                videoSearch = { listOf(Candidate("so82007", "作品 第1話", channelId = DANIME_CHANNEL, duration = 1400)) },
                videoLoad = { _, _ -> loads++; listOf(Comment(1000, "restored")) })
            val displayed = mutableListOf<List<String>>()
            val result = repository.obtain(ep, 1400, "", 1, {}, { _, _, _ -> }, offlineSave = false, requestedSources = setOf(PlaybackSource.DANIME), onPartial = { displayed += it.comments.map { c -> c.body } })
            assertEquals(1, loads)
            assertEquals(listOf("intact"), displayed.first())
            assertEquals(listOf("intact", "restored"), result.comments.map { it.body })
            assertTrue(displayed.all { "intact" in it })
            repository.obtain(ep, 1400, "", 1, {}, { _, _, _ -> }, offlineSave = true, requestedSources = setOf(PlaybackSource.DANIME))
            assertTrue(store.inspectArchives().single().healthy)
            assertEquals(listOf("intact", "restored"), store.read(ep)!!.comments.map { it.body })
        } finally { root.deleteRecursively() }
    }

    @Test fun aCompleteCacheDoesNotInheritTheIncompleteFlagOrDiscardIntactManualComments() {
        val root = Files.createTempDirectory("anicome-recovery-combined").toFile()
        try {
            val manualRoot = File(root, "manual"); val manual = CommentStore(manualRoot)
            manual.save(ep, Candidate("so82008", "作品 第1話"), listOf(Comment(0, "intact"), Comment(1000, "broken")))
            val json = JSONObject(file(manualRoot).readText())
            json.getJSONArray("comments").getJSONObject(1).put("at", -1)
            file(manualRoot).writeText(json.toString())
            manual.salvageArchive(manual.inspectArchives().single())
            val cache = CommentStore(File(root, "cache"), fallback = manual)
            cache.save(ep, Candidate("so82008", "作品 第1話"), listOf(Comment(1000, "restored")))
            val merged = cache.read(ep)!!
            assertFalse(merged.isIncompleteSource("so82008"))
            assertEquals(listOf("intact", "restored"), merged.comments.map { it.body })
            manual.importJson(cache.exportJson(ep)!!, upgrade = true)
            assertTrue(manual.inspectArchives().single().healthy)
            assertEquals(listOf("intact", "restored"), manual.read(ep)!!.comments.map { it.body })
        } finally { root.deleteRecursively() }
    }

    @Test fun aLowerAmountRefetchDoesNotClaimThatTheOriginalArchiveHasBeenFullyRepaired() {
        val root = Files.createTempDirectory("anicome-recovery-incomplete-amount").toFile()
        try {
            val store = CommentStore(root)
            store.save(ep, Candidate("so82009", "作品 第1話"), listOf(Comment(0, "intact"), Comment(1000, "broken")), amount = 20)
            val json = JSONObject(file(root).readText())
            json.getJSONArray("comments").getJSONObject(1).put("at", -1)
            file(root).writeText(json.toString())
            store.salvageArchive(store.inspectArchives().single())
            store.save(ep, Candidate("so82009", "作品 第1話"), listOf(Comment(1000, "one times")), amount = 1)
            val pending = store.inspectArchives().single()
            assertFalse(pending.healthy)
            assertEquals(20, pending.broken.single().amount)
            assertNull(store.readSource("so82009"))
            assertEquals(listOf("intact", "one times"), store.read(ep)!!.comments.map { it.body })
            store.save(ep, Candidate("so82009", "作品 第1話"), listOf(Comment(1000, "twenty times")), amount = 20)
            assertTrue(store.inspectArchives().single().healthy)
            assertNotNull(store.readSource("so82009"))
        } finally { root.deleteRecursively() }
    }
    @Test fun renamingAPartialArchiveKeepsItsOriginalRecoveryCopyUnderTheNewEpisode() {
        val root = Files.createTempDirectory("anicome-recovery-rename").toFile()
        try {
            val store = CommentStore(root)
            val original = damaged(root, store)
            store.salvageArchive(store.inspectArchives().single())
            val to = Episode("修正した作品名", 13)
            val before = store.read(ep)!!
            store.relabel(ep, to)
            val moved = store.read(to)!!
            assertEquals(before.slots, moved.slots)
            assertEquals(before.incompleteIds, moved.incompleteIds)
            assertFalse(store.inspectEpisode(to).healthy)
            val copies = File(root, "recovery").listFiles()!!
            assertEquals(1, copies.size)
            assertTrue(copies.single().name.startsWith(CommentStore.fileKey(to.key)))
            assertEquals(original, copies.single().readText())
            store.delete(to); assertEquals(0L, store.recoveryBytes())
        } finally { root.deleteRecursively() }
    }

}
