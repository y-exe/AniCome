package net.yexe.anicome

import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import okhttp3.ResponseBody

internal class TrafficSource(private val input: BufferedSource, private val budget: TrafficBudgetRepository, private val ensureActive: () -> Unit) : ForwardingSource(input) {
    private var grant: TrafficGrant? = null
    private var remaining = 0L
    private var closed = false
    override fun read(sink: Buffer, byteCount: Long): Long {
        check(!closed)
        require(byteCount >= 0L)
        if (byteCount == 0L) return 0L
        ensureActive()
        if (input.exhausted()) return -1L
        val previous = grant
        if (previous != null && !budget.current(previous)) { budget.refund(previous, remaining); grant = null; remaining = 0L }
        if (remaining == 0L) {
            grant = budget.grant(budget.chunkSize())
            remaining = grant!!.bytes
        }
        val received = input.read(sink, minOf(byteCount, remaining))
        if (received > 0L) remaining -= received
        return received
    }
    override fun close() {
        if (closed) return
        closed = true
        try { grant?.let { budget.refund(it, remaining) } } finally { super.close() }
    }
}
internal fun ResponseBody.withTrafficBudget(budget: TrafficBudgetRepository?, check: () -> Unit): ResponseBody {
    if (budget == null) return this
    val original = this
    val source = TrafficSource(original.source(), budget, check).buffer()
    return object : ResponseBody() {
        override fun contentType() = original.contentType()
        override fun contentLength() = original.contentLength()
        override fun source() = source
    }
}
