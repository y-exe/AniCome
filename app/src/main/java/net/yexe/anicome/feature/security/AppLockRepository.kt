package net.yexe.anicome

import androidx.datastore.preferences.core.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class AppAccessState(val loaded: Boolean = false, val mode: String = "none", val biometric: Boolean = false, val locked: Boolean = true, val retryAt: Long = 0, val timeout: Int = 0)
object LockCredentials {
    const val ITERATIONS = 600000
    fun valid(mode: String, value: String) = when (mode) {
        "pin" -> value.matches(Regex("[0-9]{6,12}"))
        "password" -> value.length in 8..128
        "pattern" -> value.split(',').let { it.size in 4..9 && it.distinct().size == it.size && it.all { node -> node in (0..8).map(Int::toString) } }
        else -> false
    }
    fun derive(value: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(value.toCharArray(), salt, ITERATIONS, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }
    fun extendPattern(nodes: List<Int>, node: Int): List<Int> {
        if (node !in 0..8 || node in nodes) return nodes
        if (nodes.isEmpty()) return listOf(node)
        val last = nodes.last(); val dx = node % 3 - last % 3; val dy = node / 3 - last / 3
        val middle = if ((kotlin.math.abs(dx) == 2 || kotlin.math.abs(dy) == 2) && dx % 2 == 0 && dy % 2 == 0) (last + node) / 2 else -1
        return nodes + (if (middle >= 0 && middle !in nodes) listOf(middle) else emptyList()) + node
    }
}
class AppLockRepository(private val settings: SettingsRepository, scope: CoroutineScope, private val elapsedTime: () -> Long = android.os.SystemClock::elapsedRealtime, private val wallTime: () -> Long = System::currentTimeMillis) {
    private val mutable = MutableStateFlow(AppAccessState())
    val state = mutable.asStateFlow()
    private val mutex = Mutex()
    private var verifiedAt = -1L
    private var backgroundAt = -1L
    init { scope.launch { settings.data.catch { if (it is CancellationException) throw it }.collect { data ->
        val p = SettingsSnapshot(data); val mode = p.getString("lockMode", "none") ?: "none"
        mutable.update { it.copy(loaded = true, mode = mode, biometric = p.getBoolean("lockBiometric", false), locked = mode != "none" && it.locked,
            retryAt = p.getLong("lockRetryAt", 0), timeout = p.getInt("lockTimeout", 0)) }
    } } }
    suspend fun verify(value: String): Boolean = mutex.withLock {
        val p = settings.load()
        if (p.getLong("lockRetryAt", 0) > wallTime()) return@withLock false
        val salt = p.getString("lockSalt", null) ?: return@withLock false
        val expected = p.getString("lockHash", null) ?: return@withLock false
        val correct = withContext(Dispatchers.Default) {
            runCatching { MessageDigest.isEqual(LockCredentials.derive(value, Base64.getDecoder().decode(salt)), Base64.getDecoder().decode(expected)) }.getOrDefault(false)
        }
        settings.store.edit { data ->
            val failures = if (correct) 0 else (data[intPreferencesKey("lockFailures")] ?: 0) + 1
            data[intPreferencesKey("lockFailures")] = failures
            data[longPreferencesKey("lockRetryAt")] = if (correct || failures < 5) 0 else wallTime() + (30L * (1L shl (failures - 5).coerceAtMost(5))) * 1000
        }
        if (correct) authorize()
        correct
    }
    private fun authorize() { verifiedAt = elapsedTime(); mutable.update { it.copy(locked = false) } }
    fun biometricSucceeded() { if (state.value.biometric && state.value.mode != "none") authorize() }
    private suspend fun requireAuthorized() { check(settings.load().getString("lockMode", "none") == "none" || verifiedAt >= 0 && elapsedTime() - verifiedAt < 300000) { "現在のロックを確認してください" } }
    fun observeLifecycle(application: android.app.Application) {
        var started = 0
        application.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) { if (started++ == 0) { Session.appVisible.value = true; foreground() } }
            override fun onActivityStopped(activity: android.app.Activity) { started = (started - 1).coerceAtLeast(0); if (started == 0) { Session.appVisible.value = false; if (!activity.isChangingConfigurations) background() } }
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) {}
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
    }
    suspend fun configure(mode: String, value: String, biometric: Boolean) = mutex.withLock {
        requireAuthorized(); require(LockCredentials.valid(mode, value)) { "PINは6〜12桁、パスワードは8〜128文字、パターンは4点以上で設定してください" }
        val salt = ByteArray(32).also(SecureRandom()::nextBytes)
        val hash = withContext(Dispatchers.Default) { LockCredentials.derive(value, salt) }
        settings.store.edit {
            it[stringPreferencesKey("lockMode")] = mode; it[stringPreferencesKey("lockSalt")] = Base64.getEncoder().encodeToString(salt)
            it[stringPreferencesKey("lockHash")] = Base64.getEncoder().encodeToString(hash); it[booleanPreferencesKey("lockBiometric")] = biometric
            it[intPreferencesKey("lockFailures")] = 0; it[longPreferencesKey("lockRetryAt")] = 0
        }
        authorize()
    }
    suspend fun disable() = mutex.withLock {
        requireAuthorized()
        settings.store.edit { it[stringPreferencesKey("lockMode")] = "none"; it.remove(stringPreferencesKey("lockSalt")); it.remove(stringPreferencesKey("lockHash")); it[booleanPreferencesKey("lockBiometric")] = false }
        mutable.update { it.copy(mode = "none", biometric = false, locked = false) }
    }
    fun timeout(seconds: Int) { require(seconds in listOf(0, 15, 60, 300)); settings.putInt("lockTimeout", seconds) }
    fun background() { backgroundAt = elapsedTime() }
    fun foreground() {
        if (backgroundAt >= 0 && state.value.mode != "none" && elapsedTime() - backgroundAt >= state.value.timeout * 1000L) {
            verifiedAt = -1; mutable.update { it.copy(locked = true) }
        }
        backgroundAt = -1
    }
}
class AppLockViewModel(private val repository: AppLockRepository) : ViewModel() {
    val state = repository.state
    private val mutableError = MutableStateFlow("")
    val error = mutableError.asStateFlow()
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    fun clearError() { mutableError.value = "" }
    private fun perform(block: suspend () -> Unit) {
        if (mutableBusy.value) return
        mutableBusy.value = true; mutableError.value = ""
        viewModelScope.launch { try { block() } catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception) { mutableError.value = e.message ?: "ロックの設定に失敗しました" } finally { mutableBusy.value = false } }
    }
    fun verify(input: String, success: () -> Unit = {}) = perform { if (repository.verify(input)) success() else mutableError.value = "ロックが一致しません。しばらく待って再試行してください" }
    fun configure(mode: String, input: String, biometric: Boolean, success: () -> Unit) = perform { repository.configure(mode, input, biometric); success() }
    fun disable(success: () -> Unit) = perform { repository.disable(); success() }
    fun remove(input: String, success: () -> Unit) = perform { if (repository.verify(input)) { repository.disable(); success() } else mutableError.value = "ロックが一致しません" }
    fun timeout(seconds: Int) = repository.timeout(seconds)
    fun biometricSucceeded() = repository.biometricSucceeded()
    fun biometricError(message: String) { mutableError.value = message }
    fun background() = repository.background()
    fun foreground() = repository.foreground()
}
