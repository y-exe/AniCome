package net.yexe.anicome

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

@Composable internal fun PanelActionEditor(actions: List<PanelAction>, change: (PanelAction, String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppText("最大4個まで選べます。上から順に、パネルの左から並びます。変更は自動保存されます。")
        ReferenceGroup {
            (actions + PanelAction.entries.filter { it !in actions }).forEach { action -> key(action) {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)) {
                    val index = actions.indexOf(action)
                    val selected = index >= 0
                    SwitchRow(action.title, selected, { change(action, if (it) "show" else "hide") }, if (selected) "左から${index + 1}番" else "表示しない", enabled = selected || actions.size < 4)
                    if (selected) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalIconButton({ change(action, "up") }, enabled = index > 0) { AppIcon(Icons.Filled.ArrowUpward, "${uiText(action.title)}：上へ移動") }
                        Spacer(Modifier.width(8.dp))
                        FilledTonalIconButton({ change(action, "down") }, enabled = index < actions.lastIndex) { AppIcon(Icons.Filled.ArrowDownward, "${uiText(action.title)}：下へ移動") }
                    }
                }
            } }
        }
        if (actions.isEmpty()) AppText("操作ボタンは非表示です。収納と終了はパネル上部から使えます。")
        OutlinedButton({ change(PanelAction.TOGGLE, "reset") }) { AppText("初期の操作ボタンに戻す") }
    }
}
