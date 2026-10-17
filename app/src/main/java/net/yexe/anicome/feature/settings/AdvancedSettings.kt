package net.yexe.anicome

import androidx.datastore.preferences.core.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class AdvancedSettings(
    val proxyType: String = "direct", val proxyHost: String = "", val proxyPort: Int = 8080,
    val timeout: Int = 20, val position: String = "center", val reducedMotion: Boolean = false,
    val secureScreen: Boolean = false, val privateNotifications: Boolean = true,
    val notificationProgress: Boolean = true, val notificationCount: Boolean = true,
    val notificationPlayback: Boolean = true, val notificationCompletion: Boolean = true,
    val autoUpdate: Boolean = false, val autoDownload: Boolean = false, val updateWifi: Boolean = true, val loaded: Boolean = true, val sourceTargets: Set<PlaybackSource> = DefaultCommentSources, val autoCommentSync: Boolean = true, val syncOnlyConfirmed: Boolean = true, val customY: Float = 1f, val proxyCredentials: ProxyCredentials = ProxyCredentials()
) {
    companion object {
        fun from(p: SettingsSnapshot) = AdvancedSettings(
            p.getString("proxyType", "direct") ?: "direct", p.getString("proxyHost", "") ?: "", p.getInt("proxyPort", 8080),
            p.getInt("networkTimeout", 20).coerceIn(5, 120), p.getString("overlayPosition", "center") ?: "center",
            p.getBoolean("reducedMotion", false), p.getBoolean("secureScreen", false), p.getBoolean("privateNotifications", true),
            p.getBoolean("notificationProgress", true), p.getBoolean("notificationCount", true), p.getBoolean("notificationPlayback", true),
            p.getBoolean("notificationCompletion", true), p.getBoolean("autoUpdate", false), p.getBoolean("autoDownload", false), p.getBoolean("updateWifi", true), true, PlaybackSource.entries.filter { it.settingKey?.let { key -> p.getBoolean(key, it in DefaultCommentSources) } == true }.toSet(), p.getBoolean("autoCommentSync", true), p.getBoolean("syncOnlyConfirmed", true), p.getFloat("overlayCustomY", 1f).coerceIn(0f, 1f), ProxyCredentials(p.getString("proxyUsername", "").orEmpty(), p.getString("proxyPassword", "").orEmpty()))
    }
}
val LocalAdvancedSettings = staticCompositionLocalOf { AdvancedSettings() }
class AdvancedSettingsRepository(private val settings: SettingsRepository?, scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), private val api: ApiClient? = null) {
    val state: StateFlow<AdvancedSettings> = settings?.data?.map { AdvancedSettings.from(SettingsSnapshot(it)) }?.catch { if (it is CancellationException) throw it }
        ?.stateIn(scope, SharingStarted.Eagerly, AdvancedSettings(loaded = false)) ?: MutableStateFlow(AdvancedSettings())
    fun boolean(key: String, value: Boolean) = settings?.putBoolean(key, value)
    fun position(value: String) { require(value in listOf("top", "center", "bottom", "custom")); settings?.putString("overlayPosition", value) }
    fun customPosition(value: Float) { require(value.isFinite()); settings?.edit { p -> p[floatPreferencesKey("overlayCustomY")] = value.coerceIn(0f,1f); p[stringPreferencesKey("overlayPosition")] = "custom" } }
    fun timeout(value: Int) = settings?.putInt("networkTimeout", value.coerceIn(5, 120))
    suspend fun proxy(type: String, host: String, port: Int, credentials: ProxyCredentials = ProxyCredentials()) {
        require(type in listOf("direct", "http", "socks"))
        require(type == "direct" || (host.isNotBlank() && host.none { it.isWhitespace() || it in "/@?#" } && port in 1..65535))
        credentials.validate()
        settings?.store?.edit { it[stringPreferencesKey("proxyUsername")] = credentials.username; it[stringPreferencesKey("proxyPassword")] = credentials.password; it[stringPreferencesKey("proxyType")] = type; it[stringPreferencesKey("proxyHost")] = host.trim(); it[intPreferencesKey("proxyPort")] = port }
        api?.prepareNetwork()
    }
}
class AdvancedSettingsViewModel(private val repository: AdvancedSettingsRepository) : ViewModel() {
    val state = repository.state
    private val mutableError = MutableStateFlow("")
    val error = mutableError.asStateFlow()
    private val mutableSaving = MutableStateFlow(false)
    val saving = mutableSaving.asStateFlow()
    fun clearError() { mutableError.value = "" }
    fun boolean(key: String, value: Boolean) { repository.boolean(key, value) }
    fun position(value: String) = repository.position(value)
    fun customPosition(value: Float) = repository.customPosition(value)
    fun timeout(value: Int) = repository.timeout(value)
    fun proxy(type: String, host: String, port: String, username: String = "", password: String = "", completed: () -> Unit) {
        if (mutableSaving.value) return
        mutableSaving.value = true
        viewModelScope.launch {
            try { repository.proxy(type, host, port.toIntOrNull() ?: 0, ProxyCredentials(username, password)); mutableError.value = ""; completed() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: IllegalArgumentException) { mutableError.value = "ホスト名と1〜65535のポート番号を確認してください" }
            catch (_: Exception) { mutableError.value = "設定を保存できませんでした" }
            finally { mutableSaving.value = false }
        }
    }
}
