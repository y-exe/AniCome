package net.yexe.anicome

import android.app.*
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.lifecycle.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.savedstate.*
import kotlinx.coroutines.*
import org.koin.android.ext.android.getKoin

open class FloatingPanelService : Service(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    private val savedController = SavedStateRegistryController.create(this)
    override val savedStateRegistry get() = savedController.savedStateRegistry
    override val viewModelStore = ViewModelStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository by lazy { getKoin().get<FloatingPanelRepository>() }
    private val windows by lazy { getSystemService(WindowManager::class.java) }
    private var view: ComposeView? = null
    private var confirmation: Dialog? = null
    private var params: WindowManager.LayoutParams? = null
    private var normalizedX = 1f
    private var normalizedY = .3f
    private val snapMotion = PanelSnapMotion(AndroidPanelAnimator())
    private val positionWrites = PanelPositionWrites()
    private var positionOrientation: Boolean? = null
    private var dragging = false
    private var landscape by mutableStateOf(false)
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate(); savedController.performAttach(); savedController.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop" || !Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("floating_panel", uiString("フローティング表示"), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, net.yexe.anicome.FloatingPanelService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        startForeground(2, Notification.Builder(this, "floating_panel").setSmallIcon(R.drawable.ic_notification).setContentTitle("AniCome")
            .setContentText(uiString("フローティング表示を使用中")).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, uiString("パネルを閉じる"), stop).build()).build())
        if (view != null) return START_NOT_STICKY
        try {
            landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            normalizedX = repository.options.value.x; normalizedY = repository.options.value.y
            val panel = ComposeView(this)
            if (android.os.Build.VERSION.SDK_INT >= 29) panel.isForceDarkAllowed = false
            panel.setViewTreeLifecycleOwner(this); panel.setViewTreeViewModelStoreOwner(this); panel.setViewTreeSavedStateRegistryOwner(this)
            panel.setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            val layout = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT)
            layout.gravity = Gravity.TOP or Gravity.LEFT
            if (getKoin().get<AdvancedSettingsRepository>().state.value.secureScreen) layout.flags = layout.flags or WindowManager.LayoutParams.FLAG_SECURE
            params = layout; view = panel
            panel.setContent {
                val model = androidx.lifecycle.viewmodel.compose.viewModel<FloatingPanelViewModel>(factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>) = FloatingPanelViewModel(repository) as T
                })
                val state by model.state.collectAsStateWithLifecycle()
                val options by model.options.collectAsStateWithLifecycle()
                val theme by aniComeApp.themeMode.collectAsStateWithLifecycle()
                val advanced by getKoin().get<AdvancedSettingsRepository>().state.collectAsStateWithLifecycle()
                var collapsed by remember { mutableStateOf(options.startCollapsed) }
                val coordinates = if (landscape) PanelPosition(options.landscapeX, options.landscapeY) else PanelPosition(options.x, options.y)
                val revision = if (landscape) options.landscapePositionRevision else options.positionRevision
                LaunchedEffect(landscape, coordinates, revision) {
                    val rotated = positionOrientation != landscape; positionOrientation = landscape
                    val localWrite = positionWrites.observed(revision, landscape)
                    if (rotated || (!localWrite && !dragging)) { stopSnap(); normalizedX = coordinates.x; normalizedY = coordinates.y; reposition() }
                }
                LaunchedEffect(landscape, options.startCollapsed, options.landscapeCollapsed) { collapsed = repository.orientationOptions(landscape).startCollapsed }
                LaunchedEffect(Unit) {
                    repository.placements.collect { request ->
                        request.forOrientation(landscape)?.let { position -> dragging = false; stopSnap(); normalizedX = position.x; normalizedY = position.y; reposition() }
                        request.collapsed?.let { collapsed = it }
                    }
                }
                CompositionLocalProvider(LocalAdvancedSettings provides advanced) { AniComeTheme(theme) {
                    Box(Modifier.padding(8.dp).heightIn(max = (resources.displayMetrics.heightPixels / resources.displayMetrics.density - 100).coerceAtLeast(120f).dp)) {
                        FloatingPanelContent(state, options, collapsed, { collapsed = !collapsed; repository.rememberCollapsed(collapsed, landscape) }, model::close, model::openApp, model::offset,
                            drag = { x, y -> moveBy(x, y) }, dragEnd = { finishDrag() }, changePosition = model::commentPosition, requestClose = { showConfirmation(model) },
                            animationAlignment = if (normalizedX >= .5f) androidx.compose.ui.Alignment.TopEnd else androidx.compose.ui.Alignment.TopStart,
                            tools = { section -> showConfirmation(model, section) }, toggleComments = { repository.toggleComments() }, resumeComments = model::resumeComments)
                    }
                } }
            }
            panel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> reposition() }
            windows.addView(panel, layout); registry.currentState = Lifecycle.State.RESUMED; repository.active.value = true
            scope.launch {
                getKoin().get<AdvancedSettingsRepository>().state.collect { options ->
                    layout.flags = if (options.secureScreen) layout.flags or WindowManager.LayoutParams.FLAG_SECURE else layout.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
                    if (panel.isAttachedToWindow) runCatching { windows.updateViewLayout(panel, layout) }.onFailure { stopSelf() }
                }
            }
            scope.launch { getKoin().get<AppLockRepository>().state.collect { access -> if (!access.loaded || (access.mode != "none" && access.locked)) confirmation?.dismiss() } }
            scope.launch {
                Session.overlay.collect { enabled ->
                    if (enabled && panel.isAttachedToWindow) runCatching {
                        windows.removeViewImmediate(panel)
                        windows.addView(panel, layout)
                    }.onFailure { stopSelf() }
                }
            }
        } catch (_: Exception) { Session.message.value = "パネルを表示できませんでした。重ね合わせの権限を確認してください"; stopSelf() }
        return START_NOT_STICKY
    }
    private fun showConfirmation(model: FloatingPanelViewModel, section: String? = null) {
        if (section != null && getKoin().get<AppLockRepository>().state.value.let { !it.loaded || (it.mode != "none" && it.locked) }) return
        if (confirmation?.isShowing == true) return
        val content = ComposeView(this)
        content.setViewTreeLifecycleOwner(this)
        content.setViewTreeViewModelStoreOwner(this)
        content.setViewTreeSavedStateRegistryOwner(this)
        if (android.os.Build.VERSION.SDK_INT >= 29) content.isForceDarkAllowed = false
        val dialog = Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.window?.apply {
            setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(.32f)
            setGravity(Gravity.CENTER)
            if (getKoin().get<AdvancedSettingsRepository>().state.value.secureScreen) addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        content.setContent {
            val theme by aniComeApp.themeMode.collectAsStateWithLifecycle()
            val convenience = androidx.lifecycle.viewmodel.compose.viewModel<ConvenienceViewModel>(key = "convenience", factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>) = ConvenienceViewModel(getKoin().get()) as T
            })
            val advanced by getKoin().get<AdvancedSettingsRepository>().state.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalAdvancedSettings provides advanced) { AniComeTheme(theme) {
                if (section == null) FloatingCloseConfirmationContent({ dialog.dismiss() }, { dialog.dismiss(); model.close() })
                else if (section == "settings") FloatingPlaybackSettings(model) { dialog.dismiss() }
                else androidx.compose.material3.Surface(shape = androidx.compose.material3.MaterialTheme.shapes.extraLarge, color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh) { PlaybackTools(convenience, section, { dialog.dismiss() }) }
            } }
        }
        dialog.setContentView(content)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener { content.disposeComposition(); if (confirmation === dialog) confirmation = null }
        confirmation = dialog
        runCatching {
            dialog.show()
            val metrics = resources.displayMetrics
            val width = (metrics.widthPixels - 32 * metrics.density).coerceAtMost(440 * metrics.density).toInt()
            dialog.window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        }.onFailure { dialog.dismiss(); Session.message.value = "パネルを表示できませんでした。重ね合わせの権限を確認してください" }
    }
    private fun space(): Pair<Int, Int> {
        val metrics = resources.displayMetrics
        val bounds = if (android.os.Build.VERSION.SDK_INT >= 30) windows.currentWindowMetrics.bounds else android.graphics.Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        return (bounds.width() - (view?.width ?: 0)).coerceAtLeast(0) to (bounds.height() - (view?.height ?: 0) - (80 * metrics.density).toInt()).coerceAtLeast(0)
    }
    private fun reposition() {
        val layout = params ?: return; val panel = view ?: return
        val (x, y) = space(); val nextX = (x * normalizedX).toInt(); val nextY = (y * normalizedY).toInt()
        if (layout.x == nextX && layout.y == nextY) return
        layout.x = nextX; layout.y = nextY
        if (panel.isAttachedToWindow) runCatching { windows.updateViewLayout(panel, layout) }.onFailure { stopSelf() }
    }
    private fun moveBy(dx: Float, dy: Float) {
        dragging = true
        stopSnap()
        val layout = params ?: return; val (x, y) = space()
        normalizedX = if (x > 0) ((layout.x + dx) / x).coerceIn(0f, 1f) else 0f
        normalizedY = if (y > 0) ((layout.y + dy) / y).coerceIn(0f, 1f) else 0f
        reposition()
    }
    private fun stopSnap() = snapMotion.cancel()
    private fun finishDrag() {
        dragging = false
        val orientation = landscape
        val reduced = getKoin().get<AdvancedSettingsRepository>().state.value.reducedMotion || !android.animation.ValueAnimator.areAnimatorsEnabled()
        snapMotion.settle(normalizedX, normalizedY, repository.options.value.snapToEdge, reduced,
            frame = { position -> normalizedX = position.x; normalizedY = position.y; reposition() },
            save = { position ->
                val revision = java.util.UUID.randomUUID().toString(); positionWrites.sent(revision, orientation)
                repository.position(position.x, position.y, orientation, revision)
            })
    }

    override fun onConfigurationChanged(newConfig: Configuration) { dragging = false; stopSnap(); super.onConfigurationChanged(newConfig); landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE; view?.post { reposition() } }
    override fun onDestroy() {
        stopSnap()
        confirmation?.dismiss(); confirmation = null
        scope.cancel(); registry.currentState = Lifecycle.State.DESTROYED
        view?.let { if (it.isAttachedToWindow) runCatching { windows.removeView(it) }; it.disposeComposition() }
        viewModelStore.clear(); view = null; params = null; repository.active.value = false
        super.onDestroy()
    }
}
