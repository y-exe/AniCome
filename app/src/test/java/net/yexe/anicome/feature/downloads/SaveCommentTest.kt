package net.yexe.anicome

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SaveCommentTest {
    @Before fun before() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun after() { Dispatchers.resetMain() }
    @Test fun oneCharacterSearchStillRequiresAnExplicitUnchangedSelection() = runTest {
        val work = AnimeInfo(1, "X", "", "", null, 2026, 26, emptyList())
        val queries = mutableListOf<String>()
        val model = SaveCommentViewModel(SaveCommentRepository(catalogSearch = { queries += it; listOf(work) }))
        model.query("X")
        advanceUntilIdle()
        assertEquals(listOf("X"), queries); assertFalse(model.state.value.canSave)
        model.choose(work); assertTrue(model.state.value.canSave)
        model.all(true); assertEquals(26, model.state.value.episodesToSave().size)
        assertEquals(26, model.state.value.episodesToSave().last().number)
        model.query("X "); assertFalse(model.state.value.canSave)
        model.query("X"); assertFalse(model.state.value.canSave)
    }
    @Test fun unknownEpisodeCountsDisableAllEpisodesWithoutGuessing() {
        val work = AnimeInfo(1, "X", "", "", null, 2026, null, emptyList())
        val state = SaveCommentState(query = "X", work = work, allEpisodes = true)
        assertFalse(state.canSave); assertTrue(state.episodesToSave().isEmpty())
        assertTrue(state.copy(allEpisodes = false).canSave)
        assertFalse(state.copy(allEpisodes = false, query = "X ").canSave)
    }
    @Test fun channelChoiceIsRememberedPerWorkAndCanBeCleared() = runTest {
        val settings = SettingsRepository(MemoryPreferences(), backgroundScope)
        val first = AnimeInfo(1, "作品 第1期", "", "", null, 2026, 12, emptyList())
        val second = first.copy(id = 2, title = "作品 第2期")
        val repo = SaveCommentRepository(settings = settings, reserveSelected = { _, _ -> })
        repo.save(listOf(Episode(first.title, 1)), first, channel = 123L)
        runCurrent()
        assertEquals(123L, repo.rememberedChannel(first))
        assertNull(repo.rememberedChannel(second))
        val model = SaveCommentViewModel(SaveCommentRepository(settings = settings, catalogSearch = { listOf(first, second) }))
        model.query("作品"); advanceUntilIdle(); model.choose(first); runCurrent()
        assertEquals(123L, model.state.value.rememberedChannel)
        model.forgetChannel(); runCurrent()
        assertNull(model.state.value.rememberedChannel)
        assertNull(repo.rememberedChannel(first))
    }

    @Test fun savingWaitsForReviewAndOnlyReservesCheckedEpisodes() = runTest {
        val work = AnimeInfo(1, "作品", "", "", null, 2026, 3, emptyList())
        val reserved = mutableListOf<Episode>()
        val review = SaveReviewRepository(candidates = { ep, _ -> listOf(Candidate("so${ep.number}", "作品 第${ep.number}話", channelId = 10)) })
        val model = SaveCommentViewModel(SaveCommentRepository(catalogSearch = { listOf(work) }, reserveSelected = { ep, _ -> reserved += ep }, reviews = review, ioDispatcher = UnconfinedTestDispatcher(testScheduler)))
        model.query(work.title); advanceUntilIdle(); model.choose(work); runCurrent(); model.all(true)
        model.save(); model.state.first { !it.reviewing }; advanceUntilIdle()
        assertTrue(reserved.isEmpty()); assertNotNull(model.state.value.review)
        model.selectReview(Episode(work.title, 2), false)
        model.confirmSave(); advanceUntilIdle()
        assertEquals(listOf(1, 3), reserved.map { it.number })
        assertTrue(model.state.value.complete)
    }
    @Test fun confirmationDoesNotReserveWhenDiskSpaceIsInsufficient() = runTest {
        val work = AnimeInfo(1, "作品", "", "", null, 2026, 1, emptyList())
        var calls = 0
        val model = SaveCommentViewModel(SaveCommentRepository(catalogSearch = { listOf(work) }, reserveSelected = { _, _ -> calls++ },
            reviews = SaveReviewRepository(storage = { SaveSpace(free = 1L) })))
        model.query(work.title); advanceUntilIdle(); model.choose(work); runCurrent()
        model.save(); model.state.first { !it.reviewing }; advanceUntilIdle(); model.confirmSave(); advanceUntilIdle()
        assertEquals(0, calls); assertFalse(model.state.value.complete)
        model.closeReview(); assertNull(model.state.value.review)
    }

    @Test fun reviewWaitsForRememberedChannelSoTheConfirmedVideoCannotChangeChannelsLater() = runTest {
        val work = AnimeInfo(1, "作品", "", "", null, 2026, 1, emptyList())
        val gate = CompletableDeferred<androidx.datastore.preferences.core.Preferences>()
        val store = object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
            override val data = kotlinx.coroutines.flow.flow { emit(gate.await()) }
            override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences) = error("no writes")
        }
        val model = SaveCommentViewModel(SaveCommentRepository(settings = SettingsRepository(store, backgroundScope), catalogSearch = { listOf(work) }))
        model.query(work.title); advanceUntilIdle(); model.choose(work); runCurrent()
        assertTrue(model.state.value.restoringChannel); assertFalse(model.state.value.canSave)
        model.save(); assertFalse(model.state.value.reviewing)
        gate.complete(androidx.datastore.preferences.core.mutablePreferencesOf(androidx.datastore.preferences.core.longPreferencesKey("workChannel:${CommentStore.fileKey(work.title)}") to 123L))
        runCurrent()
        assertFalse(model.state.value.restoringChannel); assertEquals(123L, model.state.value.rememberedChannel)
        assertTrue(model.state.value.canSave)
    }

    @Test fun duplicateVideoReviewCannotEnqueueUntilTheConflictingEpisodeIsExcluded() = runTest {
        val work = AnimeInfo(1, "作品", "", "", null, 2026, 2, emptyList())
        val reserved = mutableListOf<Episode>()
        val reviews = SaveReviewRepository(resolve = { _, _ -> ResolvedEpisodeMetadata("", Episode(work.title, 13)) },
            candidates = { _, _ -> listOf(Candidate("so13", "作品 第13話", channelId = 10)) })
        val repo = SaveCommentRepository(catalogSearch = { listOf(work) }, reserveSelected = { ep, _ -> reserved += ep }, reviews = reviews, ioDispatcher = UnconfinedTestDispatcher(testScheduler))
        val model = SaveCommentViewModel(repo)
        model.query(work.title); advanceUntilIdle(); model.choose(work); runCurrent(); model.all(true)
        model.save(); model.state.first { !it.reviewing }; advanceUntilIdle()
        assertEquals(setOf("so13"), model.state.value.review!!.duplicateVideoIds)
        model.confirmSave(); advanceUntilIdle()
        assertTrue(reserved.isEmpty()); assertEquals(DuplicateReviewMessage, model.state.value.error)
        try { repo.save(model.state.value.review!!.selected.map { it.episode }, work, reviewed = model.state.value.review!!.selected); fail() }
        catch (_: IllegalArgumentException) { }
        assertTrue(reserved.isEmpty())
        model.selectReview(Episode(work.title, 2), false)
        assertEquals("", model.state.value.error)
        model.confirmSave(); advanceUntilIdle()
        assertEquals(listOf(Episode(work.title, 1)), reserved)
        assertTrue(model.state.value.complete)
    }

}
