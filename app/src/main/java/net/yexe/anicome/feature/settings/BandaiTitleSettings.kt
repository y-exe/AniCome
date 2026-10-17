package net.yexe.anicome

import android.content.Intent
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class BandaiTitleViewModel(repository: BandaiTitleRepository) : ViewModel() {
    val enabled = repository.enabled
}

@Composable internal fun BandaiTitleSettings(service: StreamingService) {
    val model: BandaiTitleViewModel = org.koin.androidx.compose.koinViewModel()
    val enabled by model.enabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val prime = service == StreamingService.PRIME_VIDEO
    val danime = service == StreamingService.D_ANIME
    PreferenceAction(if (danime) "画面から再生時刻を取得" else "画面から作品名を取得", if (danime) {
        if (enabled) "操作表示の時刻で同期。シーク後は操作表示で再同期" else "dアニメの再生位置を取得。ユーザー補助でAniComeを許可"
    } else if (prime) {
        if (enabled) "操作表示で同期。非表示中の通信待ちは反映されません" else "Prime Videoの作品名と再生位置を取得。ユーザー補助でAniComeを許可"
    } else if (enabled) "バンダイチャンネルの作品名取得は有効です" else "バンダイチャンネルのみ。ユーザー補助でAniComeを許可", Icons.Filled.AccessibilityNew) {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}
