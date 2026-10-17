package net.yexe.anicome

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.*
import app.cash.paparazzi.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReferencePressTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(locale = "ja", softButtons = false), theme = "android:Theme.Material.NoActionBar")
    @Before fun before() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun after() { Dispatchers.resetMain() }
    private fun nodes(view: ComposeView): Collection<SemanticsNode> {
        val child = view.getChildAt(0)
        val owner = child.javaClass.getMethod("getSemanticsOwner").invoke(child) as SemanticsOwner
        return owner.getAllSemanticsNodes(true)
    }
    @Test fun nativePressShrinksAndReturnsWithoutLosingTheClick() {
        var pressedWidth = 0f
        var originalWidth = 0f
        var clicked = false
        var finished = false
        val view = ComposeView(paparazzi.context).apply { setContent { AniComeTheme { Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { StudioButton({ clicked = true }) { AppText("確認") } } } } }
        fun button() = nodes(view).first { it.config.getOrNull(SemanticsActions.OnClick) != null }
        fun touch(action: Int, time: Long) {
            val bounds = button().boundsInRoot
            val pointer = android.view.MotionEvent.PointerProperties().apply { id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER }
            val coordinates = android.view.MotionEvent.PointerCoords().apply { x = bounds.center.x; y = bounds.center.y; pressure = 1f; size = 1f }
            val event = android.view.MotionEvent.obtain(100, time, action, 1, arrayOf(pointer), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            view.dispatchTouchEvent(event)
            event.recycle()
        }
        view.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewDetachedFromWindow(v: android.view.View) {}
            override fun onViewAttachedToWindow(v: android.view.View) {
                v.postDelayed({ originalWidth = button().boundsInRoot.width; touch(android.view.MotionEvent.ACTION_DOWN, 100) }, 100)
                v.postDelayed({ pressedWidth = button().boundsInRoot.width; assertTrue("押下の縮み: $originalWidth -> $pressedWidth", pressedWidth < originalWidth * .98f) }, 400)
                v.postDelayed({ touch(android.view.MotionEvent.ACTION_UP, 500) }, 500)
                v.postDelayed({ assertTrue(clicked); assertEquals(originalWidth, button().boundsInRoot.width, originalWidth * .015f); finished = true }, 1100)
            }
        })
        try { paparazzi.gif(view, "expressive_press", end = 1300, fps = 20); assertTrue(finished) }
        finally { view.disposeComposition() }
    }
}
