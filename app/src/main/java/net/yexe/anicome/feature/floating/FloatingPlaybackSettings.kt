package net.yexe.anicome

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

@Composable internal fun FloatingPlaybackSettings(model: FloatingPanelViewModel, close: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val size by Session.size.collectAsStateWithLifecycle()
    val opacity by Session.opacity.collectAsStateWithLifecycle()
    val speed by Session.speedScale.collectAsStateWithLifecycle()
    FloatingPlaybackSettingsContent(state, size, opacity, speed, model::toggleComments, model::commentPosition, model::offset, model::displayValue, close, model::customPosition)
}

@Composable internal fun FloatingPlaybackSettingsContent(state: FloatingPanelState, size: Float, opacity: Float, speed: Float,
    toggle: () -> Unit, position: (String) -> Unit, offset: (Long?) -> Unit, display: (String, Float) -> Unit, close: () -> Unit, customPosition: (Float) -> Unit = {}) {
    val maximum = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp - 64.dp).coerceIn(180.dp, 760.dp)
    Column(Modifier.fillMaxWidth().heightIn(max = maximum), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(Modifier.weight(1f, fill = false), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ReferenceGroup { SwitchRow("コメントを表示", state.overlay, { if (it != state.overlay) toggle() }) }
        CommentPositionContent(state.position, position, state.customY, customPosition)
        SettingsCard {
            AppText("全体の手動補正", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton({ offset(1000) }) { AppIcon(Icons.Filled.Remove, "1秒早める") }
                TextButton({ offset(null) }) { AppText("%+.1f 秒".format(globalDisplayDelay(state.offset) / 1000.0)); AppText("リセット", Modifier.padding(start = 4.dp)) }
                FilledTonalIconButton({ offset(-1000) }) { AppIcon(Icons.Filled.Add, "1秒遅らせる") }
            }
        }
        SettingSlider("文字サイズ", "${size.toInt()}", size, 5f..25f, 19) { display("size", it) }
        SettingSlider("不透明度", "${(opacity * 100).roundToInt()}%", opacity, .2f..1f, 15) { display("opacity", it) }
        SettingSlider("速度", "${(speed * 100).roundToInt()}%", speed, .25f..1.5f, 24) { display("speed", it) }
        }
        }
        Button(close, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color.White, contentColor = androidx.compose.ui.graphics.Color(0xFF171717))) { AppText("閉じる") }
    }
}
