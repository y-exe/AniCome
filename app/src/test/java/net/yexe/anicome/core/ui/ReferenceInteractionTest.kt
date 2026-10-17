package net.yexe.anicome

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.Color
import app.cash.paparazzi.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

class ReferenceInteractionTest : InteractionFixture() {
    @Test fun searchNavigationAndSavedLibrary() {
        val titles = listOf("保存作品A", "保存作品B")
        @Suppress("UNCHECKED_CAST")
        val cache = AnimeCatalog.javaClass.getDeclaredField("cache").apply { isAccessible = true }.get(null) as MutableMap<String, AnimeInfo>
        titles.forEachIndexed { i, title -> cache[EpisodeMatcher.normalize(title)] = AnimeInfo(i + 10, title, "", "", null, 2026, 12, emptyList()) }
        val library = titles.map { SavedEpisode(Episode(it, 1), "so1", it, 0, 1000, 1_000_000) }
        var focus: androidx.compose.ui.focus.FocusManager? = null
        var saved by mutableStateOf(false)
        val view = fixture(tab = { if (saved) AppTab.DOWNLOADS else AppTab.SETTINGS }) {
            val currentFocus = LocalFocusManager.current
            SideEffect { focus = currentFocus }
            if (saved) LibraryScreen(LocalContext.current.aniComeApp, library, Playback(), emptyList()) {}
            else SettingsScreen {}
        }
        var finished = false
        fun input(value: String) {
            val field = nodes(view).first { it.config.getOrNull(SemanticsActions.SetText) != null }
            assertTrue(field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value)))
        }
        view.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewDetachedFromWindow(v: android.view.View) {}
            override fun onViewAttachedToWindow(v: android.view.View) {
                v.postDelayed({ input("FPS") }, 200)
                v.postDelayed({
                    assertTrue(texts(view).contains("コメント表示"))
                    assertFalse(texts(view).contains("色と書体"))
                    click(view, "コメント表示")
                }, 600)
                v.postDelayed({ assertTrue(texts(view).contains("文字サイズ")); click(view, "戻る") }, 1000)
                v.postDelayed({ assertTrue(texts(view).contains("設定")); input(""); focus!!.clearFocus(force = true) }, 1400)
                val start = 2500L
                var scrollBounds: androidx.compose.ui.geometry.Rect? = null
                var scrollRange: ScrollAxisRange? = null
                val touches = mutableListOf<Boolean>()
                fun touch(action: Int, time: Long, y: Float) {
                    val bounds = scrollBounds!!
                    val pointer = android.view.MotionEvent.PointerProperties().apply { id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER }
                    val coordinates = android.view.MotionEvent.PointerCoords().apply { x = bounds.center.x; this.y = bounds.top + bounds.height * y; pressure = 1f; size = 1f }
                    val event = android.view.MotionEvent.obtain(start, time, action, 1, arrayOf(pointer), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                    touches += v.dispatchTouchEvent(event)
                    event.recycle()
                }
                v.postDelayed({ val scrollNode = nodes(view).filter { it.config.getOrNull(SemanticsActions.ScrollBy) != null }.maxBy { it.boundsInRoot.height }; scrollBounds = scrollNode.boundsInRoot; scrollRange = scrollNode.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange); touch(android.view.MotionEvent.ACTION_DOWN, start, .9f) }, start)
                repeat(12) { step ->
                    val time = start + (step + 1) * 16L
                    v.postDelayed({ touch(android.view.MotionEvent.ACTION_MOVE, time, (.9f - (step + 1) * .06f)) }, time)
                }
                v.postDelayed({ touch(android.view.MotionEvent.ACTION_UP, start + 210, .18f) }, start + 210)
                v.postDelayed({
                    val title = nodes(view).filter { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "設定" } && it.boundsInRoot.height > 0 }
                    assertTrue("スクロール後の見出し: ${title.map { it.boundsInRoot }}", title.isNotEmpty() && title.all { it.boundsInRoot.bottom <= v.resources.displayMetrics.density * 80 })
                    saved = true
                }, 2900)
                v.postDelayed({ assertFalse(nodes(view).any { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("グリッド表示に切り替え") }) }, 3300)
                v.postDelayed({ input("作品A") }, 3700)
                v.postDelayed({
                    assertTrue(texts(view).contains("保存作品A"))
                    assertFalse(texts(view).contains("保存作品B"))
                    click(view, "保存作品A")
                }, 4100)
                v.postDelayed({ assertTrue(texts(view).contains("第1話")); click(view, "戻る") }, 5100)
                v.postDelayed({ click(view, "保存予約") }, 6200)
                v.postDelayed({
                    assertTrue(texts(view).contains("保存予約はありません"))
                    assertFalse(nodes(view).any { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("グリッド表示に切り替え") })
                    finished = true
                }, 7300)
            }
        })
        try { paparazzi.gif(view, "settings_and_library", end = 7700, fps = 10); assertTrue(finished) }
        finally { view.disposeComposition() }
    }
}
