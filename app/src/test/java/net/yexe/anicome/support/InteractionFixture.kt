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

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, ExperimentalMaterial3Api::class)
abstract class InteractionFixture {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(locale = "ja", softButtons = false), theme = "android:Theme.Material.NoActionBar")
    private lateinit var mainDispatcher: TestDispatcher
    @Before fun before() { mainDispatcher = UnconfinedTestDispatcher(); Dispatchers.setMain(mainDispatcher) }
    @After fun after() { Dispatchers.resetMain() }
    internal fun fixture(mode: String = "dark", tab: () -> AppTab = { AppTab.SETTINGS }, content: @Composable () -> Unit): ComposeView {
        val application = PreviewApplication()
        val app = application.runtime
        val context = object : ContextWrapper(paparazzi.context) { override fun getApplicationContext(): Context = application }
        return ComposeView(paparazzi.context).apply {
            setViewCompositionStrategy(object : ViewCompositionStrategy {
                override fun installFor(view: AbstractComposeView): () -> Unit = {}
            })
            setContent {
                val lifecycleOwner = androidx.compose.runtime.remember {
                    object : androidx.lifecycle.LifecycleOwner {
                        override val lifecycle = androidx.lifecycle.LifecycleRegistry(this).apply { currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
                    }
                }
                CompositionLocalProvider(LocalContext provides context, LocalNiconicoLoginPreview provides true, androidx.lifecycle.compose.LocalLifecycleOwner provides lifecycleOwner) {
                    AniComeTheme(mode) { Scaffold() { padding -> Box(Modifier.fillMaxSize().padding(padding)) { content() } } }
                }
            }
        }
    }
    protected fun nodes(view: ComposeView): Collection<SemanticsNode> {
        val child = view.getChildAt(0)
        val owner = child.javaClass.getMethod("getSemanticsOwner").invoke(child) as SemanticsOwner
        return owner.getAllSemanticsNodes(true)
    }
    protected fun texts(view: ComposeView): List<String> = nodes(view).flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
    protected fun click(view: ComposeView, label: String) {
        val node = nodes(view).firstOrNull { node -> node.config.getOrNull(SemanticsActions.OnClick) != null && (node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label || it.text.startsWith(label + "\n") } || node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(label)) }
        val textNode = nodes(view).firstOrNull { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label } }
        val actionNode = node ?: generateSequence(textNode) { it.parent }.firstOrNull { it.config.getOrNull(SemanticsActions.OnClick) != null }
        assertNotNull("クリック対象: $label / ${texts(view)}", actionNode)
        assertTrue(actionNode!!.config[SemanticsActions.OnClick].action!!.invoke())
        mainDispatcher.scheduler.runCurrent()
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
    }
}

