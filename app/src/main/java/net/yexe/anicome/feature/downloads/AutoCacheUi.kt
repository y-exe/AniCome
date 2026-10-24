package net.yexe.anicome

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable internal fun AutoCacheSheet(close: () -> Unit) {
    val model: AutoCacheViewModel = org.koin.androidx.compose.koinViewModel()
    val entries by model.entries.collectAsStateWithLifecycle()
    val hours by model.hours.collectAsStateWithLifecycle()
    val failed by model.failed.collectAsStateWithLifecycle()
    val limit by model.limit.collectAsStateWithLifecycle()
    val free by model.freeBytes.collectAsStateWithLifecycle()
    StudioSheet(close) { Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp.dp - 96.dp).coerceAtLeast(160.dp)).verticalScroll(rememberScrollState()).padding(16.dp)) { AutoCacheContent(entries, hours, model::retention, model::delete, model::clear, failed, keep = model::keep, limitMb = limit, capacity = model::capacity, freeBytes = free) } }
}
@Composable internal fun AutoCacheContent(entries: List<SavedEpisode>, hours: Long, retention: (Long) -> Unit,
    delete: (Episode) -> Unit, clear: () -> Unit, failed: Boolean = false,
    keep: (Episode) -> Unit = {}, limitMb: Long = 0L, capacity: (Long) -> Unit = {}, freeBytes: Long = 0L) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var input by rememberSaveable(hours) { mutableStateOf(hours.toString()) }
    var removing by remember { mutableStateOf<Episode?>(null) }
    var clearing by rememberSaveable { mutableStateOf(false) }
    var capacityOpen by rememberSaveable { mutableStateOf(false) }
    var capacityInput by rememberSaveable { mutableStateOf("0") }
    var keeping by remember { mutableStateOf<SavedEpisode?>(null) }
    var expanded by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(60_000) } }
    AppText("自動キャッシュ保存", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(16.dp))
    SettingsCard {
        AppText("自動削除までの時間", style = MaterialTheme.typography.titleMedium)
        AppText("再生時に取得したコメントだけを自動削除します。事前ダウンロードは残ります。", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton({ input = hours.toString(); editing = true }, Modifier.fillMaxWidth()) {
            AppIcon(Icons.Filled.Schedule, null); Spacer(Modifier.width(8.dp)); AppText("${hours}時間", Modifier.weight(1f)); AppIcon(Icons.Filled.Edit, null)
        }
    }
    Spacer(Modifier.height(12.dp))
    SettingsCard {
        AppText("キャッシュ容量の上限", style = MaterialTheme.typography.titleMedium)
        AppText("上限を超えると古いキャッシュから削除します。事前ダウンロードは削除しません。")
        OutlinedButton({ capacityInput = limitMb.toString(); capacityOpen = true }, Modifier.fillMaxWidth()) {
            AppIcon(Icons.Filled.Storage, null); Spacer(Modifier.width(8.dp))
            AppText(if (limitMb == 0L) "制限なし" else "${limitMb} MB", Modifier.weight(1f)); AppIcon(Icons.Filled.Edit, null)
        }
        if (freeBytes > 0L) AppText("端末の空き容量  %.1f GB".format(freeBytes / 1073741824.0), style = MaterialTheme.typography.bodyMedium)
    }
    Spacer(Modifier.height(12.dp))
    AppText("使用容量  %.1f MB".format(entries.sumOf { it.bytes } / 1048576.0), style = MaterialTheme.typography.bodyLarge)
    if (failed) AppText("キャッシュを更新できませんでした。もう一度お試しください。", color = MaterialTheme.colorScheme.error)
    if (entries.isEmpty()) AppText("自動キャッシュはありません", Modifier.padding(vertical = 24.dp))
    else {
        OutlinedButton({ clearing = true }, Modifier.fillMaxWidth()) { AppIcon(Icons.Filled.DeleteSweep, null); Spacer(Modifier.width(8.dp)); AppText("キャッシュをすべて削除") }
        entries.groupBy { it.episode.series }.forEach { (series, episodes) ->
            Spacer(Modifier.height(12.dp))
            ReferenceGroup {
                StudioRow(modifier = Modifier.motionClickable { expanded = if (series in expanded) expanded - series else expanded + series }, headlineContent = { AppText(series, localize = false) },
                    supportingContent = { AppText("${episodes.size}話 · %.1f MB".format(episodes.sumOf { it.bytes } / 1048576.0)) },
                    leadingContent = { AnimeArtwork(null, Modifier.size(64.dp, 36.dp), series, imageOverride = episodes.first().thumbnailUrl) },
                    trailingContent = { IconButton({ expanded = if (series in expanded) expanded - series else expanded + series }) { AppIcon(if (series in expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, uiText(if (series in expanded) "閉じる" else "開く")) } })
                if (series in expanded) episodes.forEach { saved ->
                    StudioRow(headlineContent = { AppText("第${saved.videoEpisodeNumber()}話") },
                        supportingContent = { Column {
                            AppText(saved.sources.split(",").mapNotNull { runCatching { uiText(PlaybackSource.valueOf(it).label) }.getOrNull() }.joinToString(" · "))
                            val minutes = ((saved.savedAt + hours * 3_600_000L - now).coerceAtLeast(0L) + 59_999L) / 60_000L
                            AppText(if (minutes == 0L) "まもなく削除" else if (minutes >= 60L) "あと${(minutes + 59L) / 60L}時間で削除" else "あと${minutes}分で削除", style = MaterialTheme.typography.bodySmall)
                        } },
                        leadingContent = { EpisodeArtwork(saved.sourceId, saved.thumbnailUrl, Modifier.size(64.dp, 36.dp), saved.sourceTitle) },
                        trailingContent = { Row {
                            IconButton({ keeping = saved }) { AppIcon(Icons.Filled.SaveAlt, uiText("事前保存へ変更")) }
                            IconButton({ removing = saved.episode }) { AppIcon(Icons.Filled.Delete, uiText("削除")) }
                        } })
                }
            }
        }
    }
    if (keeping != null) StudioDialog({ keeping = null }, title = { AppText("事前保存へ変更") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppText("取得済みのコメントをそのまま保存します。自動削除の対象から外れます。")
            AppText(keeping!!.episode.label, localize = false)
        }
    }, confirmButton = { TextButton({ keeping?.let { keep(it.episode) }; keeping = null }) { AppText("保存") } }, dismissButton = { TextButton({ keeping = null }) { AppText("キャンセル") } })
    if (capacityOpen) StudioDialog({ capacityOpen = false }, title = { AppText("キャッシュ容量の上限") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppText("0は制限なしです。上限を小さくすると古いキャッシュを削除します。")
            StudioField(capacityInput, { capacityInput = it }, Modifier.fillMaxWidth(), label = { AppText("容量（MB）") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = capacityInput.toLongOrNull() !in 0L..1_000_000L)
        }
    }, confirmButton = { TextButton({ capacity(capacityInput.toLong()); capacityOpen = false }, enabled = capacityInput.toLongOrNull() in 0L..1_000_000L) { AppText("保存") } }, dismissButton = { TextButton({ capacityOpen = false }) { AppText("キャンセル") } })

    if (editing) StudioDialog({ editing = false }, title = { AppText("自動削除までの時間") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppText("取得から指定時間が過ぎたコメントを削除します。")
            StudioField(input, { input = it }, Modifier.fillMaxWidth(), label = { AppText("時間") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = input.toLongOrNull() !in 1L..87600L)
        }
    }, confirmButton = { TextButton({ retention(input.toLong()); editing = false }, enabled = input.toLongOrNull() in 1L..87600L) { AppText("保存") } }, dismissButton = { TextButton({ editing = false }) { AppText("キャンセル") } })
    if (removing != null || clearing) StudioDialog({ removing = null; clearing = false }, title = { AppText("キャッシュを削除？") }, text = { AppText("事前ダウンロードしたコメントは残ります。") },
        confirmButton = { TextButton({ if (clearing) clear() else removing?.let(delete); removing = null; clearing = false }) { AppText("削除") } },
        dismissButton = { TextButton({ removing = null; clearing = false }) { AppText("キャンセル") } })
}
