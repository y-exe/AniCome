package net.yexe.anicome

import android.content.ContextWrapper
import androidx.compose.ui.semantics.*
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SyncUndoInteractionTest : InteractionFixture() {
    @Test fun globalButtonsAndSliderAdjustWithoutUndoActions() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val context = ContextWrapper(null); val settings = SettingsRepository(MemoryPreferences(), scope)
        val corrections = SyncAdjustmentRepository(scope)
        val repo = ConvenienceRepository(context, settings, AppCoordinator(settings = settings, scope = scope), DownloadRepository(context, settings), EpisodeMetadataRepository(), scope, corrections = corrections)
        val model = ConvenienceViewModel(repo)
        Session.playback.value = Playback(title = "作品", mediaId = "ep1"); Session.matchedKey = Session.playback.value.key
        Session.offset.value = 0; Session.sourceOffsets.value = emptyMap(); Session.commentBinding.value = CommentBinding.AUTO
        val view = fixture { PlaybackTools(model) }
        var finished = false
        view.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewDetachedFromWindow(v: android.view.View) {}
            override fun onViewAttachedToWindow(v: android.view.View) {
                v.postDelayed({ click(view, "全体の時刻補正") }, 300)
                v.postDelayed({ click(view, "−1秒") }, 700)
                v.postDelayed({ assertEquals(1000L, Session.offset.value); assertTrue(texts(view).contains("-1.0 秒")); click(view, "全体の補正を0秒に戻す") }, 1100)
                v.postDelayed({
                    assertEquals(0L, Session.offset.value); assertFalse(texts(view).contains("補正を取り消す"))
                    Session.sourceOffsets.value = mapOf(PlaybackSource.JIKKYO to 5000L)
                    val slider = nodes(view).first { it.config.getOrNull(SemanticsActions.SetProgress) != null }
                    assertTrue(slider.config[SemanticsActions.SetProgress].action!!.invoke(-1500f))
                }, 1500)
                v.postDelayed({ assertEquals(1500L, Session.offset.value); assertEquals(5000L, Session.sourceOffsets.value[PlaybackSource.JIKKYO]); assertFalse(texts(view).contains("補正を取り消す")); finished = true }, 1900)
            }
        })
        try { paparazzi.gif(view, "global_timing_undo", end = 3100, fps = 10); assertTrue(finished) }
        finally { view.disposeComposition(); model.viewModelScope.cancel(); scope.cancel(); Session.offset.value = 0; Session.sourceOffsets.value = emptyMap(); Session.playback.value = Playback(); Session.matchedKey = "" }
    }
}
