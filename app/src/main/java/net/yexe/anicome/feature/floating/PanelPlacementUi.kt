package net.yexe.anicome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable internal fun PanelPlacementEditor(options: FloatingPanelOptions, place: (PanelCorner, Boolean) -> Unit, reset: () -> Unit, initialLandscape: Boolean = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
    var landscape by rememberSaveable(initialLandscape) { mutableStateOf(initialLandscape) }
    var subtitles by rememberSaveable { mutableStateOf("bottom") }
    val position = settledPanelPosition(if (landscape) options.landscapeX else options.x, if (landscape) options.landscapeY else options.y, false)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppText("配置の目安です。字幕の位置は自動判定しません。視聴アプリに合わせて選んでください。")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!landscape, { landscape = false }, label = { AppText("縦画面") })
            FilterChip(landscape, { landscape = true }, label = { AppText("横画面") })
        }
        AppText("字幕が表示される位置", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("bottom" to "下", "top" to "上", "none" to "字幕なし").forEach { (value, title) -> FilterChip(subtitles == value, { subtitles = value }, label = { AppText(title) }) }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            BoxWithConstraints(Modifier.width(if (landscape) 240.dp else 140.dp).height(if (landscape) 135.dp else 220.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                if (subtitles != "none") Box(Modifier.fillMaxWidth().height(32.dp).align(if (subtitles == "top") Alignment.TopCenter else Alignment.BottomCenter).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .1f)), contentAlignment = Alignment.Center) {
                    AppIcon(Icons.Filled.Subtitles, "字幕が表示される位置", Modifier.size(24.dp))
                }
                Surface(Modifier.offset(x = (maxWidth - 40.dp).coerceAtLeast(0.dp) * position.x, y = (maxHeight - 32.dp).coerceAtLeast(0.dp) * position.y).size(40.dp, 32.dp), color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                    Box(contentAlignment = Alignment.Center) { AppIcon(Icons.Filled.PictureInPictureAlt, "パネルの位置", Modifier.size(24.dp)) }
                }
            }
        }
        AppText("字幕の反対側に配置", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            subtitleSafeCorners(subtitles).forEach { corner ->
                FilterChip(position == corner.position, { place(corner, landscape) }, label = { AppText(corner.title) })
            }
        }
        OutlinedButton(reset) { AppIcon(Icons.Filled.MyLocation, null); Spacer(Modifier.width(8.dp)); AppText("パネルの位置を戻す") }
        AppText("位置を戻すと、縦・横画面とも収納して右側の初期位置へ移動します。", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable internal fun FloatingPanelRestoreAction(model: FloatingPanelViewModel?) {
    val active = model?.active?.collectAsStateWithLifecycle()?.value ?: false
    if (active) OutlinedButton({ model?.resetPosition() }) { AppIcon(Icons.Filled.MyLocation, null); Spacer(Modifier.width(8.dp)); AppText("パネルの位置を戻す") }
}
