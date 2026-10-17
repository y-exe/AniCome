package net.yexe.anicome

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.concurrent.TimeUnit

class AutoCommentCacheRepository(context: Context, private val manual: CommentStore, private val settings: SettingsRepository,
    dao: CachedEpisodeDao, scope: CoroutineScope, history: CommentHistoryRepository? = null) {
    private val retentionLock = Any()
    @Volatile private var retentionHours = 48L
    private var retentionLoaded = false
    @Volatile private var limitMb = 0L
    private var limitLoaded = false
    private fun adoptLimit(value: Long, initialOnly: Boolean = false) = synchronized(retentionLock) {
        if (!initialOnly || !limitLoaded) { limitMb = value.coerceIn(0L, 1_000_000L); limitLoaded = true }
    }
    private fun adoptRetention(value: Long, initialOnly: Boolean = false) = synchronized(retentionLock) {
        if (!initialOnly || !retentionLoaded) { retentionHours = value.coerceIn(1L, 87600L); retentionLoaded = true }
    }
    val store = CommentStore(File(context.filesDir, "comment-cache"), index = RoomCacheEpisodeIndex(dao), fallback = manual,
        expiresAfter = { retentionHours * 3_600_000L }, automaticLimit = { limitMb * 1_048_576L }, onSaved = { before, after -> history?.saved(before, after, "cache") })
    val hours = settings.data.map { SettingsSnapshot(it).getLong("autoCacheHours", 48L).coerceIn(1L, 87600L) }.onEach { adoptRetention(it) }.stateIn(scope, SharingStarted.Eagerly, 48L)
    val limit = settings.data.map { SettingsSnapshot(it).getLong("autoCacheLimitMb", 0L).coerceIn(0L, 1_000_000L) }.onEach { adoptLimit(it) }.stateIn(scope, SharingStarted.Eagerly, 0L)
    private val available = MutableStateFlow(0L)
    val freeBytes = available.asStateFlow()
    val entries = dao.observe().map { it.map(CachedEpisodeEntity::toEpisode) }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    private val failure = MutableStateFlow(false)
    val failed = failure.asStateFlow()
    init {
        scope.launch(Dispatchers.IO) {
            val initial = settings.load()
            adoptRetention(initial.getLong("autoCacheHours", 48L), initialOnly = true)
            adoptLimit(initial.getLong("autoCacheLimitMb", 0L), initialOnly = true)
            action { manual.cleanupInterruptedData(); store.cleanupInterruptedData() }
            while (isActive) { action { store.purgeExpired() }; delay(60_000) }
        }
        scope.launch(Dispatchers.IO) { hours.drop(1).collect { action { store.purgeExpired() } } }
        scope.launch(Dispatchers.IO) { limit.drop(1).collect { action { store.enforceAutomaticLimit() } } }
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("automatic-comment-cache-cleanup", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AutoCacheCleanupWorker>(15, TimeUnit.MINUTES).build())
    }
    private suspend fun action(block: () -> Unit) = withContext(Dispatchers.IO) {
        try { block(); available.value = store.storageSpace(); failure.value = false } catch (e: CancellationException) { throw e } catch (_: Exception) { failure.value = true }
    }
    suspend fun cleanup() {
        val initial = settings.load()
        adoptRetention(initial.getLong("autoCacheHours", 48L), initialOnly = true)
        adoptLimit(initial.getLong("autoCacheLimitMb", 0L), initialOnly = true)
        action { manual.cleanupInterruptedData(); store.purgeExpired(); store.cleanupInterruptedData() }
    }
    suspend fun keep(episode: Episode) = action { store.keepCached(episode) }
    suspend fun delete(episode: Episode) = action { store.delete(episode) }
    suspend fun clear() = action { store.ownList().forEach { store.delete(it.episode) } }
    fun capacity(mb: Long) { settings.putLong("autoCacheLimitMb", mb.coerceIn(0L, 1_000_000L)) }
    fun retention(hours: Long) { settings.putLong("autoCacheHours", hours.coerceIn(1L, 87600L)) }
}
class AutoCacheViewModel(private val repository: AutoCommentCacheRepository) : ViewModel() {
    val entries = repository.entries
    val hours = repository.hours
    val failed = repository.failed
    val limit = repository.limit
    val freeBytes = repository.freeBytes
    fun capacity(mb: Long) = repository.capacity(mb)
    fun keep(episode: Episode) { viewModelScope.launch { repository.keep(episode) } }
    fun retention(hours: Long) = repository.retention(hours)
    fun delete(episode: Episode) { viewModelScope.launch { repository.delete(episode) } }
    fun clear() { viewModelScope.launch { repository.clear() } }
}
open class AutoCacheCleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val cache = org.koin.core.context.GlobalContext.get().get<AutoCommentCacheRepository>()
        cache.cleanup()
        return if (cache.failed.value) Result.retry() else Result.success()
    }
}
