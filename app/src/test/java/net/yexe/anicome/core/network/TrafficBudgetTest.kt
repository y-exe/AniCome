package net.yexe.anicome

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response
import java.time.*

internal class MemoryTraffic : TrafficIndex {
    private val rows = MutableStateFlow<List<TrafficDay>>(emptyList())
    override fun observe() = rows
    @Synchronized override fun get(day: String) = rows.value.firstOrNull { it.day == day }
    @Synchronized override fun range(first: String, end: String) = rows.value.filter { it.day >= first && it.day < end }
    @Synchronized override fun put(rows: List<TrafficDay>) {
        this.rows.value = this.rows.value.associateByTo(linkedMapOf()) { it.day }.apply { rows.forEach { put(it.day, it) } }.values.toList()
    }
    fun used() = trafficTotal(rows.value)
}

@OptIn(ExperimentalCoroutinesApi::class)
class TrafficBudgetTest {
    private val utc = ZoneOffset.UTC
    private val date = Instant.parse("2026-10-15T12:00:00Z")
    private val mb = 1_048_576L
    @Test fun usageResetWakesTheObservedQueueWithoutAManualRetry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val index = MemoryTraffic(); val budget = TrafficBudgetRepository(SettingsRepository(MemoryPreferences(), scope), index, scope, { date }, { utc })
            budget.configure(1, "day"); index.put(listOf(TrafficDay("2026-10-15", mb)))
            withTimeout(5000) { budget.state.first { it.loaded && it.reached } }
            val reservations = MemoryReservations(); val queue = ReservationTransport()
            val scheduler = ReservationScheduler(reservations, queue, scope, transferAllowance = { budget.allowance() },
                transferUpdates = budget.state.map { it.loaded to it.reached }.distinctUntilChanged().map { Unit })
            scheduler.start()
            val spec = ReservationSpec(Episode("テスト作品", 13), sources = setOf(PlaybackSource.OFFICIAL, PlaybackSource.DANIME), amount = 20)
            val id = scheduler.submit(spec)
            assertTrue(queue.works.value.isEmpty()); assertTrue(scheduler.trafficBlocked.value)
            budget.reset()
            withTimeout(5000) { queue.works.first { it.isNotEmpty() } }
            assertEquals("ACTIVE", reservations.all().single { it.id == id }.state)
            assertEquals(spec.input(), queue.enqueued.single().first.input())
            assertFalse(scheduler.trafficBlocked.value)
        } finally { scope.cancel() }
    }
    @Test fun portableBudgetSettingsKeepTheirTypesAndRejectInvalidPeriods() {
        validatePortableSetting("trafficLimitMb", org.json.JSONObject().put("type", "long").put("value", 500L))
        validatePortableSetting("trafficPeriod", org.json.JSONObject().put("type", "string").put("value", "month"))
        try { validatePortableSetting("trafficPeriod", org.json.JSONObject().put("type", "string").put("value", "never")); fail() } catch (_: IllegalArgumentException) { }
        try { validatePortableSetting("trafficLimitMb", org.json.JSONObject().put("type", "long").put("value", -1L)); fail() } catch (_: IllegalArgumentException) { }
    }
    private class Service(var bytes: ByteArray, var type: String = "text/plain; charset=utf-8", var unknownLength: Boolean = false) : JsonApiService {
        var requests = 0
        override suspend fun get(url: String, headers: Map<String, String>): Response<ResponseBody> {
            requests++
            val data = bytes.toResponseBody(type.toMediaType())
            return Response.success(if (!unknownLength) data else object : ResponseBody() {
                override fun contentType() = data.contentType()
                override fun contentLength() = -1L
                override fun source() = data.source()
            })
        }
        override suspend fun post(url: String, headers: Map<String, String>, body: RequestBody) = get(url, headers)
    }
    @Test fun unlimitedDefaultCountsUtf8BytesAndKeepsBomCharsetDetection() = runTest {
        val settings = SettingsRepository(MemoryPreferences(), backgroundScope); val index = MemoryTraffic()
        val budget = TrafficBudgetRepository(settings, index, backgroundScope, { date }, { utc })
        val service = Service("こんにちはAA\n　■".toByteArray())
        val client = ApiClient(service, traffic = budget)
        assertEquals("こんにちはAA\n　■", client.requestText("https://example.com/api"))
        assertEquals(service.bytes.size.toLong(), index.used())
        val previous = index.used()
        service.bytes = byteArrayOf(0xff.toByte(), 0xfe.toByte()) + "日本語".toByteArray(Charsets.UTF_16LE)
        assertEquals("日本語", client.requestText("https://example.com/api"))
        assertEquals(previous + service.bytes.size, index.used())
    }
    @Test fun exactLimitUnknownLengthCanFinishButTheNextRequestNeverStarts() = runTest {
        val index = MemoryTraffic(); val budget = TrafficBudgetRepository(SettingsRepository(MemoryPreferences(), backgroundScope), index, backgroundScope, { date }, { utc })
        budget.configure(1, "day")
        val service = Service(ByteArray(mb.toInt()) { 'a'.code.toByte() }, unknownLength = true)
        val client = ApiClient(service, traffic = budget)
        assertEquals(mb.toInt(), client.requestText("https://example.com/api").length)
        assertEquals(mb, index.used())
        try { client.requestText("https://example.com/api"); fail() } catch (_: TrafficLimitReached) { }
        assertEquals(1, service.requests)
        assertFalse(budget.allowance().available)
    }
    @Test fun oversizedResponseStopsAtTheLimitAndRemainsLimitedAfterRepositoryRestart() = runTest {
        val index = MemoryTraffic(); val settings = SettingsRepository(MemoryPreferences(), backgroundScope)
        val budget = TrafficBudgetRepository(settings, index, backgroundScope, { date }, { utc }); budget.configure(1, "day")
        val body = ByteArray(mb.toInt() + 10).toResponseBody().withTrafficBudget(budget) { }
        val sink = Buffer()
        body.use { try { while (it.source().read(sink, 8192) >= 0) { } ; fail() } catch (_: TrafficLimitReached) { } }
        assertEquals(mb, sink.size); assertEquals(mb, index.used())
        val restored = TrafficBudgetRepository(settings, index, backgroundScope, { date }, { utc })
        try { restored.prepare(); fail() } catch (_: TrafficLimitReached) { }
    }
    @Test fun parallelStreamsShareOneLimitWithoutDroppingAlreadyReadBytes() = runTest {
        val index = MemoryTraffic(); val budget = TrafficBudgetRepository(SettingsRepository(MemoryPreferences(), backgroundScope), index, backgroundScope, { date }, { utc })
        budget.configure(1, "day")
        val received = coroutineScope { (1..4).map { async(Dispatchers.Default) {
            val sink = Buffer()
            ByteArray(800_000).toResponseBody().withTrafficBudget(budget) { }.use { body ->
                try { while (body.source().read(sink, 4096) >= 0) { } } catch (_: TrafficLimitReached) { }
            }
            sink.size
        } }.awaitAll().sum() }
        assertTrue(received <= mb); assertTrue(received > 0); assertEquals(received, index.used())
    }
    @Test fun closingOrCancellingRefundsOnlyTheUnusedLease() = runTest {
        val index = MemoryTraffic(); val budget = TrafficBudgetRepository(SettingsRepository(MemoryPreferences(), backgroundScope), index, backgroundScope, { date }, { utc })
        budget.configure(1, "day")
        val source = TrafficSource(Buffer().write(ByteArray(100_000)), budget) { }
        source.read(Buffer(), 11); assertEquals(32_768L, index.used())
        source.close(); source.close(); assertEquals(11L, index.used())
        var cancelled = false
        val next = TrafficSource(Buffer().write(ByteArray(100_000)), budget) { if (cancelled) throw CancellationException() }
        next.use { it.read(Buffer(), 17); cancelled = true; try { it.read(Buffer(), 1); fail() } catch (_: CancellationException) { } }
        assertEquals(28L, index.used())
    }
    @Test fun resettingInvalidatesOldLeasesWithoutRefundingNewUsage() = runTest {
        val index = MemoryTraffic(); val budget = TrafficBudgetRepository(SettingsRepository(MemoryPreferences(), backgroundScope), index, backgroundScope, { date }, { utc })
        budget.configure(1, "day")
        val old = TrafficSource(Buffer().write(ByteArray(100_000)), budget) { }
        old.read(Buffer(), 10); budget.reset(); assertEquals(0L, index.used())
        val fresh = TrafficSource(Buffer().write(ByteArray(100_000)), budget) { }
        fresh.read(Buffer(), 20); old.close(); fresh.close()
        assertEquals(20L, index.used())
    }
    @Test fun changingPolicyDuringAnOpenStreamRechecksItsRemainingAllowance() = runTest {
        val index = MemoryTraffic(); val budget = TrafficBudgetRepository(SettingsRepository(MemoryPreferences(), backgroundScope), index, backgroundScope, { date }, { utc })
        budget.prepare()
        val source = TrafficSource(Buffer().write(ByteArray(2_000_000)), budget) { }
        source.read(Buffer(), 100)
        budget.configure(1, "day")
        val sink = Buffer()
        source.use { try { while (it.read(sink, 8192) >= 0) { } ; fail() } catch (_: TrafficLimitReached) { } }
        assertEquals(mb - 100, sink.size); assertEquals(mb, index.used())
    }
    @Test fun dayMonthAndMidnightUseTheSamePersistedDailyRows() = runTest {
        var now = date
        val index = MemoryTraffic(); val budget = TrafficBudgetRepository(SettingsRepository(MemoryPreferences(), backgroundScope), index, backgroundScope, { now }, { utc })
        index.put(listOf(TrafficDay("2026-10-14", mb), TrafficDay("2026-09-30", 99 * mb)))
        budget.configure(1, "month"); assertFalse(budget.allowance().available)
        budget.configure(1, "day"); assertTrue(budget.allowance().available)
        val source = TrafficSource(Buffer().write(ByteArray(100_000)), budget) { }
        source.read(Buffer(), 123)
        now = Instant.parse("2026-10-16T00:00:00Z")
        source.read(Buffer(), 234); source.close()
        assertEquals(123L, index.get("2026-10-15")!!.bytes); assertEquals(234L, index.get("2026-10-16")!!.bytes)
        now = Instant.parse("2026-10-15T23:59:59Z")
        assertTrue(budget.allowance().available)
        budget.configure(1, "month"); assertFalse(budget.allowance().available)
        now = Instant.parse("2026-11-01T00:00:00Z"); assertTrue(budget.allowance().available)
    }
    @Test fun leapMonthBoundariesAndOverflowsAreSafe() {
        val w = trafficWindow(Instant.parse("2028-02-29T14:59:59Z"), ZoneId.of("Asia/Tokyo"), "month")
        assertEquals("2028-02-01", w.first); assertEquals("2028-03-01", w.end)
        assertEquals(Instant.parse("2028-02-29T15:00:00Z").toEpochMilli(), w.resetsAt)
        assertEquals(Long.MAX_VALUE, trafficTotal(listOf(TrafficDay("x", Long.MAX_VALUE), TrafficDay("y", 5))))
        assertNull(trafficLimitInput("")); assertNull(trafficLimitInput("0")); assertNull(trafficLimitInput("-1")); assertNull(trafficLimitInput("1.5")); assertNull(trafficLimitInput(Long.MAX_VALUE.toString()))
        assertEquals(1L, trafficLimitInput("1")); assertEquals(Long.MAX_VALUE / mb, trafficLimitInput((Long.MAX_VALUE / mb).toString()))
    }
    @Test fun quotaDoesNotTurnIntoAMissingSearchOrTryOtherProviders() = runTest {
        var fallback = false
        try { searchWithFallback(listOf<suspend () -> List<String>>({ throw TrafficLimitReached() }, { fallback = true; listOf("wrong") })); fail() }
        catch (e: TrafficLimitReached) { assertEquals(AcquisitionFailure.TRAFFIC_LIMIT, acquisitionFailure(e)) }
        assertFalse(fallback)
    }
    @Test fun failedLedgerWritePreventsAnyBodyBytesFromBeingRead() = runTest {
        val index = object : TrafficIndex {
            override fun observe() = flowOf(emptyList<TrafficDay>())
            override fun get(day: String): TrafficDay? = null
            override fun range(first: String, end: String) = emptyList<TrafficDay>()
            override fun put(rows: List<TrafficDay>) { throw java.io.IOException("storage unavailable") }
        }
        val budget = TrafficBudgetRepository(SettingsRepository(MemoryPreferences(), backgroundScope), index, backgroundScope, { date }, { utc })
        budget.configure(1, "day")
        val sink = Buffer()
        TrafficSource(Buffer().writeUtf8("data"), budget) { }.use { try { it.read(sink, 4); fail() } catch (_: java.io.IOException) { } }
        assertEquals(0L, sink.size)
    }
}
