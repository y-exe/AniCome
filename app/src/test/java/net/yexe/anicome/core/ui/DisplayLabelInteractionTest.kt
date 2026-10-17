package net.yexe.anicome

import android.content.ContextWrapper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DisplayLabelInteractionTest : InteractionFixture() {
    @Test fun customTitleAndEpisodeAreVisibleWhileTheChannelAndSourceTitleStayIntact() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val settings = SettingsRepository(MemoryPreferences(), scope)
        val app = AppCoordinator(settings = settings, scope = scope)
        val item = SavedEpisode(Episode("元の作品名", 1), "so13", "元の動画 第13話", 0, 1000, 1024, ownerName = "公式チャンネル", ownerUrl = "https://ch.nicovideo.jp/example")
        app.library.value = listOf(item)
        val repository = ConvenienceRepository(ContextWrapper(null), settings, app, DownloadRepository(ContextWrapper(null), settings), EpisodeMetadataRepository(), scope)
        repository.start(); repository.correct(item, "自分の表示名", 42)
        val model = ConvenienceViewModel(repository)
        val view = fixture { CompositionLocalProvider(LocalConvenience provides model) { LibraryScreen(app, listOf(item), Playback(), emptyList()) {} } }
        try {
            paparazzi.snapshot(view, "custom_library_title")
            assertTrue(texts(view).contains("自分の表示名"))
            assertTrue(texts(view).contains(item.ownerUrl))
            assertEquals("元の作品名", item.episode.series)
            for (query in listOf("公式チャンネル", "第13話", "自分の表示名")) {
                val search = nodes(view).first { it.config.getOrNull(SemanticsActions.SetText) != null }
                assertTrue(search.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(query)))
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                paparazzi.snapshot(view, "custom_title_search_${query.length}")
                assertTrue(texts(view).contains("自分の表示名"))
            }
        } finally { view.disposeComposition() }
        val episode = fixture { EpisodeDownloadRow(item, number = model.episodeLabel(item)) {} }
        try {
            paparazzi.snapshot(episode, "custom_episode_label")
            assertTrue(texts(episode).contains("第42話"))
            assertTrue(texts(episode).contains("元の動画 第13話"))
        } finally { episode.disposeComposition(); scope.cancel() }
    }
}
