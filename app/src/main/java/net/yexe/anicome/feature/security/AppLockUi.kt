package net.yexe.anicome

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

val LocalAppLock = staticCompositionLocalOf<AppLockViewModel?> { null }
val LocalBiometric = staticCompositionLocalOf<() -> Unit> { {} }
val LocalBiometricAvailable = staticCompositionLocalOf { false }
val LocalConfidentialInput = staticCompositionLocalOf<(Boolean) -> Unit> { {} }
fun lockModeLabel(mode: String) = when (mode) { "pin" -> "PIN"; "password" -> "パスワード"; "pattern" -> "パターン"; else -> "なし" }

@Composable fun AppAccessGate(model: AppLockViewModel, content: @Composable () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    if (!state.loaded) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    else if (!state.locked) content()
    else {
        var value by remember { mutableStateOf("") }
        val error by model.error.collectAsStateWithLifecycle()
        val busy by model.busy.collectAsStateWithLifecycle()
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(state.retryAt) { now = System.currentTimeMillis(); while (now < state.retryAt) { delay(1000); now = System.currentTimeMillis() } }
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(40.dp))
            AppText("AniCome", style = MaterialTheme.typography.headlineLarge)
            AppText("${uiText(lockModeLabel(state.mode))}を入力", style = MaterialTheme.typography.titleLarge)
            LockCredentialInput(state.mode, value, { value = it; model.clearError() })
            if (error.isNotEmpty()) AppText(error, color = MaterialTheme.colorScheme.error)
            val waiting = state.retryAt > now
            if (waiting) AppText("${(state.retryAt - now + 999) / 1000}秒後に再試行できます")
            Button({ model.verify(value) }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !busy && !waiting && value.isNotEmpty()) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else AppText("ロックを解除")
            }
            if (state.biometric && LocalBiometricAvailable.current) OutlinedButton(LocalBiometric.current, Modifier.fillMaxWidth(), enabled = !busy) { AppIcon(Icons.Filled.Fingerprint, null); Spacer(Modifier.width(8.dp)); AppText("指紋認証を使用") }
        }
    }
}

@Composable fun LockCredentialInput(mode: String, value: String, change: (String) -> Unit, enabled: Boolean = true) {
    if (mode == "pattern") PatternInput(value, change, enabled)
    else OutlinedTextField(value, { change(if (mode == "pin") it.filter { ch -> ch in '0'..'9' }.take(12) else it.take(128)) }, Modifier.fillMaxWidth(),
        label = { AppText(lockModeLabel(mode)) }, singleLine = true, enabled = enabled, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = if (mode == "pin") KeyboardType.NumberPassword else KeyboardType.Password))
}

@Composable fun PatternInput(value: String, change: (String) -> Unit, enabled: Boolean = true) {
    var nodes by remember { mutableStateOf<List<Int>>(emptyList()) }
    val latestChange by rememberUpdatedState(change)
    LaunchedEffect(value) { if (value.isEmpty()) nodes = emptyList() }
    val color = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxWidth().widthIn(max = 320.dp).aspectRatio(1f).pointerInput(enabled) {
        if (!enabled) return@pointerInput
        fun add(position: Offset) {
            val cell = size.width / 3f
            val x = (position.x / cell).toInt(); val y = (position.y / cell).toInt()
            if (x !in 0..2 || y !in 0..2) return
            val center = Offset((x + .5f) * cell, (y + .5f) * cell)
            if ((position - center).getDistance() > cell * .42f) return
            nodes = LockCredentials.extendPattern(nodes, y * 3 + x); latestChange(nodes.joinToString(","))
        }
        detectDragGestures(onDragStart = { nodes = emptyList(); add(it) }, onDrag = { event, _ -> event.consume(); add(event.position) })
    }) {
        Canvas(Modifier.fillMaxSize()) {
            val cell = size.width / 3
            fun center(node: Int) = Offset((node % 3 + .5f) * cell, (node / 3 + .5f) * cell)
            nodes.zipWithNext().forEach { (a, b) -> drawLine(color.copy(alpha = .6f), center(a), center(b), 4.dp.toPx()) }
            (0..8).forEach { drawCircle(if (it in nodes) color else color.copy(alpha = .4f), if (it in nodes) 12.dp.toPx() else 8.dp.toPx(), center(it)) }
        }
        Column(Modifier.fillMaxSize()) {
            repeat(3) { y -> Row(Modifier.weight(1f)) {
                repeat(3) { x -> val node = y * 3 + x
                    Box(Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = "パターンの点${node + 1}" }.clickable(enabled = enabled) { nodes = LockCredentials.extendPattern(nodes, node); change(nodes.joinToString(",")) })
                }
            } }
        }
    }
    OutlinedButton({ nodes = emptyList(); change("") }, enabled = enabled) { AppText("描き直す") }
}

@Composable fun AppSecuritySettings(advanced: AdvancedSettingsViewModel) {
    val model = LocalAppLock.current
    val options by advanced.state.collectAsStateWithLifecycle()
    var operation by remember { mutableStateOf<String?>(null) }
    if (model != null) {
        val access by model.state.collectAsStateWithLifecycle()
        ReferenceGroup {
            PreferenceAction("起動時のロック", if (access.mode == "none") "設定されていません" else lockModeLabel(access.mode) + if (access.biometric) "・指紋認証" else "", Icons.Filled.Fingerprint) { model.clearError(); operation = "change" }
            if (access.mode != "none") PreferenceAction("ロックを解除する", "現在の認証が必要です", Icons.Filled.Fingerprint) { model.clearError(); operation = "disable" }
        }
        if (access.mode != "none") {
            PreferenceSection("再ロックまでの時間")
ReferenceGroup {
            listOf(0 to "すぐに", 15 to "15秒", 60 to "1分", 300 to "5分").forEach { (seconds, label) ->
                PreferenceChoice(label, access.timeout == seconds, { model.timeout(seconds) })
            }
}

        }
        operation?.let { LockEditor(model, it) { operation = null } }
    } else AppText("PIN・パスワード・パターンに、指紋認証を追加できます。")
    PreferenceSection("画面と通知の保護")
ReferenceGroup {
    SwitchRow("画面の撮影を防ぐ", options.secureScreen, { advanced.boolean("secureScreen", it) }, "スクリーンショット・録画・履歴のプレビューを保護")
    SwitchRow("通知の作品名を隠す", options.privateNotifications, { advanced.boolean("privateNotifications", it) })
}

}

@Composable private fun LockEditor(model: AppLockViewModel, operation: String, close: () -> Unit) {
    val access by model.state.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    var stage by remember { mutableIntStateOf(if (access.mode == "none") 1 else 0) }
    var mode by remember { mutableStateOf(access.mode.takeIf { it != "none" } ?: "pin") }
    var input by remember { mutableStateOf("") }
    var first by remember { mutableStateOf("") }
    var biometric by remember { mutableStateOf(access.biometric) }
    var validation by remember { mutableStateOf("") }
    val biometricAvailable = LocalBiometricAvailable.current
    val protect = LocalConfidentialInput.current
    DisposableEffect(Unit) { protect(true); onDispose { protect(false) } }
    StudioSheet(close, secure = true, dismissEnabled = !busy) {
        StudioFormLayout(if (operation == "disable") "ロックを解除" else "ロックを設定", actions = {
            if (error.isNotEmpty() || validation.isNotEmpty()) AppText(validation.ifEmpty { error }, color = MaterialTheme.colorScheme.error)
            Button({
                when (stage) {
                    0 -> if (operation == "disable") model.remove(input, close) else model.verify(input) { input = ""; stage = 1 }
                    1 -> if (!LockCredentials.valid(mode, input)) validation = "入力の長さを確認してください" else { first = input; input = ""; stage = 2 }
                    else -> if (input != first) { validation = "入力が一致しません"; input = "" } else model.configure(mode, input, biometric && biometricAvailable, close)
                }
            }, Modifier.fillMaxWidth(), enabled = !busy && input.isNotEmpty()) { if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else AppText(if (stage == 2) "保存" else "続ける") }
            OutlinedButton(close, Modifier.fillMaxWidth(), enabled = !busy) { AppText("キャンセル") }
        }) {
            AppText(if (stage == 0) "現在の${uiText(lockModeLabel(access.mode))}を確認します" else if (stage == 2) "確認のため、もう一度入力してください" else "ロックの種類を選んでください")
            if (stage == 1) {
                ReferenceGroup { listOf("pin", "password", "pattern").forEach { item -> PreferenceChoice(lockModeLabel(item), mode == item, { mode = item; input = ""; validation = "" }) } }
                SwitchRow("指紋認証も使用する", biometric, { biometric = it }, if (LocalBiometricAvailable.current) "登録済みの指紋などを使用" else "Android設定で生体認証を登録してください", enabled = LocalBiometricAvailable.current)
                AppText(when (mode) { "pin" -> "6〜12桁の数字"; "pattern" -> "4点以上をつないでください"; else -> "8〜128文字" })
            }
            LockCredentialInput(if (stage == 0) access.mode else mode, input, { input = it; validation = ""; model.clearError() }, enabled = !busy)

        }
    }
}
