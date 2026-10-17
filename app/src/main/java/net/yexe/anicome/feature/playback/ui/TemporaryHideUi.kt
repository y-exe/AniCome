package net.yexe.anicome

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable internal fun TemporaryHideControls(state: TemporaryCommentVisibility, hide: (String) -> Unit, full: Boolean = false) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppText(temporaryHideLabel(state), style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton({ hide("episode") }, enabled = state.canHideEpisode) { AppText("この話を隠す") }
            FilledTonalButton({ hide("none") }, enabled = state.active) { AppIcon(Icons.Filled.Visibility, null); Spacer(Modifier.width(4.dp)); AppText("再開") }
        }
        if (!full) TextButton({ expanded = !expanded }) { AppIcon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.Timer, null); Spacer(Modifier.width(4.dp)); AppText("非表示の時間を選ぶ") }
        if (full || expanded) {
            AppText("時間が経つと自動で再開します。コメントの取得・保存と動画の再生は続きます。", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("15" to "15秒", "60" to "1分", "300" to "5分").forEach { (value, title) -> OutlinedButton({ hide(value) }) { AppText(title) } }
            }
            if (!state.canHideEpisode) AppText("作品の再生を検出すると、この話だけを隠せます。", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
