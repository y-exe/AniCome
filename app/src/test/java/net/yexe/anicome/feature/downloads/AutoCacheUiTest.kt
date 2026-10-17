package net.yexe.anicome

import androidx.compose.foundation.layout.Column
import org.junit.Assert.*
import org.junit.Test

class AutoCacheUiTest : InteractionFixture() {
    @Test fun cacheGroupsExpandAndShowTheVideoEpisodeNumberAndExpiry() {
        val saved = SavedEpisode(Episode("作品 第2クール", 2), "so1", "作品 第13話", System.currentTimeMillis(), 100, 1024, sources = "OFFICIAL,DANIME")
        val view = fixture { Column { AutoCacheContent(listOf(saved), 48L, {}, {}, {}) } }
        var checked = false
        view.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewDetachedFromWindow(v: android.view.View) {}
            override fun onViewAttachedToWindow(v: android.view.View) {
                v.postDelayed({
                    assertTrue(texts(view).contains("作品 第2クール"))
                    assertFalse(texts(view).contains("第13話"))
                    click(view, "開く")
                }, 300)
                v.postDelayed({
                    assertTrue(texts(view).toString(), texts(view).contains("第13話"))
                    assertTrue(texts(view).any { it.startsWith("あと") && it.endsWith("時間で削除") })
                    checked = true
                }, 700)
            }
        })
        try { paparazzi.gif(view, "cache_expand", end = 1000, fps = 10); assertTrue(checked) }
        finally { view.disposeComposition() }
    }
}
