package net.yexe.anicome

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TemporaryHideInteractionTest : InteractionFixture() {
    @Test fun durationEpisodeAndFloatingResumeOperateOnTheSameVisibilityState() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val comments = listOf(Comment(1000, "　■\n ■　"))
        Session.comments.value = comments; Session.playback.value = Playback(title = "テスト作品", mediaId = "ep1", playing = true)
        Session.hiddenUntil.value = 0; Session.hiddenEpisode.value = ""
        val visibility = CommentVisibilityRepository(scope) { 0L }
        var section = ""; var finished = false
        val view = fixture {
            val state by visibility.state.collectAsStateWithLifecycle()
            Column {
                TemporaryHideControls(state, visibility::hide, full = true)
                FloatingPanelContent(FloatingPanelState(label = temporaryHideLabel(state), temporaryHidden = state.active), FloatingPanelOptions(compact = true, actions = listOf(PanelAction.HIDE)), false,
                    {}, {}, {}, {}, tools = { section = it }, resumeComments = { visibility.hide("none") })
            }
        }
        view.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewDetachedFromWindow(v: android.view.View) {}
            override fun onViewAttachedToWindow(v: android.view.View) {
                v.postDelayed({ click(view, "1分") }, 300)
                v.postDelayed({ assertEquals(60, visibility.state.value.remainingSeconds); assertTrue(texts(view).contains("60秒後にコメントを再開")); click(view, "この話を隠す") }, 700)
                v.postDelayed({ assertTrue(visibility.state.value.episodeHidden); click(view, "再開") }, 1100)
                v.postDelayed({
                    assertFalse(visibility.state.value.active); assertSame(comments, Session.comments.value); assertTrue(Session.playback.value.playing)
                    click(view, "コメント表示の設定"); assertEquals("settings", section); finished = true
                }, 1500)
            }
        })
        try { paparazzi.gif(view, "temporary_hide_controls", end = 1800, fps = 10); assertTrue(finished) }
        finally { view.disposeComposition(); scope.cancel(); Session.hiddenUntil.value = 0; Session.hiddenEpisode.value = ""; Session.playback.value = Playback(); Session.comments.value = emptyList() }
    }
}
