package net.yexe.anicome

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

fun settingsParent(page: String): String = when (page) {
    "通知", "ダウンロード", "コメント取得", "ネットワーク", "セキュリティ", "アップデート" -> "その他"
    "OSSライセンス" -> "アプリ詳細"
    else -> ""
}
fun otherSettings(linked: Boolean, limit: Long) = listOf(
    SettingsCategory("通知", "進捗・再生状態・保存完了", Icons.Filled.Notifications, "通知"),
    SettingsCategory("ストレージ", if (limit == 0L) "保存容量・キャッシュ" else "${limit} MB・キャッシュ", Icons.Filled.Storage, "ダウンロード"),
    SettingsCategory("ニコニコ連携", if (linked) "連携済み・ユーザー情報" else "ログイン・ユーザー情報", Icons.Filled.AccountCircle, "コメント取得"),
    SettingsCategory("ネットワーク", "プロキシ・通信のタイムアウト", Icons.Filled.Router, "ネットワーク"),
    SettingsCategory("アップデート", "バージョン確認・自動更新", Icons.Filled.SystemUpdate, "アップデート"))
val HomeSettings = listOf(
    SettingsCategory("コメント表示", "文字サイズ・速度・取得量", Icons.Filled.TextFields, "コメント表示"),
    SettingsCategory("取得元", "自動取得・手動取得・コメントの種類", Icons.Filled.Forum, "取得元"),
    SettingsCategory("時刻補正", "手動再生・動画とのずれを調整", Icons.Filled.Sync, "タイミング"),
    SettingsCategory("フローティング表示", "取得状況・再生件数・時刻補正", Icons.Filled.PictureInPictureAlt, "フローティング表示"),
    SettingsCategory("NGワード", "指定した言葉を非表示", Icons.Filled.FilterAlt, "NGワード"),
    SettingsCategory("その他", "通知・ストレージ・連携・ネットワーク", Icons.Filled.MoreHoriz, "その他"),
    SettingsCategory("アプリ詳細", "GitHub・不具合報告・OSSライセンス", Icons.Filled.Info, "アプリ詳細"))

@Composable internal fun SettingsCategoryList(items: List<SettingsCategory>, select: (String) -> Unit) {
    ReferenceGroup { items.forEachIndexed { index, item -> ReferenceCategory(item.title, item.summary, item.icon, index, items.size) { select(item.page) } } }
}
@Composable internal fun SettingsHomeList(linked: Boolean, limit: Long, query: String, displaying: Boolean, toggle: () -> Unit, select: (String) -> Unit) {
    val all = HomeSettings + otherSettings(linked, limit)
    val items = if (query.isBlank()) HomeSettings else all.filter { item ->
        val extra = when (item.page) {
            "コメント表示" -> listOf("FPS", "文字サイズ", "不透明度", "速度", "取得量", "描画フレームレート", "表示位置", "視聴アプリ別", "表示位置", "カスタム", "描画の負荷", "省電力", "バランス", "不透明度", "文字サイズ")
            "ネットワーク" -> listOf("HTTP", "SOCKS", "proxy", "タイムアウト")
            "セキュリティ" -> listOf("PIN", "パスワード", "指紋", "パターン", "ロック")
            "ダウンロード" -> listOf("バックアップ", "事前保存", "保存容量", "充電", "キャッシュ", "先に保存する話数", "Wi-Fi")
            else -> emptyList()
        }
        searchSettingMatches(query, listOf(item.title, item.summary, uiText(item.title), uiText(item.summary)) + extra.flatMap { listOf(it, uiText(it)) })
    }
    ReferenceGroup { items.forEachIndexed { index, item ->
        ReferenceCategory(item.title, item.summary, item.icon, index, items.size, tone = all.indexOf(item),
            trailing = if (item.page == "コメント表示") { { StudioSwitch(displaying, { if (it != displaying) toggle() }, "コメント表示") } } else { {} }) { select(item.page) }
    } }
    if (items.isEmpty()) AppText("一致する設定がありません", Modifier.padding(16.dp))
}
@Composable internal fun ServiceProfileSettings(model: ConvenienceViewModel?, app: AppCoordinator) {
    if (model == null) return
    val options by model.options.collectAsStateWithLifecycle()
    val service by app.activeService.collectAsStateWithLifecycle()
    ServiceProfileContent(options.serviceProfiles, service, { model.boolean("serviceProfiles", it) }, app::setActiveService)
}
@Composable internal fun ServiceProfileContent(enabled: Boolean, service: StreamingService, change: (Boolean) -> Unit, choose: (StreamingService) -> Unit) {
    ReferenceGroup {
        SwitchRow("視聴アプリ別の設定を使用", enabled, change)
        if (enabled) ServiceSelector(service, choose)
    }

}
@Composable internal fun HomeViewingCard(app: AppCoordinator, toggle: () -> Unit, open: (StreamingService) -> Unit, metrics: FloatingPanelState? = null) {
    val service by app.activeService.collectAsStateWithLifecycle()
    val playback by Session.playback.collectAsStateWithLifecycle()
    val manual by Session.manualPlayback.collectAsStateWithLifecycle()
    val current = manual ?: playback
    val overlay by Session.overlay.collectAsStateWithLifecycle()
    val progress by app.fetchProgress.collectAsStateWithLifecycle()
    val comments by Session.presented.collectAsStateWithLifecycle()
    val busy by app.busy.collectAsStateWithLifecycle()
    var choosing by rememberSaveable { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(current.playing) {
        now = android.os.SystemClock.elapsedRealtime()
        if (current.playing) while (true) { kotlinx.coroutines.delay(1000); now = android.os.SystemClock.elapsedRealtime() }
    }
    ReferenceGroup {
        HomeServiceRow(service, { choosing = true }, { if (!overlay) toggle(); open(service) })
        if (current.connected) {
            val position = current.positionAt(now) ?: 0L
            StudioRow(headlineContent = { AppText(current.title, localize = false, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                leadingContent = { AppIcon(if (current.playing) Icons.Filled.PlayCircle else Icons.Filled.PauseCircle, "現在視聴中") },
                supportingContent = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppText(if (metrics != null) "${metrics.current} / ${metrics.total}件" else if (busy && progress.target > 0) "${progress.current} / ${progress.target}件" else "${playedCommentCount(comments, position)} / ${comments.size}件", style = MaterialTheme.typography.bodyMedium)
                    AppText("${formatTime(position)} / ${formatTime(current.duration)}", localize = false, style = MaterialTheme.typography.bodyMedium)
                    if (current.duration > 0) StudioProgress({ (position.toFloat() / current.duration).coerceIn(0f,1f) })
                } })
        }
    }
    if (choosing) ServiceSelectionSheet(service, { app.setActiveService(it); choosing = false }) { choosing = false }
}
@Composable internal fun HomeServiceRow(service: StreamingService, choose: () -> Unit, open: () -> Unit) {
    StudioRow(headlineContent = { AppText(service.label, localize = false, style = MaterialTheme.typography.titleMedium) }, supportingContent = { AppText("視聴アプリ") },
        leadingContent = { ServiceAppIcon(service, 32.dp) }, modifier = Modifier.motionClickable(onClick = choose),
        trailingContent = { StudioIconButton(open) { AppIcon(Icons.AutoMirrored.Filled.OpenInNew, "動画アプリを開く") } })
}
@Composable internal fun AcquisitionSettings(app: AppCoordinator, settings: (String) -> Unit = {}) {
    val automatic by app.auto.collectAsStateWithLifecycle()
    val busy by app.busy.collectAsStateWithLifecycle()
    val status by app.status.collectAsStateWithLifecycle()
    val comments by Session.comments.collectAsStateWithLifecycle()
    val progress by app.fetchProgress.collectAsStateWithLifecycle()
    val reports by Session.commentSyncReports.collectAsStateWithLifecycle()
    val sources by Session.commentSources.collectAsStateWithLifecycle()
    var manual by rememberSaveable { mutableStateOf(false) }
    ReferenceGroup {
        SwitchRow("自動取得", automatic, app::setAuto, "再生中の作品に合わせて取得")
        StudioRow(headlineContent = { AppText(if (busy) "コメントを取得中" else "${comments.size}件のコメント") }, supportingContent = { AppText(status) }, trailingContent = { StudioIconButton(app::retryCurrent, enabled = automatic && !busy) { AppIcon(Icons.Filled.Refresh, "コメントを再取得") } })
        PreferenceAction("手動で取得", "作品名・話数・動画IDを指定", Icons.Filled.Search) { manual = true }
    }
    if (busy) { if (progress.target > 0) StudioProgress({ (progress.current.toFloat() / progress.target).coerceIn(0f, 1f) }) else StudioProgress(); AppText(if (progress.target > 0) "${progress.current} / ${progress.target}件" else progress.label) }
    if (sources.isNotEmpty()) AppText(sources.map { uiText(it.label) }.joinToString(" · "), localize = false, style = MaterialTheme.typography.bodyMedium)
    CommentSyncStatus(reports)
    ErrorActions(status, app::retryCurrent, { manual = true }, settings)
    if (manual) ManualCommentSheet { manual = false }
}
@Composable internal fun ManualPlaybackSettings(app: AppCoordinator) {
    val manual by Session.manualPlayback.collectAsStateWithLifecycle()
    val playback by Session.playback.collectAsStateWithLifecycle()
    val paused by Session.adPause.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    var tools by rememberSaveable { mutableStateOf(false) }
    val model = LocalConvenience.current
    ReferenceGroup {
        PreferenceAction("手動再生", if (manual != null) "再生位置を更新" else "再生情報を取得できない場合", Icons.Filled.PlayCircle) { editing = true }
        if (manual != null) PreferenceAction("手動再生を終了", "自動認識に戻る", Icons.Filled.Stop) { app.endManualPlayback() }
        if (model != null) PreferenceAction("再生の操作", "取得元・同期・一時的に隠す", Icons.Filled.Tune) { tools = true }
        if (playback.connected || manual != null) SwitchRow("広告中は停止", paused, { Session.adPause.value = it })
    }
    if (editing) ManualPlaybackSheet { editing = false }
    if (tools && model != null) StudioSheet({ tools = false }) { PlaybackTools(model, close = { tools = false }) }
}
