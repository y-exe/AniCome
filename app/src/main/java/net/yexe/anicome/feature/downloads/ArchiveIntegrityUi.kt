package net.yexe.anicome

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable internal fun ArchiveIntegritySheet(settings: ((String) -> Unit)? = null, close: () -> Unit) {
    val model: ArchiveIntegrityViewModel = org.koin.androidx.compose.koinViewModel()
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { model.inspect() }
    StudioSheet(close, dismissEnabled = state.repairing == null) { ArchiveIntegrityContent(state, model::inspect, model::repair, settings) }
}
@Composable internal fun ArchiveIntegrityContent(state: ArchiveIntegrityState, inspect: () -> Unit, repair: (ArchiveCheck) -> Unit, settings: ((String) -> Unit)? = null) {
    var confirming by remember { mutableStateOf<ArchiveCheck?>(null) }
    val busy = state.checking || state.repairing != null
    Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * .85f).dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppText("保存データの検査と修復", style = MaterialTheme.typography.titleLarge)
        AppText("問題のある話だけを修復します。修復前のファイルは復旧用に残します。")
        OutlinedButton(inspect, Modifier.fillMaxWidth(), enabled = !busy) { AppIcon(Icons.Filled.Refresh, null); Spacer(Modifier.width(8.dp)); AppText("もう一度検査") }
        if (busy) StudioProgress()
        if (state.error.isNotBlank()) {
            AppText(state.error, color = MaterialTheme.colorScheme.error)
            val page = state.failure?.settingsPage ?: acquisitionFailure(state.error).settingsPage
            if (page != null && settings != null) OutlinedButton({ settings(page) }, Modifier.fillMaxWidth(), enabled = !busy) { AppText("設定を確認") }
        }
        if (state.recoveryBytes > 0L) AppText("復旧用のデータ  %.1f MB".format(state.recoveryBytes / 1048576.0))
        if (state.checked) AppText("${state.checks.count { it.healthy }} / ${state.checks.size}件が正常です")
        if (state.checked && state.checks.isEmpty()) AppText("保存データはありません")
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.checks.sortedBy { it.healthy }, key = { it.fileName }) { check ->
                SettingsCard {
                    AppText(check.info?.episode?.label ?: check.fileName.take(20), localize = false, style = MaterialTheme.typography.titleMedium)
                    AppText(archiveStatusLabel(check.status), color = if (check.healthy) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                    if (check.damaged.isNotEmpty()) AppText(check.damaged.map { uiText(it.label) }.joinToString(" · "), localize = false)
                    if (check.info == null) AppText("作品・話数を特定できないため、自動修復できません")
                    if (check.repairable) OutlinedButton({ confirming = check }, Modifier.fillMaxWidth(), enabled = !busy) { AppIcon(Icons.Filled.Build, null); Spacer(Modifier.width(8.dp)); AppText("この話を修復") }
                }
            }
        }
    }
    confirming?.let { check -> StudioDialog({ confirming = null }, title = { AppText("この話を修復？") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppText(check.info!!.episode.label, localize = false)
            AppText("正常なコメントを残し、破損した取得元を再取得します。通信が発生する場合があります。")
            AppText("復旧用のファイルは、この話を削除すると一緒に削除されます。")
        }
    }, confirmButton = { TextButton({ confirming = null; repair(check) }) { AppText("修復") } }, dismissButton = { TextButton({ confirming = null }) { AppText("キャンセル") } }) }
}
