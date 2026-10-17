package net.yexe.anicome

import android.content.ContextWrapper
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ConvenienceTest {
    @get:Rule val folder = TemporaryFolder()
    @After fun reset() {
        Session.hiddenUntil.value = 0; Session.hiddenEpisode.value = ""; Session.hiddenSources.value = emptySet(); Session.sourceOffsets.value = emptyMap()
        Session.playback.value = Playback(); Session.manualPlayback.value = null
        Session.fps.value = 60; Session.commentAmount.value = 1
        Session.size.value = 17f; Session.opacity.value = 1f; Session.speedScale.value = .5f; Session.renderMode.value = "balanced"
    }
    private fun repository(scope: CoroutineScope, settings: SettingsRepository = SettingsRepository(MemoryPreferences(), scope)): ConvenienceRepository {
        val context = ContextWrapper(null)
        return ConvenienceRepository(context, settings, AppCoordinator(settings = settings, scope = scope), DownloadRepository(context, settings), EpisodeMetadataRepository(), scope)
    }
    @Test fun compactPanelModeIsOptInPortableAndSurvivesReadingStoredPreferences() {
        val prefs = androidx.datastore.preferences.core.mutablePreferencesOf()
        assertFalse(panelOptions(SettingsSnapshot(prefs)).compact)
        prefs[androidx.datastore.preferences.core.booleanPreferencesKey("panelCompact")] = true
        assertTrue(panelOptions(SettingsSnapshot(prefs)).compact)
        assertTrue(portableKey("panelCompact"))
        validatePortableSetting("panelCompact", JSONObject().put("type", "boolean").put("value", true))
    }
    @Test fun viewingAppProfilesRestoreAllCommentDisplayFieldsWithoutMixingApps() = runTest {
        val context = ContextWrapper(null)
        val settings = SettingsRepository(MemoryPreferences(), backgroundScope)
        val app = AppCoordinator(settings = settings, scope = backgroundScope)
        val repo = ConvenienceRepository(context, settings, app, DownloadRepository(context, settings), EpisodeMetadataRepository(), backgroundScope)
        repo.start()
        runCurrent()
        app.setActiveService(StreamingService.NETFLIX)
        repo.boolean("serviceProfiles", true)
        runCurrent()
        app.setCommentSize(21f); app.setCommentAmount(10); app.setFps(144)
        settings.putBoolean("hideOnOwnApp", false); repo.renderMode("quality")
        runCurrent()
        runCurrent()
        app.setActiveService(StreamingService.ABEMA); runCurrent()
        assertEquals(14f, Session.size.value)
        assertEquals(5, app.commentAmount.value)
        assertEquals(60, Session.fps.value)
        app.setCommentSize(9f); app.setCommentAmount(2); app.setFps(30)
        settings.putBoolean("hideOnOwnApp", true); repo.renderMode("battery")
        runCurrent()
        runCurrent()
        app.setActiveService(StreamingService.NETFLIX); runCurrent()
        assertEquals(21f, Session.size.value)
        assertEquals(10, app.commentAmount.value)
        assertEquals(144, Session.fps.value)
        assertEquals("quality", Session.renderMode.value)
        assertFalse(portableKey("hideOnOwnApp"))
        app.setActiveService(StreamingService.ABEMA); runCurrent()
        assertEquals(9f, Session.size.value)
        assertEquals(2, app.commentAmount.value)
        assertEquals(30, Session.fps.value)
    }
    @Test fun rapidProfileSwitchesFlushEditsToTheCorrectAppAndRestoreTheFinalSelection() = runTest {
        val context = ContextWrapper(null)
        val settings = SettingsRepository(MemoryPreferences(), backgroundScope)
        val app = AppCoordinator(settings = settings, scope = backgroundScope)
        val repo = ConvenienceRepository(context, settings, app, DownloadRepository(context, settings), EpisodeMetadataRepository(), backgroundScope)
        repo.start(); runCurrent()
        app.setActiveService(StreamingService.NETFLIX); repo.boolean("serviceProfiles", true); runCurrent()
        app.setCommentSize(23f); app.setOpacity(.7f)
        app.setActiveService(StreamingService.ABEMA)
        app.setCommentSize(11f)
        app.setActiveService(StreamingService.NETFLIX)
        runCurrent()
        assertEquals(23f, Session.size.value)
        assertEquals(.7f, Session.opacity.value)
        app.setActiveService(StreamingService.ABEMA); runCurrent()
        assertEquals(11f, Session.size.value)
        assertEquals(.75f, Session.opacity.value)
    }
    @Test fun anExistingManualProfileIsRestoredOnStartupWithoutLosingItsValues() = runTest {
        val store = MemoryPreferences()
        store.updateData { androidx.datastore.preferences.core.mutablePreferencesOf(
            androidx.datastore.preferences.core.stringPreferencesKey("activeService") to StreamingService.NETFLIX.name,
            androidx.datastore.preferences.core.booleanPreferencesKey("serviceProfiles") to true,
            androidx.datastore.preferences.core.floatPreferencesKey("commentSize") to 9f,
            androidx.datastore.preferences.core.stringPreferencesKey("serviceProfile:NETFLIX") to """{"size":21,"fps":144,"position":"top"}""") }
        val repo = repository(backgroundScope, SettingsRepository(store, backgroundScope))
        repo.start(); runCurrent()
        assertEquals(21f, Session.size.value)
        assertEquals(144, Session.fps.value)
        assertEquals("top", Session.overlayPosition.value)
    }
    @Test fun filteringAndOffsetsKeepOriginalOfficialAndArtistData() {
        val official = Comment(1000, "official", """{"commands":["full"]}""")
        val live = Comment(2000, "/\\\nartist", """{"commands":["ue","gothic"]}""")
        val slots = listOf(PlaybackCommentSlot("so1", "", PlaybackSource.OFFICIAL, listOf(official), true), PlaybackCommentSlot("live", "", PlaybackSource.LIVE_ARCHIVE, listOf(live)))
        val raw = listOf(official, live)
        assertSame(raw, presentedComments(raw, slots, emptySet(), emptyMap()))
        assertEquals(listOf(official), presentedComments(raw, slots, setOf(PlaybackSource.LIVE_ARCHIVE), emptyMap()))
        val shifted = presentedComments(raw, slots, emptySet(), mapOf(PlaybackSource.LIVE_ARCHIVE to -1500L))
        assertEquals(500, shifted.first().at); assertEquals(live.raw, shifted.first().raw); assertEquals(live.body, shifted.first().body)
        assertSame(official, shifted.last()); assertEquals(2000, live.at)
    }
    @Test fun negativeShiftDropsOnlyBeforeZeroAndPreservesDuplicates() {
        val c = Comment(100, "same")
        val slot = PlaybackCommentSlot("jk1", "", PlaybackSource.JIKKYO, listOf(c, c, Comment(2000, "later")))
        val result = presentedComments(slot.comments, listOf(slot), emptySet(), mapOf(PlaybackSource.JIKKYO to -500L))
        assertEquals(1, result.size); assertEquals(1500L, result.single().at)
        assertEquals(3, presentedComments(slot.comments, listOf(slot), emptySet(), mapOf(PlaybackSource.JIKKYO to 10L)).size)
    }
    @Test fun undoCancelsDeletionBeforeEightSeconds() = runTest {
        val repo = repository(backgroundScope)
        var deleted = false
        repo.defer("delete") { deleted = true }
        runCurrent(); advanceTimeBy(7900); assertFalse(deleted)
        val id = repo.undo.value!!.id
        repo.undo(id); advanceTimeBy(9000); runCurrent(); assertFalse(deleted); assertNull(repo.undo.value)
    }
    @Test fun deferredActionRunsOnceAndIndependentUndosDoNotCancelOtherActions() = runTest {
        val repo = repository(backgroundScope)
        var first = 0; var second = 0
        repo.defer("first") { first++ }; val firstId = repo.undo.value!!.id
        repo.defer("second") { second++ }
        repo.undo(firstId); advanceTimeBy(8001); runCurrent()
        assertEquals(0, first); assertEquals(1, second); assertNull(repo.undo.value)
    }
    @Test fun rememberedEpisodeSelectionSurvivesNewRepositoryAndIsBoundToExactEpisode() = runTest {
        val settings = SettingsRepository(MemoryPreferences(), backgroundScope)
        val ep = Episode("second season", 13)
        val repo = repository(backgroundScope, settings)
        repo.rememberCandidate(ep, Candidate("so123", "episode13", channelId = 123))
        runCurrent()
        val restarted = repository(backgroundScope, settings)
        assertEquals("so123", restarted.selectedCandidate(ep)?.id)
        assertNull(restarted.selectedCandidate(ep.copy(number = 14)))
        assertNull(restarted.selectedCandidate(ep.copy(series = "first season")))
        settings.putBoolean("rememberSelection", false); runCurrent(); assertNull(restarted.selectedCandidate(ep))
    }
    @Test fun backupWhitelistExcludesCredentialsLocksAndUnknownSources() {
        for (key in listOf("lockHash", "lockSalt", "niconicoCookie", "setupCompleted", "proxyPassword", "sourcePrivateCredential")) assertFalse(key, portableKey(key))
        for (key in listOf("ngWords", "commentSize", "sourceJikkyo", "watched:abc", "selectedVideo:abc", "serviceProfile:NETFLIX")) assertTrue(key, portableKey(key))
    }
    @Test fun backupRejectsWrongPreferenceTypesInvalidLanguageAndNonfiniteNumbers() {
        fun rejects(key: String, type: String, value: Any) {
            try { validatePortableSetting(key, JSONObject().put("type", type).put("value", value)); fail(key) } catch (_: IllegalArgumentException) { }
        }
        rejects("commentSize", "boolean", true); rejects("fps", "string", "60"); rejects("language", "string", "xx"); rejects("themeMode", "string", "neon"); rejects("storageLimitMb", "long", -1)
        validatePortableSetting("commentSize", JSONObject().put("type", "float").put("value", 17))
        validatePortableSetting("sourceJikkyo", JSONObject().put("type", "boolean").put("value", true))
    }
    @Test fun commentTransferAddsOnlyMissingDataAndPreservesAsciiArtAndSourceMetadata() {
        val original = CommentStore(folder.newFolder())
        val target = CommentStore(folder.newFolder())
        val ep = Episode("work", 13)
        val candidate = Candidate("so123", "work 第13話", thumbnailUrl = "https://example.com/image", ownerName = "channel", ownerUrl = "https://ch.nicovideo.jp/work")
        val artist = Comment(100, "  /\\  \n (・ω・) ", """{"commands":["gothic","full"]}""")
        original.save(ep, candidate, listOf(artist), 3)
        val text = original.exportJson(ep)!!
        target.importJson(text)
        val loaded = target.read(ep)!!
        assertEquals(artist, loaded.comments.single()); assertEquals(3, loaded.info.amount); assertEquals(candidate.thumbnailUrl, loaded.info.thumbnailUrl)
        target.save(ep, candidate, listOf(Comment(200, "existing")))
        target.importJson(text)
        assertEquals("existing", target.read(ep)!!.comments.single().body)
    }
    @Test fun correctingEpisodeKeepsSourceAndNeverOverwritesAnotherEpisode() {
        val store = CommentStore(folder.newFolder())
        val from = Episode("work", 2); val to = Episode("work", 13)
        store.save(from, Candidate("so123", "work 第13話"), listOf(Comment(100, "art")))
        store.relabel(from, to)
        assertNull(store.read(from)); assertEquals("so123", store.read(to)!!.info.sourceId)
        store.save(from, Candidate("so456", "another"), listOf(Comment(200, "keep")))
        try { store.relabel(from, to); fail() } catch (_: IllegalStateException) { }
        assertEquals("keep", store.read(from)!!.comments.single().body)
        assertEquals("so123", store.read(to)!!.info.sourceId)
    }
    @Test fun correctingSeriesAndEpisodePreservesEverySourceAndArtistData() {
        val store = CommentStore(folder.newFolder())
        val from = Episode("作品 第2クール", 2); val to = Episode("表示名を修正", 13)
        val artist = Comment(100, "  /\\\n　 / \\", """{"commands":["ue","gothic","full"]}""", "owner")
        store.save(from, Candidate("so13", "実際の動画 第13話", thumbnailUrl = "https://example.com/13.jpg", ownerName = "公式"), listOf(artist), 20)
        store.saveSources(from, listOf(PlaybackCommentSlot("live:13", "放送アーカイブ", PlaybackSource.LIVE_ARCHIVE, listOf(Comment(200, "live")))))
        val before = store.read(from)!!
        store.relabel(from, to)
        val after = store.read(to)!!
        assertEquals(before.slots, after.slots)
        assertEquals(before.comments, after.comments)
        assertEquals(before.info.sourceId, after.info.sourceId)
        assertEquals(before.info.sourceTitle, after.info.sourceTitle)
        assertEquals(before.info.thumbnailUrl, after.info.thumbnailUrl)
        assertEquals(to, after.info.episode)
        assertNull(store.read(from))
    }
    @Test fun displayLabelsPersistWithoutTouchingOriginalIdentityCommentsOrRequiringANetworkRepository() = runTest {
        val directory = folder.newFolder()
        val store = CommentStore(directory)
        val episode = Episode("検索に使う元の作品名", 1)
        store.save(episode, Candidate("so13", "実際の動画 第13話", ownerName = "公式チャンネル", ownerUrl = "https://ch.nicovideo.jp/example"), listOf(Comment(100, "original")))
        val item = store.read(episode)!!.info
        val before = directory.listFiles()!!.single().readBytes()
        val settings = SettingsRepository(MemoryPreferences(), backgroundScope)
        fun repo(): ConvenienceRepository {
            val app = AppCoordinator(settings = settings, scope = backgroundScope)
            app.library.value = listOf(item)
            return ConvenienceRepository(ContextWrapper(null), settings, app, DownloadRepository(ContextWrapper(null), settings), EpisodeMetadataRepository(), backgroundScope).also { it.start() }
        }
        val first = repo(); runCurrent()
        first.correct(item, "表示する別名", 42); runCurrent()
        assertEquals("表示する別名", first.libraryTitle(item))
        assertEquals(42, first.episodeLabel(item))
        assertFalse(first.operation.value.busy); assertEquals("", first.operation.value.error)
        val restored = repo(); runCurrent()
        assertEquals("表示する別名", restored.libraryTitle(item)); assertEquals(42, restored.episodeLabel(item))
        assertEquals(episode, store.read(episode)!!.info.episode)
        assertEquals("実際の動画 第13話", store.read(episode)!!.info.sourceTitle)
        assertArrayEquals(before, directory.listFiles()!!.single().readBytes())
        val key = "libraryTitle:${CommentStore.fileKey(item.libraryKey())}"
        assertTrue(portableKey(key))
        validatePortableSetting(key, JSONObject().put("type", "string").put("value", "表示する別名"))
        try { validatePortableSetting(key, JSONObject().put("type", "string").put("value", "x".repeat(301))); fail() } catch (_: IllegalArgumentException) { }
        restored.resetLabels(item); runCurrent()
        assertEquals("公式チャンネル", restored.libraryTitle(item)); assertEquals(13, restored.episodeLabel(item))
        assertArrayEquals(before, directory.listFiles()!!.single().readBytes())
    }
    @Test fun correctingNeverOverwritesAnUnreadableDestinationAndKeepsTheNewCopyIfOldIndexCleanupFails() {
        val directory = folder.newFolder()
        val from = Episode("作品", 2); val to = Episode("作品", 13)
        val index = object : SavedEpisodeIndex {
            val rows = linkedMapOf<String, SavedEpisode>()
            override fun all() = rows.values.toList()
            override fun bySource(id: String) = all().filter { it.sourceId == id }
            override fun upsert(info: SavedEpisode) { rows[info.episode.key] = info }
            override fun delete(key: String) { if (key == from.key) error("index cleanup failed"); rows.remove(key) }
            override fun replace(items: List<SavedEpisode>) { rows.clear(); items.forEach(::upsert) }
        }
        val store = CommentStore(directory, index = index)
        store.save(from, Candidate("so13", "第13話"), listOf(Comment(100, "keep")))
        val target = java.io.File(directory, "${CommentStore.fileKey(to.key)}.json")
        target.writeText("unreadable destination")
        try { store.relabel(from, to); fail() } catch (_: IllegalStateException) { }
        assertEquals("unreadable destination", target.readText())
        assertEquals("keep", store.read(from)!!.comments.single().body)
        target.delete()
        try { store.relabel(from, to); fail() } catch (_: IllegalStateException) { }
        assertEquals("keep", store.read(to)!!.comments.single().body)
        assertEquals("so13", store.read(to)!!.info.sourceId)
    }
    @Test fun invalidCommentImportDoesNotTouchExistingData() {
        val store = CommentStore(folder.newFolder())
        val ep = Episode("work", 1)
        store.save(ep, Candidate("so123", "source"), listOf(Comment(100, "keep")))
        val root = JSONObject(store.exportJson(ep)!!).put("episode", -1)
        try { store.importJson(root.toString()); fail() } catch (_: IllegalArgumentException) { }
        assertEquals("keep", store.read(ep)!!.comments.single().body)
    }
    @Test fun floatingOptionsKeepIndependentCoordinatesAndCollapseState() {
        val prefs = androidx.datastore.preferences.core.mutablePreferencesOf(
            androidx.datastore.preferences.core.floatPreferencesKey("panelLandscapeX") to .2f,
            androidx.datastore.preferences.core.floatPreferencesKey("panelLandscapeY") to .8f,
            androidx.datastore.preferences.core.booleanPreferencesKey("panelLandscapeCollapsed") to false)
        val first = panelOptions(SettingsSnapshot(prefs))
        assertEquals(.2f, first.landscapeX); assertEquals(.8f, first.landscapeY); assertFalse(first.landscapeCollapsed)
        assertEquals(1f, first.x); assertTrue(first.startCollapsed)
        prefs[androidx.datastore.preferences.core.floatPreferencesKey("panelX")] = .4f
        prefs[androidx.datastore.preferences.core.floatPreferencesKey("panelY")] = .6f
        prefs[androidx.datastore.preferences.core.booleanPreferencesKey("panelCollapsed")] = false
        val second = panelOptions(SettingsSnapshot(prefs))
        assertEquals(.4f, second.x); assertEquals(.6f, second.y); assertFalse(second.startCollapsed)
        assertEquals(.2f, second.landscapeX); assertEquals(.8f, second.landscapeY); assertFalse(second.landscapeCollapsed)
    }
    @Test fun protectedTitlesAreExcludedFromDeletionAndCanBeUnprotected() = runTest {
        val store = MemoryPreferences()
        val settings = SettingsRepository(store, backgroundScope)
        val first = Episode("作品 第1期", 1); val second = Episode("作品 第2期", 1)
        settings.putBoolean(protectedWorkKey(first.series), true); runCurrent()
        assertEquals(listOf(second), unprotectedEpisodes(listOf(first, first.copy(number = 2), second), settings.load()))
        settings.putBoolean(protectedWorkKey(first.series), false); runCurrent()
        assertEquals(listOf(first, second), unprotectedEpisodes(listOf(first, second), settings.load()))
    }

}
