package net.yexe.anicome

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.json.JSONObject
import org.koin.androidx.viewmodel.ext.android.viewModel

class CommentPreviewActivity : ComponentActivity() {
    private val model: CommentPreviewViewModel by viewModel()
    private var player: CommentPreviewPlayer? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars()); systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            AniComeTheme("dark") {
                val selected by model.selected.collectAsStateWithLifecycle()
                val currentComments = rememberUpdatedState(selected)
                val load by model.load.collectAsStateWithLifecycle()
                val playback by model.playback.collectAsStateWithLifecycle()
                val limit by model.limit.collectAsStateWithLifecycle()
                val displaySettings by model.settings.collectAsStateWithLifecycle()
                var settings by remember { mutableStateOf(false) }
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    if (load.available > 0 && displaySettings != null) AndroidView(factory = { context -> CommentPreviewPlayer(context, { currentComments.value }, { model.playback.value = it }, { model.settings.value }).also { player = it } }, modifier = Modifier.fillMaxSize(), onRelease = { it.release(); player = null })
                    Row(Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp)) {
                        FilledTonalIconButton({ requestedOrientation = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE }) { AppIcon(Icons.Filled.ScreenRotation, "画面の向きを切り替え") }
                        FilledTonalIconButton({ settings = true }) { AppIcon(Icons.Filled.Settings, "試し表示の設定") }
                        FilledTonalIconButton({ finish() }) { AppIcon(Icons.Filled.Close, "閉じる") }
                    }
                    if (load.loading) Surface(Modifier.align(Alignment.TopStart).padding(12.dp), shape = MaterialTheme.shapes.large) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            AppText("コメントを準備しています")
                            LinearProgressIndicator(modifier = Modifier.width(220.dp))
                        }
                    }
                    if (load.error.isNotBlank() || playback.error != 0) Surface(Modifier.align(Alignment.Center).widthIn(max = 400.dp).padding(16.dp), shape = MaterialTheme.shapes.large) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppText(if (playback.error != 0) "YouTubeの動画を再生できません（${playback.error}）" else load.error)
                            if (load.error.isNotBlank()) OutlinedButton(model::fetch) { AppText("再試行") }
                            OutlinedButton({ finish() }) { AppText("閉じる") }
                        }
                    }
                    if (settings) StudioDialog({ settings = false }, title = { AppText("試し表示の設定") }, text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            AppText("取得済み ${load.available}件 · 試し表示 ${selected.size}件")
                            PreviewCommentCountPicker(limit, model::count)
                            AppText("取得済みコメントから新しい順に選び、動画の時刻に合わせて表示します。")
                            AppText("表示位置は中央固定。文字サイズ・不透明度・速度・NGワードは現在の設定を使用します。")
                        }
                    }, confirmButton = { TextButton({ settings = false }) { AppText("閉じる") } })
                }
            }
        }
    }
    override fun onStart() { super.onStart(); Session.previewVisible.value = true }
    override fun onStop() { Session.previewVisible.value = false; super.onStop() }
    override fun onPause() { player?.pause(); super.onPause() }
    override fun onResume() { super.onResume(); player?.resume() }
}

@SuppressLint("SetJavaScriptEnabled")
internal class CommentPreviewPlayer(context: android.content.Context, comments: () -> List<Comment>, private val report: (PreviewPlayback) -> Unit, private val displaySettings: () -> PreviewDisplaySettings?) : FrameLayout(context) {
    private val main = Handler(Looper.getMainLooper())
    private var current = PreviewPlayback()
    private var updatedAt = 0L
    private var disposed = false
    private var paused = false
    private val video = WebView(context)
    private val overlay = CommentWebView(context, externalPreview = {
        PreviewRenderFrame(comments(), PreviewCommentStart + current.position + if (current.playing && !paused) ((SystemClock.elapsedRealtime() - updatedAt).coerceAtLeast(0) * current.speed).toLong() else 0, current.playing && !paused, current.speed, displaySettings())
    })
    private val tick = object : Runnable {
        override fun run() {
            if (disposed || paused) return
            video.evaluateJavascript("typeof previewStatus==='function'?previewStatus():null") { value ->
                if (!disposed && !paused) runCatching {
                    val data = JSONObject(JSONObject("{\"value\":$value}").getString("value"))
                    val position = data.optDouble("position", 0.0).takeIf { it.isFinite() && it >= 0 } ?: 0.0
                    val duration = data.optDouble("duration", 90.0).takeIf { it.isFinite() && it > 0 } ?: 90.0
                    current = PreviewPlayback((position * 1000).toLong(), (duration * 1000).toLong(), data.optBoolean("playing"), data.optInt("error"), data.optDouble("speed", 1.0).takeIf { it.isFinite() && it in .25..4.0 }?.toFloat() ?: 1f)
                    updatedAt = SystemClock.elapsedRealtime(); report(current)
                }
            }
            main.postDelayed(this, 100)
        }
    }
    init {
        video.settings.apply { javaScriptEnabled = true; domStorageEnabled = true; mediaPlaybackRequiresUserGesture = true; allowFileAccess = false; allowContentAccess = false }
        video.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = request?.isForMainFrame == true
        }
        addView(video, LayoutParams(-1, -1)); addView(overlay, LayoutParams(-1, -1))
        video.loadDataWithBaseURL("https://yexe.net/", context.assets.open("comment-preview.html").bufferedReader().use { it.readText() }, "text/html", "UTF-8", null)
        main.post(tick)
    }
    fun pause() { paused = true; main.removeCallbacks(tick); video.evaluateJavascript("if(player&&player.pauseVideo)player.pauseVideo()", null); video.onPause(); current = current.copy(playing = false); report(current) }
    fun resume() { if (disposed) return; paused = false; video.onResume(); main.removeCallbacks(tick); main.post(tick) }
    fun release() { disposed = true; main.removeCallbacksAndMessages(null); overlay.release(); video.stopLoading(); video.destroy(); removeAllViews() }
}
