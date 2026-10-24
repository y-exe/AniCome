package net.yexe.anicome

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class VideoReplacementTest {
    @Before fun before() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun after() { Dispatchers.resetMain() }
    @Test fun replacingOfficialRetainsOtherSourcesAndArtistRawCommands() = runTest {
        val root = Files.createTempDirectory("anicome-replace").toFile()
        try {
            val store = CommentStore(root)
            val episode = Episode("作品", 1)
            store.save(episode, Candidate("so980001", "作品 第1話", channelId = 10), listOf(Comment(0, "old")))
            val artist = Comment(2000, " /\\\n AA", """{"commands":["ue","gothic"]}""")
            store.saveSources(episode, listOf(PlaybackCommentSlot("so980003", "作品 第1話", PlaybackSource.DANIME, listOf(artist))))
            val selected = Candidate("so980002", "作品 第1話", channelId = 10)
            val repo = CommentRepository(store, load = { _, _ -> listOf(Comment(1000, "new")) })
            val remembered = mutableListOf<Pair<Episode, Candidate>>()
            VideoReplacementRepository(repo, metadata = { selected }, remembered = { target, candidate -> remembered += target to candidate })
                .replace(store.read(episode)!!.info, selected, episode, "")
            assertEquals(listOf(episode to selected), remembered)
            val result = store.read(episode)!!
            assertEquals(selected.id, result.info.sourceId)
            assertEquals(listOf("new"), result.comments.map { it.body })
            assertEquals(listOf(artist), result.slots.single { it.source == PlaybackSource.DANIME }.comments)
            assertFalse(result.slots.any { it.id == "so980001" })
        } finally { root.deleteRecursively() }
    }
    @Test fun existingAlternativeInSameEpisodeBecomesPrimaryWithoutDownloadingAgain() = runTest {
        val root = Files.createTempDirectory("anicome-replace-cached").toFile()
        try {
            val store = CommentStore(root); val ep = Episode("作品", 1)
            store.save(ep, Candidate("so980011", "作品 第1話", channelId = 10), listOf(Comment(0, "old")))
            store.saveSources(ep, listOf(PlaybackCommentSlot("so980012", "作品 第1話", PlaybackSource.OFFICIAL, listOf(Comment(1000, "cached")))))
            val chosen = Candidate("so980012", "作品 第1話", channelId = 10)
            val repo = CommentRepository(store, load = { _, _ -> error("network must not be used") })
            repo.replaceOfficial(store.read(ep)!!.info, chosen)
            assertEquals(chosen.id, store.read(ep)!!.info.sourceId)
            assertEquals("cached", store.read(ep)!!.comments.single().body)
        } finally { root.deleteRecursively() }
    }
    @Test fun changedOrWrongSeasonMetadataCannotOverwriteAnExistingArchive() = runTest {
        val root = Files.createTempDirectory("anicome-replace-reject").toFile()
        try {
            val store = CommentStore(root); val ep = Episode("作品", 1)
            store.save(ep, Candidate("so980021", "作品 第1話", channelId = 10), listOf(Comment(0, "old")))
            val chosen = Candidate("so980022", "作品 第1話", channelId = 10)
            val repo = CommentRepository(store, load = { _, _ -> error("must validate first") })
            try { VideoReplacementRepository(repo, metadata = { chosen.copy(title = "作品 第2期 第1話") }).replace(store.read(ep)!!.info, chosen, ep, ""); fail() }
            catch (_: MatchUnavailable) { }
            assertEquals("old", store.read(ep)!!.comments.single().body)
        } finally { root.deleteRecursively() }
    }
    @Test fun editedQueriesInvalidateOldSelectionsAndRejectMismatchingCandidates() = runTest {
        val root = Files.createTempDirectory("anicome-replace-query").toFile()
        try {
            val correct = Candidate("so980031", "作品 第1話", channelId = 10)
            val wrong = correct.copy(id = "so980032", title = "作品 第2期 第1話")
            val queries = mutableListOf<String>()
            val repo = CommentRepository(CommentStore(root), search = { queries += it; listOf(correct, wrong) })
            val model = VideoReplacementViewModel(VideoReplacementRepository(repo))
            model.start(SavedEpisode(Episode("作品", 1), "so980030", "作品 第1話", 0L, 1, 1)); advanceUntilIdle()
            model.choose(wrong); assertNull(model.state.value.selected)
            model.choose(correct); assertEquals(correct, model.state.value.selected)
            model.edit("作品 新しい旅"); assertNull(model.state.value.selected); assertTrue(model.state.value.candidates.isEmpty())
            model.query(); advanceUntilIdle(); assertEquals("作品 新しい旅", queries.last())
        } finally { root.deleteRecursively() }
    }
}
