package net.yexe.anicome

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalInspectionMode
import org.junit.Assert.*
import org.junit.Test

class SetupAnimationPreviewTest : InteractionFixture() {
    @Test fun sixStepsMoveForwardAndBackUsingTheSharedSpatialMotion() {
        val pages = listOf(0, 1, 2, 3, 4, 6)
        val index = mutableStateOf(0)
        val view = fixture {
            CompositionLocalProvider(LocalInspectionMode provides false) {
                SetupScreen(true, true, {}, {}, {}, linked = true, currentStep = pages[index.value],
                    onNext = { index.value = (index.value + 1).coerceAtMost(pages.lastIndex) },
                    onBack = { index.value = (index.value - 1).coerceAtLeast(0) })
            }
        }
        view.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewDetachedFromWindow(v: android.view.View) {}
            override fun onViewAttachedToWindow(v: android.view.View) {
                (1..5).forEach { i -> v.postDelayed({ index.value = i }, i * 900L) }
                v.postDelayed({ index.value = 4 }, 5500L)
            }
        })
        try { paparazzi.gif(view, "setup_six_step_motion", end = 6300, fps = 20); assertEquals(4, index.value) }
        finally { view.disposeComposition() }
    }
}
