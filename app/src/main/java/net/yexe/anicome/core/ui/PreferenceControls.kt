package net.yexe.anicome

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

@Composable fun SwitchRow(title: String, checked: Boolean, change: (Boolean) -> Unit, detail: String? = null, enabled: Boolean = true) {
    val label = uiText(title)
    StudioRow(modifier = Modifier.semantics { contentDescription = label }.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = change), headlineContent = { AppText(title) }, supportingContent = detail?.let { { AppText(it) } }, trailingContent = { Switch(checked, null, enabled = enabled) })
}
@Composable fun SettingSlider(title: String, label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, enabled: Boolean = true, animate: Boolean = true, finished: (() -> Unit)? = null, change: (Float) -> Unit) {
    val description = uiText(title)
    Surface(shape = if (LocalGroupedRows.current) androidx.compose.ui.graphics.RectangleShape else RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { AppText(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall); AppText(label, style = MaterialTheme.typography.titleSmall) }
            StudioSlider(value.coerceIn(range), change, Modifier.semantics { contentDescription = description }, valueRange = range, steps = steps, enabled = enabled, animate = animate, onValueChangeFinished = finished)
        }
    }

}
