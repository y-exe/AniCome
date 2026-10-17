package net.yexe.anicome

import androidx.compose.runtime.*
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import androidx.compose.ui.platform.LocalContext
import org.junit.Test
import org.junit.Assert.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SourceToolsInteractionTest : InteractionFixture() {
    @Test fun togglingAndAdjustingOneSourceKeepsTheOtherSource() {
        Session.hiddenSources.value = emptySet(); Session.sourceOffsets.value = emptyMap()
        Session.commentSlots.value = listOf(PlaybackCommentSlot("so1", "official", PlaybackSource.OFFICIAL, listOf(Comment(1000, "official")), true, 1400000, CommentSync.NATIVE))
        var done = false
        val view = fixture {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            val model = remember {
                val settings = SettingsRepository(MemoryPreferences(), scope)
                ConvenienceViewModel(ConvenienceRepository(context, settings, AppCoordinator(scope = scope), DownloadRepository(context, settings), EpisodeMetadataRepository(), scope), UnconfinedTestDispatcher())
            }
            DisposableEffect(model) { onDispose { model.viewModelScope.cancel() } }
            PlaybackTools(model)
        }
        view.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewDetachedFromWindow(v: android.view.View) {}
            override fun onViewAttachedToWindow(v: android.view.View) {
                v.postDelayed({ click(view, "ニコニコ公式") }, 300)
                v.postDelayed({ assertTrue(PlaybackSource.OFFICIAL in Session.hiddenSources.value); click(view, "ニコニコ公式") }, 600)
                v.postDelayed({ assertTrue(Session.hiddenSources.value.isEmpty()); click(view, "1秒遅らせる") }, 900)
                v.postDelayed({ assertEquals(1000L, Session.sourceOffsets.value[PlaybackSource.OFFICIAL]); assertFalse(Session.sourceOffsets.value.containsKey(PlaybackSource.JIKKYO)); click(view, "+1.0 秒") }, 1200)
                v.postDelayed({ assertEquals(0L, Session.sourceOffsets.value[PlaybackSource.OFFICIAL]); assertFalse(texts(view).contains("補正を取り消す")) }, 1500)
                v.postDelayed({ assertEquals(0L, Session.sourceOffsets.value[PlaybackSource.OFFICIAL]); assertFalse(Session.sourceOffsets.value.containsKey(PlaybackSource.JIKKYO)); done = true }, 1800)
            }
        })
        try { paparazzi.gif(view, "source_tools_actions", end = 2100, fps = 10); assertTrue(done) }
        finally { view.disposeComposition(); Session.commentSlots.value = emptyList(); Session.hiddenSources.value = emptySet(); Session.sourceOffsets.value = emptyMap() }
    }
}
