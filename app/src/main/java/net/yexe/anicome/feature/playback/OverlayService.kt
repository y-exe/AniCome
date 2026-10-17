package net.yexe.anicome

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.WindowManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.android.ext.android.getKoin

open class OverlayService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notificationJob: Job? = null
    private var layer: CommentWebView? = null
    private val windows by lazy { getSystemService(WindowManager::class.java) }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") getKoin().get<AppCoordinator>().setCommentDisplayEnabled(false)
        if (intent?.action == "stop" || !Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        when (intent?.action) {
            "earlier" -> getKoin().get<SyncAdjustmentRepository>().global(1000)
            "later" -> getKoin().get<SyncAdjustmentRepository>().global(-1000)
        }
        startForeground(1, overlayNotification())
        if (notificationJob == null) {
            notificationJob = serviceScope.launch {
                getKoin().get<AdvancedSettingsRepository>().state.map { it.secureScreen }.distinctUntilChanged().collect { secure ->
                    layer?.let { view -> (view.layoutParams as? WindowManager.LayoutParams)?.let { params ->
                        params.flags = if (secure) params.flags or WindowManager.LayoutParams.FLAG_SECURE else params.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
                        windows.updateViewLayout(view, params)
                    } }
                }
            }
        }
        if (layer == null) {
            try {
                Diagnostics.renderer.value = "描画ページ読み込み中"
                val view = CommentWebView(this)
                val params = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT)
                 
                if (getKoin().get<AdvancedSettingsRepository>().state.value.secureScreen) params.flags = params.flags or WindowManager.LayoutParams.FLAG_SECURE
                params.alpha = 1f
                windows.addView(view, params)
                layer = view
                Session.overlay.value = true
            } catch (_: Exception) { Session.message.value = "重ね合わせ表示を開始できませんでした。権限を確認してください"; stopSelf() }
        }
        return START_NOT_STICKY
    }
    private fun overlayNotification(): Notification = getKoin().get<CommentNotifications>().overlay()
    override fun onDestroy() {
        serviceScope.cancel()
        layer?.let {
            windows.removeView(it)
            it.release()
        }
        layer = null
        Session.overlay.value = false
        super.onDestroy()
    }
}
