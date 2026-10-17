package net.yexe.anicome

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.*
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable internal fun FloatingPanelContent(state: FloatingPanelState, options: FloatingPanelOptions, collapsed: Boolean, collapse: () -> Unit,
    close: () -> Unit, open: () -> Unit, offset: (Long?) -> Unit, drag: (Float, Float) -> Unit = { _, _ -> }, dragEnd: () -> Unit = {}, changePosition: (String) -> Unit = {}, confirmingClose: Boolean = false, requestClose: (() -> Unit)? = null, animationAlignment: Alignment = Alignment.TopStart, tools: ((String) -> Unit)? = null, toggleComments: (() -> Unit)? = null, resumeComments: (() -> Unit)? = null) {
    var closeRequested by remember { mutableStateOf(confirmingClose) }
    val openDescription = uiText("パネルを開く")
    val dragDescription = uiText("ドラッグして移動")
    val handle = Modifier.pointerInput(Unit) {
        detectDragGestures(onDragEnd = dragEnd, onDragCancel = dragEnd) { change, delta -> change.consume(); drag(delta.x, delta.y) }
    }
    if (closeRequested) androidx.compose.ui.window.Dialog(onDismissRequest = { closeRequested = false }) {
        FloatingCloseConfirmationContent({ closeRequested = false }, { closeRequested = false; close() })
    }
    val reduced = LocalAdvancedSettings.current.reducedMotion
    AnimatedContent(collapsed, contentAlignment = animationAlignment, transitionSpec = {
        (fadeIn(tween(if (reduced) 0 else 160)) + scaleIn(tween(if (reduced) 0 else 160), initialScale = .97f)) togetherWith
            fadeOut(tween(if (reduced) 0 else 80)) using null
    }, label = "floatingPanel") { compact ->
        Surface(shape = if (compact) androidx.compose.foundation.shape.CircleShape else MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = options.opacity).compositeOver(MaterialTheme.colorScheme.surface),
            contentColor = MaterialTheme.colorScheme.onSurface, shadowElevation = 6.dp) {
            if (compact) Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
            IconButton(collapse, handle.size(64.dp).semantics { contentDescription = openDescription }) { BrandLogo(Modifier.size(36.dp), cropped = true) }
            if (state.busy) CircularProgressIndicator(Modifier.size(58.dp), strokeWidth = 2.dp)
        } else Column(Modifier.width(280.dp).heightIn(max = 340.dp).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(handle.weight(1f).heightIn(min = 48.dp).padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    BrandLogo(Modifier.size(21.dp).semantics { contentDescription = dragDescription }, cropped = true); Spacer(Modifier.width(4.dp))
                    AppText("AniCome", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!state.locked && tools != null) IconButton({ tools("settings") }) { AppIcon(Icons.Filled.Settings, "コメント表示の設定") }
                IconButton({ if (requestClose != null) requestClose() else closeRequested = true }) { AppIcon(Icons.Filled.Close, "パネルを閉じる") }
                IconButton(collapse) { AppIcon(Icons.Filled.ExpandLess, "収納する") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AppText(state.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                if (state.fromArchive) AppIcon(Icons.Filled.DownloadDone, "保存済みコメントを使用", Modifier.size(20.dp))
            }
            if (options.showTitle && state.title.isNotBlank()) AppText(state.title, localize = false, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            if (state.total > 0) {
                LinearProgressIndicator(progress = { (state.current.toFloat() / state.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                if (options.showCount) AppText(if (state.fetching) "${state.current} / ${state.total}件取得中" else "再生済み ${state.current} / ${state.total}件", modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            } else if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                if (options.showCount) AppText("${state.current}件のコメント", modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            }
            if (state.locked) TextButton(open, Modifier.fillMaxWidth()) { AppIcon(Icons.Filled.Lock, null); Spacer(Modifier.width(6.dp)); AppText("アプリでロックを解除") }

        }
        }
    }
}

@Composable internal fun FloatingCloseConfirmationContent(dismiss: () -> Unit, confirm: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface, tonalElevation = 6.dp) {
        Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AppText("パネルを閉じますか？", style = MaterialTheme.typography.headlineSmall)
            AppText("フローティング表示を終了します。", style = MaterialTheme.typography.bodyMedium)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(dismiss) { AppText("キャンセル") }
                TextButton(confirm) { AppText("閉じる") }
            }
        }
    }
}
@Composable fun FloatingPanelSettings(model: FloatingPanelViewModel?) {
    val options = model?.options?.collectAsStateWithLifecycle()?.value ?: FloatingPanelOptions()
    val active = model?.active?.collectAsStateWithLifecycle()?.value ?: false
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { model?.show() }
    SwitchRow("フローティング表示", active, { enabled ->
        if (enabled) { if (Settings.canDrawOverlays(context)) model?.show() else permission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) }
        else model?.close()
    }, "アプリを閉じても取得状況と再生件数を確認できます")
    val state = model?.state?.collectAsStateWithLifecycle()?.value ?: FloatingPanelState()
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        FloatingPanelContent(FloatingPanelState(label = "コメント再生中", current = 240, total = 1000, overlay = true, position = state.position), options, false, {}, {}, {}, {}, changePosition = { model?.commentPosition(it) }, tools = {}, toggleComments = {})
    }
    PreferenceHint("上部をドラッグして移動、矢印で収納できます。件数は現在の再生位置までのコメント数です。")
    SwitchRow("画面端に吸着", options.snapToEdge, { model?.boolean("panelSnap", it) }, "ドラッグを離すと、近い方の左右の端へ移動します。")
    PreferenceSection("パネルに表示する情報")
    ReferenceGroup {
    SwitchRow("最初は収納して表示", options.startCollapsed, { model?.boolean("panelCollapsed", it) })
    SwitchRow("作品名を表示", options.showTitle, { model?.boolean("panelTitle", it) })
    SwitchRow("コメント件数を表示", options.showCount, { model?.boolean("panelCount", it) })
    }
    SettingSlider("パネルの不透明度", "${(options.opacity * 100).toInt()}%", options.opacity, .6f..1f, 7) { model?.opacity(it) }
}
