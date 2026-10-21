package net.yexe.anicome

import android.content.ComponentName
import android.app.Notification
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

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
    val requiresAccessibility: Boolean get() = this in accessibilityServices
    companion object {
        val standardServices = listOf(NETFLIX, U_NEXT, ABEMA, DMM_TV, TVER, NHK_PLUS)
        val accessibilityServices = listOf(PRIME_VIDEO, HULU, D_ANIME, BANDAI_CHANNEL)
        val displayOrder = standardServices + accessibilityServices
    }
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
     
    val advertising: Boolean = false,
    val commentDuration: Long? = null
) {
    val commentLookupDuration: Long get() = commentDuration ?: duration
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
    override fun dump(fd: java.io.FileDescriptor, writer: java.io.PrintWriter, args: Array<out String>?) {
        val time = SystemClock.elapsedRealtime()
        writer.println("service=${aniComeApp.activeService.value}")
        writer.println("ready=${aniComeApp.ready.value} setup=${aniComeApp.setupCompleted.value} auto=${aniComeApp.auto.value} busy=${aniComeApp.busy.value} status=${aniComeApp.status.value}")
        writer.println("fetch=${aniComeApp.fetchProgress.value} trace=${Diagnostics.log.value}")
        writer.println("prime=${prime.playback.value}")
        writer.println("danime=${danime.anchor.value}")
        writer.println("hulu=${hulu.playback.value}")
        writer.println("playback=${Session.playback.value}")
        writer.println("positionNow=${Session.playback.value.positionAt(time)} now=$time offset=${Session.offset.value} manual=${Session.manualPlayback.value}")
        writer.println("rendererAt=${Diagnostics.renderedAt.value} renderer=${Diagnostics.renderer.value}")
        writer.println("native=${controller?.playbackState}")
        writer.println("sources=${Session.commentSlots.value.map { listOf(it.id, it.title, it.source, it.syncOffset, it.durationMs, it.comments.firstOrNull()?.at, it.comments.lastOrNull()?.at) }}")
    }

    private var serviceJob: Job? = null
    private var titleJob: Job? = null
    private var primeJob: Job? = null
    private var netflixJob: Job? = null
    private var danimeJob: Job? = null
    private var huluJob: Job? = null
    private val bandaiTitles: BandaiTitleRepository by inject()
    private val prime: PrimePlaybackRepository by inject()
    private val netflix: NetflixEpisodeRepository by inject()
    private val danime: DanimePlaybackRepository by inject()
    private val hulu: HuluPlaybackRepository by inject()
    private var controller: MediaController? = null
    private var primeSessionToken: MediaSession.Token? = null
    private var danimeSessionToken: MediaSession.Token? = null
    private var selectedSessionCount = 0
    private val playbackNotifications = mutableMapOf<String, StatusBarNotification>()
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
            playbackNotifications.clear()
            activeNotifications.orEmpty().forEach(::updateNotification)
            manager.addOnActiveSessionsChangedListener(listener, component, handler)
            bind(manager.getActiveSessions(component))
            titleJob?.cancel()
            titleJob = aniComeApp.scope.launch {
                bandaiTitles.title.collect { if (aniComeApp.activeService.value == StreamingService.BANDAI_CHANNEL) publish() }
            }
            primeJob?.cancel()
            primeJob = aniComeApp.scope.launch {
                prime.playback.collect { if (aniComeApp.activeService.value == StreamingService.PRIME_VIDEO) publish() }
            }
            netflixJob?.cancel()
            netflixJob = aniComeApp.scope.launch {
                netflix.match.collect { if (aniComeApp.activeService.value == StreamingService.NETFLIX) publish() }
            }
            danimeJob?.cancel()
            danimeJob = aniComeApp.scope.launch {
                danime.anchor.collect { if (aniComeApp.activeService.value == StreamingService.D_ANIME) publish() }
            }
            huluJob?.cancel()
            huluJob = aniComeApp.scope.launch {
                hulu.playback.collect { if (aniComeApp.activeService.value == StreamingService.HULU) publish() }
            }
            serviceJob?.cancel()
            serviceJob = aniComeApp.scope.launch {
                aniComeApp.activeService.collect {
                    try { bind(manager.getActiveSessions(component)) } catch (_: SecurityException) { clearPlayback() }
                }
            }
        } catch (_: SecurityException) { Session.message.value = "通知へのアクセスを許可してください" }
    }
    override fun onNotificationPosted(notification: StatusBarNotification) {
        if (notification.packageName != StreamingService.D_ANIME.packageName) return
        updateNotification(notification)
        if (aniComeApp.activeService.value == StreamingService.D_ANIME) publish()
    }
    override fun onNotificationRemoved(notification: StatusBarNotification) {
        if (playbackNotifications.remove(notification.key) != null && aniComeApp.activeService.value == StreamingService.D_ANIME) publish()
    }
    private fun updateNotification(notification: StatusBarNotification) {
        if (notification.packageName != StreamingService.D_ANIME.packageName) return
        if (notification.notification.category == Notification.CATEGORY_TRANSPORT && notification.isOngoing) {
            playbackNotifications[notification.key] = notification
        } else playbackNotifications.remove(notification.key)
    }
    @Suppress("DEPRECATION")
    private fun notificationFields(current: MediaController?): List<Pair<String, String?>> {
        if (current == null || aniComeApp.activeService.value != StreamingService.D_ANIME || current.packageName != StreamingService.D_ANIME.packageName) return emptyList()
        val notification = playbackNotifications.values.filter { entry ->
            val token = entry.notification.extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
            if (token != null) token == current.sessionToken else selectedSessionCount == 1
        }.maxByOrNull { it.postTime }?.notification ?: return emptyList()
        return listOf(
            "NOTIFICATION_TITLE" to notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            "NOTIFICATION_TEXT" to notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            "NOTIFICATION_SUBTEXT" to notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        )
    }
    private fun bind(controllers: List<MediaController>) {
        val service = aniComeApp.activeService.value
        Diagnostics.receiver.value = "通知接続済み / ${service.label}セッション ${controllers.count { it.packageName == service.packageName }}件"
        controller?.unregisterCallback(callback)
        val selected = controllers.filter { it.packageName == service.packageName }
        selectedSessionCount = selected.size
        controller = selected
            .sortedByDescending { when (it.playbackState?.state) { PlaybackState.STATE_PLAYING -> 3; PlaybackState.STATE_PAUSED -> 2; PlaybackState.STATE_BUFFERING -> 1; else -> 0 } }.firstOrNull()
        if (service == StreamingService.PRIME_VIDEO) {
            val token = controller?.sessionToken
            if (primeSessionToken != token) { primeSessionToken = token; prime.resetClock() }
        } else primeSessionToken = null
        if (service == StreamingService.D_ANIME) {
            val token = controller?.sessionToken
            if (danimeSessionToken != token) { danimeSessionToken = token; danime.clear() }
        } else { danimeSessionToken = null; danime.clear() }
        controller?.registerCallback(callback, handler)
        publish()
    }
    private fun publish() {
        try {
            val c = controller
            val m = c?.metadata
            val p = c?.playbackState
            val bandai = aniComeApp.activeService.value == StreamingService.BANDAI_CHANNEL
            val mediaIdentity = if (bandai && c != null && p?.state != PlaybackState.STATE_STOPPED && p?.state != PlaybackState.STATE_NONE)
                bandaiVideoIdentity(m?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID), m?.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)) else ""
            bandaiTitles.mediaChanged(mediaIdentity)
            val sessionFields = listOf(
                "TITLE" to m?.getString(MediaMetadata.METADATA_KEY_TITLE),
                "DISPLAY_TITLE" to m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                "DISPLAY_SUBTITLE" to m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
                "ARTIST" to m?.getString(MediaMetadata.METADATA_KEY_ARTIST),
                "ALBUM" to m?.getString(MediaMetadata.METADATA_KEY_ALBUM),
                "DESCRIPTION" to m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION)
            )
            val bandaiFields = bandaiTitles.title.value?.takeIf { bandai }?.let { listOf("TITLE" to it.series, "DISPLAY_SUBTITLE" to it.episode) }.orEmpty()
            val fields = PlaybackMetadata.withNotification(sessionFields, if (bandai) bandaiFields else notificationFields(c))
            val netflixIdentity = if (aniComeApp.activeService.value == StreamingService.NETFLIX) NetflixEpisodeMetadata.identity(sessionFields) else null
            netflix.select(netflixIdentity)
            val searchTitle = PlaybackMetadata.searchTitle(fields)
            val (title, subtitle) = PlaybackMetadata.resolve(fields)
            Diagnostics.metadata.value = (fields.filter { !it.second.isNullOrBlank() }.joinToString("\n") { "${it.first}: ${it.second}" }.ifBlank { "${aniComeApp.activeService.value.label}から作品名が公開されていません" } + "\n再生状態=${p?.state ?: "なし"} / 位置=${p?.position ?: -1}ms / 長さ=${m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0}ms").take(1200)
            val native = Playback(
                connected = c != null,
                title = title,
                subtitle = subtitle,
                searchTitle = searchTitle,
                mediaId = if (bandai) mediaIdentity else m?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty(),
                position = p?.position ?: -1,
                updatedAt = p?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime(),
                speed = p?.playbackSpeed ?: 0f,
                playing = p?.state == PlaybackState.STATE_PLAYING,
                duration = m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0,
                advertising = PlaybackMetadata.isAdvertisement(fields)
            )
            val screen = prime.playback.value.takeIf { aniComeApp.activeService.value == StreamingService.PRIME_VIDEO }
            val netflixMatch = netflix.match.value?.takeIf { it.identity == netflixIdentity }
            Session.playback.value = when {
                aniComeApp.activeService.value == StreamingService.HULU -> hulu.playback.value ?: Playback()
                aniComeApp.activeService.value == StreamingService.D_ANIME -> danime.resolve(native)
                netflixIdentity != null && netflixMatch != null -> native.copy(
                    title = netflixMatch.episode.series,
                    subtitle = NetflixEpisodeMetadata.subtitle(netflixMatch),
                    searchTitle = netflixMatch.episode.label,
                    mediaId = native.mediaId.ifBlank { "netflix:${netflixMatch.source.id}" },
                    commentDuration = netflixMatch.source.duration * 1000
                )
                netflixIdentity != null -> native.copy(title = "", subtitle = "", searchTitle = netflixIdentity.title)
                screen != null && native.position < 0 -> screen
                else -> native
            }
            hulu.playback.value?.takeIf { aniComeApp.activeService.value == StreamingService.HULU }?.let {
                Diagnostics.metadata.value = "TITLE: ${it.title}\nEPISODE: ${it.subtitle}\nSCREEN / position=${it.position}ms / duration=${it.duration}ms / playing=${it.playing}"
            }
            if (screen != null && native.position < 0) Diagnostics.metadata.value = "TITLE: ${screen.title}\nEPISODE: ${screen.subtitle}\nSCREEN / position=${screen.position}ms / duration=${screen.duration}ms / playing=${screen.playing}"
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
        titleJob?.cancel()
        titleJob = null
        primeJob?.cancel()
        primeJob = null
        netflixJob?.cancel()
        netflixJob = null
        danimeJob?.cancel()
        danimeJob = null
        danime.clear()
        huluJob?.cancel(); huluJob = null; hulu.clear()
        netflix.select(null)
        bandaiTitles.mediaChanged("")
        manager.removeOnActiveSessionsChangedListener(listener)
        controller?.unregisterCallback(callback)
        controller = null
        primeSessionToken = null
        danimeSessionToken = null
        selectedSessionCount = 0
        playbackNotifications.clear()
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
