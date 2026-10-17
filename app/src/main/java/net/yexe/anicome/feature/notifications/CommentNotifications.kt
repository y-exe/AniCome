package net.yexe.anicome

import android.app.*
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class CommentNotificationState(val busy: Boolean, val progress: FetchProgress, val title: String, val playing: Boolean, val count: Int, val overlay: Boolean, val advertising: Boolean, val played: Int = 0)
data class NotificationContent(val title: String, val text: String, val progress: Int = 0, val maximum: Int = 0, val indeterminate: Boolean = false)
fun commentNotificationContent(state: CommentNotificationState, options: AdvancedSettings): NotificationContent {
    if (!options.loaded) return NotificationContent("AniCome", "設定を読み込んでいます")
    val details = buildList {
        if (!options.privateNotifications && state.title.isNotBlank()) add(state.title)
        if (options.notificationCount && state.count > 0) add("${state.played.coerceIn(0, state.count)} / ${state.count}件")
    }.joinToString(" · ")
    return if (state.busy && options.notificationProgress) NotificationContent(state.progress.label,
        if (options.notificationCount && state.progress.target > 0) "${state.progress.current} / ${state.progress.target}件取得中" else "コメントを準備しています",
        state.progress.current, state.progress.target, state.progress.target <= 0)
    else NotificationContent(if (!options.notificationPlayback) "コメント表示中" else if (state.advertising) "広告中・コメント停止" else if (state.playing && state.count > 0) "コメント再生中" else "コメント待機中", details.ifBlank { "動画の再生に合わせて表示します" },
        if (options.notificationProgress) state.played.coerceIn(0, state.count.coerceAtLeast(0)) else 0, if (options.notificationProgress) state.count.coerceAtLeast(0) else 0)
}
class CommentNotifications(private val context: Context, private val app: AppCoordinator, private val preferences: AdvancedSettingsRepository, private val scope: CoroutineScope) {
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private var started = false
    private fun current() = with(Session.manualPlayback.value ?: Session.playback.value) {
        CommentNotificationState(app.busy.value, app.fetchProgress.value, title, playing, Session.presented.value.size, Session.overlay.value, advertising || Session.adPause.value, playedCommentCount(Session.presented.value, positionAt(android.os.SystemClock.elapsedRealtime())?.plus(Session.offset.value) ?: -1L))
    }
    fun overlay() = build(commentNotificationContent(current(), preferences.state.value), "overlay", ongoing = true, stop = true)
    fun start() {
        if (started) return; started = true
        scope.launch {
            app.completedFetches.collect { completed ->
                val options = preferences.state.value
                if (options.notificationCompletion) notify(301, build(NotificationContent("コメントを取得しました",
                    if (options.privateNotifications) "保存済み・再生に使用できます" else if (options.notificationCount) "${completed.count}件 · ${completed.title}" else completed.title), "downloads"))
            }
        }
        scope.launch {
            val playback = combine(Session.playback, Session.manualPlayback, Session.presented, Session.overlay, Session.adPause) { detected, manual, comments, overlay, ad ->
                val p = manual ?: detected; CommentNotificationState(false, FetchProgress(), p.title, p.playing, comments.size, overlay, p.advertising || ad)
            }
            val clock = flow { while (currentCoroutineContext().isActive) { emit(android.os.SystemClock.elapsedRealtime()); delay(1000) } }
            val progressing = playback.combine(clock) { state, now ->
                val p = Session.manualPlayback.value ?: Session.playback.value
                state.copy(played = playedCommentCount(Session.presented.value, p.positionAt(now)?.plus(Session.offset.value) ?: -1L))
            }
            val hidden = combine(Session.hiddenUntil, Session.hiddenEpisode, Session.playback, Session.manualPlayback) { until, episode, detected, manual -> until > android.os.SystemClock.elapsedRealtime() || episode == (manual ?: detected).key }
            val visible = progressing.combine(hidden) { p, paused -> p.copy(playing = p.playing && !paused) }
            val state = combine(visible, app.busy, app.fetchProgress, preferences.state) { p, busy, progress, options -> p.copy(busy = busy, progress = progress) to options }
            var wasOverlay = false
            state.combine(app.convenience?.options ?: flowOf(ConvenienceOptions())) { (s, o), convenience -> Triple(s, o, convenience.notificationActions) }.distinctUntilChanged().conflate().collect { (status, options, _) ->
                delay(200)
                if (status.overlay && Session.overlay.value) notify(1, build(commentNotificationContent(status, options), "overlay", true, true))
                if (wasOverlay && !Session.overlay.value) manager.cancel(1)
                if (status.busy && options.notificationProgress && !status.overlay) notify(300, build(commentNotificationContent(status, options), "downloads", true)) else manager.cancel(300)
                wasOverlay = status.overlay
            }
        }
    }
    fun download(id: Int, label: String, current: Int, target: Int) {
        val options = preferences.state.value
        if (!options.notificationProgress) return
        notify(id, build(NotificationContent("コメントを取得中", (if (options.privateNotifications) "保存予約を処理しています" else label) + if (options.notificationCount && target > 0) " · $current / $target 件" else "",
            current, target, target <= 0), "downloads", true))
    }
    fun downloaded(id: Int, label: String, count: Int) {
        manager.cancel(id)
        if (preferences.state.value.notificationCompletion) notify(id, build(NotificationContent("コメントを保存しました", if (preferences.state.value.privateNotifications) "保存一覧から使用できます" else if (preferences.state.value.notificationCount) "$label · ${count}件" else label), "downloads"))
    }
    fun cancel(id: Int) = manager.cancel(id)
    private fun notify(id: Int, notification: Notification) { if (manager.areNotificationsEnabled()) runCatching { manager.notify(id, notification) } }
    private fun build(content: NotificationContent, channel: String, ongoing: Boolean = false, stop: Boolean = false): Notification {
        manager.createNotificationChannel(NotificationChannel(channel, context.uiString(if (channel == "overlay") "コメント表示" else "コメントの取得と保存"), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_notification).setContentTitle(context.uiString(content.title)).setContentText(context.uiString(content.text))
            .setStyle(Notification.BigTextStyle().bigText(context.uiString(content.text))).setContentIntent(open).setOngoing(ongoing).setOnlyAlertOnce(true).setShowWhen(!ongoing)
            .setVisibility(if (preferences.state.value.privateNotifications) Notification.VISIBILITY_SECRET else Notification.VISIBILITY_PRIVATE)
        if (content.maximum > 0 || content.indeterminate) builder.setProgress(content.maximum.coerceAtLeast(1), content.progress.coerceIn(0, content.maximum.coerceAtLeast(1)), content.indeterminate)
        if (stop) {
            val actions = app.convenience?.options?.value?.notificationActions ?: setOf("stop")
            listOf("stop" to "停止", "earlier" to "1秒早める", "later" to "1秒遅らせる").filter { it.first in actions }.take(3).forEachIndexed { index, (action, label) ->
                val intent = PendingIntent.getService(context, 10 + index, Intent(context, net.yexe.anicome.OverlayService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                builder.addAction(Notification.Action.Builder(null, context.uiString(label), intent).build())
            }
        }
        return builder.build()
    }
}
