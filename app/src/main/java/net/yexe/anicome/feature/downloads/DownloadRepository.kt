package net.yexe.anicome

import android.content.Context
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import androidx.work.Data
import java.util.UUID

interface DownloadQueue {
    fun observe(): kotlinx.coroutines.flow.Flow<List<WorkInfo>>
    suspend fun all(): List<WorkInfo>
    suspend fun cancel(id: UUID)
    suspend fun cancelAll()
    suspend fun enqueue(spec: ReservationSpec, workId: UUID, reservation: String) { error("unsupported queue") }
    fun conditions(): Flow<DownloadConditions> = flowOf(DownloadConditions())
    suspend fun currentConditions() = conditions().first()
    suspend fun watchBudget(resetsAt: Long) {}
    suspend fun watchConditions(specs: List<ReservationSpec>) {}
}
class AndroidDownloadQueue(private val context: Context) : DownloadQueue {
    private var budgetWake = 0L
    private val manager get() = WorkManager.getInstance(context)
    override fun observe() = manager.getWorkInfosByTagFlow(DownloadWorker.TAG)
    override suspend fun all() = withContext(Dispatchers.IO) { manager.getWorkInfosByTag(DownloadWorker.TAG).get() }
    override suspend fun cancel(id: UUID) { withContext(Dispatchers.IO) { manager.cancelWorkById(id).result.get() } }
    override suspend fun cancelAll() { withContext(Dispatchers.IO) { manager.cancelAllWorkByTag(DownloadWorker.TAG).result.get() } }
    override suspend fun enqueue(spec: ReservationSpec, workId: UUID, reservation: String) { withContext(Dispatchers.IO) { manager.enqueueUniqueWork(spec.name, androidx.work.ExistingWorkPolicy.REPLACE, spec.request(workId, reservation)).result.get() } }
    override suspend fun watchBudget(resetsAt: Long) = withContext(Dispatchers.IO) {
        if (resetsAt > 0 && resetsAt != budgetWake) {
            val request = androidx.work.OneTimeWorkRequestBuilder<ReservationDispatchWorker>()
                .setInitialDelay((resetsAt - System.currentTimeMillis()).coerceAtLeast(1000L), java.util.concurrent.TimeUnit.MILLISECONDS)
                .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build()).build()
            manager.enqueueUniqueWork("traffic-budget-reset", androidx.work.ExistingWorkPolicy.REPLACE, request).result.get()
            budgetWake = resetsAt
        } else if (resetsAt == 0L) { budgetWake = 0L }
        Unit
    }
    override fun conditions() = downloadConditions(context)
    override suspend fun currentConditions() = currentDownloadConditions(context)
    override suspend fun watchConditions(specs: List<ReservationSpec>) {
        withContext(Dispatchers.IO) { specs.distinctBy { it.wifi to it.charging }.forEach { spec ->
            val request = androidx.work.OneTimeWorkRequestBuilder<ReservationDispatchWorker>().setInputData(androidx.work.workDataOf("wifi" to spec.wifi, "charging" to spec.charging))
                .setConstraints(androidx.work.Constraints.Builder().setRequiresCharging(spec.charging).setRequiredNetworkType(if (spec.wifi) androidx.work.NetworkType.UNMETERED else androidx.work.NetworkType.CONNECTED).build()).build()
            manager.enqueueUniqueWork("reservation-conditions-${spec.wifi}-${spec.charging}", androidx.work.ExistingWorkPolicy.KEEP, request).result.get()
        } }
    }
}
class DownloadRepository(private val context: Context, private val settings: SettingsRepository, private val queue: DownloadQueue = AndroidDownloadQueue(context), private val scheduler: ReservationScheduler? = null, private val scope: CoroutineScope? = null) {
    private val visibility = ReservationVisibility(settings.store)
    init { scheduler?.start() }
    val work get() = (if (scheduler == null) queue.observe() else combine(scheduler.entries, queue.observe(), scheduler.trafficBlocked) { rows, works, _ -> scheduler.present(rows, works) }.flowOn(Dispatchers.Default))
        .combine(visibility.hidden) { works, ids -> works.filter { it.id.toString() !in ids } }
    private suspend fun all() = if (scheduler == null) queue.all() else scheduler.present(withContext(Dispatchers.IO) { schedulerSnapshot() }, queue.all())
    private suspend fun schedulerSnapshot() = scheduler!!.entries.first()
    suspend fun cancel(id: UUID) = withContext(Dispatchers.IO) {
        if (scheduler != null && all().any { it.id == id && reservationManaged(it) }) scheduler.cancel(setOf(id.toString())) else queue.cancel(id)
        visibility.dismiss(setOf(id.toString()))
    }
    suspend fun cancelMany(ids: Set<UUID>) = withContext(Dispatchers.IO) {
        val managed = all().filter { it.id in ids && reservationManaged(it) }.map { it.id.toString() }.toSet()
        scheduler?.cancel(managed)
        for (id in ids.filter { it.toString() !in managed }) queue.cancel(id)
        visibility.dismiss(ids.map { it.toString() }.toSet())
    }
    suspend fun cancelPrefetch(series: String) = withContext(Dispatchers.IO) {
        val targets = all().filter { work ->
            "prefetch" in work.tags && reservationEpisode(work.tags)?.let { prefetchWorkKey(it.series) == prefetchWorkKey(series) } == true && !work.state.isFinished
        }
        cancelMany(targets.map { it.id }.toSet())
    }
    suspend fun cancelAll() = withContext(Dispatchers.IO) {
        cancelMany(all().filter { it.state !in setOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED) }.map { it.id }.toSet())
    }
    suspend fun reserve(spec: ReservationSpec) {
        val prepared = if (spec.amount == 0) settings.load().let { p -> spec.copy(amount = p.getInt("commentAmount", 5).coerceAtLeast(1), maxComments = if (p.getBoolean("commentCountMode", false)) p.getInt("commentCountLimit", 5000).coerceAtLeast(1) else 0) } else spec
        if (scheduler != null) scheduler.submit(prepared) else DownloadWorker.enqueueLegacy(context, prepared)
    }
    fun submit(spec: ReservationSpec) {
        if (scheduler == null) { DownloadWorker.enqueueLegacy(context, spec); return }
        checkNotNull(scope).launch { try { reserve(spec) } catch (e: CancellationException) { throw e } catch (_: Exception) { Session.message.value = "保存予約を作成できませんでした" } }
    }
    fun enqueue(episode: Episode, catalog: AnimeInfo? = null, channel: Long? = null, sources: Set<PlaybackSource> = OfflineSources, reviewed: SaveEpisodeReview? = null) { DownloadWorker.enqueue(context, episode, catalog = catalog, channel = channel, sources = sources, reviewed = reviewed) }
    fun enqueuePrefetch(episode: Episode, catalog: AnimeInfo, wifi: Boolean, charging: Boolean) { DownloadWorker.enqueue(context, episode, "prefetch", catalog, wifiOverride = wifi, charging = charging) }
    suspend fun pause(ids: Set<UUID>) { scheduler?.pause(ids.map { it.toString() }.toSet()) }
    suspend fun resume(ids: Set<UUID>) { scheduler?.resume(ids.map { it.toString() }.toSet()) }
    suspend fun reorder(id: UUID, direction: String) { scheduler?.reorder(id.toString(), direction) }
    suspend fun pumpReservations() { scheduler?.pump() }
    suspend fun canRun(id: UUID, name: String) = scheduler?.canRun(id, name) ?: true
    suspend fun workerFinished(id: UUID, state: WorkInfo.State?, output: Data) { if (state != null) scheduler?.finished(id, state, output) }
    fun retry(work: WorkInfo) {
        if (scheduler != null && reservationManaged(work)) {
            checkNotNull(scope).launch { try { scheduler.retry(work.id.toString()) } catch (e: CancellationException) { throw e } catch (_: Exception) { Session.message.value = "保存予約を作成できませんでした" } }
        } else ReservationSpec.fromTags(work.tags, work.constraints.requiredNetworkType == androidx.work.NetworkType.UNMETERED, work.constraints.requiresCharging())?.let { spec ->
            if (scheduler == null) submit(spec) else checkNotNull(scope).launch {
                try { reserve(spec); visibility.dismiss(setOf(work.id.toString())) }
                catch (e: CancellationException) { throw e } catch (_: Exception) { Session.message.value = "保存予約を作成できませんでした" }
            }
        }
    }
}
