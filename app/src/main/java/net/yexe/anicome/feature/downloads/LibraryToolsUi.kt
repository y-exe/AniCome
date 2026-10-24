package net.yexe.anicome

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.work.WorkInfo
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable internal fun ReservationGroup(title: String, all: List<WorkInfo>, library: List<SavedEpisode>, retry: (WorkInfo) -> Unit, cancel: (java.util.UUID) -> Unit, settings: (String) -> Unit = {}, channel: (String) -> Unit = {}, pause: ((Set<java.util.UUID>) -> Unit)? = null, resume: ((Set<java.util.UUID>) -> Unit)? = null) {
    var expanded by remember(title) { mutableStateOf(false) }
    val pending = all.filter { it.state !in setOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED) }
    val complete = all.count { it.state == WorkInfo.State.SUCCEEDED }
    val failure = all.count { it.state == WorkInfo.State.FAILED }
    val total = all.size
    val artwork = rememberArtwork(title)
    val picture = all.firstNotNullOfOrNull { work -> work.tags.firstOrNull { it.startsWith("artwork:") }?.removePrefix("artwork:") }.orEmpty()
    val running = all.count { it.state == WorkInfo.State.RUNNING }
    val paused = all.count(::reservationPaused)
    val estimatedBytes = pending.filter { it.state != WorkInfo.State.FAILED }.sumOf { it.progress.getLong("reservationBytes", 0L).coerceIn(0L, MAX_DOWNLOAD_ESTIMATE) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        ListItem(headlineContent = { AppText(title, localize = false, maxLines = 2) }, supportingContent = { Column {
            AppText("${complete} / ${total}話 · 処理中 ${running} · 失敗 ${failure}")
            if (paused > 0) AppText("一時停止中：${paused}件")
            if (estimatedBytes > 0) AppText("保存容量の目安：%.1f MB".format(estimatedBytes / 1048576.0))
        } },
            leadingContent = { AnimeArtwork(artwork, Modifier.width(56.dp).height(64.dp), title, imageOverride = picture) },
            trailingContent = { IconButton({ expanded = !expanded }) { AppIcon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, "保存予約の詳細") } }, modifier = Modifier.motionClickable { expanded = !expanded })
        LinearProgressIndicator(progress = { if (total == 0) 0f else complete.toFloat() / total }, modifier = Modifier.fillMaxWidth())
        if (expanded) {
            if (failure > 0) OutlinedButton({ all.filter { it.state == WorkInfo.State.FAILED }.forEach(retry) }) { AppText("失敗した話だけ再試行") }
            pending.forEach { work -> ReservationRow(work, { retry(work) }, { cancel(work.id) }, settings, { reservationEpisode(work.tags)?.series?.let(channel) }, pause?.let { { it(setOf(work.id)) } }, resume?.let { { it(setOf(work.id)) } }) }
        }
    }
}

@Composable fun EpisodeActions(item: SavedEpisode, model: ConvenienceViewModel, delete: () -> Unit, dismiss: () -> Unit) {
    var history by remember { mutableStateOf(false) }
    if (history) CommentHistorySheet(series = item.episode.series) { history = false }
    var replacing by remember { mutableStateOf(false) }
    if (replacing) VideoReplacementDialog(item) { replacing = false; dismiss() }
    var correcting by remember { mutableStateOf(false) }
    var number by remember { mutableStateOf(model.episodeLabel(item).toString()) }
    var series by remember { mutableStateOf(model.libraryTitle(item)) }
    val operation by model.operation.collectAsStateWithLifecycle()
    val sources by model.offlineDetails.collectAsStateWithLifecycle()
    val revision by model.revision.collectAsStateWithLifecycle()
    val prefetchDisabled = remember(revision, item.episode.series) { model.prefetchDisabled(item.episode.series) }
    LaunchedEffect(item.episode.key, item.savedAt) { model.loadOfflineDetails(item.episode) }
    Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppText(item.sourceTitle, localize = false, style = MaterialTheme.typography.titleLarge)
        AppText("${item.count}件 · %.1f MB".format(item.bytes / 1048576.0))
        AppText(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(item.savedAt)), localize = false)
        if (sources.isNotEmpty()) AppText("保存する取得元", style = MaterialTheme.typography.titleMedium)
        ReferenceGroup { sources.forEach { detail -> StudioRow(headlineContent = { AppText(detail.source.label) },
            supportingContent = { Column { if (detail.count > 0) { AppText("${detail.count}件"); AppText(detail.sync.label, style = MaterialTheme.typography.bodySmall) } else AppText("未保存") } },
            trailingContent = { if (detail.count > 0) AppIcon(Icons.Filled.OfflinePin, "保存済み") }) } }
        if (sources.any { it.count > 0 && !it.sync.confirmed }) AppText("同期未確認の取得元があります。表示するにはコメントの同期設定を確認してください。", style = MaterialTheme.typography.bodyMedium)
        ReferenceGroup {
            MenuAction("不足している取得元を保存", Icons.Filled.Download, !operation.busy) { model.completeOffline(listOf(item)); dismiss() }
            MenuAction("コメントを更新", Icons.Filled.Refresh, !operation.busy) { model.refresh(listOf(item)) }
            MenuAction(if (model.watched(item.episode)) "未視聴にする" else "視聴済みにする", Icons.Filled.DoneAll) { model.markWatched(listOf(item.episode), !model.watched(item.episode)); dismiss() }
            MenuAction("取得・変更履歴", Icons.Filled.History) { history = true }
            MenuAction("公式動画を差し替え", Icons.Filled.SwapHoriz, !operation.busy) { replacing = true }
            MenuAction("作品名・話数を修正", Icons.Filled.Edit) {
                if (!correcting) { series = model.libraryTitle(item); number = model.episodeLabel(item).toString() }
                correcting = !correcting
            }
        }
        if (correcting) SettingsCard {
            OutlinedTextField(series, { series = it.take(300) }, label = { AppText("表示する作品名") }, modifier = Modifier.fillMaxWidth(), visualTransformation = rememberRoundedInput())
            OutlinedTextField(number, { number = it.filter(Char::isDigit).take(4) }, label = { AppText("表示する話数") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), visualTransformation = rememberRoundedInput())
            Button({ model.correct(item, series, number.toInt()); correcting = false }, Modifier.fillMaxWidth(), enabled = !operation.busy && series.isNotBlank() && number.toIntOrNull() in 0..9999) { AppText("修正") }
            TextButton({ model.resetLabels(item); correcting = false }, enabled = !operation.busy) { AppText("元の表示に戻す") }
            AppText("コメント本体・動画・検索に使う作品情報は変更しません。作品名の表示はこの作品全体に適用します。", style = MaterialTheme.typography.bodyMedium)
        }
        SettingsCard {
            StudioRow(headlineContent = { AppText("作品を保護") }, supportingContent = { AppText("削除するには先に保護を解除してください") },
                trailingContent = { Switch(model.protected(item.episode.series), { model.protect(item.episode.series, it) }) })
            StudioRow(headlineContent = { AppText("この作品の先読みを停止") },
                supportingContent = { AppText("再生中の自動取得や手動保存には影響しません") },
                trailingContent = { Switch(prefetchDisabled, { model.disablePrefetch(item.episode.series, it) }) })
        }
        SettingsCard { BackupControls(model, listOf(item)) }
        ReferenceGroup { MenuAction("削除", Icons.Filled.Delete, destructive = true) { delete(); dismiss() } }
    }
}
