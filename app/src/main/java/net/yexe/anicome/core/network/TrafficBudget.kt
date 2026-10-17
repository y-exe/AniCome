package net.yexe.anicome

import androidx.datastore.preferences.core.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.*

class TrafficLimitReached : java.io.IOException("通信量の上限に達しました")
data class TrafficPolicy(val limitMb: Long = 0L, val period: String = "day") {
    val limitBytes get() = limitMb.coerceIn(0L, Long.MAX_VALUE / 1048576L) * 1048576L
}
data class TrafficWindow(val day: String, val first: String, val end: String, val resetsAt: Long)
internal fun trafficWindow(now: Instant, zone: ZoneId, period: String): TrafficWindow {
    val day = now.atZone(zone).toLocalDate()
    val first = if (period == "month") day.withDayOfMonth(1) else day
    val end = if (period == "month") first.plusMonths(1) else first.plusDays(1)
    return TrafficWindow(day.toString(), first.toString(), end.toString(), end.atStartOfDay(zone).toInstant().toEpochMilli())
}
@Entity(tableName = "traffic_usage")
data class TrafficDay(@PrimaryKey val day: String, val bytes: Long, val generation: Long = 0L)
@Dao interface TrafficDao {
    @Query("SELECT * FROM traffic_usage ORDER BY day DESC") fun observe(): Flow<List<TrafficDay>>
    @Query("SELECT * FROM traffic_usage WHERE day = :day LIMIT 1") fun get(day: String): TrafficDay?
    @Query("SELECT * FROM traffic_usage WHERE day >= :first AND day < :end") fun range(first: String, end: String): List<TrafficDay>
    @Upsert fun upsert(row: TrafficDay)
    @Transaction fun put(rows: List<TrafficDay>) { rows.forEach(::upsert) }
}
interface TrafficIndex {
    fun observe(): Flow<List<TrafficDay>>
    fun get(day: String): TrafficDay?
    fun range(first: String, end: String): List<TrafficDay>
    fun put(rows: List<TrafficDay>)
}
class RoomTrafficIndex(private val dao: TrafficDao) : TrafficIndex {
    override fun observe() = dao.observe()
    override fun get(day: String) = dao.get(day)
    override fun range(first: String, end: String) = dao.range(first, end)
    override fun put(rows: List<TrafficDay>) = dao.put(rows)
}
data class TrafficBudgetState(val loaded: Boolean = false, val policy: TrafficPolicy = TrafficPolicy(), val used: Long = 0L, val resetsAt: Long = 0L, val error: String = "") {
    val reached get() = policy.limitBytes > 0L && used >= policy.limitBytes
}
internal data class TrafficGrant(val day: String, val generation: Long, val bytes: Long, val policy: TrafficPolicy)
internal fun trafficTotal(rows: List<TrafficDay>): Long = rows.fold(0L) { total, row -> total + row.bytes.coerceIn(0L, Long.MAX_VALUE - total) }
class TrafficBudgetRepository(private val settings: SettingsRepository, private val index: TrafficIndex, scope: CoroutineScope,
    private val clock: () -> Instant = Instant::now, private val zone: () -> ZoneId = ZoneId::systemDefault) {
    private val lock = Any()
    @Volatile private var policy = TrafficPolicy()
    private var policyEpoch = 0L
    private val generations = mutableMapOf<String, Long>()
    private fun readPolicy(p: SettingsSnapshot) = TrafficPolicy(p.getLong("trafficLimitMb", 0L).coerceIn(0L, Long.MAX_VALUE / 1048576L), p.getString("trafficPeriod", "day").takeIf { it in setOf("day", "month") } ?: "day")
    private fun window(p: TrafficPolicy) = trafficWindow(clock(), zone(), p.period)
    init { scope.launch(Dispatchers.IO) { settings.data.onEach { refreshPolicy() }.retryWhen { error, _ -> if (error is CancellationException) throw error; delay(1000); true }.collect() } }
    private suspend fun refreshPolicy() {
        while (true) {
            currentCoroutineContext().ensureActive()
            val epoch = synchronized(lock) { policyEpoch }
            val next = readPolicy(settings.load())
            if (synchronized(lock) {
                if (epoch != policyEpoch) false else { if (policy != next) { policy = next; policyEpoch++ }; true }
            }) return
        }
    }
    private val ticks = flow { while (currentCoroutineContext().isActive) { emit(Unit); delay(60_000) } }
    val state = combine(settings.data, index.observe(), ticks) { p, rows, _ ->
        val current = readPolicy(SettingsSnapshot(p)); val w = window(current)
        TrafficBudgetState(true, current, trafficTotal(rows.filter { it.day >= w.first && it.day < w.end }), w.resetsAt)
    }.catch { error -> if (error is CancellationException) throw error; emit(TrafficBudgetState(error = "通信量を読み込めませんでした")); throw error }
        .retryWhen { error, _ -> if (error is CancellationException) throw error; delay(1000); true }
        .flowOn(Dispatchers.IO).stateIn(scope, SharingStarted.WhileSubscribed(5000), TrafficBudgetState())
    suspend fun allowance(): TransferAllowance = try { prepare(); TransferAllowance() } catch (_: TrafficLimitReached) { TransferAllowance(false, synchronized(lock) { window(policy).resetsAt }) }
    suspend fun prepare() = withContext(Dispatchers.IO) {
        refreshPolicy()
        synchronized(lock) {
            val p = policy; val w = window(p)
            if (p.limitBytes > 0 && trafficTotal(index.range(w.first, w.end)) >= p.limitBytes) throw TrafficLimitReached()
        }
    }
    internal fun grant(wanted: Long): TrafficGrant = synchronized(lock) {
        val p = policy; val w = window(p)
        val used = trafficTotal(index.range(w.first, w.end))
        val allowed = if (p.limitBytes == 0L) wanted else minOf(wanted, (p.limitBytes - used).coerceAtLeast(0L))
        if (allowed <= 0L) throw TrafficLimitReached()
        val old = index.get(w.day) ?: TrafficDay(w.day, 0L)
        val allocated = minOf(allowed, Long.MAX_VALUE - old.bytes.coerceAtLeast(0L))
        if (allocated <= 0L) throw TrafficLimitReached()
        index.put(listOf(old.copy(bytes = old.bytes.coerceAtLeast(0L) + allocated)))
        generations[w.day] = old.generation
        TrafficGrant(w.day, old.generation, allocated, p)
    }
    internal fun current(grant: TrafficGrant): Boolean = synchronized(lock) {
        grant.policy == policy && grant.day == window(policy).day && (generations[grant.day] ?: grant.generation) == grant.generation
    }
    internal fun refund(grant: TrafficGrant, bytes: Long) = synchronized(lock) {
        if (bytes <= 0L) return@synchronized
        val old = index.get(grant.day) ?: return@synchronized
        if (old.generation == grant.generation) index.put(listOf(old.copy(bytes = (old.bytes - bytes.coerceAtMost(grant.bytes)).coerceAtLeast(0L))))
    }
    internal fun chunkSize() = if (policy.limitBytes > 0L) 32_768L else 131_072L
    suspend fun configure(limitMb: Long, period: String) = withContext(Dispatchers.IO) {
        require(limitMb in 0..Long.MAX_VALUE / 1048576L && period in setOf("day", "month"))
        settings.store.edit { it[longPreferencesKey("trafficLimitMb")] = limitMb; it[stringPreferencesKey("trafficPeriod")] = period }
        refreshPolicy()
    }
    suspend fun reset() = withContext(Dispatchers.IO) {
        val p = readPolicy(settings.load())
        synchronized(lock) {
            val w = window(p)
            val rows = (index.range(w.first, w.end) + listOfNotNull(index.get(w.day) ?: TrafficDay(w.day, 0L))).distinctBy { it.day }
            val next = rows.map { it.copy(bytes = 0L, generation = it.generation + 1L) }
            index.put(next); next.forEach { generations[it.day] = it.generation }
        }
    }
}
class TrafficBudgetViewModel(private val repository: TrafficBudgetRepository) : ViewModel() {
    val state = repository.state
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private val mutableError = MutableStateFlow("")
    val error = mutableError.asStateFlow()
    fun configure(limit: Long, period: String, complete: () -> Unit) = action({ repository.configure(limit, period) }, complete)
    fun reset(complete: () -> Unit = {}) = action({ repository.reset() }, complete)
    private fun action(block: suspend () -> Unit, complete: () -> Unit = {}) {
        if (mutableBusy.value) return
        mutableBusy.value = true; mutableError.value = ""
        viewModelScope.launch {
            try { block(); complete() } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableError.value = "通信量の設定を更新できませんでした" }
            finally { mutableBusy.value = false }
        }
    }
}
