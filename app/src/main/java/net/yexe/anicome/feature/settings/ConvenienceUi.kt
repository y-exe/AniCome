package net.yexe.anicome

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle

val LocalConvenience = staticCompositionLocalOf<ConvenienceViewModel?> { null }

@Composable fun CommentSelectionSettings(model: ConvenienceViewModel?) {
    if (model == null) return
    val o by model.options.collectAsStateWithLifecycle()
    var forget by remember { mutableStateOf(false) }
    PreferenceSection("動画の選択")
SettingsCard {
    SwitchRow("手動で選んだ動画を記憶", o.rememberSelection, { model.boolean("rememberSelection", it) }, "同じ作品・話数を再生したときに使用します")
    OutlinedButton({ forget = true }, Modifier.fillMaxWidth()) { AppText("記憶した選択をリセット") }
}

    if (forget) StudioDialog(onDismissRequest = { forget = false }, title = { AppText("選択をリセット？") }, text = { AppText("保存済みコメントは削除しません") }, confirmButton = { TextButton({ model.forgetSelections(); forget = false }) { AppText("リセット") } }, dismissButton = { TextButton({ forget = false }) { AppText("キャンセル") } })
}

@Composable fun StorageExtras(model: ConvenienceViewModel?) {
    if (model == null) return
    val o by model.options.collectAsStateWithLifecycle()
    PreferenceSection("次の話の事前保存")
SettingsCard {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppText("先に保存する話数", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalIconButton({ model.next(o.nextEpisodes - 1) }, Modifier.size(48.dp), enabled = o.nextEpisodes > 0) { AppIcon(Icons.Filled.Remove, "少なくする") }
            Surface(Modifier.weight(1f), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    AppText(if (o.nextEpisodes == 0) "オフ" else "${o.nextEpisodes}話", style = MaterialTheme.typography.titleLarge)
                    if (o.nextEpisodes > 0) AppText("次の話から保存", style = MaterialTheme.typography.bodyMedium)
                }
            }
            FilledTonalIconButton({ model.next(o.nextEpisodes + 1) }, Modifier.size(48.dp), enabled = o.nextEpisodes < 5) { AppIcon(Icons.Filled.Add, "多くする") }
        }
    }
    PreferenceHint("作品と話数を確認して、利用できる取得元のコメントをまとめて保存します")
    SwitchRow("事前保存はWi-Fiのみ", o.prefetchWifi, { model.boolean("prefetchWifi", it) })
    SwitchRow("事前保存は充電中のみ", o.prefetchCharging, { model.boolean("prefetchCharging", it) })
}

    PreferenceSection("バックアップと移行")
    SettingsCard { BackupControls(model) }
}

val HomeSectionLabels = listOf("resume" to "続きから見る", "watch" to "視聴", "fetch" to "コメント取得", "adjust" to "再生の調整")
fun moveHomeSection(sections: List<String>, section: String, direction: Int): List<String> {
    val index = sections.indexOf(section)
    val target = index + direction
    if (index < 0 || target !in sections.indices || direction !in listOf(-1, 1)) return sections
    return sections.toMutableList().apply { removeAt(index); add(target, section) }
}
@Composable fun HomeLayoutSettings(model: ConvenienceViewModel?) {
    if (model == null) return
    val o by model.options.collectAsStateWithLifecycle()
    HomeSectionOrder(o.homeSections, model::homeSections)
}
@Composable fun HomeSectionOrder(sections: List<String>, change: (List<String>) -> Unit) {
    PreferenceSection("ホームの表示")
    PreferenceHint("上から順にホームに表示します。矢印で並べ替え、スイッチで表示を切り替えます。")
    val largeText = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.25f
    val labels = HomeSectionLabels.toMap()
    val ordered = sections.filter { it in labels }.distinct()
    val rows = ordered + labels.keys.filter { it !in ordered }
    ReferenceGroup {
        rows.forEach { section -> key(section) {
            val title = labels.getValue(section)
            val spokenTitle = uiText(title)
            val index = ordered.indexOf(section)
            val visible = index >= 0
            val moves: @Composable () -> Unit = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalIconButton({ change(moveHomeSection(ordered, section, -1)) }, enabled = index > 0) { AppIcon(Icons.Filled.ArrowUpward, "${uiText(title)}：上へ移動") }
                    FilledTonalIconButton({ change(moveHomeSection(ordered, section, 1)) }, enabled = index < ordered.lastIndex) { AppIcon(Icons.Filled.ArrowDownward, "${uiText(title)}：下へ移動") }
                }
            }
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { if (visible) AppText("${index + 1}", style = MaterialTheme.typography.labelLarge) else AppIcon(Icons.Filled.VisibilityOff, null, Modifier.size(18.dp)) }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) { AppText(title, style = MaterialTheme.typography.titleMedium); AppText(if (visible) "ホームに表示" else "非表示", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (visible && !largeText) moves()
                    Switch(visible, { checked -> change(if (checked) ordered + section else ordered - section) }, Modifier.semantics { contentDescription = spokenTitle })
                }
                if (visible && largeText) Box(Modifier.align(Alignment.End)) { moves() }
            }
        } }
    }
}

@Composable fun CommentPreviewAction() {
    val context = androidx.compose.ui.platform.LocalContext.current
    PreferenceAction("コメントの試し表示", "実際の動画とコメントで確認", Icons.Filled.Preview) {
        context.startActivity(android.content.Intent(context, CommentPreviewActivity::class.java))
    }
}

@Composable fun BackupControls(model: ConvenienceViewModel, episodes: List<SavedEpisode>? = null) {
    var include by rememberSaveable { mutableStateOf(episodes != null) }
    var confirmImport by remember { mutableStateOf<android.net.Uri?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) model.export(uri, include, episodes) }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { confirmImport = it }
    val state by model.operation.collectAsStateWithLifecycle()
    if (episodes == null) SwitchRow("コメントもバックアップ", include, { include = it })
    OutlinedButton({ export.launch("AniCome-${java.time.LocalDate.now()}.zip") }, Modifier.fillMaxWidth(), enabled = !state.busy) { AppIcon(Icons.Filled.Upload, null); Spacer(Modifier.width(8.dp)); AppText(if (episodes == null) "バックアップを書き出す" else "この作品を書き出す") }
    if (episodes == null) OutlinedButton({ import.launch(arrayOf("application/zip", "application/octet-stream")) }, Modifier.fillMaxWidth(), enabled = !state.busy) { AppIcon(Icons.Filled.Download, null); Spacer(Modifier.width(8.dp)); AppText("バックアップを読み込む") }
    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (state.message.isNotBlank()) AppText(state.message)
    if (state.error.isNotBlank()) AppText(state.error, color = MaterialTheme.colorScheme.error)
    PreferenceHint("ログイン情報・アプリロックは含みません。既存のコメントは上書きしません")
    confirmImport?.let { uri -> StudioDialog(onDismissRequest = { confirmImport = null }, title = { AppText("バックアップを読み込む？") }, text = { AppText("表示設定を復元し、まだ保存されていないコメントを追加します") }, confirmButton = { TextButton({ model.import(uri); confirmImport = null }) { AppText("読み込む") } }, dismissButton = { TextButton({ confirmImport = null }) { AppText("キャンセル") } }) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun PlaybackTools(model: ConvenienceViewModel, section: String = "sources", close: () -> Unit = {}) {
    val slots by Session.commentSlots.collectAsStateWithLifecycle()
    val hidden by Session.hiddenSources.collectAsStateWithLifecycle()
    val offsets by Session.sourceOffsets.collectAsStateWithLifecycle()
    val reports by Session.commentSyncReports.collectAsStateWithLifecycle()
    val markerOptions by model.markers.collectAsStateWithLifecycle()
    val visibility by model.temporaryVisibility.collectAsStateWithLifecycle()
    var globalTiming by remember(section) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppText(when (section) { "ng" -> "NGワード"; "sync" -> "同期の詳細"; "visibility" -> "一時的に隠す"; else -> "再生の操作" }, style = MaterialTheme.typography.titleLarge)
        if (section == "ng") ScopedNgEditor(model) else if (section == "visibility") TemporaryHideControls(visibility, model::hide, full = true) else {
            val missing = reports.filter { (source, sync) -> slots.none { it.source == source && it.sync == sync } }
            if (section == "sync") CommentSyncStatus(missing, unavailable = true)
            else TemporaryHideControls(visibility, model::hide)
            OutlinedButton({ globalTiming = !globalTiming }) { AppText("全体の時刻補正") }
            if (globalTiming) TimingControls(model)
            for ((source, sourceSlots) in slots.groupBy { it.source }) { SettingsCard {
                SwitchRow(source.label, source !in hidden, { model.source(source, it) }, "${sourceSlots.sumOf { it.comments.size }}件")
                CommentSyncStatus(sourceSlots.map { source to it.sync }, showSource = false)
                sourceSlots.forEach { slot ->
                    if (slot.syncOffset != 0L) AppText("自動補正 %+.1f 秒".format(slot.syncOffset / 1000.0), Modifier.padding(horizontal = 16.dp))
                    if (slot.syncSpans.isNotEmpty()) {
                        val removed = slot.syncSpans.zipWithNext().sumOf { (a, b) -> (b.sourceStart - a.sourceEnd).coerceAtLeast(0) }
                        AppText("CM推定カット ${removed / 1000}秒 · ${slot.syncSpans.size}区間", Modifier.padding(horizontal = 16.dp))
                        slot.syncSpans.forEach { span -> AppText("${formatTime(span.sourceStart)}–${formatTime(span.sourceEnd)} → ${formatTime(span.playbackStart)}", Modifier.padding(horizontal = 16.dp), localize = false, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                AppText(if (sourceSlots.any { it.fromArchive }) "保存済みコメントを使用" else "オンライン取得", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium)
                AppText("この取得元の手動補正", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    FilledTonalIconButton({ model.offset(source, -1000) }) { AppIcon(Icons.Filled.Remove, "1秒早める") }
                    OutlinedButton({ model.offset(source, null) }) { Column(horizontalAlignment = Alignment.CenterHorizontally) { AppText("%+.1f 秒".format((offsets[source] ?: 0L) / 1000.0)); AppText("リセット", style = MaterialTheme.typography.labelMedium) } }
                    FilledTonalIconButton({ model.offset(source, 1000) }) { AppIcon(Icons.Filled.Add, "1秒遅らせる") }
                }
                val markers = markerOptions[source].orEmpty()
                if (markers.isNotEmpty()) {
                    PreferenceHint("今の動画位置に合わせる")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { listOf("OP", "B", "ED").forEach { name -> markers[name]?.let { candidate -> OutlinedButton({ model.alignMarker(candidate) }) { AppText(name, localize = false) } } } }
                }
            } }
            if (section != "sync") CommentSyncStatus(missing, unavailable = true)
            PreferenceHint("CM補正はコメントからの推定です。時刻補正は取得元ごとに適用します")
        }
        OutlinedButton(close, Modifier.align(Alignment.End)) { AppText("閉じる") }
    }
}

@Composable fun ScopedNgEditor(model: ConvenienceViewModel) {
    val revision by model.revision.collectAsStateWithLifecycle()
    val words = remember(revision) { model.ngText("global") }
    NgWordsEditor(words) { model.ng("global", it) }
}

@Composable fun ResumeViewing(model: ConvenienceViewModel, open: (StreamingService) -> Unit) {
    val o by model.options.collectAsStateWithLifecycle()
    val h = o.history
    if (h.title.isBlank()) return
    val service = StreamingService.entries.firstOrNull { it.name == h.service } ?: return
    PreferenceSection("続きから見る")
    Box(Modifier.padding(horizontal = 16.dp)) {
        ListItem(headlineContent = { AppText(h.title, localize = false, maxLines = 2) }, supportingContent = { AppText("${formatTime(h.position)} / ${formatTime(h.duration)} · ${service.label}", localize = false) }, leadingContent = { AppIcon(Icons.Filled.History, null) }, trailingContent = { IconButton({ if (!model.resume(h)) open(service) }) { AppIcon(Icons.Filled.PlayArrow, "視聴アプリを開く") } })
    }
}

@Composable fun ErrorActions(status: String, retry: () -> Unit, manual: () -> Unit, settings: (String) -> Unit) {
    val failure = acquisitionFailure(status)
    val target = failure.settingsPage.orEmpty()
    if (AcquisitionFailure.entries.any { status.startsWith(it.label + ":") } || listOf("失敗", "見つかりません", "ありません", "特定できません", "HTTP", "timeout", "Unable").any { status.contains(it, true) }) {
        AppText(failure.explanation, style = MaterialTheme.typography.bodyMedium)
        FlowRow(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(retry) { AppText("再試行") }
            OutlinedButton(if (target.isEmpty()) manual else { { settings(target) } }) { AppText(if (target.isEmpty()) "手動で選ぶ" else "設定を確認") }
        }
    }
}
