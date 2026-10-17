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

class LogoStartupInteractionTest : InteractionFixture() {
    @Test fun logoStartupCompletesTheProvidedAnimation() {
        com.airbnb.lottie.LottieCompositionFactory.fromAssetSync(paparazzi.context, "logo-animation.json").value!!.let { assertTrue(it.duration > 3000) }
        var completed by mutableStateOf(false)
        var started = -1L
        var elapsed = 0L
        val view = fixture {
            CompositionLocalProvider(LocalInspectionMode provides false) {
                SideEffect { if (started < 0) started = android.os.SystemClock.uptimeMillis() }
                StartupTransition(!completed, { completed = true; elapsed = android.os.SystemClock.uptimeMillis() - started }) {
                    Surface(Modifier.fillMaxSize()) { Box(contentAlignment = androidx.compose.ui.Alignment.Center) { AppText("ホーム") } }
                }
            }
        }
        try { paparazzi.gif(view, "logo_startup", end = 3800, fps = 20); assertTrue("起動アニメーションが完了する", completed); assertTrue(texts(view).contains("ホーム")); assertTrue("参考GIFに近い約3秒で完了する: $elapsed ms", elapsed in 2800..3400) }
        finally { view.disposeComposition() }
    }
}
