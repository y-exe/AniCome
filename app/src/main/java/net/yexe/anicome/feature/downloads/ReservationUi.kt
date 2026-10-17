package net.yexe.anicome

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo

@Composable internal fun ReservationRow(work: WorkInfo, retry: () -> Unit, remove: () -> Unit, settings: (String) -> Unit = {}, channel: () -> Unit = {}, pause: (() -> Unit)? = null, resume: (() -> Unit)? = null) {
    val episode = reservationEpisode(work.tags)
    val artwork = rememberArtwork(episode?.series.orEmpty())
    val image = work.tags.firstOrNull { it.startsWith("artwork:") }?.removePrefix("artwork:").orEmpty()
    val failed = work.state == WorkInfo.State.FAILED
    val failure = runCatching { AcquisitionFailure.valueOf(work.outputData.getString("failureKind").orEmpty()) }.getOrElse { acquisitionFailure(work.outputData.getString("message").orEmpty()) }
    var details by remember { mutableStateOf(false) }
    val paused = reservationPaused(work)
    val trafficBlocked = work.progress.getBoolean("reservationTrafficBlocked", false)
    val order = work.progress.getInt("reservationOrder", 0)
    val status = when { paused -> "一時停止中"; trafficBlocked -> "通信量の上限で待機中"; failed -> work.outputData.getString("message") ?: "保存失敗"; work.state == WorkInfo.State.RUNNING -> work.progress.getString("label") ?: "取得中"; order > 0 -> "保存順：${order}番"; else -> "待機中" }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().motionClickable { details = true }.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AnimeArtwork(artwork, Modifier.size(72.dp, 48.dp), episode?.series.orEmpty(), imageOverride = image)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AppText(episode?.let { "${it.series} 第${work.progress.getInt("sourceNumber", it.number)}話" }.orEmpty(), localize = false, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                AppText(status, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                reservationTimeLabel(work)?.let { AppText(it, style = MaterialTheme.typography.bodyMedium) }
                val estimatedBytes = work.progress.getLong("reservationBytes", 0L)
                if (estimatedBytes > 0) AppText("保存容量の目安：%.1f MB".format(estimatedBytes / 1048576.0), style = MaterialTheme.typography.bodyMedium)
                if (work.state == WorkInfo.State.RUNNING) {
                    val target = work.progress.getInt("target", 0)
                    val current = work.progress.getInt("current", 0)
                    if (target > 0) { StudioProgress({ (current.toFloat() / target).coerceIn(0f, 1f) }); AppText("$current / $target 件", style = MaterialTheme.typography.bodyMedium) }
                    else StudioProgress(Modifier.fillMaxWidth())
                }
            }
        }
        FlowRow(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (trafficBlocked) OutlinedButton({ settings("ネットワーク") }) { AppText("通信量の上限") }
            if (failed && failure.settingsPage != null) OutlinedButton({ settings(failure.settingsPage) }) { AppText("設定を確認") }
            if (failed && failure.manual) OutlinedButton(channel) { AppText("チャンネルを指定") }
            if (failed) OutlinedButton(retry) { AppIcon(Icons.Filled.Refresh, null); Spacer(Modifier.width(4.dp)); AppText("再試行") }
            if (reservationManaged(work) && !work.state.isFinished) {
                if (paused && resume != null) OutlinedButton(resume) { AppIcon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(4.dp)); AppText("再開") }
                else if (!paused && pause != null) OutlinedButton(pause) { AppIcon(Icons.Filled.Pause, null); Spacer(Modifier.width(4.dp)); AppText("一時停止") }
            }
            OutlinedButton(remove) { AppIcon(if (failed) Icons.Filled.Delete else Icons.Filled.Close, null); Spacer(Modifier.width(4.dp)); AppText(if (failed) "削除" else "キャンセル") }
        }
    }
    if (details) StudioDialog(onDismissRequest = { details = false }, title = { AppText(episode?.let { "${it.series} 第${work.progress.getInt("sourceNumber", it.number)}話" }.orEmpty(), localize = false) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (failed) { AppText(failure.label, style = MaterialTheme.typography.titleMedium); AppText(failure.explanation) }
        AppText(status)
        if (trafficBlocked) AppText(AcquisitionFailure.TRAFFIC_LIMIT.explanation)
        if (reservationManaged(work)) {
            if (paused) AppText("保存済みのコメントを残して停止しています。再開すると不足分から取得します。")
            if (work.progress.getBoolean("reservationWifi", false)) AppText("Wi-Fi接続時に保存")
            if (work.progress.getBoolean("reservationCharging", false)) AppText("充電中に保存")
            if (work.progress.getLong("reservationBytes", 0L) > 0 || work.progress.getLong("reservationDurationMs", 0L) > 0) AppText("時間と容量は目安です。待機時間は含まず、取得元・取得量・回線によって変わります。")
            if (work.progress.getLong("reservationDurationMs", 0L) > 0) AppText("同じ取得元・取得量の保存実績から計算しています。")
            else if (work.state == WorkInfo.State.RUNNING) AppText("現在の取得元の速度から計算します。取得元の切り替え中は時間を表示しません。")
        }
        work.progress.getString("episodeTitle")?.takeIf(String::isNotBlank)?.let { AppText(it, localize = false) }
        if (work.progress.getBoolean("missingEpisodeTitle", false)) AppText("各話タイトルを取得できなかったため話数で検索中")
    } }, confirmButton = { TextButton({ details = false }) { AppText("閉じる") } })
}

@Composable internal fun ChannelInputDialog(state: SaveCommentState, input: (String) -> Unit, connect: () -> Unit, dismiss: () -> Unit) {
    StudioDialog(onDismissRequest = dismiss, title = { AppText("チャンネルを直接指定") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppText("公式動画の検索に使うニコニコチャンネルのURL・ID・名前を指定できます。他の取得元は作品と話数から検索します。")
            OutlinedTextField(state.channelInput, input, Modifier.fillMaxWidth(), label = { AppText("URL・ID・チャンネル名") }, placeholder = { AppText("例：mushokutensei") }, singleLine = true, enabled = !state.checkingChannel, isError = state.channelError.isNotBlank())
            if (state.checkingChannel) { StudioProgress(); AppText("チャンネルを確認しています") }
            if (state.channelError.isNotBlank()) AppText(state.channelError, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(connect, enabled = state.channelInput.isNotBlank() && !state.checkingChannel) { AppText("確認") } }, dismissButton = { TextButton(dismiss) { AppText("キャンセル") } })
}
