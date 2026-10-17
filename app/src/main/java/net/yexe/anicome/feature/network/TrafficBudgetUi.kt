package net.yexe.anicome

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

val LocalTrafficBudgetModel = staticCompositionLocalOf<TrafficBudgetViewModel?> { null }
internal fun trafficLimitInput(value: String): Long? = value.toLongOrNull()?.takeIf { it in 1..Long.MAX_VALUE / 1048576L }

@Composable fun TrafficBudgetSettings() {
    val model = LocalTrafficBudgetModel.current ?: return
    val state by model.state.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    TrafficBudgetContent(state, busy, error, { editing = true }, { resetting = true })
    if (editing) TrafficBudgetEditor(state.policy, busy, error, { editing = false }) { limit, period -> model.configure(limit, period) { editing = false } }
    if (resetting) StudioDialog(onDismissRequest = { if (!busy) resetting = false }, title = { AppText("使用量をリセット") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppText("今の期間の使用量を0に戻します。上限で待機している保存予約は再開します。保存済みコメントは削除しません。")
            if (error.isNotBlank()) AppText(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton({ model.reset { resetting = false } }, enabled = !busy) { AppText("リセット") } },
        dismissButton = { TextButton({ resetting = false }, enabled = !busy) { AppText("キャンセル") } })
}

@Composable internal fun TrafficBudgetContent(state: TrafficBudgetState, busy: Boolean, error: String, edit: () -> Unit, reset: () -> Unit) {
    SettingsCard {
        AppText("API通信量", style = MaterialTheme.typography.titleMedium)
        when {
            state.error.isNotBlank() -> AppText(state.error, color = MaterialTheme.colorScheme.error)
            !state.loaded -> StudioProgress(Modifier.fillMaxWidth())
            else -> {
                AppText(if (state.policy.period == "month") "今月の使用量" else "今日の使用量", style = MaterialTheme.typography.bodyMedium)
                AppText("%.2f MB".format(state.used / 1048576.0), style = MaterialTheme.typography.headlineMedium)
                if (state.policy.limitBytes > 0) {
                    StudioProgress({ (state.used.toDouble() / state.policy.limitBytes).toFloat().coerceIn(0f, 1f) })
                    AppText("上限：${state.policy.limitMb} MB")
                    if (state.reached) AppText("上限に達したため新しい取得を待機しています", color = MaterialTheme.colorScheme.error)
                } else AppText("上限なし")
                AppText(uiText("次のリセット") + "：" + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(state.resetsAt)), localize = false, style = MaterialTheme.typography.bodyMedium)
            }
        }
        AppText("コメント取得・検索の通信量です。画像や動画は含みません。", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(edit, enabled = state.loaded && !busy) { AppText("通信量の上限") }
            OutlinedButton(reset, enabled = state.loaded && !busy && state.used > 0L) { AppText("使用量をリセット") }
        }
        if (error.isNotBlank()) AppText(error, color = MaterialTheme.colorScheme.error)
    }
}

@Composable internal fun TrafficBudgetEditor(policy: TrafficPolicy, busy: Boolean, error: String, dismiss: () -> Unit, save: (Long, String) -> Unit) {
    var limited by remember { mutableStateOf(policy.limitMb > 0L) }
    var limit by remember { mutableStateOf(policy.limitMb.takeIf { it > 0L }?.toString().orEmpty()) }
    var period by remember { mutableStateOf(policy.period) }
    StudioDialog(onDismissRequest = { if (!busy) dismiss() }, title = { AppText("通信量の上限") }, text = {
        TrafficBudgetEditorContent(limited, limit, period, busy, error, { limited = it }, { limit = it }, { period = it })
    }, confirmButton = { TrafficBudgetSaveButton(limited, limit, period, busy, save) },
        dismissButton = { TextButton(dismiss, enabled = !busy) { AppText("キャンセル") } })
}

@Composable internal fun TrafficBudgetSaveButton(limited: Boolean, limit: String, period: String, busy: Boolean, save: (Long, String) -> Unit) {
    val parsed = trafficLimitInput(limit)
    TextButton({ save(if (limited) parsed!! else 0L, period) }, enabled = !busy && (!limited || parsed != null)) { AppText("保存") }
}
@Composable internal fun TrafficBudgetEditorContent(limited: Boolean, limit: String, period: String, busy: Boolean, error: String,
    limitMode: (Boolean) -> Unit, changeLimit: (String) -> Unit, changePeriod: (String) -> Unit) {
    val parsed = trafficLimitInput(limit)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ReferenceGroup {
            PreferenceChoice("上限なし", !limited, { limitMode(false) }, enabled = !busy)
            PreferenceChoice("上限を設定", limited, { limitMode(true) }, enabled = !busy)
        }
        ReferenceGroup {
            PreferenceChoice("1日ごと", period == "day", { changePeriod("day") }, enabled = !busy)
            PreferenceChoice("1か月ごと", period == "month", { changePeriod("month") }, enabled = !busy)
        }
        if (limited) OutlinedTextField(limit, { changeLimit(it.filter(Char::isDigit).take(13)) }, Modifier.fillMaxWidth(), label = { AppText("上限（MB）") }, singleLine = true,
            isError = limit.isNotEmpty() && parsed == null, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), visualTransformation = rememberRoundedInput(),
            supportingText = { AppText("1 MB以上の整数を入力してください") })
        AppText("端末の時刻で日付・月が変わると使用量をリセットし、待機中の保存を再開します。上限を変更しても使用量は消えません。")
        if (error.isNotBlank()) AppText(error, color = MaterialTheme.colorScheme.error)
    }
}
