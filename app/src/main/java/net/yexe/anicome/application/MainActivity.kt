@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package net.yexe.anicome

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.viewmodel.ext.android.viewModel

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.ui.graphics.luminance
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.*
import androidx.compose.foundation.shape.CircleShape
import android.widget.ImageView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@kotlinx.serialization.Serializable
internal enum class AppTab(val label: String) : androidx.navigation3.runtime.NavKey { HOME("ホーム"), DOWNLOADS("保存"), SETTINGS("設定") }

class MainActivity : androidx.fragment.app.FragmentActivity() {
    private val lockModel: AppLockViewModel by viewModel()
    private val advancedModel: AdvancedSettingsViewModel by viewModel()
    private val updatesModel: UpdatesViewModel by viewModel()
    private val trafficModel: TrafficBudgetViewModel by viewModel()
    private val storageModel: StorageViewModel by viewModel()
    private var requestedSettingsPage by mutableStateOf<String?>(null)
    private var biometricAvailable by mutableStateOf(false)
    private lateinit var biometricPrompt: androidx.biometric.BiometricPrompt
    private val appModel: AppViewModel by viewModel()
    private val serviceArtworkModel: ServiceArtworkViewModel by viewModel()
    private val setupModel: SetupViewModel by viewModel()
    private var notificationsAllowed by mutableStateOf(false)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { notificationsAllowed = it }
    private val niconicoLogin = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        aniComeApp.refreshNiconicoLogin()
        if (aniComeApp.niconicoLinked.value) aniComeApp.retryCurrent()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        if (intent.getBooleanExtra("open_updates", false)) { requestedSettingsPage = "アップデート"; intent.removeExtra("open_updates") }
        biometricPrompt = androidx.biometric.BiometricPrompt(this, androidx.core.content.ContextCompat.getMainExecutor(this), object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) { lockModel.biometricSucceeded() }
            override fun onAuthenticationError(code: Int, message: CharSequence) { if (code != androidx.biometric.BiometricPrompt.ERROR_USER_CANCELED && code != androidx.biometric.BiometricPrompt.ERROR_NEGATIVE_BUTTON) lockModel.biometricError(message.toString()) }
        })
        setContent {
            val rootState by appModel.state.collectAsStateWithLifecycle()
            val serviceArtwork by serviceArtworkModel.state.collectAsStateWithLifecycle()
            val introCompleted by appModel.introCompleted.collectAsStateWithLifecycle()
            val options by advancedModel.state.collectAsStateWithLifecycle()
            val access by lockModel.state.collectAsStateWithLifecycle()
            var confidential by remember { mutableStateOf(false) }
            val secure = !options.loaded || !access.loaded || access.locked || options.secureScreen || confidential
            SideEffect { if (secure) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
            val convenience: ConvenienceViewModel = org.koin.androidx.compose.koinViewModel()
            CompositionLocalProvider(LocalServiceArtwork provides serviceArtwork, LocalConvenience provides convenience, LocalAdvancedSettings provides options, LocalAppLock provides lockModel, LocalUpdatesModel provides updatesModel, LocalStorageModel provides storageModel, LocalTrafficBudgetModel provides trafficModel,
                LocalConfidentialInput provides { confidential = it }, LocalBiometricAvailable provides biometricAvailable, LocalBiometric provides {
                    biometricPrompt.authenticate(androidx.biometric.BiometricPrompt.PromptInfo.Builder().setTitle(this@MainActivity.uiString("AniComeのロックを解除"))
                        .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG).setNegativeButtonText(this@MainActivity.uiString("${this@MainActivity.uiString(lockModeLabel(access.mode))}を使用")).build())
                }) {
            AniComeTheme(rootState.theme) { ArtworkProvider {
                val lightBars = MaterialTheme.colorScheme.surfaceContainer.luminance() > .5f
                SideEffect {
                    androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightNavigationBars = lightBars
                        isAppearanceLightStatusBars = lightBars
                    }
                }
                StartupTransition((!introCompleted || !rootState.ready) && rootState.error.isBlank(), appModel::finishIntro) {
                if (rootState.ready) AppAccessGate(lockModel) { Root() } else Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        if (rootState.error.isBlank()) BrandLogo(Modifier.size(160.dp), animated = true) else {
                            AppText(rootState.error, style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = appModel.controller::start) { AppText("再試行") }
                        }
                    }
                }
                }
            } }
            }
        }
    }
    override fun onStart() {
        super.onStart()
        biometricAvailable = androidx.biometric.BiometricManager.from(this).canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); if (intent.getBooleanExtra("open_updates", false)) requestedSettingsPage = "アップデート" }
    private fun listenerAllowed() = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        ?.split(':')?.any { ComponentName.unflattenFromString(it) == ComponentName(this, net.yexe.anicome.PlaybackListener::class.java) } == true

    @Composable private fun Root() {
        val setup by setupModel.state.collectAsStateWithLifecycle()
        var overlay by remember { mutableStateOf(Settings.canDrawOverlays(this)) }
        var listener by remember { mutableStateOf(listenerAllowed()) }
        val linked by aniComeApp.niconicoLinked.collectAsStateWithLifecycle()
        val completed by aniComeApp.setupCompleted.collectAsStateWithLifecycle()
        val lifecycleOwner = LocalLifecycleOwner.current
        LaunchedEffect(setup.loaded) { if (setup.loaded) setupModel.resumed(listener, overlay) }
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    notificationsAllowed = Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    overlay = Settings.canDrawOverlays(this@MainActivity)
                    listener = listenerAllowed()
                    aniComeApp.refreshNiconicoLogin()
                    setupModel.resumed(listener, overlay)
                }
                if (event == Lifecycle.Event.ON_PAUSE) setupModel.background()
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        if (!setup.loaded) Surface(Modifier.fillMaxSize()) {} else if (setup.finished && initialSetupReady(overlay, listener, completed, linked)) AppShell() else Surface(Modifier.fillMaxSize()) {
            SetupScreen(listener, overlay,
                onListener = { setupModel.requested(1); startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                onOverlay = { setupModel.requested(2); startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) },
                onHelp = { setupModel.help(if (setup.step == 2) 2 else 1) }, linked = linked, needsLogin = setup.needsLogin,
                onLogin = { niconicoLogin.launch(Intent(this, NiconicoFullscreenLoginActivity::class.java)) }, currentStep = setup.step,
                notificationsAllowed = notificationsAllowed, onNotifications = { if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) },
                onNext = { setupModel.next(listener, overlay, linked) }, onBack = setupModel::back,
                helpStep = setup.help, dismissHelp = { setupModel.help(null) }, onAppInfo = {
                    setupModel.help(null)
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }, posters = setup.posters)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun AppShell() {
        val requested by Session.overlayRequested.collectAsStateWithLifecycle()
        LaunchedEffect(requested) {
            if (requested && Settings.canDrawOverlays(this@MainActivity) && !Session.overlay.value) startForegroundService(Intent(this@MainActivity, net.yexe.anicome.OverlayService::class.java))
        }
        val backStack = androidx.navigation3.runtime.rememberNavBackStack(AppTab.HOME)
        val tab = backStack.last() as AppTab
        fun select(destination: AppTab) {
            if (destination == AppTab.SETTINGS) { if (tab != destination) backStack.add(destination) }
            else { while (backStack.size > 1) backStack.removeLastOrNull(); if (destination != AppTab.HOME) backStack.add(destination) }
        }
        LaunchedEffect(requestedSettingsPage) { if (requestedSettingsPage != null) select(AppTab.HOME) }
        val bar = LocalReferenceBar.current
        LaunchedEffect(tab) { bar?.state?.heightOffset = 0f }
        val snackbar = remember { SnackbarHostState() }
        val message by Session.message.collectAsStateWithLifecycle()
        val convenience = LocalConvenience.current
        val currentLanguage = LocalUiLanguage.current
        LaunchedEffect(message, currentLanguage) { if (message.isNotBlank()) { snackbar.showSnackbar(UiTranslations.text(message, currentLanguage)); if (Session.message.value == message) Session.message.value = "" } }
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
                val pushMotion = detailMotion(false)
                val popMotion = detailMotion(true)
                val forwardMotion = tabMotion(true)
                val backwardMotion = tabMotion(false)
                androidx.navigation3.ui.NavDisplay(backStack = backStack,
                    onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                    entryDecorators = listOf(androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator(), androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator()),
                    transitionSpec = { if (tab == AppTab.SETTINGS) pushMotion else forwardMotion },
                    popTransitionSpec = { if (initialState.entries.lastOrNull()?.metadata?.get("detail") == true) popMotion else backwardMotion },
                    predictivePopTransitionSpec = { if (initialState.entries.lastOrNull()?.metadata?.get("detail") == true) popMotion else backwardMotion },
                    entryProvider = androidx.navigation3.runtime.entryProvider {
                        entry(AppTab.HOME) {
                            SettingsScreen(model = org.koin.androidx.compose.koinViewModel(), advancedModel = advancedModel, panelModel = org.koin.androidx.compose.koinViewModel(), home = true,
                                onOffline = { select(AppTab.DOWNLOADS) }, toggleOverlay = ::toggleComments, openService = ::openViewingApp,
                                requestedPage = requestedSettingsPage, onRequestConsumed = { requestedSettingsPage = null }) {
                                niconicoLogin.launch(Intent(this@MainActivity, NiconicoLoginActivity::class.java))
                            }
                        }
                        entry(AppTab.DOWNLOADS) { DownloadsPanel(onSettings = { page -> requestedSettingsPage = page; select(AppTab.SETTINGS) }) { backStack.removeLastOrNull() } }
                        entry(AppTab.SETTINGS, metadata = mapOf("detail" to true)) {
                            SettingsScreen(model = org.koin.androidx.compose.koinViewModel(), advancedModel = advancedModel, panelModel = org.koin.androidx.compose.koinViewModel(), requestedPage = requestedSettingsPage,
                                onRequestConsumed = { requestedSettingsPage = null }, onClose = { backStack.removeLastOrNull() }, toggleOverlay = ::toggleComments, openService = ::openViewingApp) {
                                niconicoLogin.launch(Intent(this@MainActivity, NiconicoLoginActivity::class.java))
                            }
                        }
                    })
            }
        }
    }
    private fun toggleComments() {
        appModel.controller.setCommentDisplayEnabled(!Session.overlay.value)
        if (Session.overlay.value) stopService(Intent(this, net.yexe.anicome.OverlayService::class.java)) else {
            if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            startForegroundService(Intent(this, net.yexe.anicome.OverlayService::class.java))
        }
    }
    private fun openViewingApp(service: StreamingService) {
        packageManager.getLaunchIntentForPackage(service.packageName)?.let(::startActivity) ?: run {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${service.packageName}"))) }
            catch (_: android.content.ActivityNotFoundException) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${service.packageName}"))) }
        }
    }
}
@Composable internal fun StartupTransition(showStartup: Boolean, onFinished: () -> Unit, content: @Composable () -> Unit) {
    val reduced = LocalAdvancedSettings.current.reducedMotion
    val shift = with(androidx.compose.ui.platform.LocalDensity.current) { 28.dp.roundToPx() }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AnimatedVisibility(!showStartup,
            enter = fadeIn(tween(if (reduced) 0 else 480, delayMillis = if (reduced) 0 else 80, easing = FastOutSlowInEasing)) +
                scaleIn(tween(if (reduced) 0 else 620, easing = FastOutSlowInEasing), initialScale = if (reduced) 1f else .96f) +
                slideInVertically(tween(if (reduced) 0 else 620, easing = FastOutSlowInEasing)) { if (reduced) 0 else shift },
            exit = ExitTransition.None) { Box(Modifier.fillMaxSize()) { content() } }
        AnimatedVisibility(showStartup, enter = EnterTransition.None,
            exit = fadeOut(tween(if (reduced) 0 else 360, easing = FastOutSlowInEasing)) +
                scaleOut(tween(if (reduced) 0 else 420, easing = FastOutSlowInEasing), targetScale = if (reduced) 1f else .94f) +
                slideOutVertically(tween(if (reduced) 0 else 420, easing = FastOutSlowInEasing)) { if (reduced) 0 else -shift / 2 }) { StartupScreen(onFinished) }
    }
}

@Composable internal fun StartupScreen(onFinished: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
        StartupBrand(onFinished)
    }
}

@Composable internal fun ServiceSelector(selected: StreamingService, select: (StreamingService) -> Unit) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    StudioRow(modifier = Modifier.motionClickable { choosing = true }, headlineContent = { AppText("視聴アプリ") }, supportingContent = { AppText(selected.label) }, leadingContent = { ServiceAppIcon(selected, 24.dp) })
    if (choosing) ServiceSelectionSheet(selected, { select(it); choosing = false }) { choosing = false }
}
@Composable internal fun ServiceSelectionSheet(selected: StreamingService, select: (StreamingService) -> Unit, dismiss: () -> Unit) {
    StudioSheet(onDismissRequest = dismiss) { ServiceSelectionContent(selected, select) }
}
@Composable internal fun ServiceSelectionContent(selected: StreamingService, select: (StreamingService) -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        AppText("視聴アプリを選ぶ", style = MaterialTheme.typography.headlineSmall)
        AppText("コメントを表示する動画アプリを選択してください。", style = MaterialTheme.typography.bodyLarge)
        ReferenceGroup {
            StreamingService.displayOrder.forEach { service ->
                ListItem(headlineContent = { AppText(service.label, style = MaterialTheme.typography.titleMedium) }, leadingContent = { ServiceAppIcon(service, 40.dp) }, trailingContent = { RadioButton(service == selected, onClick = null) }, colors = ListItemDefaults.colors(containerColor = if (service == selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.selectable(service == selected, role = Role.RadioButton) { select(service) })
            }
        }
    }
}

@Composable internal fun ServiceAppIcon(service: StreamingService, size: androidx.compose.ui.unit.Dp = 26.dp) {
    coil3.compose.AsyncImage(LocalServiceArtwork.current[service], uiText(service.label), Modifier.size(size), contentScale = androidx.compose.ui.layout.ContentScale.Fit)

}
@Composable internal fun ManualPlaybackSheet(dismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.aniComeApp
    val current by Session.manualPlayback.collectAsStateWithLifecycle()
    val parsed = current?.let { EpisodeMatcher.parse(it.title, it.subtitle) }
    var series by rememberSaveable { mutableStateOf(parsed?.series.orEmpty()) }
    var episode by rememberSaveable { mutableStateOf(parsed?.number?.toString() ?: "1") }
    var position by rememberSaveable { mutableStateOf(current?.position?.div(1000)?.toString() ?: "0") }
    var duration by rememberSaveable { mutableStateOf(current?.duration?.div(1000)?.takeIf { it > 0 }?.toString().orEmpty()) }
    var playing by rememberSaveable { mutableStateOf(current?.playing ?: true) }
    var error by rememberSaveable { mutableStateOf("") }
    StudioSheet(onDismissRequest = dismiss) {
        StudioFormLayout(if (current == null) "手動再生を設定" else "手動再生を更新", actions = {
            if (error.isNotBlank()) AppText(error, color = MaterialTheme.colorScheme.error)
            StudioButton(onClick = {
                val number = episode.toIntOrNull()
                val at = position.toLongOrNull() ?: 0
                val length = duration.toLongOrNull() ?: 0
                if (series.trim().length < 2 || number == null || number !in 1..9999 || at < 0 || length < 0 || (length > 0 && at > length)) error = "作品名・話数・再生位置を確認してください"
                else {
                    if (current == null) app.beginManualPlayback(series.trim(), number, at, length, playing)
                    else app.updateManualPlayback(at, length, playing)
                    dismiss()
                }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                AppIcon(Icons.Filled.PlayCircle, null)
                Spacer(Modifier.width(8.dp))
                AppText(if (current == null) "手動再生を開始" else "再生位置を更新")
            }
        }) {
            AppText("再生情報を公開しないサービスでも、コメントの時刻を手動で合わせられます。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (current != null) StudioField(series, {}, Modifier.fillMaxWidth(), label = { AppText("作品名") }, supportingText = { AppText("作品を変える場合は手動再生を終了してください") }, readOnly = true, singleLine = true)
            else AnimeSearchField(series, { series = it.take(300); error = "" })

            StudioField(episode, { episode = it.filter(Char::isDigit).take(4); error = "" }, Modifier.fillMaxWidth(), label = { AppText("話数") }, readOnly = current != null, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number), singleLine = true)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StudioField(position, { position = it.filter(Char::isDigit).take(7); error = "" }, Modifier.fillMaxWidth(), label = { AppText("再生位置（秒）") }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number), singleLine = true)
                StudioField(duration, { duration = it.filter(Char::isDigit).take(7); error = "" }, Modifier.fillMaxWidth(), label = { AppText("長さ（秒・任意）") }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number), singleLine = true)
            }
            SwitchRow("再生中", playing, { playing = it })

        }
    }
}
@Composable internal fun ManualCommentSheet(initialQuery: String = "", initialEpisode: Episode? = null, initialVideoId: String = "", dismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.aniComeApp
    val detectedPlayback by Session.playback.collectAsStateWithLifecycle()
    val manualPlayback by Session.manualPlayback.collectAsStateWithLifecycle()
    val playback = manualPlayback ?: detectedPlayback
    val parsed = remember(playback.title, playback.subtitle) { EpisodeMatcher.parse(playback.title, playback.subtitle) }
    var series by rememberSaveable { mutableStateOf(initialEpisode?.series ?: EpisodeMatcher.parse(initialQuery)?.series ?: initialQuery.ifBlank { parsed?.series ?: playback.searchTitle.ifBlank { playback.title } }) }
    var episode by rememberSaveable { mutableStateOf((initialEpisode?.number ?: EpisodeMatcher.parse(initialQuery)?.number ?: parsed?.number ?: 1).toString()) }
    var videoId by rememberSaveable { mutableStateOf(initialVideoId.ifBlank { niconicoVideoId(initialQuery).orEmpty() }) }
    var exactQuery by rememberSaveable { mutableStateOf(initialQuery) }
    val model: ManualCommentViewModel = org.koin.androidx.compose.koinViewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val candidates = state.candidates
    val searching = state.searching
    val loadingId = state.loadingId
    var validation by remember { mutableStateOf("") }
    val error = validation.ifBlank { state.error }
    var initialized by rememberSaveable { mutableStateOf(false) }
    val close = { initialized = false; model.reset(); dismiss() }
    LaunchedEffect(Unit) {
        if (!initialized) {
            model.reset()
            if (series.isNotBlank()) model.search(series, episode.toIntOrNull() ?: 1, videoId, exactQuery)
            initialized = true
        }
    }
    LaunchedEffect(state.completed) { if (state.completed) close() }
    StudioSheet(onDismissRequest = close) {
        StudioFormLayout("手動でコメント取得", actions = {
            if (error.isNotBlank()) AppText(error, color = MaterialTheme.colorScheme.error)
            StudioButton(onClick = {
                val number = episode.toIntOrNull()
                if (series.isBlank() || number == null || number !in 1..9999) validation = "作品名と話数を入力してください"
                else model.search(series, number, videoId, exactQuery)
            }, enabled = !searching && loadingId == null && series.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                if (searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else AppIcon(Icons.Filled.Search, null)
                Spacer(Modifier.width(8.dp)); AppText(if (videoId.isBlank()) "作品名で検索" else "動画IDを使用")
            }
        }) {
            StudioField(series, { series = it.take(300); validation = "" }, Modifier.fillMaxWidth(), label = { AppText("作品名") }, singleLine = true)
            StudioField(episode, { episode = it.filter(Char::isDigit).take(4); validation = "" }, Modifier.fillMaxWidth(), label = { AppText("話数") }, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number), singleLine = true)
            StudioField(exactQuery, { exactQuery = it.take(300); validation = "" }, Modifier.fillMaxWidth(), label = { AppText("検索語（任意）") }, singleLine = true)
            StudioField(videoId, { videoId = it.take(100); validation = "" }, Modifier.fillMaxWidth(), label = { AppText("ニコニコ動画IDまたはURL（任意）") }, singleLine = true)
            candidates.take(8).forEach { candidate ->
                StudioRow(modifier = Modifier.motionClickable(enabled = loadingId == null) {
                    val number = episode.toIntOrNull() ?: return@motionClickable
                    model.obtain(series, number, candidate)
                }, headlineContent = { AppText(candidate.title, localize = false, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }, supportingContent = { AppText("${candidate.id} · ${candidate.commentCount}件") }, trailingContent = { if (loadingId == candidate.id) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) else AppIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) })
            }
            if (candidates.isNotEmpty()) AppText("候補を選択してください", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else if (!searching && error.isBlank()) AppText("候補がありません。別の作品名で検索してください。")
        }
    }
}
internal fun formatTime(ms: Long) = "%02d:%02d".format(ms / 60000, ms / 1000 % 60)
