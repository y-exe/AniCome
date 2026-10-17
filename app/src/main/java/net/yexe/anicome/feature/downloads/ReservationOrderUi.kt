package net.yexe.anicome

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.*
import androidx.work.WorkInfo
import java.util.UUID

fun reservationWaiting(work: WorkInfo) = reservationManaged(work) && !work.state.isFinished && (reservationPaused(work) || work.progress.getInt("reservationOrder", 0) > 0)
@Composable internal fun ReservationOrderContent(works: List<WorkInfo>, change: (UUID, String) -> Unit) {
    val waiting = works.filter(::reservationWaiting).sortedBy { it.progress.getLong("reservationRank", 0L) }
    Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * .85f).dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppText("保存順を変更", style = MaterialTheme.typography.titleLarge)
        AppText("処理中の話が終わると、この順に保存します。通信・充電条件を満たす予約から処理します。")
        if (waiting.isEmpty()) AppText("順番を変更できる予約はありません")
        LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(waiting, key = { _, work -> work.id }) { position, work ->
                val title = reservationEpisode(work.tags)?.let { "${it.series} 第${work.progress.getInt("sourceNumber", it.number)}話" }.orEmpty()
                val topLabel = "${uiText("先頭へ移動")}: $title"
                val upLabel = "${uiText("1つ上へ移動")}: $title"
                val downLabel = "${uiText("1つ下へ移動")}: $title"
                SettingsCard {
                    AppText(title, localize = false, style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        AppText(if (reservationPaused(work)) "一時停止中" else "保存順：${work.progress.getInt("reservationOrder", position + 1)}番", Modifier.weight(1f))
                        IconButton({ change(work.id, "first") }, Modifier.semantics { contentDescription = topLabel }, enabled = position > 0) { AppIcon(Icons.Filled.VerticalAlignTop, null) }
                        IconButton({ change(work.id, "up") }, Modifier.semantics { contentDescription = upLabel }, enabled = position > 0) { AppIcon(Icons.Filled.ArrowUpward, null) }
                        IconButton({ change(work.id, "down") }, Modifier.semantics { contentDescription = downLabel }, enabled = position < waiting.lastIndex) { AppIcon(Icons.Filled.ArrowDownward, null) }
                    }
                }
            }
        }
    }
}
