package net.yexe.anicome

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OfflineTest {
    @get:Rule val folder = TemporaryFolder()
    private val episode = Episode("テスト作品", 2)
    private val source = Candidate("so123", "テスト作品 第2話", channelId = 1234)
    private val comments = listOf(Comment(1000, "こんにちは"), Comment(2500, "いいね"))
    @Test fun manualUrlReusesDownloadedVideoUnderAnotherSeriesWithoutNetwork() = runBlocking {
        val store = CommentStore(folder.newFolder())
        store.save(episode, source, comments)
        val repository = CommentRepository(store, { error("検索してはいけない") }, { _, _ -> error("取得してはいけない") })
        val result = repository.obtain(Episode("別の作品表記", 1), source.copy(id = "https://nico.ms/so123?from=share"))
        assertEquals(comments, result.comments)
        assertEquals(1, store.list().size)
    }
    @Test fun automaticallyMatchedVideoAlsoReusesTheSourceArchive() = runBlocking {
        val store = CommentStore(folder.newFolder())
        store.save(Episode("以前の表記", 8), source, comments)
        val repository = CommentRepository(store, { listOf(source) }, { _, _ -> error("再取得してはいけない") })
        assertEquals(comments, repository.obtain(episode).comments)
        assertEquals(1, store.list().size)
    }
    @Test fun failedAutomaticMatchOnlyFallsBackToActualNiconicoChannels() = runBlocking {
        val store = CommentStore(folder.newFolder())
        val channel = Candidate("so987", "表記が異なる公式動画", channelId = 1234, commentCount = 100)
        val user = Candidate("sm987", "一般投稿動画", userId = 999, commentCount = 10000)
        var loaded = ""
        val repository = CommentRepository(store, { listOf(user, channel) }, { id, _ -> loaded = id; comments }, snapshotLoader = { _, _, _, _, _ -> emptyList() })
        assertEquals(channel.id, repository.obtain(episode).info.sourceId)
        assertEquals(channel.id, loaded)
    }
    @Test(expected = MatchUnavailable::class) fun failedAutomaticMatchNeverAcceptsOrdinaryUploaders() = runBlocking {
        val repository = CommentRepository(CommentStore(folder.newFolder()), { listOf(Candidate("sm987", "一般投稿動画", userId = 999)) }, { _, _ -> error("取得してはいけない") }, snapshotLoader = { _, _, _, _, _ -> emptyList() })
        repository.obtain(episode)
        Unit
    }
    @Test fun persistsUploaderIdentityAndChannelGroupingAcrossRestart() {
        val directory = folder.newFolder()
        CommentStore(directory).save(episode, source.copy(ownerName = "作品公式チャンネル", ownerUrl = "https://ch.nicovideo.jp/ch123"), comments)
        val saved = CommentStore(directory).read(episode)!!.info
        assertEquals("作品公式チャンネル", saved.libraryTitle())
        assertEquals("https://ch.nicovideo.jp/ch123", saved.libraryKey())
    }
    @Test fun preservesVideoThumbnailAndSourceAcrossRestart() {
        val directory = folder.newFolder()
        val candidate = source.copy(thumbnailUrl = "https://nicovideo.cdn.nimg.jp/thumbnails/123/123")
        CommentStore(directory).save(episode, candidate, comments)
        val saved = CommentStore(directory).read(episode)!!.info
        assertEquals(candidate.thumbnailUrl, saved.thumbnailUrl)
        assertEquals(candidate.id, saved.sourceId)
        assertEquals(candidate.title, saved.sourceTitle)
    }
    @Test fun updatesLegacySourceWithoutChangingCommentsOrSaveDate() {
        val store = CommentStore(folder.newFolder())
        val original = store.save(episode, source, comments).info
        assertNotNull(store.updateSource(episode, source.id, source.copy(title = "取得元の実際のタイトル", thumbnailUrl = "https://example.com/thumbnail.jpg")))
        val updated = store.read(episode)!!
        assertEquals(original.savedAt, updated.info.savedAt)
        assertEquals(comments, updated.comments)
        assertEquals("取得元の実際のタイトル", updated.info.sourceTitle)
        assertEquals("https://example.com/thumbnail.jpg", updated.info.thumbnailUrl)
    }
    @Test fun metadataUpdateDoesNotOverwriteNewSourcesOrResurrectDeletedEpisodes() {
        val store = CommentStore(folder.newFolder())
        store.save(episode, source, comments)
        assertNull(store.updateSource(episode, "so99999", source.copy(title = "古い取得元")))
        assertEquals(source.title, store.read(episode)!!.info.sourceTitle)
        store.delete(episode)
        assertNull(store.updateSource(episode, source.id, source))
        assertTrue(store.list().isEmpty())
    }
    @Test fun survivesRestartAndLoadsWithNoNetworkCalls() = runBlocking {
        val directory = folder.newFolder()
        CommentStore(directory).save(episode, source, comments)
        val freshProcess = CommentRepository(CommentStore(directory), { error("検索にアクセスしてはいけない") }, { _, _ -> error("取得にアクセスしてはいけない") })
        assertEquals(comments, freshProcess.obtain(episode).comments)
    }
    @Test fun automaticallySelectsAndSavesTheCorrectEpisode() = runBlocking {
        val store = CommentStore(folder.newFolder())
        var loadedId = ""
        val repository = CommentRepository(store, { listOf(Candidate("so999", "テスト作品 第1話", channelId = 1234), source) }, { id, _ -> loadedId = id; comments })
        repository.obtain(episode)
        assertEquals(source.id, loadedId)
        assertEquals(comments, store.read(episode)?.comments)
    }
    @Test fun triesTheNextEditionWhenTheFirstCannotProvideComments() = runBlocking {
        val store = CommentStore(folder.newFolder())
        var requested = emptyList<String>()
        val first = source.copy(commentCount = 30)
        val second = Candidate("so456", source.title, channelId = 1234, commentCount = 20)
        val repository = CommentRepository(store, { listOf(first, second) }, { id, _ -> requested += id; if (id == first.id) error("第一候補にはコメントがありません") else comments })
        assertEquals(second.id, repository.obtain(episode).info.sourceId)
        assertEquals(listOf(source.id, second.id), requested)
    }
    @Test fun corruptionDoesNotMasqueradeAsOfflineAvailability() {
        val directory = folder.newFolder()
        File(directory, "${CommentStore.fileKey(episode.key)}.json").writeText("partial write")
        assertNull(CommentStore(directory).read(episode))
        assertTrue(CommentStore(directory).list().isEmpty())
    }
    @Test fun failedReplacementKeepsExistingDownload() {
        val directory = folder.newFolder()
        CommentStore(directory).save(episode, source, comments)
        try { CommentStore(directory, 1).save(episode, source, listOf(Comment(5000, "replacement"))); fail("Quota expected") } catch (_: IllegalStateException) { }
        assertEquals(comments, CommentStore(directory).read(episode)?.comments)
    }
    @Test fun deleteRemovesPersistedDownload() {
        val store = CommentStore(folder.newFolder())
        store.save(episode, source, comments)
        store.delete(episode)
        assertNull(store.read(episode))
    }
    @Test fun batchReusesSearchResultsForTheSameSeries() = runBlocking {
        var searches = 0
        val repository = CommentRepository(CommentStore(folder.newFolder()), {
            searches++
            listOf(source, Candidate("so456", "テスト作品 第3話", channelId = 1234))
        }, { _, _ -> comments })
        repository.obtain(episode)
        repository.obtain(episode.copy(number = 3))
        assertEquals(1, searches)
    }
    @Test fun searchesOriginalTitleBeforeUsingNotationFallback() = runBlocking {
        val store = CommentStore(folder.newFolder())
        val queries = mutableListOf<String>()
        val repository = CommentRepository(store, { query ->
            queries += query
            if (query == "テスト作品 2") listOf(Candidate("so-step", "テスト作品 Step2「続き」", channelId = 1, duration = 120)) else emptyList()
        }, { _, _ -> comments })
        repository.obtain(episode, duration = 120, searchTitle = "Step2 続き")
        assertEquals("Step2 続き", queries.first())
        assertEquals("so-step", store.read(episode)?.info?.sourceId)
    }
    @Test fun publicAccessUsesTheStoredOneTimesDownloadForHigherRequests() = runBlocking {
        val store = CommentStore(folder.newFolder())
        store.save(episode, source, comments, amount = 1)
        val repository = CommentRepository(store, { error("検索にアクセスしてはいけない") }, { _, _ -> error("取得にアクセスしてはいけない") })
        repository.obtain(episode, amount = 10)
        assertEquals(1, store.read(episode)?.info?.amount)
    }
    @Test fun linkedNiconicoSessionAllowsHigherAmounts() {
        assertEquals(10, Comments.availableAmount(10, linked = true))
        assertEquals(20, Comments.availableAmount(99, linked = true))
        assertEquals(1, Comments.availableAmount(10, linked = false))
    }
}
