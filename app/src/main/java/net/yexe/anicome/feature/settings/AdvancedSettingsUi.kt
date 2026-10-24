package net.yexe.anicome

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

val LocalUpdatesModel = staticCompositionLocalOf<UpdatesViewModel?> { null }
val LocalStorageModel = staticCompositionLocalOf<StorageViewModel?> { null }

@Composable fun NetworkSettings(model: AdvancedSettingsViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val saving by model.saving.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    var type by remember { mutableStateOf(state.proxyType) }
    var host by remember { mutableStateOf(state.proxyHost) }
    var port by remember { mutableStateOf(state.proxyPort.toString()) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    PreferenceAction("プロキシ", when (state.proxyType) { "http" -> "HTTP · ${state.proxyHost}:${state.proxyPort}"; "socks" -> "SOCKS5 · ${state.proxyHost}:${state.proxyPort}"; else -> "使用しない" }, Icons.Filled.Router) {
        model.clearError(); type = state.proxyType; host = state.proxyHost; port = state.proxyPort.toString(); username = state.proxyCredentials.username; password = state.proxyCredentials.password; editing = true
    }
    PreferenceHint("HTTP／SOCKS5の認証に対応します。")
    TrafficBudgetSettings()
    SettingSlider("通信のタイムアウト", "${state.timeout}秒", state.timeout.toFloat(), 5f..120f, 22) { model.timeout(it.toInt()) }
    if (editing) StudioDialog({ if (!saving) editing = false }, title = { AppText("プロキシ") }, text = {
        ProxyEditorContent(type, host, port, error, saving,
            { type = it; model.clearError() }, { host = it; model.clearError() }, { port = it; model.clearError() }, username, password, { username = it; model.clearError() }, { password = it; model.clearError() })
    }, confirmButton = { TextButton({ model.proxy(type, host, if (type == "direct") "8080" else port, username, password) { editing = false } }, enabled = !saving) {
        if (saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else AppText("保存")
    } }, dismissButton = { TextButton({ editing = false }, enabled = !saving) { AppText("キャンセル") } })

}

@Composable internal fun ProxyEditorContent(type: String, host: String, port: String, error: String, saving: Boolean, changeType: (String) -> Unit, changeHost: (String) -> Unit, changePort: (String) -> Unit, username: String = "", password: String = "", changeUsername: (String) -> Unit = {}, changePassword: (String) -> Unit = {}) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ReferenceGroup { listOf("direct" to "使用しない", "http" to "HTTP", "socks" to "SOCKS5").forEach { (value, name) ->
            PreferenceChoice(name, type == value, { if (!saving) changeType(value) }, enabled = !saving)
        } }
        if (type != "direct") {
            OutlinedTextField(host, { changeHost(it.trim().take(253)) }, Modifier.fillMaxWidth(), label = { AppText("ホスト名・IPアドレス") }, singleLine = true, enabled = !saving, isError = error.isNotEmpty(), visualTransformation = rememberRoundedInput())
            OutlinedTextField(port, { changePort(it.filter(Char::isDigit).take(5)) }, Modifier.fillMaxWidth(), label = { AppText("ポート番号") }, singleLine = true, enabled = !saving, isError = error.isNotEmpty(), visualTransformation = rememberRoundedInput(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(username, { changeUsername(it.take(255)) }, Modifier.fillMaxWidth(), label = { AppText("ユーザー名（任意）") }, singleLine = true, enabled = !saving, visualTransformation = rememberRoundedInput())
            OutlinedTextField(password, { changePassword(it.take(255)) }, Modifier.fillMaxWidth(), label = { AppText("パスワード") }, singleLine = true, enabled = !saving, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        }
        if (error.isNotEmpty()) AppText(error, color = MaterialTheme.colorScheme.error)
    }
}

@Composable fun CommentPositionSettings(model: AdvancedSettingsViewModel, serviceSettings: @Composable () -> Unit = {}) {
    val state by model.state.collectAsStateWithLifecycle()
    CommentPositionContent(state.position, model::position, state.customY, model::customPosition, serviceSettings)
}

@Composable internal fun CommentPositionContent(position: String, change: (String) -> Unit, customY: Float = 1f, changeCustomY: (Float) -> Unit = {}, serviceSettings: @Composable () -> Unit = {}) {
    var localY by remember(customY) { mutableFloatStateOf(customY) }
    SettingsCard {
    AppText("表示する位置", style = MaterialTheme.typography.titleMedium)
    serviceSettings()
    AppText("縦画面では動画の位置に合わせて選んでください。横画面にも適用します。", style = MaterialTheme.typography.bodyMedium)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("top" to "上", "center" to "中央", "custom" to "カスタム").forEach { (value, label) ->
            val selected = position == value || value == "custom" && position == "bottom"
            Card(onClick = { change(value) }, Modifier.weight(1f).fillMaxHeight().semantics { this.selected = selected; role = Role.RadioButton }, colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(Modifier.width(48.dp).height(80.dp), shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                        Box(Modifier.padding(4.dp)) { Surface(Modifier.fillMaxWidth().height(20.dp).offset(y = if (value == "custom") ((localY - .5f) * 52).dp else 0.dp).align(when (value) { "top" -> Alignment.TopCenter; "custom" -> Alignment.Center; else -> Alignment.Center }), color = MaterialTheme.colorScheme.primary, shape = MaterialTheme.shapes.extraSmall) {} }
                    }
                    Spacer(Modifier.height(8.dp)); AppText(label, style = MaterialTheme.typography.labelLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center); Spacer(Modifier.height(8.dp)); Spacer(Modifier.weight(1f)); RadioButton(selected, null)
                }
            }
        }
    }
    if (position == "custom" || position == "bottom") SettingSlider("上下の位置", "%.1f%%".format(localY * 100), localY, 0f..1f, 999, animate = false, finished = { changeCustomY(localY) }, change = { localY = it })
    }
}

@Composable fun NotificationsSettings(model: AdvancedSettingsViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    PreferenceAction("Androidの通知設定", "通知の許可・音・ロック画面での表示", Icons.Filled.Notifications) {
        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }
    PreferenceSection("表示する情報")
ReferenceGroup {
    SwitchRow("取得の進捗", state.notificationProgress, { model.boolean("notificationProgress", it) }, "検索状況・取得件数・進捗バー")
    SwitchRow("コメントの件数", state.notificationCount, { model.boolean("notificationCount", it) })
    SwitchRow("再生中・待機中の状態", state.notificationPlayback, { model.boolean("notificationPlayback", it) })
    SwitchRow("取得・保存の完了", state.notificationCompletion, { model.boolean("notificationCompletion", it) })
    SwitchRow("通知の作品名を隠す", state.privateNotifications, { model.boolean("privateNotifications", it) })
}

    PreferenceHint("コメント表示中の常駐通知と停止ボタンは、表示を止めるまで残ります。")
}

@Composable fun StorageSummary(limitMb: Long) {
    val model = LocalStorageModel.current
    val state = model?.state?.collectAsStateWithLifecycle()?.value ?: StorageState()
    var clear by remember { mutableStateOf(false) }
    LaunchedEffect(model) { model?.refresh() }
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppText("%.1f MB".format(state.comments / 1048576.0), style = MaterialTheme.typography.displayMedium)
        AppText(uiText("保存済みコメント") + " · " + uiText("${state.episodes}話"), localize = false, style = MaterialTheme.typography.bodyLarge)
        if (limitMb > 0) {
            LinearProgressIndicator(progress = { (state.comments.toFloat() / (limitMb * 1048576.0)).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            AppText("上限 ${limitMb} MB")
        } else AppText("上限なし · 空き容量 %.1f GB".format(state.free / 1073741824.0))
    }
    PreferenceAction("画像キャッシュ", "%.1f MB · 保存済みコメントは削除しません".format(state.images / 1048576.0), Icons.Filled.Image) { clear = true }
    if (state.error.isNotBlank()) AppText(state.error)
    if (clear) StudioDialog({ clear = false }, title = { AppText("画像キャッシュを削除？") }, text = { AppText("作品画像を次の表示時に再取得します。保存済みコメントは残ります。") }, confirmButton = { TextButton({ model?.clearImages(); clear = false }, enabled = !state.busy) { AppText("削除") } }, dismissButton = { TextButton({ clear = false }) { AppText("キャンセル") } })
}

@Composable fun UpdatesSettings(preferences: AdvancedSettingsViewModel) {
    if (BuildConfig.FDROID) {
        val context = LocalContext.current
        StatusCard {
            AppText("AniCome ${BuildConfig.VERSION_NAME}", localize = false, style = MaterialTheme.typography.titleLarge)
            AppText("F-Droid", localize = false, style = MaterialTheme.typography.bodyLarge)
            Button({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/${context.packageName}/"))) }, Modifier.fillMaxWidth()) { AppText("F-Droid", localize = false) }
        }
        return
    }
    val options by preferences.state.collectAsStateWithLifecycle()
    val model = LocalUpdatesModel.current
    val state = model?.state?.collectAsStateWithLifecycle()?.value ?: UpdateState()
    val context = LocalContext.current
    var pendingInstall by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (pendingInstall && context.packageManager.canRequestPackageInstalls()) state.ready?.let { file -> model?.installIntent(file)?.let(context::startActivity) }
        pendingInstall = false
    }
    StatusCard {
        AppText("AniCome ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleLarge)
        AppText(state.release?.name ?: "インストール済みのバージョン", style = MaterialTheme.typography.bodyLarge)
        if (state.message.isNotBlank()) AppText(state.message)
        if (state.checking) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.downloading) { LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth()); AppText("${(state.progress * 100).toInt()}%") }
        Button({ model?.check() }, Modifier.fillMaxWidth(), enabled = model != null && !state.checking && !state.downloading) { AppText("更新を確認") }
        if (state.release != null && state.ready == null) FilledTonalButton({ model?.download() }, Modifier.fillMaxWidth(), enabled = !state.downloading) { AppText("ダウンロード") }
        state.ready?.let { file -> Button({
            if (context.packageManager.canRequestPackageInstalls()) model?.installIntent(file)?.let(context::startActivity)
            else { pendingInstall = true; permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))) }
        }, Modifier.fillMaxWidth()) { AppText("インストール") } }
    }
    if (!state.release?.notes.isNullOrBlank()) { PreferenceSection("変更内容"); AppText(state.release!!.notes, localize = false) }
    PreferenceSection("自動更新")
ReferenceGroup {
    SwitchRow("更新を自動確認", options.autoUpdate, { preferences.boolean("autoUpdate", it) }, "1日ごとにGitHub Releasesを確認")
    SwitchRow("更新を自動ダウンロード", options.autoDownload, { preferences.boolean("autoDownload", it) }, enabled = options.autoUpdate)
    SwitchRow("従量課金なしの回線のみ", options.updateWifi, { preferences.boolean("updateWifi", it) }, enabled = options.autoUpdate)
}

    PreferenceHint("インストール時はAndroidの確認画面で操作します。公開リリースがない場合は更新を取得できません。")
}
