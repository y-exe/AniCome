package net.yexe.anicome

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

@SuppressLint("SetJavaScriptEnabled")
class CommentWebView(context: Context, private val preview: Boolean = false, private val externalPreview: (() -> PreviewRenderFrame)? = null,
    private val viewport: PlayerViewportRepository? = null) : WebView(context) {
    private val renderScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loadJob: Job? = null
    private var serializedComments: List<Comment>? = null
    private var serializedFilter = ""
    private var serializedPayload = listOf<String>()
    private var loadRevision = 0L
    private val main = Handler(Looper.getMainLooper())
    private var requestedFps = 0
    private var ready = false
    private var lastComments: List<Comment>? = null
    private var lastRenderKey = ""
    private var lastPlaybackKey = ""
    private var lastViewport = ""
    private val screenLocation = IntArray(2)
    private val previewStart = SystemClock.elapsedRealtime()
    private val sample = (0..9).map { Comment(it * 1000L, "表示テスト • コメントが流れています！") } + Comment(2000, "  /\\_/\\\n (o.o)\n /o  o\\", """{"commands":["ue","gothic","full","@6"],"postedAt":"2017-01-01T00:00:00Z"}""")
    private val tick = object : Runnable {
        override fun run() {
            if (!preview && isAttachedToWindow && requestedFps != Session.fps.value) {
                (layoutParams as? android.view.WindowManager.LayoutParams)?.let { params ->
                    params.preferredRefreshRate = Session.fps.value.toFloat()
                    runCatching { (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).updateViewLayout(this@CommentWebView, params) }
                    requestedFps = Session.fps.value
                }
            }
            if (ready) {
                if (viewport != null && !preview && externalPreview == null) {
                    val cutout = if (android.os.Build.VERSION.SDK_INT >= 28) rootWindowInsets?.displayCutout else null
                    val bounds = viewport.resolve(context.aniComeApp.activeService.value, width, height, cutout?.safeInsetLeft ?: 0, cutout?.safeInsetRight ?: 0)
                    val coordinates = screenLocation.also(::getLocationOnScreen)
                    val key = "$bounds:$width:$height:${coordinates[0]}:${coordinates[1]}"
                    if (key != lastViewport) {
                        lastViewport = key
                        val value = bounds?.let { JSONObject().put("left", (it.left - coordinates[0]) / width)
                            .put("top", (it.top - coordinates[1]) / height).put("width", it.width / width).put("height", it.height / height).toString() } ?: "null"
                        evaluateJavascript("window.commentOverlay.setViewport($value);", null)
                    }
                }
                val now = SystemClock.elapsedRealtime()
                val external = externalPreview?.invoke()
                val testing = preview || external != null || Session.testUntil.value > now
                val manualPlayback = Session.manualPlayback.value
                val playback = manualPlayback ?: Session.playback.value
                val sourceChanged = external == null && lastPlaybackKey.isNotEmpty() && lastPlaybackKey != playback.key
                lastPlaybackKey = playback.key
                val matching = when (Session.commentBinding.value) {
                    CommentBinding.AUTO -> playback.key.isNotBlank() && playback.key == Session.matchedKey
                    CommentBinding.MANUAL -> playback.key.isNotBlank() && playback.key == Session.manualCommentKey.value
                }
                val data = external?.comments ?: if (testing) sample else if (matching) Session.presented.value else emptyList()
                val position = external?.position ?: if (testing) if (preview) (now - previewStart) % 10_000 else now - (Session.testUntil.value - 10_000) else playback.positionAt(now)
                val advertisement = !testing && (playback.advertising || Session.adPause.value)
                val ownApp = Session.previewVisible.value
                val enabled = if (external != null) external.playing else testing || (playback.connected && playback.playing && position != null && matching && data.isNotEmpty() && !advertisement && !ownApp && Session.hiddenUntil.value <= now && Session.hiddenEpisode.value != playback.key)
                val display = external?.settings
                val profile = (display?.profile ?: CommentDisplayProfile(size = Session.size.value, opacity = Session.opacity.value,
                    speed = Session.speedScale.value, fps = Session.fps.value, color = Session.colorStyle.value, font = Session.fontStyle.value,
                    position = Session.overlayPosition.value, customY = Session.customPosition.value, renderMode = Session.renderMode.value))
                    .let { if (external != null) it.copy(position = "center") else it }
                val ngWords = display?.ngWords ?: Session.effectiveNgWords.value
                val density = if (display != null) 1f else Session.displayDensity.value
                val renderKey = listOf(profile.size, profile.opacity, profile.speed, density, profile.fps, profile.color, profile.font, ngWords, Session.commentBinding.value, profile.position, profile.customY, profile.renderMode).joinToString("|")
                if (sourceChanged || lastComments !== data || lastRenderKey != renderKey) {
                    lastComments = data
                    lastRenderKey = renderKey
                    val ng = ngWords.lines().map { it.trim() }.filter { it.isNotBlank() }
                    val filterKey = ngWords
                    val options = JSONObject().put("nativeV1", external != null).put("size", profile.size).put("opacity", profile.opacity).put("speed", profile.speed).put("position", profile.position).put("customY", profile.customY)
                        .put("renderMode", profile.renderMode).put("epoch", if (external != null) "video-preview" else if (testing) "test:${Session.testUntil.value}" else "${playback.key}|${Session.commentBinding.value}").put("density", density).put("fps", profile.fps).put("colorStyle", profile.color).put("fontStyle", profile.font).toString()
                    val revision = ++loadRevision
                    loadJob?.cancel()
                    loadJob = renderScope.launch {
                        try {
                            val payload = if (serializedComments === data && serializedFilter == filterKey) serializedPayload else withContext(Dispatchers.Default) {
                                val chunks = mutableListOf<String>()
                                var items = JSONArray()
                                var length = 0
                                data.forEachIndexed { index, comment ->
                                    if (index % 128 == 0) ensureActive()
                                    if (renderableComment(comment, ng)) {
                                        val item = JSONObject().put("at", comment.at).put("body", comment.body).put("raw", comment.raw).put("fork", comment.fork)
                                        val size = item.toString().length
                                        if (items.length() > 0 && (items.length() >= 128 || length + size > 65536)) { chunks += items.toString(); items = JSONArray(); length = 0 }
                                        items.put(item); length += size
                                    }
                                }
                                if (items.length() > 0) chunks += items.toString()
                                chunks
                            }
                            ensureActive()
                            serializedComments = data; serializedFilter = filterKey; serializedPayload = payload
                            evaluateJavascript("window.commentOverlay.beginUpdate($revision, $options);", null)
                            for (chunk in payload) {
                                ensureActive()
                                evaluateJavascript("window.commentOverlay.pushUpdate($revision, $chunk);", null)
                                delay(8)
                            }
                            ensureActive()
                            evaluateJavascript("window.commentOverlay.commitUpdate($revision);", null)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { lastRenderKey = ""; Diagnostics.renderer.value = "コメントの描画準備に失敗しました" }
                    }
                }
                val adjusted = (position ?: 0) + if (testing) 0 else Session.offset.value
                val speed = if (external != null) if (external.playing) external.speed else 0f else if (testing) 1f else if (playback.playing) playback.speed else 0f
                val canDisplay = enabled
                evaluateJavascript("window.commentOverlay.sync($adjusted,$speed,$canDisplay);", null)
                if (!enabled) {
                    Diagnostics.renderedAt.value = null; Diagnostics.renderedObservedAt.value = null
                    Diagnostics.renderer.value = when {
                        ownApp && !testing -> "描画待機: アプリ操作中"
                        advertisement -> "描画待機: 広告中"
                        !playback.connected -> "描画待機: DMM TVセッションなし"
                        !playback.playing -> "描画待機: 再生停止中"
                        position == null -> "描画待機: 再生位置が未取得"
                        data.isEmpty() -> "描画待機: コメント0件"
                        else -> "描画待機: 作品情報の一致待ち"
                    }
                }
            }
            main.postDelayed(this, 250)
        }
    }
    init {
        setBackgroundColor(Color.TRANSPARENT)
        settings.javaScriptEnabled = true
         
        settings.allowFileAccess = true
        settings.allowContentAccess = false
        settings.blockNetworkLoads = true
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                return if (request?.url?.toString()?.startsWith("file:///android_asset/") == true) null
                else WebResourceResponse("text/plain", "UTF-8", "".byteInputStream())
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                Diagnostics.renderer.value = "描画ページの読み込み失敗: ${error?.description}"
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    main.post { Diagnostics.renderer.value = "描画コンソールエラー: ${message.message().take(300)}"; Diagnostics.trace(Diagnostics.renderer.value) }
                }
                return true
            }
        }
        addJavascriptInterface(object {
            @JavascriptInterface fun ready(unused: String) { main.post { ready = true; Diagnostics.renderer.value = "描画ライブラリ準備完了" } }
            @JavascriptInterface fun loaded(count: Int) { main.post { Diagnostics.renderer.value = "描画データ設定済み: ${count}件" } }
            @JavascriptInterface fun error(message: String) { main.post { lastRenderKey = ""; Diagnostics.renderer.value = "描画エラー: ${message.take(200)}"; Diagnostics.trace(Diagnostics.renderer.value) } }
            @JavascriptInterface fun frame(position: Long) { main.post { Diagnostics.renderedAt.value = position; Diagnostics.renderedObservedAt.value = android.os.SystemClock.elapsedRealtime(); Diagnostics.renderer.value = "描画処理実行中（画面に見えるかは表示テストで確認）" } }
            @JavascriptInterface fun viewport(message: String) { main.post { Diagnostics.viewport.value = message } }
        }, "AndroidRenderer")
        loadUrl("file:///android_asset/overlay.html")
        main.post(tick)
    }
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean = if (externalPreview != null) false else super.onTouchEvent(event)
    fun release() { renderScope.cancel(); serializedComments = null; serializedPayload = emptyList(); main.removeCallbacksAndMessages(null); removeJavascriptInterface("AndroidRenderer"); stopLoading(); destroy() }
}
