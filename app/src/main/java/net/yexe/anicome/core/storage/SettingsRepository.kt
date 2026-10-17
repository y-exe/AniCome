package net.yexe.anicome

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.channels.Channel

class SettingsSnapshot(private val values: Preferences) {
    fun getBoolean(key: String, default: Boolean) = values[booleanPreferencesKey(key)] ?: default
    fun getInt(key: String, default: Int) = values[intPreferencesKey(key)] ?: default
    fun getLong(key: String, default: Long) = values[longPreferencesKey(key)] ?: default
    fun getFloat(key: String, default: Float) = values[floatPreferencesKey(key)] ?: default
    fun getString(key: String, default: String?) = values[stringPreferencesKey(key)] ?: default
}
class SettingsRepository(val store: DataStore<Preferences>, scope: CoroutineScope) {
    @Volatile var storageLimitMb: Long = 0L
        private set
    private val writes = Channel<suspend (MutablePreferences) -> Unit>(Channel.UNLIMITED)
    private val failure = MutableStateFlow(false)
    val saveFailed = failure.asStateFlow()
    val data = store.data
    init { scope.launch {
        for (write in writes) {
            var waitMillis = 1000L
            while (isActive) {
                try {
                    store.edit { write(it) }
                    failure.value = false
                    break
                } catch (_: java.io.IOException) {
                    failure.value = true
                    delay(waitMillis)
                    waitMillis = (waitMillis * 2).coerceAtMost(10000L)
                }
            }
        }
    } }
    suspend fun load(): SettingsSnapshot = SettingsSnapshot(store.data.first()).also { storageLimitMb = it.getLong("storageLimitMb", 0L).coerceAtLeast(0L) }
    fun edit(change: (MutablePreferences) -> Unit) { check(writes.trySend { change(it) }.isSuccess) }
    private fun <T> put(key: Preferences.Key<T>, value: T) { check(writes.trySend { it[key] = value }.isSuccess) }
    fun putBoolean(key: String, value: Boolean) = put(booleanPreferencesKey(key), value)
    fun putInt(key: String, value: Int) = put(intPreferencesKey(key), value)
    fun putLong(key: String, value: Long) { if (key == "storageLimitMb") storageLimitMb = value; put(longPreferencesKey(key), value) }
    fun putFloat(key: String, value: Float) = put(floatPreferencesKey(key), value)
    fun putString(key: String, value: String) = put(stringPreferencesKey(key), value)
    companion object {
        fun create(context: Context, scope: CoroutineScope, legacyName: String = "settings", fileName: String = "settings-v2") = SettingsRepository(PreferenceDataStoreFactory.create(
            migrations = listOf(SharedPreferencesMigration(context, legacyName)), scope = CoroutineScope(scope.coroutineContext + Dispatchers.IO),
            produceFile = { context.preferencesDataStoreFile(fileName) }), scope)
    }
}
