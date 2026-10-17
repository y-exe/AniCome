package net.yexe.anicome

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

fun historyKindLabel(kind: String) = when (kind) { "WORK_SEARCH" -> "作品の検索"; "VIDEO_SEARCH" -> "動画の検索"; "USE" -> "保存済みから使用"; "REPLACE" -> "動画を変更"; "UPDATE" -> "コメントを更新"; else -> "新しく保存" }
fun historyRedoLabel(entry: CommentHistoryEntry) = if (entry.isSearch) "もう一度検索" else if (niconicoVideoId(entry.sourceId) != null) "もう一度取得" else "事前保存を開く"
@Composable internal fun CommentHistorySheet(series: String? = null, redo: ((CommentHistoryEntry) -> Unit)? = null, close: () -> Unit) {
    val model: CommentHistoryViewModel = org.koin.androidx.compose.koinViewModel()
    val entries by model.entries.collectAsStateWithLifecycle()
    val failed by model.failed.collectAsStateWithLifecycle()
    StudioSheet(close) {
        CommentHistoryContent(entries.filter { series == null || it.series == series }, failed, model::delete, model::clear, redo)
    }
}
@Composable internal fun CommentHistoryContent(entries: List<CommentHistoryEntry>, failed: Boolean = false, delete: (Long) -> Unit = {}, clear: () -> Unit = {}, redo: ((CommentHistoryEntry) -> Unit)? = null) {
    var kind by rememberSaveable { mutableStateOf("all") }
    var query by rememberSaveable { mutableStateOf("") }
    var detail by remember { mutableStateOf<CommentHistoryEntry?>(null) }
    var clearing by remember { mutableStateOf(false) }
    val filtered = entries.filter { (kind == "all" || (kind == "search") == it.isSearch) && listOf(it.query, it.series, it.sourceTitle).any { text -> text.contains(query.trim(), true) } }
    Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * .85f).dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            AppText("検索・取得履歴", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            IconButton({ clearing = true }, enabled = entries.isNotEmpty()) { AppIcon(Icons.Filled.DeleteSweep, "履歴をすべて削除") }
        }
        AppText("直近500件を表示します。履歴を削除してもコメントは残ります。", style = MaterialTheme.typography.bodyMedium)
        StudioSearchField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = "履歴を検索")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("all" to "すべて", "search" to "検索", "saved" to "取得・変更").forEach { (value, label) -> FilterChip(kind == value, { kind = value }, label = { AppText(label) }) }
        }
        if (failed) AppText("履歴を更新できませんでした", color = MaterialTheme.colorScheme.error)
        if (filtered.isEmpty()) AppText("履歴はありません", Modifier.padding(vertical = 16.dp))
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(filtered, key = { it.id }) { item ->
                ReferenceGroup { StudioRow(modifier = Modifier.motionClickable { detail = item },
                    headlineContent = { AppText(if (item.isSearch) item.query else item.target?.label ?: item.sourceTitle, localize = false, maxLines = 2) },
                    supportingContent = { Column {
                        AppText(historyKindLabel(item.kind))
                        if (item.isSearch) AppText(if (item.count < 0) "検索できませんでした" else "${item.count}件の候補")
                        else if (item.kind == "USE") AppText("${item.count}件")
                        else { AppText("${item.previousCount} → ${item.count}件"); AppText(if (item.storage == "cache") "自動キャッシュ" else "事前保存") }
                        AppText(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(item.time)), localize = false, style = MaterialTheme.typography.bodySmall)
                    } }, leadingContent = { AppIcon(if (item.isSearch) Icons.Filled.Search else Icons.Filled.History, null) }) }
            }
        }
    }
    detail?.let { item -> StudioDialog({ detail = null }, title = { AppText(historyKindLabel(item.kind)) }, text = {
        CommentHistoryDetail(item, redo, { delete(item.id); detail = null }) { detail = null }
    }, confirmButton = { TextButton({ detail = null }) { AppText("閉じる") } }) }
    if (clearing) StudioDialog({ clearing = false }, title = { AppText("履歴をすべて削除？") }, text = { AppText("保存済みのコメントと設定は残ります。") }, confirmButton = { TextButton({ clear(); clearing = false }) { AppText("削除") } }, dismissButton = { TextButton({ clearing = false }) { AppText("キャンセル") } })
}

@Composable internal fun CommentHistoryDetail(item: CommentHistoryEntry, redo: ((CommentHistoryEntry) -> Unit)?, delete: () -> Unit, close: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppText(if (item.isSearch) item.query else item.target?.label ?: item.sourceTitle, localize = false)
        if (item.isSearch) { item.target?.let { AppText(it.label, localize = false) }; AppText(if (item.count < 0) "検索できませんでした" else "${item.count}件の候補") }
        if (!item.isSearch) {
            AppText(item.sourceTitle, localize = false)
            AppText(if (item.kind == "USE") "${item.count}件" else "${item.previousCount} → ${item.count}件")
            if (item.previousId.isNotBlank() && item.previousId != item.sourceId) AppText("${item.previousId} → ${item.sourceId}", localize = false)
            else if (item.sourceId.isNotBlank()) AppText(item.sourceId, localize = false)
            val sources = item.sources.split(",").mapNotNull { name -> PlaybackSource.entries.find { it.name == name }?.let { uiText(it.label) } }
            if (sources.isNotEmpty()) AppText(sources.joinToString(" · "), localize = false)
        }
        if (redo != null) OutlinedButton({ close(); redo(item) }, Modifier.fillMaxWidth()) { AppText(historyRedoLabel(item)) }
        TextButton(delete) { AppText("履歴から削除") }
    }
}
