@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package net.yexe.anicome

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable fun CommentSourcesSettings(model: AdvancedSettingsViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    PreferenceSection("自動取得するコメント")
    PreferenceHint("再生時は複数の取得元を重ねて表示します。コメント保存で取得元を選び、事前に保存できます。")
ReferenceGroup {
    PlaybackSource.entries.filter { it.settingKey != null }.forEach { source ->
        SwitchRow(source.label, source in state.sourceTargets, { model.boolean(source.settingKey!!, it) }, when (source) {
            PlaybackSource.CHAPTER -> "各パートのコメントを時刻順につなぎます"
            PlaybackSource.JIKKYO -> "放送日時に一致する実況過去ログ"
            PlaybackSource.LIVE_ARCHIVE -> "ニコニコ生放送のアニメコメントアーカイブ"
            else -> ""
        })
    }
}

    CommentSyncSettings(state, model::boolean)
    if (state.sourceTargets.isEmpty()) AppText("取得元が未選択です", color = MaterialTheme.colorScheme.error)
}

@Composable internal fun LanguagePicker(selected: String, choose: (String) -> Unit) {
    val flags = mapOf("ja" to R.drawable.flag_ja, "en" to R.drawable.flag_en, "ko" to R.drawable.flag_ko, "fr" to R.drawable.flag_fr, "zh" to R.drawable.flag_zh, "es" to R.drawable.flag_es, "pt" to R.drawable.flag_pt)
    val maximum = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp - 120.dp).coerceAtLeast(180.dp)
    Column(Modifier.fillMaxWidth().heightIn(max = maximum).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        AppText("言語", style = MaterialTheme.typography.headlineSmall)
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            ReferenceGroup {
                SupportedLanguages.forEach { (tag, name) ->
                    ListItem(
                        headlineContent = { AppText(if (tag == "system") uiText(name) else name, localize = false, cjkFont = if (tag == "zh") ChineseRoundedFont else JapaneseRoundedFont) },
                        leadingContent = { if (tag == "system") AppIcon(Icons.Filled.PhoneAndroid, null, Modifier.size(40.dp)) else androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(flags.getValue(tag)), null, Modifier.size(40.dp)) },
                        trailingContent = { RadioButton(tag == selected, null) },
                        modifier = Modifier.selectable(tag == selected, role = Role.RadioButton) { choose(tag) },
                        colors = ListItemDefaults.colors(containerColor = if (tag == selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer),
                    )
                }
            }
        }
    }
}

@Composable internal fun CommentSyncStatus(reports: List<Pair<PlaybackSource, CommentSync>>, unavailable: Boolean = false, showSource: Boolean = true) {
    if (reports.isEmpty()) return
    ReferenceGroup { reports.distinct().forEach { (source, sync) ->
        StudioRow(headlineContent = {
            AppText(if (showSource) uiText(source.label) + " · " + uiText(sync.label) else uiText(sync.label), localize = false, style = MaterialTheme.typography.titleSmall)
        }, supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AppText(commentSyncExplanation(sync), style = MaterialTheme.typography.bodyMedium)
                if (unavailable) AppText("この同期状態のコメントは、現在の表示に含まれていません。", style = MaterialTheme.typography.bodyMedium)
            }
        }, leadingContent = {
            AppIcon(when (sync) {
                CommentSync.NATIVE -> Icons.Filled.CheckCircle
                CommentSync.OFFSET -> Icons.Filled.Sync
                CommentSync.CUT -> Icons.Filled.ContentCut
                CommentSync.START_ONLY -> Icons.Filled.Schedule
                CommentSync.UNKNOWN -> Icons.Filled.Help
                CommentSync.DISABLED -> Icons.Filled.SyncDisabled
            }, null)
        })
    } }
}

@Composable internal fun CommentSyncSettings(state: AdvancedSettings, boolean: (String, Boolean) -> Unit) {
    PreferenceSection("コメントの自動同期")
ReferenceGroup {
    SwitchRow("取得元ごとに時刻を合わせる", state.autoCommentSync, { boolean("autoCommentSync", it) }, "OP・本編・EDの反応を照合して補正します")
    if (state.autoCommentSync) SwitchRow("同期を確認できた取得元だけ表示", state.syncOnlyConfirmed, { boolean("syncOnlyConfirmed", it) }, "情報不足の実況・生放送は表示から外します")
}

    PreferenceHint("CM区間の補正は推定です。情報が足りない場合は自動で削りません。")
}

@Composable internal fun PreviewCommentCountPicker(selected: Int, choose: (Int) -> Unit) {
    AppText("試し表示のコメント数", style = MaterialTheme.typography.titleMedium)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PreviewCommentCounts.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { count ->
                    FilterChip(selected = selected == count, onClick = { choose(count) },
                        label = { AppText(if (LocalUiLanguage.current == "ja") "${count}件" else count.toString(), localize = false) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).fillMaxHeight(),
                        leadingIcon = if (selected == count) { { AppIcon(Icons.Filled.Check, null) } } else null)
                }
            }
        }
    }
}
