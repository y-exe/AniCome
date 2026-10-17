package net.yexe.anicome

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import org.junit.Assert.*
import org.junit.Test

class SetupInteractionTest : InteractionFixture() {
    @Test fun inlineLoginOffersFullscreenWithoutSkippingLogin() {
        var fullscreen = 0
        val view = fixture { SetupScreen(true, true, {}, {}, {}, currentStep = 3, onLogin = { fullscreen++ }) }
        try {
            paparazzi.snapshot(view, "setup_inline_login")
            click(view, "全画面で表示")
            assertEquals(1, fullscreen)
            assertFalse(texts(view).contains("次へ"))
            assertFalse(texts(view).contains("ログイン完了"))
        } finally { view.disposeComposition() }
    }
    @Test fun eachPermissionHasAnExplanationAndBlockedNextButton() {
        for (step in listOf(1, 2)) {
            var helps = 0
            val view = fixture { SetupScreen(false, false, {}, {}, { helps++ }, currentStep = step) }
            try {
                paparazzi.snapshot(view, "setup_permission_$step")
                click(view, "アクセスを許可できない場合"); assertEquals(1, helps)
                assertFalse(texts(view).contains("次へ")); assertTrue(texts(view).contains("Android設定を開く"))
            } finally { view.disposeComposition() }
        }
    }
    @Test fun welcomeAndGuideUseTheSameNavigationControls() {
        val step = mutableStateOf(0)
        val view = fixture { SetupScreen(true, true, {}, {}, {}, linked = true, currentStep = step.value, onNext = { step.value = listOf(0, 1, 2, 3, 4, 6).let { it[(it.indexOf(step.value) + 1).coerceAtMost(it.lastIndex)] } }, onBack = { step.value = if (step.value == 6) 4 else step.value - 1 }) }
        try {
            paparazzi.snapshot(view, "setup_welcome")
            click(view, "はじめる"); assertEquals(1, step.value)
            step.value = 4; paparazzi.snapshot(view, "setup_guide_app")
            click(view, "次へ"); paparazzi.snapshot(view, "setup_guide_play")
            assertEquals(6, step.value); assertTrue(texts(view).contains("事前ダウンロード"))
            click(view, "戻る"); assertEquals(4, step.value)
            step.value = 6; paparazzi.snapshot(view, "setup_guide_save")
            assertTrue(texts(view).contains("AniComeをはじめる"))
        } finally { view.disposeComposition() }
    }
    @Test fun everyStepFitsWithoutScrollingOrClippingItsText() {
        for (step in listOf(0, 1, 2, 3, 4, 6)) {
            val view = fixture { SetupScreen(false, false, {}, {}, {}, currentStep = step, notificationsAllowed = false) }
            try {
                paparazzi.snapshot(view, "setup_fits_$step")
                assertFalse("step=$step must not scroll", nodes(view).any { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null })
                nodes(view).filter { it.config.getOrNull(SemanticsProperties.Text) != null }.forEach { node ->
                    val bounds = node.boundsInRoot
                    assertTrue("step=$step text=${node.config.getOrNull(SemanticsProperties.Text)} bounds=$bounds height=${view.height}", bounds.top >= 0 && bounds.bottom <= view.height + 1)
                }
            } finally { view.disposeComposition() }
        }
    }
    @Test fun floatingCloseActionIsOutsideTheScrollableSettings() {
        var closed = false
        val view = fixture { FloatingPlaybackSettingsContent(FloatingPanelState(overlay = true), 14f, .75f, .7f, {}, {}, {}, { _, _ -> }, { closed = true }) }
        try { paparazzi.snapshot(view, "floating_settings_detached_close"); click(view, "閉じる"); assertTrue(closed) }
        finally { view.disposeComposition() }
    }
}
