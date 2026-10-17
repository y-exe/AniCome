package net.yexe.anicome

import android.app.Activity
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.viewmodel.ext.android.viewModel

open class NiconicoLoginActivity : androidx.fragment.app.FragmentActivity() {
    protected open val fullscreenLogin = false
    private val lockModel: AppLockViewModel by viewModel()
    private val loginModel: NiconicoLoginViewModel by viewModel()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        if (fullscreenLogin) WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        setContent {
            val theme by aniComeApp.themeMode.collectAsStateWithLifecycle()
            AniComeTheme(theme) { AppAccessGate(lockModel) {
                val linked by loginModel.linked.collectAsStateWithLifecycle()
                val complete = { setResult(Activity.RESULT_OK); finish() }
                if (fullscreenLogin) NiconicoFullscreenLoginScreen(linked, { finish() }, complete, loginModel::retry) { modifier ->
                    NiconicoLoginWebContent(modifier, loginModel, showRetryButton = false)
                } else Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        NiconicoLoginHeader { finish() }
                        NiconicoLoginWebContent(Modifier.weight(1f).fillMaxWidth(), loginModel)
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            StudioButton(onClick = complete, modifier = Modifier.weight(1f), enabled = linked) { AppText("ログイン完了") }
                            StudioIconButton(loginModel::retry) { AppIcon(Icons.Filled.Refresh, "ログイン画面を開き直す") }
                        }
                    }
                }
            } }
        }
    }
}

class NiconicoFullscreenLoginActivity : NiconicoLoginActivity() {
    override val fullscreenLogin = true
}

@Composable internal fun NiconicoFullscreenLoginScreen(linked: Boolean, back: () -> Unit, complete: () -> Unit,
    reload: () -> Unit, page: @Composable (Modifier) -> Unit) {
    Box(Modifier.fillMaxSize().imePadding()) {
        page(Modifier.fillMaxSize())
        Surface(Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(horizontal = 8.dp, vertical = 8.dp), shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.onSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shadowElevation = 6.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                StudioIconButton(back) { AppIcon(Icons.AutoMirrored.Filled.ArrowBack, "戻る", tint = MaterialTheme.colorScheme.onSurface) }
                Button(complete, enabled = linked) { AppText("ログイン完了") }
                StudioIconButton(reload) { AppIcon(Icons.Filled.Refresh, "再読み込み", tint = MaterialTheme.colorScheme.onSurface) }
            }
        }
    }
}

@Composable internal fun NiconicoLoginHeader(back: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 20.dp), verticalAlignment = Alignment.Top) {
        StudioIconButton(back) { AppIcon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") }
        Column(Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AppText("ニコニコ連携", style = MaterialTheme.typography.titleLarge)
            AppText("ニコニコ公式ページでログイン", style = MaterialTheme.typography.bodySmall)
        }
    }
}
