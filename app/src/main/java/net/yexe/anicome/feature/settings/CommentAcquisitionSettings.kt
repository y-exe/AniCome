package net.yexe.anicome

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun CommentAcquisitionSettings(amount: Int, countMode: Boolean, countLimit: Int, linked: Boolean,
    setAmount: (Int) -> Unit, setMode: (Boolean) -> Unit, setCount: (Int) -> Unit, login: () -> Unit) {
    val displayed = if (countMode) countLimit else if (linked) amount else 1
    var input by rememberSaveable(displayed, countMode) { mutableStateOf(displayed.toString()) }
    var hadFocus by remember { mutableStateOf(false) }
    var sliding by remember(amount, linked) { mutableFloatStateOf(if (linked) amount.coerceIn(1, 20).toFloat() else 1f) }
    val focus = LocalFocusManager.current
    val value = input.toIntOrNull()?.takeIf { it >= 1 }
    fun apply() { value?.let { if (countMode) setCount(it) else setAmount(it) } }
    SettingsCard {
        AppText("取得量", style = MaterialTheme.typography.titleMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("倍率", "最大件数").forEachIndexed { index, title ->
                SegmentedButton(selected = countMode == (index == 1), onClick = { focus.clearFocus(); setMode(index == 1) },
                    shape = SegmentedButtonDefaults.itemShape(index, 2), label = { AppText(title) })
            }
        }
        OutlinedTextField(value = input, onValueChange = { text -> if (text.all(Char::isDigit)) input = text },
            label = { AppText(if (countMode) "合計の最大件数" else "取得倍率") }, suffix = { AppText(if (countMode) "件" else "倍") },
            enabled = countMode || linked, singleLine = true, isError = value == null,
            supportingText = { if (value == null) AppText("1以上の整数を入力してください") else if (!countMode) AppText("20倍を超える値は数値で指定できます。") },
            trailingIcon = { if (value != null && value != displayed) StudioIconButton({ apply(); focus.clearFocus() }) { AppIcon(Icons.Filled.Check, "適用") } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { apply(); focus.clearFocus() }),
            modifier = Modifier.fillMaxWidth().onFocusChanged { state -> if (hadFocus && !state.isFocused) apply(); hadFocus = state.isFocused })
        if (!countMode) {
            Slider(sliding, { sliding = it }, enabled = linked, valueRange = 1f..20f, steps = 18,
                onValueChangeFinished = { setAmount(sliding.roundToInt()); input = sliding.roundToInt().toString() })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { AppText("1倍"); AppText("20倍") }
        } else PreferenceHint("選択した取得元の合計です。件数に届くまで取得し、残りがなければ終了します。")
        if (countMode && (value ?: countLimit) > 20000 || !countMode && (value ?: amount) > 20) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppIcon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                AppText("取得量が多いと、通信・メモリ使用量が増え、動作が重くなる場合があります。", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (!linked) {
            PreferenceHint("過去のコメントを追加取得するにはニコニコへのログインが必要です。")
            OutlinedButton(login, Modifier.fillMaxWidth()) { AppText("ニコニコ連携を開く") }
        }
    }
}
