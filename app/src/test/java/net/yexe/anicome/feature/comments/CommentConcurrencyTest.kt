package net.yexe.anicome

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CommentConcurrencyTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun manualSearchDoesNotWaitForACommentDownload() = runBlocking {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val candidate = Candidate("so8811", "テスト 第1話", channelId = 1234)
        val repository = CommentRepository(CommentStore(folder.newFolder()), { listOf(candidate) }, { _, _ ->
            started.complete(Unit); release.await(); listOf(Comment(1000, "test"))
        })
        val download = async { repository.obtain(Episode("テスト", 1), candidate) }
        try { started.await(); assertEquals(listOf(candidate), withTimeout(1000) { repository.searchCandidates("テスト") }) }
        finally { release.complete(Unit); download.await() }
    }
    @Test fun DifferentEpisodesDownloadConcurrentlyAndWriteSeparateArchives() = runBlocking {
        val started = kotlinx.coroutines.channels.Channel<String>(3); val release = CompletableDeferred<Unit>()
        val repository = CommentRepository(CommentStore(folder.newFolder()), load = { id, _ ->
            started.send(id); release.await(); listOf(Comment(1000, id))
        })
        val jobs = (1..3).map { number -> async { repository.obtain(Episode("テスト", number), Candidate("so882$number", "第${number}話", channelId = 1234)) } }
        try { withTimeout(1500) { repeat(3) { started.receive() } } }
        finally { release.complete(Unit) }
        assertEquals(3, jobs.awaitAll().map { it.info.episode.key }.distinct().size)
        assertEquals(3, repository.store.list().size)
    }
    @Test fun EmptyEpisodeQueryFallsBackToTitleAndKeepsUserVideoCandidates() = runBlocking {
        val queries = mutableListOf<String>()
        val candidate = Candidate("sm8822", "テスト #01", userId = 22)
        val repository = CommentRepository(CommentStore(folder.newFolder()), search = { query -> queries.add(query); if (query == "テスト") listOf(candidate) else emptyList() })
        assertEquals(listOf(candidate), repository.searchEpisodeCandidates("テスト", 1))
        assertEquals("テスト", queries.last())
    }
    @Test fun PlayedCountHandlesSeekDuplicatesAndBoundaries() {
        val comments = listOf(Comment(1000, "a"), Comment(1000, "b"), Comment(2000, "c"))
        assertEquals(0, playedCommentCount(comments, -1))
        assertEquals(2, playedCommentCount(comments, 1000))
        assertEquals(3, playedCommentCount(comments, 2500))
        assertEquals(0, playedCommentCount(comments, 500))
        assertEquals(0, playedCommentCount(emptyList(), 1000))
    }
    @Test fun APreciseUserVideoDoesNotHideOfficialTitleMatches() = runBlocking {
        val user = Candidate("sm8899", "テスト 第1話", userId = 3, commentCount = 5000)
        val official = Candidate("so8899", "テスト 第1話", channelId = 1234, commentCount = 100)
        val repository = CommentRepository(CommentStore(folder.newFolder()), search = { if (it == "テスト") listOf(official) else listOf(user) })
        assertEquals(listOf(official, user), repository.searchEpisodeCandidates("テスト", 1))
    }
    @Test fun FloatingCountAndRendererKeepFixedCommentsAndArtUnlessBlockedByNg() {
        assertFalse(renderableComment(Comment(0, "Spoiler"), listOf("spoiler")))
        for (command in listOf("ue", "shita", "naka")) {
            val art = Comment(0, "　■\n ■　", """{"commands":["$command"]}""")
            assertTrue(renderableComment(art, emptyList()))
            assertFalse(renderableComment(art, listOf("■")))
        }
    }
}
