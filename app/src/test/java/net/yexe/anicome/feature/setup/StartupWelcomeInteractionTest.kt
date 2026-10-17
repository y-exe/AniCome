package net.yexe.anicome

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalInspectionMode
import org.junit.Assert.*
import org.junit.Test

class StartupWelcomeInteractionTest : InteractionFixture() {
    @Test fun startupRevealsTheActualWelcomeScreen() {
        var completed by mutableStateOf(false)
        val view = fixture {
            CompositionLocalProvider(LocalInspectionMode provides false) {
                StartupTransition(!completed, { completed = true }) {
                    SetupScreen(false, false, {}, {}, {})
                }
            }
        }
        try {
            paparazzi.gif(view, "startup_to_welcome", end = 4300, fps = 20)
            assertTrue(completed)
            assertTrue(texts(view).contains("いつものアニメに、\nみんなのコメントを。"))
            assertTrue(texts(view).contains("はじめる"))
        } finally { view.disposeComposition() }
    }
}
