package net.yexe.anicome

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class StreamingService(val label: String, val packageName: String, val domain: String, val fallbackMark: String) {
    DMM_TV("DMM TV", "com.dmm.premium", "dmm.com", "DMM"),
    NETFLIX("Netflix", "com.netflix.mediaclient", "netflix.com", "N"),
    PRIME_VIDEO("Prime Video", "com.amazon.avod.thirdpartyclient", "primevideo.com", "prime"),
    U_NEXT("U-NEXT", "jp.unext.mediaplayer", "video.unext.jp", "U"),
    HULU("Hulu", "jp.happyon.android", "hulu.jp", "hulu"),
    ABEMA("ABEMA", "tv.abema", "abema.tv", "ABEMA"),
    D_ANIME("dアニメ", "com.nttdocomo.android.danimeapp", "animestore.docomo.ne.jp", "d"),
    TVER("TVer", "jp.hamitv.hamiand1", "tver.jp", "TVer"),
    NHK_PLUS("NHK ONE", "nhk.app.tep", "plus.nhk.jp", "NHK+"),
    FOD("FOD", "air.jp.co.fujitv.fodviewer", "fod.fujitv.co.jp", "FOD"),
    BANDAI_CHANNEL("バンダイチャンネル", "com.bch.sp.bchmembershipapp", "bandai-channel.net", "B");
    companion object { val displayOrder = listOf(NETFLIX, PRIME_VIDEO, U_NEXT, HULU, ABEMA, DMM_TV, D_ANIME, TVER, NHK_PLUS, FOD, BANDAI_CHANNEL) }
}

const val DMM_PACKAGE = "com.dmm.premium"

data class Playback(
    val connected: Boolean = false,
    val title: String = "",
    val subtitle: String = "",
     
    val searchTitle: String = "",
    val mediaId: String = "",
    val position: Long = -1,
    val updatedAt: Long = 0,
    val speed: Float = 0f,
    val playing: Boolean = false,
    val duration: Long = 0,
     
    val advertising: Boolean = false
) {
    val key: String get() = listOf(mediaId, title, subtitle).joinToString("\u001f")
    fun positionAt(now: Long): Long? {
        if (position < 0) return null
        val value = position + if (playing && updatedAt > 0) ((now - updatedAt).coerceAtLeast(0) * speed).toLong() else 0
        return value.coerceAtLeast(0).let { if (duration > 0) it.coerceAtMost(duration) else it }
    }
}

object Session {
    val appVisible = MutableStateFlow(false)
    val previewVisible = MutableStateFlow(false)
    val customPosition = MutableStateFlow(1f)
    val overlayPosition = MutableStateFlow("center")
    val playback = MutableStateFlow(Playback())
    val overlay = MutableStateFlow(false)
    val overlayRequested = MutableStateFlow(true)
    val message = MutableStateFlow("")
    val comments = MutableStateFlow<List<Comment>>(emptyList())
    val commentsFromArchive = MutableStateFlow(false)
    val commentSyncReports = MutableStateFlow<List<Pair<PlaybackSource, CommentSync>>>(emptyList())
    val commentSlots = MutableStateFlow<List<PlaybackCommentSlot>>(emptyList())
    val presented = MutableStateFlow<List<Comment>>(emptyList())
    val hiddenSources = MutableStateFlow<Set<PlaybackSource>>(emptySet())
    val sourceOffsets = MutableStateFlow<Map<PlaybackSource, Long>>(emptyMap())
    val effectiveNgWords = MutableStateFlow("")
    val hiddenUntil = MutableStateFlow(0L)
    val hiddenEpisode = MutableStateFlow("")
    val renderMode = MutableStateFlow("balanced")
    val offset = MutableStateFlow(0L)
    val size = MutableStateFlow(14f)
    val opacity = MutableStateFlow(.75f)
    val speedScale = MutableStateFlow(.7f)
    val commentSources = MutableStateFlow<List<PlaybackSource>>(emptyList())
    val displayDensity = MutableStateFlow(1f)
    val fps = MutableStateFlow(60)
    val colorStyle = MutableStateFlow("original")
    val fontStyle = MutableStateFlow("default")
    val ngWords = MutableStateFlow("")
    val commentAmount = MutableStateFlow(5)
    val testUntil = MutableStateFlow(0L)
     
    val manualPlayback = MutableStateFlow<Playback?>(null)
    val commentBinding = MutableStateFlow(CommentBinding.AUTO)
    val manualCommentKey = MutableStateFlow("")
     
    val adPause = MutableStateFlow(false)
    var matchedTitle = ""
    var matchedKey = ""
}

enum class CommentBinding { AUTO, MANUAL }

open class PlaybackListener : NotificationListenerService() {
    private var serviceJob: Job? = null
    private var controller: MediaController? = null
    private val handler = Handler(Looper.getMainLooper())
    private val manager by lazy { getSystemService(MediaSessionManager::class.java) }
    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onSessionDestroyed() { bind(emptyList()) }
    }
    private val listener = MediaSessionManager.OnActiveSessionsChangedListener { bind(it.orEmpty()) }
    override fun onListenerConnected() {
        Diagnostics.receiver.value = "通知リスナー接続済み"
        val component = ComponentName(this, javaClass)
        try {
            manager.addOnActiveSessionsChangedListener(listener, component, handler)
            bind(manager.getActiveSessions(component))
            serviceJob?.cancel()
            serviceJob = aniComeApp.scope.launch {
                aniComeApp.activeService.collect {
                    try { bind(manager.getActiveSessions(component)) } catch (_: SecurityException) { clearPlayback() }
                }
            }
        } catch (_: SecurityException) { Session.message.value = "通知へのアクセスを許可してください" }
    }
    private fun bind(controllers: List<MediaController>) {
        val service = aniComeApp.activeService.value
        Diagnostics.receiver.value = "通知接続済み / ${service.label}セッション ${controllers.count { it.packageName == service.packageName }}件"
        controller?.unregisterCallback(callback)
        controller = controllers.filter { it.packageName == service.packageName }
            .sortedByDescending { it.playbackState?.state == PlaybackState.STATE_PLAYING }.firstOrNull()
        controller?.registerCallback(callback, handler)
        publish()
    }
    private fun publish() {
        try {
            val c = controller
            val m = c?.metadata
            val p = c?.playbackState
            val fields = listOf(
                "TITLE" to m?.getString(MediaMetadata.METADATA_KEY_TITLE),
                "DISPLAY_TITLE" to m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                "DISPLAY_SUBTITLE" to m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
                "ARTIST" to m?.getString(MediaMetadata.METADATA_KEY_ARTIST),
                "ALBUM" to m?.getString(MediaMetadata.METADATA_KEY_ALBUM),
                "DESCRIPTION" to m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION)
            )
            val searchTitle = fields.firstOrNull { (label, value) ->
                label == "TITLE" || label == "DISPLAY_TITLE"
            }?.second?.trim().orEmpty()
            val (title, subtitle) = PlaybackMetadata.resolve(fields)
            Diagnostics.metadata.value = (fields.filter { !it.second.isNullOrBlank() }.joinToString("\n") { "${it.first}: ${it.second}" }.ifBlank { "${aniComeApp.activeService.value.label}から作品名が公開されていません" } + "\n再生状態=${p?.state ?: "なし"} / 位置=${p?.position ?: -1}ms / 長さ=${m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0}ms").take(1200)
            Session.playback.value = Playback(
                connected = c != null,
                title = title,
                subtitle = subtitle,
                searchTitle = searchTitle,
                mediaId = m?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty(),
                position = p?.position ?: -1,
                updatedAt = p?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime(),
                speed = p?.playbackSpeed ?: 0f,
                playing = p?.state == PlaybackState.STATE_PLAYING,
                duration = m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0,
                advertising = PlaybackMetadata.isAdvertisement(fields)
            )
        } catch (e: Exception) {
            Diagnostics.metadata.value = "メタデータ解析エラー: ${e.message?.take(200)}"
        }
    }
    override fun onListenerDisconnected() { clear() }
    override fun onDestroy() { clear(); super.onDestroy() }
    private fun clear() {
        Diagnostics.receiver.value = "通知リスナー未接続（権限を確認してください）"
        serviceJob?.cancel()
        serviceJob = null
        manager.removeOnActiveSessionsChangedListener(listener)
        controller?.unregisterCallback(callback)
        controller = null
        Session.playback.value = Playback()
    }
    private fun clearPlayback() { controller = null; Session.playback.value = Playback() }
}

internal fun prepareAutomaticComments(playback: Playback, enabled: Boolean, allowedSources: Set<PlaybackSource>?) {
    if (enabled && playback.connected && Session.matchedKey == playback.key &&
        (allowedSources == null || Session.commentSources.value.all { it in allowedSources })) return
    Session.commentSyncReports.value = emptyList()
    Session.commentsFromArchive.value = false
    Session.comments.value = emptyList()
    Session.commentSources.value = emptyList()
    Session.commentSlots.value = emptyList()
    Session.hiddenSources.value = emptySet()
    Session.sourceOffsets.value = emptyMap()
    Session.matchedKey = ""
}
