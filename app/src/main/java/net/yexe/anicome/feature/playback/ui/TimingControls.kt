package net.yexe.anicome

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable fun TimingControls(model: ConvenienceViewModel? = LocalConvenience.current) {
    val offset by Session.offset.collectAsStateWithLifecycle()
    DisposableEffect(model) { onDispose { model?.finishGlobalOffset() } }
    SettingsCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppText("全体の手動補正", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            AppText("%+.1f 秒".format(globalDisplayDelay(offset) / 1000.0), style = MaterialTheme.typography.titleMedium)
        }
        AppText("正の値は遅く、負の値は早く表示します。", style = MaterialTheme.typography.bodyMedium)
        StudioSlider(globalDisplayDelay(offset).coerceIn(-15000, 15000).toFloat(), { model?.previewGlobalOffset(-it.toLong()) },
            Modifier.semantics { contentDescription = "全体のコメント時刻" }, valueRange = -15000f..15000f, steps = 59, enabled = model != null,
            onValueChangeFinished = { model?.finishGlobalOffset() })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { AppText("−15秒"); AppText("+15秒") }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            StudioButton({ model?.globalOffset(1000) }, Modifier.weight(1f), enabled = model != null, quiet = true, contentPadding = PaddingValues(8.dp)) { AppText("−1秒") }
            StudioButton({ model?.globalOffset(null) }, Modifier.weight(1f).semantics { contentDescription = "全体の補正を0秒に戻す" }, enabled = model != null, quiet = true, contentPadding = PaddingValues(8.dp)) { AppText("0秒") }
            StudioButton({ model?.globalOffset(-1000) }, Modifier.weight(1f), enabled = model != null, quiet = true, contentPadding = PaddingValues(8.dp)) { AppText("+1秒") }
        }
    }
}
