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
class ReferenceStaticTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(locale = "ja", softButtons = false), theme = "android:Theme.Material.NoActionBar")
    @Before fun before() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun after() { Dispatchers.resetMain() }
    private fun fixture(mode: String = "dark", tab: () -> AppTab = { AppTab.SETTINGS }, content: @Composable () -> Unit): ComposeView {
        val application = PreviewApplication()
        val app = application.runtime
        val context = object : ContextWrapper(paparazzi.context) { override fun getApplicationContext(): Context = application }
        return ComposeView(paparazzi.context).apply {
            setViewCompositionStrategy(object : ViewCompositionStrategy {
                override fun installFor(view: AbstractComposeView): () -> Unit = {}
            })
            setContent {
                CompositionLocalProvider(LocalContext provides context) {
                    AniComeTheme(mode) { Scaffold() { padding -> Box(Modifier.fillMaxSize().padding(padding)) { content() } } }
                }
            }
        }
    }
    private fun nodes(view: ComposeView): Collection<SemanticsNode> {
        val child = view.getChildAt(0)
        val owner = child.javaClass.getMethod("getSemanticsOwner").invoke(child) as SemanticsOwner
        return owner.getAllSemanticsNodes(true)
    }
    private fun texts(view: ComposeView): List<String> = nodes(view).flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
    private fun click(view: ComposeView, label: String) {
        val node = nodes(view).firstOrNull { node -> node.config.getOrNull(SemanticsActions.OnClick) != null && (node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label || it.text.startsWith(label + "\n") } || node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(label)) }
        val textNode = nodes(view).firstOrNull { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label } }
        val actionNode = node ?: generateSequence(textNode) { it.parent }.firstOrNull { it.config.getOrNull(SemanticsActions.OnClick) != null }
        assertNotNull("クリック対象: $label / ${texts(view)}", actionNode)
        assertTrue(actionNode!!.config[SemanticsActions.OnClick].action!!.invoke())
    }
    @Test fun savedCardsGroupByUploaderAndShowTheChannelUrl() {
        val owner = "のんのんびより"
        val url = "https://ch.nicovideo.jp/ch2581583"
        val items = listOf("元の作品表記A", "元の作品表記B").mapIndexed { index, title ->
            SavedEpisode(Episode(title, index + 1), "so${index + 1}", "動画 第${index + 1}話", 0, 100, 1024, ownerName = owner, ownerUrl = url)
        }
        val view = fixture(tab = { AppTab.DOWNLOADS }) { LibraryScreen(AppCoordinator(), items, Playback(), emptyList()) {} }
        try {
            paparazzi.snapshot(view, "uploader_library")
            assertTrue(texts(view).contains(owner))
            assertTrue(texts(view).contains(url))
            assertTrue(texts(view).contains("1作品・2話"))
            assertFalse(texts(view).contains("元の作品表記A"))
        } finally { view.disposeComposition() }
    }
    @Test fun allThemeRolesAreMonochrome() {
        for (mode in listOf("dark", "light")) {
            var scheme: ColorScheme? = null
            val view = fixture(mode) { val current = MaterialTheme.colorScheme; SideEffect { scheme = current }; Text("AniCome") }
            try {
                paparazzi.snapshot(view, "palette_$mode")
                val colors = ColorScheme::class.java.methods.filter { it.name.startsWith("get") && it.parameterTypes.isEmpty() && it.returnType.name == "long" }
                assertTrue(colors.size >= 25)
                colors.forEach { method ->
                    val color = Color((method.invoke(scheme) as Long).toULong())
                    assertEquals("$mode ${method.name}", color.red, color.green, .0001f)
                    assertEquals("$mode ${method.name}", color.green, color.blue, .0001f)
                }
            } finally { view.disposeComposition() }
        }
    }
}
