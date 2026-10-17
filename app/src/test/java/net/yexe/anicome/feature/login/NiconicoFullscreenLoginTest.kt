package net.yexe.anicome

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import org.junit.Assert.*
import org.junit.Test

class NiconicoFullscreenLoginTest : InteractionFixture() {
    @Test fun pageFillsTheViewportAndOnlyTheBottomControlsOverlayIt() {
        for (theme in listOf("dark", "light")) {
            val linked = mutableStateOf(false)
            var back = 0; var reload = 0; var completed = 0
            val view = fixture(theme) {
                Box(Modifier.fillMaxSize().testTag("viewport")) {
                    NiconicoFullscreenLoginScreen(linked.value, { back++ }, { completed++ }, { reload++ }) { modifier ->
                        NiconicoLoginWebContent(modifier.testTag("official-login-page"))
                    }
                }
            }
            try {
                paparazzi.snapshot(view, "fullscreen_login_$theme")
                val viewport = nodes(view).first { it.config.getOrNull(SemanticsProperties.TestTag) == "viewport" }.boundsInRoot
                val page = nodes(view).first { it.config.getOrNull(SemanticsProperties.TestTag) == "official-login-page" }.boundsInRoot
                assertEquals(viewport, page)
                val backButton = nodes(view).first { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("戻る") }
                assertTrue("Controls stay at the bottom", backButton.boundsInRoot.top > viewport.height / 2)
                assertFalse(texts(view).contains("ニコニコ連携"))
                assertTrue(nodes(view).any { it.config.contains(SemanticsProperties.Disabled) })
                click(view, "戻る"); click(view, "再読み込み")
                assertEquals(1, back); assertEquals(1, reload); assertEquals(0, completed)
                linked.value = true
                paparazzi.snapshot(view, "fullscreen_login_linked_$theme")
                click(view, "ログイン完了"); assertEquals(1, completed)
            } finally { view.disposeComposition() }
        }
    }
}
