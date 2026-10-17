package net.yexe.anicome

import androidx.compose.material.icons.filled.Tv
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal const val NiconicoLoginUrl = "https://account.nicovideo.jp/login"
internal val LocalNiconicoLoginPreview = staticCompositionLocalOf { false }

class NiconicoLoginRepository(private val app: AppCoordinator) {
    val linked = app.niconicoLinked.asStateFlow()
    fun refresh() {
        if (NiconicoLogin.sessionCookie() != null) NiconicoLogin.markLinked()
        app.refreshNiconicoLogin()
    }
}
class NiconicoLoginViewModel(private val repository: NiconicoLoginRepository) : ViewModel() {
    val linked = repository.linked
    private val mutableError = MutableStateFlow(false)
    val error = mutableError.asStateFlow()
    private val mutableReload = MutableStateFlow(0)
    val reload = mutableReload.asStateFlow()
    fun pageStarted() { mutableError.value = false }
    fun pageFinished() = repository.refresh()
    fun pageFailed() { mutableError.value = true }
    fun retry() { mutableError.value = false; mutableReload.value++ }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable internal fun NiconicoLoginWebContent(modifier: Modifier = Modifier, provided: NiconicoLoginViewModel? = null, showRetryButton: Boolean = true) {
    if (LocalInspectionMode.current || LocalNiconicoLoginPreview.current) {
        Surface(modifier, color = Color.White) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(androidx.compose.material.icons.Icons.Default.Tv, null, Modifier.size(48.dp), tint = Color.Black)
                Spacer(Modifier.height(16.dp))
                AppText("ニコニコ公式ログイン画面", color = Color.Black, style = MaterialTheme.typography.titleMedium)
                AppText("実行時に表示", Modifier.padding(top = 8.dp), color = Color(0xFF666666), style = MaterialTheme.typography.bodyMedium)
            }
        }
        return
    }
    val model = provided ?: org.koin.androidx.compose.koinViewModel<NiconicoLoginViewModel>()
    val reload by model.reload.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val protect = LocalConfidentialInput.current
    DisposableEffect(protect) { protect(true); onDispose { protect(false) } }
    var browser by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(Unit) { onDispose { browser?.stopLoading(); browser?.destroy(); browser = null } }
    Box(modifier) {
        AndroidView(factory = { context -> WebView(context).also { view ->
            browser = view
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            view.settings.allowFileAccess = false
            view.settings.allowContentAccess = false
            view.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            view.settings.javaScriptCanOpenWindowsAutomatically = false
            view.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) { model.pageStarted() }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    val host = uri.host?.lowercase().orEmpty()
                    if (uri.scheme == "https" && (host == "nicovideo.jp" || host.endsWith(".nicovideo.jp"))) return false
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    return true
                }
                override fun onPageFinished(view: WebView, url: String) { model.pageFinished() }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) { if (request.isForMainFrame) model.pageFailed() }
            }
        } }, update = { view -> if (view.tag != reload) { view.tag = reload; view.loadUrl(NiconicoLoginUrl) } }, modifier = Modifier.fillMaxSize())
        if (error) Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                AppText("ログイン画面を読み込めませんでした", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                if (showRetryButton) TextButton(model::retry) { AppText("再読み込み") }
            }
        }
    }
}
