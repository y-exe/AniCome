package net.yexe.anicome

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ServicePermissionRequest(val service: StreamingService, val waiting: Boolean = false)

class ServiceSelectionViewModel(private val accessibility: BandaiTitleRepository, private val settings: SettingsRepository) : ViewModel() {
    val enabled = accessibility.enabled
    private val mutableRequest = MutableStateFlow<ServicePermissionRequest?>(null)
    val request = mutableRequest.asStateFlow()
    private fun key(service: StreamingService) = "accessibilityIntro:${service.name}"

    suspend fun choose(service: StreamingService): Boolean {
        if (service !in StreamingService.displayOrder) return false
        if (service.requiresAccessibility && (!enabled.value || !settings.load().getBoolean(key(service), false))) {
            mutableRequest.value = ServicePermissionRequest(service)
            return false
        }
        return true
    }
    fun waitForPermission() { mutableRequest.value = mutableRequest.value?.copy(waiting = true) }
    fun confirm(): StreamingService? {
        val service = mutableRequest.value?.service ?: return null
        if (!enabled.value) return null
        settings.putBoolean(key(service), true)
        cancel()
        return service
    }
    fun cancel() { mutableRequest.value = null }
}

@Composable internal fun ServiceSelectionContent(selected: StreamingService, select: (StreamingService) -> Unit) {
    val model: ServiceSelectionViewModel = org.koin.androidx.compose.koinViewModel()
    val enabled by model.enabled.collectAsStateWithLifecycle()
    val request by model.request.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var help by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(enabled, request) {
        if (enabled && request?.waiting == true) model.confirm()?.let(select)
    }
    ServiceSelectionList(selected, { service -> scope.launch { if (model.choose(service)) select(service) } }, { help = true })
    if (help) AccessibilityServiceHelp { help = false }
    request?.let { pending ->
        StudioDialog(onDismissRequest = model::cancel,
            title = { AppText("ユーザー補助が必要です") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ServiceAppIcon(pending.service, 36.dp)
                    AppText(pending.service.label, localize = false, style = MaterialTheme.typography.titleMedium)
                }
                AppText("画面の作品名や再生時刻を読み取って、コメントを同期します。")
                AppText("Androidのユーザー補助でAniComeを有効にしてください。")
                if (enabled) AppText("ユーザー補助は許可済みです。")
            },
            confirmButton = {
                StudioButton(onClick = {
                    if (enabled) model.confirm()?.let(select) else {
                        model.waitForPermission()
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                }) { AppText(if (enabled) "選択する" else "ユーザー補助を開く") }
            }, dismissButton = { StudioTextButton(model::cancel) { AppText("キャンセル") } })
    }
}

@Composable internal fun ServiceSelectionList(selected: StreamingService, select: (StreamingService) -> Unit, help: () -> Unit) {
    val helpLabel = uiText("ユーザー補助について")
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppText("視聴アプリを選ぶ", style = MaterialTheme.typography.headlineSmall)
        AppText("通常の取得方法", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        ServiceSelectionGroup(StreamingService.standardServices, selected, select)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppText("ユーザー補助で取得", style = MaterialTheme.typography.titleSmall)
            FilledTonalIconButton(onClick = help, modifier = Modifier.size(40.dp)) {
                AppIcon(Icons.AutoMirrored.Filled.Help, helpLabel, Modifier.size(22.dp))
            }
        }
        ServiceSelectionGroup(StreamingService.accessibilityServices, selected, select)
    }
}

@Composable private fun ServiceSelectionGroup(services: List<StreamingService>, selected: StreamingService, select: (StreamingService) -> Unit) {
    ReferenceGroup {
        services.forEach { service ->
            ListItem(headlineContent = { AppText(service.label, localize = false, style = MaterialTheme.typography.titleMedium) },
                leadingContent = { ServiceAppIcon(service, 36.dp) }, trailingContent = { RadioButton(service == selected, onClick = null) },
                colors = ListItemDefaults.colors(containerColor = if (service == selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.selectable(service == selected, role = Role.RadioButton) { select(service) })
        }
    }
}

@Composable internal fun AccessibilityServiceHelp(dismiss: () -> Unit) {
    StudioDialog(onDismissRequest = dismiss, title = { AppText("ユーザー補助での取得") }, text = {
        AppText("通常の再生情報では足りない情報を、画面内の文字から読み取ります。")
        listOf(
            StreamingService.PRIME_VIDEO to "作品名・話数・再生時刻",
            StreamingService.D_ANIME to "再生時刻",
            StreamingService.BANDAI_CHANNEL to "作品名・話数",
            StreamingService.HULU to "作品名・話数・再生時刻"
        ).forEach { (service, detail) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ServiceAppIcon(service, 36.dp)
                Column(Modifier.weight(1f)) {
                    AppText(service.label, localize = false, style = MaterialTheme.typography.titleMedium)
                    AppText(detail, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        AppText("必要な情報が見えるよう、再生画面の操作表示を出してください。")
        AppText("操作表示が隠れている間は時刻を推定。シーク後は操作表示で再同期します。")
        AppText("Huluは操作表示を隠した状態でも再生時刻を取得します。")
    }, confirmButton = { StudioButton(dismiss) { AppText("閉じる") } })
}
