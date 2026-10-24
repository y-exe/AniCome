package net.yexe.anicome

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.horizontalScroll
import kotlin.math.roundToInt
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import coil3.compose.AsyncImage
import coil3.request.crossfade

@Composable private fun SettingsLink(title: String, detail: String, icon: ImageVector, click: () -> Unit) {
    PreferenceAction(title, detail, icon, click)
}
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun SettingsScreen(initialPage: String = "", model: SettingsViewModel? = null, advancedModel: AdvancedSettingsViewModel? = null, requestedPage: String? = null, onRequestConsumed: () -> Unit = {}, onClose: (() -> Unit)? = null, home: Boolean = false, onOffline: () -> Unit = {}, toggleOverlay: () -> Unit = {}, openService: (StreamingService) -> Unit = {}, panelModel: FloatingPanelViewModel? = null, login: () -> Unit) {
    val app = model?.controller ?: LocalContext.current.aniComeApp
    var restoredPage by rememberSaveable { mutableStateOf(initialPage) }
    val settings = model ?: remember(app) { SettingsViewModel(app, initialPage = restoredPage) }
    SideEffect { settings.initializePage(restoredPage) }
    val page by settings.page.collectAsStateWithLifecycle()
    val goingBack by settings.backNavigation.collectAsStateWithLifecycle()
    LaunchedEffect(page) { restoredPage = page }
    val advanced = advancedModel ?: remember { AdvancedSettingsViewModel(AdvancedSettingsRepository(null)) }
    val options by advanced.state.collectAsStateWithLifecycle()
    val profile by settings.profile.collectAsStateWithLifecycle()
    LaunchedEffect(requestedPage) { if (requestedPage != null) { settings.openPage(requestedPage); onRequestConsumed() } }
    val bar = LocalReferenceBar.current
    LaunchedEffect(page) { bar?.state?.heightOffset = 0f }
    BackHandler(page.isNotEmpty() || onClose != null) { if (settings.page.value.isNotEmpty()) settings.backPage() else onClose?.invoke() }
    val size by Session.size.collectAsStateWithLifecycle(); val opacity by Session.opacity.collectAsStateWithLifecycle()
    val speed by Session.speedScale.collectAsStateWithLifecycle()
    val fps by Session.fps.collectAsStateWithLifecycle(); val color by Session.colorStyle.collectAsStateWithLifecycle()
    val font by Session.fontStyle.collectAsStateWithLifecycle()
    val ngWords by Session.ngWords.collectAsStateWithLifecycle(); val amount by app.commentAmount.collectAsStateWithLifecycle()
    val profileId by NiconicoLogin.profileId.collectAsStateWithLifecycle()
    val linked by app.niconicoLinked.collectAsStateWithLifecycle(); val wifi by app.wifiOnly.collectAsStateWithLifecycle()
    val watch by app.watchDownloads.collectAsStateWithLifecycle(); val batch by app.batchLimit.collectAsStateWithLifecycle()
    val theme by app.themeMode.collectAsStateWithLifecycle()
    val darkTheme = theme == "dark" || (theme == "system" && androidx.compose.foundation.isSystemInDarkTheme())
    val limitMb by app.storageLimitMb.collectAsStateWithLifecycle()
    var capacityDialog by rememberSaveable { mutableStateOf(false) }
    var capacityEnabled by rememberSaveable { mutableStateOf(false) }
    var capacityInput by rememberSaveable { mutableStateOf("") }
    var capacityError by rememberSaveable { mutableStateOf("") }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsQuery by rememberSaveable { mutableStateOf("") }
    var choosingLanguage by rememberSaveable { mutableStateOf(false) }
    val selectedLanguage by app.language.collectAsStateWithLifecycle()
    var unlink by remember { mutableStateOf(false) }
    val context = LocalContext.current
    fun openLink(url: String) { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        val pushMotion = detailMotion(false)
        val popMotion = detailMotion(true)
        AnimatedContent(page, transitionSpec = { if (goingBack || targetState.isEmpty() || targetState == settingsParent(initialState)) popMotion else pushMotion }, label = "settingsPage") { currentPage ->
            CollapsingPage(Modifier.fillMaxSize().then(if (currentPage != page) Modifier.clearAndSetSemantics {} else Modifier)) {
        StudioHeader(title = { if (home && currentPage.isEmpty()) BrandWordmark(LocalHeaderCompact.current) else AppText(when (currentPage) { "タイミング" -> "時刻補正"; "コメント取得" -> "ニコニコ連携"; "ダウンロード" -> "ストレージ"; "テーマ" -> "外観"; else -> currentPage.ifEmpty { "設定" } }) }, navigationIcon = { if (currentPage.isNotEmpty() || onClose != null) StudioIconButton({ if (settings.page.value.isNotEmpty()) settings.backPage() else onClose?.invoke() }) { AppIcon(Icons.AutoMirrored.Filled.ArrowBack, uiText("戻る")) } }, actions = { StudioIconButton({ settings.setThemeMode(if (darkTheme) "light" else "dark") }) { AppIcon(if (darkTheme) Icons.Filled.LightMode else Icons.Filled.DarkMode, uiText("テーマを切り替え")) }; StudioIconButton({ choosingLanguage = true }) { AppIcon(Icons.Filled.Translate, uiText("言語を選ぶ")) } }, expandedHeight = if (currentPage == "アプリ詳細") 64.dp else if (home && currentPage.isEmpty()) 144.dp else 170.dp, titleBottomPadding = if (home && currentPage.isEmpty()) 12.dp else 24.dp)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 8.dp, bottom = if (home && currentPage.isEmpty()) 112.dp else 32.dp), verticalArrangement = Arrangement.spacedBy(if (currentPage.isEmpty()) 0.dp else 8.dp)) {
            when (currentPage) {
                "" -> {
                    HomeViewingCard(app, toggleOverlay, openService, panelModel?.state?.collectAsStateWithLifecycle()?.value)
                    Spacer(Modifier.height(16.dp))
                    FloatingPanelRestoreAction(panelModel)
                    StudioSearchField(settingsQuery, { settingsQuery = it.take(128) }, Modifier.padding(bottom = 20.dp))
                    val displaying by Session.overlay.collectAsStateWithLifecycle()
                    SettingsHomeList(linked, limitMb, settingsQuery, displaying, toggleOverlay) { settings.openPage(it) }
                }
                "その他" -> SettingsCategoryList(otherSettings(linked, limitMb)) { settings.openPage(it) }
                "アプリ詳細" -> AboutSettings(::openLink) { settings.openPage("OSSライセンス") }
                "コメント表示" -> {
                    PreferenceAction("コメントの取得元", "公式・dアニメ・実況・生放送", Icons.Filled.Forum) { settings.openPage("取得元") }
                    CommentPreviewAction()
                    CommentPositionSettings(advanced) { ServiceProfileSettings(LocalConvenience.current, app) }
                    PreferenceSection("文字と動き")
                    ReferenceGroup {
                    SettingSlider("文字サイズ", "${size.toInt()}", size, 5f..25f, 19, change = settings::setCommentSize)
                    SettingSlider("不透明度", "${(opacity * 100).roundToInt()}%", opacity, .2f..1f, 15, change = settings::setOpacity)
                    SettingSlider("速度", "${(speed * 100).roundToInt()}%", speed, .25f..1.5f, 24, change = settings::setSpeedScale)
                    }
                    val countMode by app.commentCountMode.collectAsStateWithLifecycle()
                    val countLimit by app.commentCountLimit.collectAsStateWithLifecycle()
                    CommentAcquisitionSettings(amount, countMode, countLimit, linked, settings::setCommentAmount, settings::setCommentCountMode, settings::setCommentCountLimit) { settings.openPage("コメント取得") }
                    PreferenceSection("描画")
                    SettingsCard {
                    SettingSlider("描画フレームレート", "$fps fps", fps.toFloat(), 15f..144f, 128) { settings.setFps(it.roundToInt()) }
                    PreferenceHint("実際の描画速度は端末のリフレッシュレートが上限です。")
                    }
                }
                "取得元" -> { AcquisitionSettings(app) { settings.openPage(it) }; CommentSourcesSettings(advanced); CommentSelectionSettings(LocalConvenience.current) }
                "タイミング" -> { TimingControls(); ManualPlaybackSettings(app) }
                "NGワード" -> { val convenience = LocalConvenience.current; if (convenience != null) ScopedNgEditor(convenience) else NgWordsEditor(ngWords, settings::setNgWords) }
                "ネットワーク" -> NetworkSettings(advanced)
                "通知" -> {
                    NotificationsSettings(advanced)
                    LocalConvenience.current?.let { model ->
                        val o by model.options.collectAsStateWithLifecycle()
                        PreferenceSection("通知の操作")
                        ReferenceGroup { listOf("stop" to "停止", "earlier" to "1秒早める", "later" to "1秒遅らせる").forEach { (key, label) -> SwitchRow(label, key in o.notificationActions, { model.notificationAction(key, it) }) } }
                        PreferenceHint("Androidの通知には選択した操作を最大3つ表示します")
                    }
                }
                "フローティング表示" -> FloatingPanelSettings(panelModel)
                "アップデート" -> UpdatesSettings(advanced)
                "コメント取得" -> {
                    StatusCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (linked && !profile?.icon.isNullOrBlank()) coil3.compose.SubcomposeAsyncImage(profile!!.icon, uiText("ユーザーアイコン"), Modifier.size(56.dp).clip(ReferenceClover), contentScale = androidx.compose.ui.layout.ContentScale.Crop, loading = { NiconicoBadge() }, error = { NiconicoBadge() })
                            else NiconicoBadge()
                            Column(Modifier.weight(1f)) {
                                AppText(if (linked) profile?.name?.takeIf { it.isNotBlank() } ?: uiText("ニコニコ") else uiText("ニコニコ"), localize = false, style = MaterialTheme.typography.titleMedium)
                                AppText(if (linked) (profileId ?: NiconicoLogin.userId())?.let { "ユーザーID: $it" } ?: "ログイン済み・ユーザーIDを確認できませんでした" else "ログインしていません", style = MaterialTheme.typography.bodyMedium)
                            }
                            if (linked) StudioIconButton(settings::refreshProfile) { AppIcon(Icons.Filled.Refresh, "ユーザー情報を更新") }
                        }
                        if (linked) StudioTonalButton({ unlink = true }, Modifier.fillMaxWidth()) { AppIcon(Icons.AutoMirrored.Filled.Logout, null); Spacer(Modifier.width(8.dp)); AppText("ログアウト") }
                        else StudioButton(login, Modifier.fillMaxWidth()) { AppText("ニコニコにログイン") }
                    }
                }
                "OSSライセンス" -> {
                    AppText("AniCome", style = MaterialTheme.typography.titleLarge)
                    PreferenceHint("© 2026 yexe · GNU GPL-3.0")
                    ReferenceGroup { OssNotices.forEach { entry ->
                        StudioRow(modifier = Modifier.motionClickable { notice = entry.file }, headlineContent = { AppText(entry.name) }, supportingContent = { AppText(entry.license) })
                    } }
                    PreferenceHint("作品画像とサービスロゴの権利は各権利者に帰属します。")
                    StudioTextButton({ notice = "Assets.txt" }) { AppText("画像の出典") }
                }
                "ダウンロード" -> {
                    StorageSummary(limitMb)
                    PreferenceSection("保存")
                    ReferenceGroup {
                    SettingsLink("保存容量の上限", if (limitMb == 0L) "無制限" else "${limitMb} MB", Icons.Filled.Storage) {
                        capacityEnabled = limitMb > 0; capacityInput = limitMb.takeIf { it > 0 }?.toString().orEmpty(); capacityError = ""; capacityDialog = true
                    }
                    SwitchRow("従量課金なしの回線のみ", wifi, settings::setWifiOnly, "新しい保存予約に適用")
                    }
                    StorageExtras(LocalConvenience.current)
                }
            }
            }
        }
    }
    }
    val offlineLabel = uiText("事前ダウンロード")
    if (home && page.isEmpty()) ExtendedFloatingActionButton(onClick = onOffline, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).navigationBarsPadding().widthIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp - 32).dp).clearAndSetSemantics { role = Role.Button; contentDescription = offlineLabel; onClick { onOffline(); true } }, icon = { AppIcon(Icons.Filled.Download, null) }, text = { AppText("事前ダウンロード", style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) })
    }
    if (choosingLanguage) StudioSheet({ choosingLanguage = false }) {
        LanguagePicker(selectedLanguage) { settings.setLanguage(it); choosingLanguage = false }
    }
    if (capacityDialog) StudioDialog(onDismissRequest = { capacityDialog = false }, title = { AppText("保存容量の上限") }, text = {
        StorageLimitEditorContent(capacityEnabled, capacityInput, capacityError,
            { capacityEnabled = it; capacityError = "" }, { capacityInput = it; capacityError = "" })
    }, confirmButton = { StudioTextButton({
        val value = if (capacityEnabled) capacityInput.toLongOrNull() else 0L
        if (value == null || value < 0 || (capacityEnabled && value == 0L) || value > Long.MAX_VALUE / 1048576) capacityError = "1以上の有効な容量を入力してください"
        else { settings.setStorageLimitMb(value); capacityDialog = false }
    }) { AppText("保存") } }, dismissButton = { StudioTextButton({ capacityDialog = false }) { AppText("キャンセル") } })
    notice?.let { file ->
        val language = LocalUiLanguage.current
        val state by settings.license.collectAsStateWithLifecycle()
        LaunchedEffect(file, language) { settings.readLicense(file, language) }
        StudioSheet({ notice = null }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                AppText(OssNotices.firstOrNull { it.file == file }?.name ?: "画像の出典", style = MaterialTheme.typography.titleLarge)
                if (state.loading) CircularProgressIndicator()
                else androidx.compose.foundation.text.selection.SelectionContainer { AppText(state.text, style = MaterialTheme.typography.bodyMedium, localize = state.error) }
            }
        }
    }
    if (unlink) StudioDialog(onDismissRequest = { unlink = false }, title = { AppText("ニコニコからログアウト？") }, confirmButton = { StudioTextButton({ settings.unlinkNiconico(); unlink = false }) { AppText("ログアウト") } }, dismissButton = { StudioTextButton({ unlink = false }) { AppText("キャンセル") } })
}

@Composable internal fun StorageLimitEditorContent(enabled: Boolean, input: String, error: String, changeEnabled: (Boolean) -> Unit, changeInput: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SwitchRow("上限を設定する", enabled, changeEnabled)
        if (enabled) OutlinedTextField(input, { changeInput(it.filter(Char::isDigit).take(19)) }, Modifier.fillMaxWidth(),
            visualTransformation = rememberRoundedInput(), label = { AppText("容量（MB）") }, singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
            isError = error.isNotEmpty(), supportingText = { AppText(error.ifBlank { "任意の容量を入力してください" }) })
        else AppText("端末の空き容量の範囲で保存します。", style = MaterialTheme.typography.bodyMedium)
    }
}
