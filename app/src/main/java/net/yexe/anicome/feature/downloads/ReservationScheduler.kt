package net.yexe.anicome

import androidx.room.*
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

@Entity(tableName = "download_reservations", indices = [Index("name"), Index(value = ["state", "rank"]), Index("workId")])
data class ReservationEntry(@PrimaryKey val id: String, val name: String, val series: String, val episode: Int, val payload: String,
    val state: String, val rank: Long, val createdAt: Long, val workId: String = "", val output: String = "") {
    @get:Ignore val spec get() = ReservationSpec.decode(payload)
    @get:Ignore val pending get() = state in setOf("QUEUED", "ACTIVE", "PAUSED")
}
@Dao interface ReservationDao {
    @Query("SELECT * FROM download_reservations ORDER BY rank, createdAt, id") fun all(): List<ReservationEntry>
    @Query("SELECT * FROM download_reservations ORDER BY rank, createdAt, id") fun observe(): Flow<List<ReservationEntry>>
    @Upsert fun upsert(entry: ReservationEntry)
    @Transaction fun put(entries: List<ReservationEntry>) { entries.forEach(::upsert) }
}
interface ReservationIndex {
    fun all(): List<ReservationEntry>
    fun observe(): Flow<List<ReservationEntry>>
    fun put(entries: List<ReservationEntry>)
}
class RoomReservationIndex(private val dao: ReservationDao) : ReservationIndex {
    override fun all() = dao.all()
    override fun observe() = dao.observe()
    override fun put(entries: List<ReservationEntry>) = dao.put(entries)
}
data class DownloadConditions(val connected: Boolean = true, val metered: Boolean = false, val charging: Boolean = true) {
    fun permits(spec: ReservationSpec) = connected && (!spec.wifi || !metered) && (!spec.charging || charging)
}
data class TransferAllowance(val available: Boolean = true, val resetsAt: Long = 0L)
class ReservationScheduler(private val index: ReservationIndex, private val queue: DownloadQueue, private val scope: CoroutineScope,
    private val parallelism: Int = 2, private val clock: () -> Long = System::currentTimeMillis,
    private val estimateBytes: suspend (ReservationSpec) -> Long = { 0L },
    private val transferAllowance: suspend () -> TransferAllowance = { TransferAllowance() },
    private val transferUpdates: Flow<Unit> = flowOf(Unit)) {
    private val mutex = Mutex()
    private var observer: Job? = null
    val trafficBlocked = MutableStateFlow(false)
    val entries = index.observe()
    fun start() {
        if (observer?.isActive == true) return
        observer = scope.launch(Dispatchers.IO) {
            combine(entries, queue.observe(), queue.conditions(), transferUpdates) { _, _, _, _ -> Unit }.retryWhen { error, _ ->
                if (error is CancellationException) throw error
                Session.message.value = "保存予約を更新できませんでした。再試行してください。"
                delay(1000); true
            }.collect {
                try { pump() } catch (e: CancellationException) { throw e } catch (_: Exception) { Session.message.value = "保存予約を更新できませんでした。再試行してください。"; delay(1000) }
            }
        }
    }
    suspend fun submit(request: ReservationSpec): String = withContext(Dispatchers.IO) { mutex.withLock {
        val spec = if (request.estimatedBytes > 0) request else request.copy(estimatedBytes = estimateBytes(request).coerceIn(0L, MAX_DOWNLOAD_ESTIMATE))
        require(spec.episode.series.isNotBlank() && spec.episode.series.length <= 300 && spec.episode.number in 0..9999 && spec.sources.isNotEmpty() && spec.amount >= 0 && spec.maxComments >= 0 && spec.estimatedBytes in 0..MAX_DOWNLOAD_ESTIMATE)
        spec.input()
        val rows = index.all()
        rows.firstOrNull { it.name == spec.name && it.pending }?.let { return@withLock it.id }
        val id = UUID.randomUUID().toString()
        index.put(listOf(ReservationEntry(id, spec.name, spec.episode.series, spec.episode.number, spec.encode(), "QUEUED", (rows.maxOfOrNull { it.rank } ?: 0L) + 1L, maxOf(clock(), (rows.maxOfOrNull { it.createdAt } ?: 0L) + 1L))))
        pumpLocked()
        id
    } }
    suspend fun pump() = withContext(Dispatchers.IO) { mutex.withLock { pumpLocked() } }
    private suspend fun pumpLocked() {
        var rows = index.all()
        val works = queue.all()
        val known = rows.flatMap { listOf(it.id, it.workId) }.toSet()
        for (work in works.filter { !it.state.isFinished && it.id.toString() !in known && it.tags.none { tag -> tag.startsWith("managed-reservation:") } }) {
            val original = ReservationSpec.fromTags(work.tags, work.constraints.requiredNetworkType == NetworkType.UNMETERED, work.constraints.requiresCharging()) ?: continue
            val spec = if (original.estimatedBytes > 0) original else original.copy(estimatedBytes = estimateBytes(original).coerceIn(0L, MAX_DOWNLOAD_ESTIMATE))
            val entry = ReservationEntry(work.id.toString(), spec.name, spec.episode.series, spec.episode.number, spec.encode(), "QUEUED", (rows.maxOfOrNull { it.rank } ?: 0L) + 1L, clock(), work.id.toString())
            index.put(listOf(entry)); rows = rows + entry
            queue.cancel(work.id)
        }
        val environment = queue.currentConditions()
        val updates = mutableListOf<ReservationEntry>()
        for (entry in rows.filter { it.state == "ACTIVE" }) {
            val actual = works.firstOrNull { it.id.toString() == entry.workId }
            when {
                actual?.state?.isFinished == true -> updates += terminalEntry(entry, actual.state, actual.outputData)
                actual == null -> updates += entry.copy(state = "QUEUED", workId = "")
                actual.state != WorkInfo.State.RUNNING && !environment.permits(entry.spec) -> {
                    index.put(listOf(entry.copy(state = "QUEUED")))
                    queue.cancel(actual.id)
                    updates += entry.copy(state = "QUEUED", workId = "")
                }
            }
        }
        if (updates.isNotEmpty()) index.put(updates)
        rows = index.all()
        val allowance = transferAllowance()
        trafficBlocked.value = !allowance.available
        var available = if (allowance.available) (parallelism - rows.count { it.state == "ACTIVE" }).coerceAtLeast(0) else 0
        for (entry in rows.filter { it.state == "QUEUED" }.sortedWith(compareBy<ReservationEntry> { it.rank }.thenBy { it.createdAt }.thenBy { it.id })) {
            if (available <= 0) break
            if (!environment.permits(entry.spec)) continue
            if (entry.workId.isNotBlank()) queue.cancel(UUID.fromString(entry.workId))
            val next = entry.copy(state = "ACTIVE", workId = UUID.randomUUID().toString(), output = "")
            index.put(listOf(next))
            try { queue.enqueue(next.spec, UUID.fromString(next.workId), next.id); available-- }
            catch (e: Exception) { index.put(listOf(next.copy(state = "QUEUED"))); throw e }
        }
        queue.watchBudget(if (!allowance.available && index.all().any { it.state == "QUEUED" }) allowance.resetsAt else 0L)
        queue.watchConditions(index.all().filter { it.state == "QUEUED" && !environment.permits(it.spec) }.map { it.spec })
    }
    suspend fun pause(ids: Set<String>) = withContext(Dispatchers.IO) { mutex.withLock {
        val targets = index.all().filter { it.id in ids && it.state in setOf("QUEUED", "ACTIVE") }
        index.put(targets.map { it.copy(state = "PAUSED") })
        for (target in targets) if (target.workId.isNotBlank()) queue.cancel(UUID.fromString(target.workId))
        pumpLocked()
    } }
    suspend fun resume(ids: Set<String>) = withContext(Dispatchers.IO) { mutex.withLock {
        val targets = index.all().filter { it.id in ids && it.state == "PAUSED" }
        index.put(targets.map { it.copy(state = "QUEUED") })
        pumpLocked()
    } }
    suspend fun cancel(ids: Set<String>) = withContext(Dispatchers.IO) { mutex.withLock {
        val targets = index.all().filter { it.id in ids && it.state != "SUCCEEDED" }
        index.put(targets.map { it.copy(state = "CANCELLED") })
        for (target in targets) if (target.workId.isNotBlank()) queue.cancel(UUID.fromString(target.workId))
        pumpLocked()
    } }
    suspend fun reorder(id: String, direction: String) = withContext(Dispatchers.IO) { mutex.withLock {
        val waiting = index.all().filter { it.state in setOf("QUEUED", "PAUSED") }.sortedWith(compareBy<ReservationEntry> { it.rank }.thenBy { it.createdAt }.thenBy { it.id }).toMutableList()
        val from = waiting.indexOfFirst { it.id == id }
        if (from < 0) return@withLock
        val to = when (direction) { "first" -> 0; "up" -> (from - 1).coerceAtLeast(0); "down" -> (from + 1).coerceAtMost(waiting.lastIndex); "last" -> waiting.lastIndex; else -> return@withLock }
        waiting.add(to, waiting.removeAt(from))
        index.put(waiting.mapIndexed { order, entry -> entry.copy(rank = order.toLong()) })
        pumpLocked()
    } }
    suspend fun canRun(workId: UUID, name: String) = withContext(Dispatchers.IO) {
        val rows = index.all()
        rows.firstOrNull { it.workId == workId.toString() }?.let { it.state == "ACTIVE" } ?: rows.none { it.name == name }
    }
    suspend fun retry(id: String) = withContext(Dispatchers.IO) { mutex.withLock {
        val entry = index.all().firstOrNull { it.id == id && it.state == "FAILED" } ?: return@withLock
        index.put(listOf(entry.copy(state = "QUEUED", workId = "", output = "")))
        pumpLocked()
    } }
    suspend fun finished(workId: UUID, state: WorkInfo.State, output: Data) = withContext(Dispatchers.IO) { mutex.withLock {
        val entry = index.all().firstOrNull { it.workId == workId.toString() && it.state == "ACTIVE" } ?: return@withLock
        index.put(listOf(terminalEntry(entry, state, output)))
        pumpLocked()
    } }
    private fun terminalEntry(entry: ReservationEntry, state: WorkInfo.State, output: Data) =
        if (state == WorkInfo.State.FAILED && output.getString("failureKind") == "TRAFFIC_LIMIT") entry.copy(state = "QUEUED", workId = "", output = reservationOutput(output))
        else entry.copy(state = state.name, output = reservationOutput(output))
    fun present(rows: List<ReservationEntry>, works: List<WorkInfo>): List<WorkInfo> {
        val durationHistory = ReservationDurationHistory(rows)
        val runIds = rows.flatMap { listOf(it.id, it.workId) }.toSet()
        val legacy = works.filter { it.id.toString() !in runIds && it.tags.none { tag -> tag.startsWith("managed-reservation:") } }
        val latest = rows.groupBy { it.name }.values.map { values -> values.maxBy { it.createdAt } }
        val order = latest.filter { it.state == "QUEUED" }.sortedWith(compareBy<ReservationEntry> { it.rank }.thenBy { it.createdAt }.thenBy { it.id }).mapIndexed { i, entry -> entry.id to i + 1 }.toMap()
        return latest.sortedWith(compareBy<ReservationEntry> { it.rank }.thenBy { it.createdAt }.thenBy { it.id }).map { entry ->
            val actual = works.firstOrNull { it.id.toString() == entry.workId }
            val spec = entry.spec
            val duration = durationHistory.estimate(spec)
            val state = when (entry.state) { "PAUSED", "QUEUED" -> WorkInfo.State.ENQUEUED; "ACTIVE" -> actual?.state?.takeUnless { it.isFinished } ?: WorkInfo.State.ENQUEUED; else -> WorkInfo.State.valueOf(entry.state) }
            WorkInfo(UUID.fromString(entry.id), state, spec.tags(), outputData = if (entry.state == "ACTIVE") actual?.outputData ?: Data.EMPTY else readReservationOutput(entry.output),
                progress = Data.Builder().apply {
                    putInt("sourceNumber", spec.reviewed?.searchEpisode?.number ?: entry.episode)
                    putString("episodeTitle", spec.reviewed?.subtitle.orEmpty())
                    if (entry.state == "ACTIVE" && actual != null) putAll(actual.progress)
                    putBoolean("reservationTrafficBlocked", entry.state == "QUEUED" && trafficBlocked.value)
                    putBoolean("reservationManaged", true); putBoolean("reservationPaused", entry.state == "PAUSED"); putInt("reservationOrder", order[entry.id] ?: 0); putLong("reservationRank", entry.rank)
                    putBoolean("reservationWifi", spec.wifi); putBoolean("reservationCharging", spec.charging)
                    putLong("reservationBytes", spec.estimatedBytes)
                    duration?.let { putLong("reservationDurationMs", it) }
                }.build(), runAttemptCount = actual?.runAttemptCount ?: 0)
        } + legacy
    }
}
