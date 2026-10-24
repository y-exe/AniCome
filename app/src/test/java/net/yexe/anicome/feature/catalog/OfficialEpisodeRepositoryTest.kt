package net.yexe.anicome

import androidx.datastore.preferences.core.*
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OfficialEpisodeRepositoryTest {
    private fun saved(title: String, channel: String, count: Int = 11) = JSONObject().put("id", 1).put("title", title)
        .put("year", 2024).put("episodes", count).put("provider", "bangumi-data").put("niconicoChannel", channel).toString()
    @Test fun savedChannelIsReusedAcrossEquivalentPlaybackTitles() = runTest {
        val settings = SettingsRepository(MemoryPreferences(), backgroundScope)
        settings.putString("selectedWork:${CommentStore.fileKey("この素晴らしい世界に祝福を！3")}", saved("この素晴らしい世界に祝福を！3", "konosuba_3rd"))
        testScheduler.runCurrent()
        val video = Candidate("so123", "この素晴らしい世界に祝福を！３ 第1話", channelId = 42)
        val repository = OfficialEpisodeRepository(settings, channel = { assertEquals("konosuba_3rd", it.niconicoChannel); 42L },
            byTitle = { error("No new title matching expected") }, videos = { listOf(video) }, resolve = { _, _ -> null })
        assertEquals(listOf(video), repository.candidates(Episode("この素晴らしい世界に祝福を！ 第3期", 1)))
        assertEquals(listOf(video), repository.candidates(Episode("この素晴らしい世界に祝福を! 3", 1)))
        assertTrue(repository.candidates(Episode("この素晴らしい世界に祝福を！ 第3期", 2)).isEmpty())
    }
    @Test fun courLocalNumbersResolveButAbsoluteNumbersAreNotShiftedAgain() = runTest {
        val title = "作品 第2クール"
        val settings = SettingsRepository(MemoryPreferences(), backgroundScope)
        settings.putString("selectedWork:${CommentStore.fileKey(title)}", saved(title, "second-cour", 12))
        testScheduler.runCurrent()
        val first = Candidate("so13", "作品 第13話", channelId = 42)
        var resolved = 0
        val repository = OfficialEpisodeRepository(settings, { 42L }, { null }, { listOf(first) }, { _, number -> resolved++; ResolvedEpisodeMetadata("", Episode("作品", number + 12)) })
        assertEquals(listOf(first), repository.candidates(Episode(title, 1)))
        assertEquals(listOf(first), repository.candidates(Episode(title, 13)))
        assertEquals(1, resolved)
    }
    @Test fun otherSeasonsAndAmbiguousSavedWorksAreNotReused() {
        val p = mutablePreferencesOf(stringPreferencesKey("selectedWork:first") to saved("作品 第1期", "first"),
            stringPreferencesKey("selectedWork:second") to saved("作品 第2期", "second"))
        assertEquals("second", rememberedAnimeWork(p, "作品2")!!.niconicoChannel)
        assertNull(rememberedAnimeWork(p, "作品 第3期"))
        p[stringPreferencesKey("selectedWork:duplicate")] = saved("作品2", "different")
        assertNull(rememberedAnimeWork(p, "作品 第2期"))
    }
}
