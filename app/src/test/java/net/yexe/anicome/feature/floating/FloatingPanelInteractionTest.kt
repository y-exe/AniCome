package net.yexe.anicome

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onSizeChanged
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

class FloatingPanelInteractionTest : InteractionFixture() {
    @Test fun compactPanelIsShorterAndKeepsEssentialActionsWithoutExpandingInline() {
        val state = FloatingPanelState(label = "コメント再生中", title = "テスト作品 第13話", current = 240, total = 1000, overlay = true, fromArchive = true)
        var standardHeight = 0; var compactHeight = 0
        val standard = fixture { Box(Modifier.onSizeChanged { standardHeight = it.height }) { FloatingPanelContent(state, FloatingPanelOptions(), false, {}, {}, {}, {}, tools = {}, toggleComments = {}) } }
        try { paparazzi.snapshot(standard, "floating_standard_size") } finally { standard.disposeComposition() }
        var section = ""; var toggled = false; var opened = false; var collapsed = false; var confirmation = false
        val view = fixture { Box(Modifier.onSizeChanged { compactHeight = it.height }) {
            FloatingPanelContent(state, FloatingPanelOptions(compact = true), false, { collapsed = true }, { fail("close must be confirmed") }, { opened = true }, {},
                requestClose = { confirmation = true }, tools = { section = it }, toggleComments = { toggled = true })
        } }
        try {
            paparazzi.snapshot(view, "floating_compact_actions")
            assertTrue("compact=$compactHeight standard=$standardHeight", compactHeight in 1..standardHeight)
            assertFalse(texts(view).contains("表示位置と時刻補正")); assertTrue(texts(view).contains(state.title))
            click(view, "コメント表示の設定"); assertEquals("settings", section)
            assertFalse(texts(view).contains("NGワード")); assertFalse(toggled); assertFalse(opened)
            click(view, "収納する"); assertTrue(collapsed)
            click(view, "パネルを閉じる"); assertTrue(confirmation)
        } finally { view.disposeComposition() }
    }
    @Test fun floatingPanelExpandsChangesPositionAndConfirmsClosing() {
        var collapsed by mutableStateOf(true)
        var position by mutableStateOf("center")
        var confirmation by mutableStateOf(false)
        var closed = 0
        var compact by mutableStateOf(false)
        var section = ""
        var done = false
        val view = fixture {
            CompositionLocalProvider(LocalContentColor provides Color.Black) {
                Box(Modifier.fillMaxSize()) {
                    FloatingPanelContent(FloatingPanelState(position = position), FloatingPanelOptions(compact = compact), collapsed,
                        { collapsed = !collapsed }, { closed++ }, {}, {}, changePosition = { position = it }, requestClose = { confirmation = true }, tools = { section = it })
                    if (confirmation) Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Box(Modifier.padding(24.dp)) {
                            FloatingCloseConfirmationContent({ confirmation = false }, { confirmation = false; closed++ })
                        }
                    }
                }
            }
        }
        view.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewDetachedFromWindow(v: android.view.View) {}
            override fun onViewAttachedToWindow(v: android.view.View) {
                v.postDelayed({ click(view, "パネルを開く") }, 200)
                v.postDelayed({ assertFalse(collapsed); click(view, "コメント表示の設定"); assertEquals("settings", section) }, 500)
                v.postDelayed({ assertFalse(texts(view).contains("表示する位置")) }, 800)
                v.postDelayed({ assertEquals("center", position); click(view, "収納する") }, 1200)
                v.postDelayed({ assertTrue(collapsed); click(view, "パネルを開く") }, 1800)
                v.postDelayed({ click(view, "パネルを閉じる") }, 2400)
                v.postDelayed({ assertEquals(0, closed); assertTrue(texts(view).contains("パネルを閉じますか？")); click(view, "キャンセル") }, 2900)
                v.postDelayed({ assertEquals(0, closed); click(view, "パネルを閉じる") }, 3400)
                v.postDelayed({ click(view, "閉じる"); assertEquals(1, closed) }, 3900)
                v.postDelayed({ assertFalse(texts(view).contains("表示する位置")); compact = true }, 4200)
                v.postDelayed({ assertFalse(texts(view).contains("表示する位置")); click(view, "コメント表示の設定"); assertEquals("settings", section); done = true }, 4600)
            }
        })
        try { paparazzi.gif(view, "floating_panel_actions", end = 4900, fps = 10); assertTrue(done) }
        finally { view.disposeComposition() }
    }
}
