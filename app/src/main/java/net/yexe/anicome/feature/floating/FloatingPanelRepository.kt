package net.yexe.anicome

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class FloatingPanelOptions(val startCollapsed: Boolean = true, val showTitle: Boolean = true, val showCount: Boolean = true,
    val showTiming: Boolean = true, val opacity: Float = .95f, val x: Float = 1f, val y: Float = .3f, val landscapeX: Float = 1f, val landscapeY: Float = .3f, val landscapeCollapsed: Boolean = true, val compact: Boolean = false, val actions: List<PanelAction> = DefaultPanelActions, val snapToEdge: Boolean = true, val positionRevision: String = "", val landscapePositionRevision: String = "")
data class FloatingPanelState(val busy: Boolean = false, val label: String = "コメント待機中", val title: String = "", val current: Int = 0,
    val total: Int = 0, val fetching: Boolean = false, val overlay: Boolean = false, val offset: Long = 0, val locked: Boolean = false, val position: String = "center", val hasPlaybackComments: Boolean = false, val fromArchive: Boolean = false, val syncReports: List<Pair<PlaybackSource, CommentSync>> = emptyList(), val temporaryHidden: Boolean = false, val undoOffsetId: Long? = null, val customY: Float = 1f)

fun playedCommentCount(comments: List<Comment>, position: Long): Int {
    var low = 0; var high = comments.size
    while (low < high) { val mid = (low + high) ushr 1; if (comments[mid].at <= position) low = mid + 1 else high = mid }
    return low
}
fun panelOptions(p: SettingsSnapshot) = FloatingPanelOptions(p.getBoolean("panelCollapsed", true), p.getBoolean("panelTitle", true),
    p.getBoolean("panelCount", true), p.getBoolean("panelTiming", true), p.getFloat("panelOpacity", .95f).coerceIn(.6f, 1f),
    p.getFloat("panelX", 1f).coerceIn(0f, 1f), p.getFloat("panelY", .3f).coerceIn(0f, 1f), p.getFloat("panelLandscapeX", 1f).coerceIn(0f, 1f), p.getFloat("panelLandscapeY", .3f).coerceIn(0f, 1f), p.getBoolean("panelLandscapeCollapsed", true), p.getBoolean("panelCompact", false), panelActions(p.getString("panelActions", null)), p.getBoolean("panelSnap", true), p.getString("panelPositionRevision", "").orEmpty(), p.getString("panelLandscapePositionRevision", "").orEmpty())

class FloatingPanelRepository(private val context: Context, private val settings: SettingsRepository, private val app: AppCoordinator,
    private val security: AppLockRepository, private val advanced: AdvancedSettingsRepository, private val downloads: DownloadRepository, scope: CoroutineScope, private val visibility: CommentVisibilityRepository = CommentVisibilityRepository(scope), private val corrections: SyncAdjustmentRepository = SyncAdjustmentRepository(scope)) {
    private val placementRequests = MutableSharedFlow<PanelPlacementRequest>(extraBufferCapacity = 8)
    val placements = placementRequests.asSharedFlow()
    val active = MutableStateFlow(false)
    val options = settings.data.map { panelOptions(SettingsSnapshot(it)) }.stateIn(scope, SharingStarted.Eagerly, FloatingPanelOptions())
    val state = flow {
        var previous: List<Comment>? = null
        var previousFilter = ""
        var displayed: List<Comment> = emptyList()
        while (currentCoroutineContext().isActive) {
            val playback = Session.manualPlayback.value ?: Session.playback.value
            val locked = security.state.value.let { !it.loaded || (it.mode != "none" && it.locked) }
            val private = locked || advanced.state.value.privateNotifications
            val progress = app.fetchProgress.value
            val busy = app.busy.value
            val temporarilyHidden = visibility.state.value
            val paused = temporarilyHidden.active || playback.advertising || Session.adPause.value
            val position = playback.positionAt(android.os.SystemClock.elapsedRealtime())?.plus(Session.offset.value)
            val matches = if (Session.commentBinding.value == CommentBinding.MANUAL) Session.manualCommentKey.value == playback.key else Session.matchedKey == playback.key
            val comments = if (matches) Session.presented.value else emptyList()
            val filter = Session.effectiveNgWords.value
            if (previous !== comments || previousFilter != filter) {
                val ng = Session.effectiveNgWords.value.lines().map(String::trim).filter(String::isNotBlank)
                displayed = withContext(Dispatchers.Default) { comments.filterIndexed { index, comment -> if (index % 256 == 0) ensureActive(); renderableComment(comment, ng) } }
                previous = comments; previousFilter = filter
            }
            val hasComments = comments.isNotEmpty()
            val fetching = busy && !hasComments
            emit(FloatingPanelState(fetching, if (temporarilyHidden.active) temporaryHideLabel(temporarilyHidden) else if (fetching) progress.label else if (paused) "コメント停止中" else if (playback.playing && Session.overlay.value) "コメント再生中" else "コメント待機中",
                if (private) "" else playback.title, if (fetching) progress.current else if (position == null) 0 else playedCommentCount(displayed, position),
                if (fetching) progress.target else displayed.size, fetching, Session.overlay.value, Session.offset.value, locked, advanced.state.value.position, hasComments, hasComments && Session.commentsFromArchive.value, if (matches && !private) Session.commentSyncReports.value else emptyList(), temporarilyHidden.active, corrections.undo.value?.takeIf { it.source == null }?.id, advanced.state.value.customY))
            delay(250)
        }
    }.combine(downloads.work) { state, works ->
        val running = works.filter { it.state == androidx.work.WorkInfo.State.RUNNING }
        if (state.busy || state.hasPlaybackComments || running.isEmpty()) state else {
            val total = if (running.all { it.progress.getInt("target", 0) > 0 }) running.sumOf { it.progress.getInt("target", 0).toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0
            val title = if (state.locked || advanced.state.value.privateNotifications) "" else if (running.size == 1) running.first().tags.firstOrNull { it.startsWith("label:") }?.removePrefix("label:").orEmpty() else "保存予約 ${running.size}件"
            state.copy(busy = true, fetching = true, label = "保存予約を処理中", title = title, current = running.sumOf { it.progress.getInt("current", 0).toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), total = total)
        }
    }.distinctUntilChanged().stateIn(scope, SharingStarted.WhileSubscribed(1000), FloatingPanelState())
    fun show(): Boolean {
        if (!Settings.canDrawOverlays(context)) return false
        return runCatching { context.startForegroundService(Intent(context, net.yexe.anicome.FloatingPanelService::class.java)); true }.getOrDefault(false)
    }
    fun close() { context.stopService(Intent(context, net.yexe.anicome.FloatingPanelService::class.java)) }
    fun boolean(key: String, value: Boolean) { require(key in setOf("panelCollapsed", "panelTitle", "panelCount", "panelTiming", "panelCompact", "panelSnap")); settings.putBoolean(key, value) }
    fun action(action: PanelAction, operation: String) = settings.panelAction(action, operation)
    fun opacity(value: Float) = settings.putFloat("panelOpacity", value.coerceIn(.6f, 1f))
    fun position(x: Float, y: Float, landscape: Boolean = false, revision: String = java.util.UUID.randomUUID().toString()) = settings.applyPanelPlacement(if (landscape) PanelPlacementRequest(landscape = PanelPosition(x, y)) else PanelPlacementRequest(portrait = PanelPosition(x, y)), revision)
    fun place(corner: PanelCorner, landscape: Boolean) {
        val request = if (landscape) PanelPlacementRequest(landscape = corner.position) else PanelPlacementRequest(portrait = corner.position)
        settings.applyPanelPlacement(request); placementRequests.tryEmit(request)
    }
    fun resetPosition() { settings.applyPanelPlacement(DefaultPanelPlacement); placementRequests.tryEmit(DefaultPanelPlacement) }
    fun orientationOptions(landscape: Boolean): FloatingPanelOptions = options.value.let { if (landscape) it.copy(x = it.landscapeX, y = it.landscapeY, startCollapsed = it.landscapeCollapsed) else it }
    fun rememberCollapsed(value: Boolean, landscape: Boolean) { settings.putBoolean(if (landscape) "panelLandscapeCollapsed" else "panelCollapsed", value) }
    fun toggleComments() {
        if (security.state.value.let { !it.loaded || (it.mode != "none" && it.locked) }) return
        app.setCommentDisplayEnabled(!Session.overlay.value)
        if (Session.overlay.value) context.stopService(Intent(context, net.yexe.anicome.OverlayService::class.java)) else context.startForegroundService(Intent(context, net.yexe.anicome.OverlayService::class.java))
    }
    fun resumeComments() { if (security.state.value.let { it.loaded && (it.mode == "none" || !it.locked) }) visibility.hide("none") }
    fun commentPosition(value: String) {
        if (security.state.value.let { it.loaded && (it.mode == "none" || !it.locked) }) advanced.position(value)
    }
    fun displayValue(key: String, value: Float) {
        if (security.state.value.let { !it.loaded || (it.mode != "none" && it.locked) }) return
        when (key) {
            "size" -> app.setCommentSize(value)
            "opacity" -> app.setOpacity(value)
            "speed" -> app.setSpeedScale(value)
        }
    }
    fun customPosition(value: Float) { if (security.state.value.let { it.loaded && (it.mode == "none" || !it.locked) }) advanced.customPosition(value) }
    fun offset(delta: Long?) { if (security.state.value.let { it.loaded && (it.mode == "none" || !it.locked) }) corrections.global(delta) }
    fun undoCorrection(id: Long) { if (security.state.value.let { it.loaded && (it.mode == "none" || !it.locked) }) corrections.undo(id) }
    fun openApp() { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
}
class FloatingPanelViewModel(private val repository: FloatingPanelRepository) : ViewModel() {
    val active = repository.active.asStateFlow()
    val options = repository.options
    val state = repository.state
    fun show() = repository.show()
    fun close() = repository.close()
    fun boolean(key: String, value: Boolean) = repository.boolean(key, value)
    fun action(action: PanelAction, operation: String) = repository.action(action, operation)
    fun place(corner: PanelCorner, landscape: Boolean) = repository.place(corner, landscape)
    fun resetPosition() = repository.resetPosition()
    fun resumeComments() = repository.resumeComments()
    fun opacity(value: Float) = repository.opacity(value)
    fun commentPosition(value: String) = repository.commentPosition(value)
    fun customPosition(value: Float) = repository.customPosition(value)
    fun toggleComments() = repository.toggleComments()
    fun displayValue(key: String, value: Float) = repository.displayValue(key, value)
    fun offset(delta: Long?) = repository.offset(delta)
    fun undoCorrection(id: Long) = repository.undoCorrection(id)
    fun openApp() = repository.openApp()
}
