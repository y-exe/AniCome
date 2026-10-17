package net.yexe.anicome

import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

private class MemoryHistory : CommentHistoryIndex {
    val rows = MutableStateFlow<List<CommentHistoryEntry>>(emptyList())
    var failure = false
    private var next = 1L
    override fun observe() = rows
    override fun append(entry: CommentHistoryEntry) {
        check(!failure)
        if (!suppressHistoryDuplicate(rows.value.firstOrNull(), entry)) rows.value = (listOf(entry.copy(id = next++)) + rows.value).take(500)
    }
    override fun delete(id: Long) { rows.value = rows.value.filter { it.id != id } }
    override fun clear() { rows.value = emptyList() }
}
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CommentHistoryTest {
    @Test fun savesUpdatesAndReplacementKeepOldAndNewMetadataWithoutChangingComments() = runTest {
        val root = Files.createTempDirectory("anicome-history").toFile()
        try {
            val index = MemoryHistory()
            val history = CommentHistoryRepository(index, backgroundScope) { 1000L }
            val store = CommentStore(root, onSaved = { before, after -> history.saved(before, after, "manual") })
            val ep = Episode("作品", 1)
            store.save(ep, Candidate("so80001", "作品 第1話"), listOf(Comment(0, "first")))
            store.save(ep, Candidate("so80001", "作品 第1話"), listOf(Comment(0, "first"), Comment(1000, "next")))
            store.save(ep, Candidate("so80002", "作品 第1話"), listOf(Comment(0, "replacement")))
            assertEquals(listOf("REPLACE", "UPDATE", "SAVE"), index.rows.value.map { it.kind })
            assertEquals("so80001", index.rows.value.first().previousId)
            assertEquals(2, index.rows.value.first().previousCount)
            assertEquals(1, index.rows.value.first().count)
            history.clear(); assertTrue(index.rows.value.isEmpty())
            assertEquals("replacement", store.read(ep)!!.comments.single().body)
        } finally { root.deleteRecursively() }
    }
    @Test fun duplicateSearchesAreSuppressedOnlyForTheSameContextAndResult() {
        val entry = CommentHistoryEntry(time = 1000L, kind = "VIDEO_SEARCH", series = "作品", episode = 1, query = "作品", count = 3)
        assertTrue(suppressHistoryDuplicate(entry, entry.copy(time = 1001L)))
        assertFalse(suppressHistoryDuplicate(entry, entry.copy(episode = 2)))
        assertFalse(suppressHistoryDuplicate(entry, entry.copy(count = 4)))
        assertFalse(suppressHistoryDuplicate(entry, entry.copy(time = 62_000L)))
        assertFalse(suppressHistoryDuplicate(entry, entry.copy(kind = "SAVE")))
    }
    @Test fun URLsDoNotKeepCredentialsQueriesFragmentsOrControlCharacters() {
        assertEquals("https://example.com/path", historyQuery("HTTPS://alice:secret@example.com/path?session=secret#token"))
        assertFalse(historyQuery("作品\u0000名").contains('\u0000'))
        assertEquals("https://example.com:8443/path", historyQuery("https://alice:secret@example.com:8443/path?session=secret"))
        assertEquals(300, historyQuery("a".repeat(400)).length)
    }
    @Test fun videoSearchRetainsTheEpisodeForRepeatingItAndHistoryFailuresDoNotFailSaves() = runTest {
        val root = Files.createTempDirectory("anicome-history-search").toFile()
        try {
            val index = MemoryHistory()
            val history = CommentHistoryRepository(index, backgroundScope)
            val ep = Episode("作品 第2クール", 13)
            val repo = CommentRepository(CommentStore(root), search = { emptyList() }, onSearch = { query, count, target -> history.search("VIDEO_SEARCH", query, count, target) })
            repo.searchEpisodeCandidates(ep.series, ep.number)
            assertEquals(ep, index.rows.value.first().target)
            index.failure = true
            val store = CommentStore(root, onSaved = { before, after -> history.saved(before, after, "cache") })
            store.save(ep, Candidate("so80003", "作品 第13話"), listOf(Comment(0, "cached")))
            assertTrue(history.failed.value)
            assertEquals("cached", store.read(ep)!!.comments.single().body)
        } finally { root.deleteRecursively() }
    }
    @Test fun archiveUseIsRecordedWithoutChangingTheArchiveOrRepeatingTheSameEvent() = runTest {
        val index = MemoryHistory(); val history = CommentHistoryRepository(index, backgroundScope) { 1000L }
        val slot = PlaybackCommentSlot("so80004", "作品 第13話", PlaybackSource.OFFICIAL, listOf(Comment(0, "cached")), true)
        val comments = PlaybackComments(listOf(slot))
        val app = AppCoordinator(scope = backgroundScope, history = history)
        app.recordArchiveUse(Episode("作品", 13), comments)!!.join()
        app.recordArchiveUse(Episode("作品", 13), comments)!!.join()
        assertEquals(1, index.rows.value.size)
        assertEquals("USE", index.rows.value.single().kind)
        assertEquals("so80004", index.rows.value.single().sourceId)
        history.used(Episode("作品", 13), PlaybackComments(listOf(slot.copy(fromArchive = false))))
        assertEquals(1, index.rows.value.size)
    }

    @Test fun failuresAreRecordedForRetryButCancelledSearchesAreNotRecorded() = runTest {
        val rows = mutableListOf<Int>()
        try { trackedSearch<Int>("query", record = { _, count, _ -> rows += count }) { throw java.io.IOException("offline") }; fail() }
        catch (_: java.io.IOException) { }
        assertEquals(listOf(-1), rows)
        try { trackedSearch<Int>("query", record = { _, count, _ -> rows += count }) { throw kotlinx.coroutines.CancellationException() }; fail() }
        catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(listOf(-1), rows)
        trackedSearch("query", record = { _, count, _ -> rows += count }) { listOf(1, 2) }
        assertEquals(listOf(-1, 2), rows)
    }

}
