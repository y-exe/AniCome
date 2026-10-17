package net.yexe.anicome

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*

private const val ManualSyncLimit = 3_600_000L
data class SyncUndoState(val source: PlaybackSource?, val before: Long, val after: Long, val id: Long = 0L)
private data class SyncSlotIdentity(val id: String, val sync: CommentSync, val offset: Long, val spans: List<CommentTimeSpan>)
private data class SyncCorrectionContext(val key: String, val binding: CommentBinding, val matched: String, val slots: List<SyncSlotIdentity>)
private data class SyncCorrection(val source: PlaybackSource?, val context: SyncCorrectionContext, val before: Long?, val after: Long, val id: Long)

class SyncAdjustmentRepository(scope: CoroutineScope) {
    private val lock = Any()
    private val pending = MutableStateFlow<SyncCorrection?>(null)
    private var gesture: SyncCorrection? = null
    private var sequence = 0L
    private fun context(source: PlaybackSource?): SyncCorrectionContext {
        val p = Session.manualPlayback.value ?: Session.playback.value
        val binding = Session.commentBinding.value
        return SyncCorrectionContext(p.key, binding, if (binding == CommentBinding.MANUAL) Session.manualCommentKey.value else Session.matchedKey,
            if (source == null) emptyList() else Session.commentSlots.value.filter { it.source == source }.map { SyncSlotIdentity(it.id, it.sync, it.syncOffset, it.syncSpans.toList()) }.sortedBy { it.id })
    }
    private fun current(source: PlaybackSource?) = if (source == null) Session.offset.value else Session.sourceOffsets.value[source]
    private fun valid(change: SyncCorrection) = context(change.source) == change.context && current(change.source) == change.after
    private val contextEvents = combine(Session.playback, Session.manualPlayback, Session.commentBinding, Session.commentSlots) { _, _, _, _ -> Unit }
    val undo = combine(pending, Session.offset, Session.sourceOffsets, contextEvents) { change, _, _, _ ->
        change?.takeIf(::valid)?.let { SyncUndoState(it.source, it.before ?: 0L, it.after, it.id) }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private fun next(current: Long, delta: Long) = (current.coerceIn(-ManualSyncLimit, ManualSyncLimit) + delta.coerceIn(-2 * ManualSyncLimit, 2 * ManualSyncLimit)).coerceIn(-ManualSyncLimit, ManualSyncLimit)
    fun global(delta: Long?) = synchronized(lock) { setGlobalLocked(delta?.let { next(Session.offset.value, it) } ?: 0L) }
    fun setGlobal(value: Long) = synchronized(lock) { setGlobalLocked(value) }
    private fun setGlobalLocked(value: Long) {
        finishPreviewLocked()
        val before = Session.offset.value; val after = value.coerceIn(-ManualSyncLimit, ManualSyncLimit)
        if (before == after) return
        val change = SyncCorrection(null, context(null), before, after, ++sequence)
        Session.offset.value = after; pending.value = change
    }
    fun source(source: PlaybackSource, delta: Long?): Boolean = synchronized(lock) {
        setSourceLocked(source, delta?.let { next(Session.sourceOffsets.value[source] ?: 0L, it) } ?: 0L)
    }
    fun setSource(source: PlaybackSource, value: Long): Boolean = synchronized(lock) { setSourceLocked(source, value) }
    fun alignMarker(candidate: SyncMarkerCandidate, now: Long): Boolean = synchronized(lock) {
        if (candidate.at < 0 || candidate.context != currentSyncMarkerContext() || Session.commentSlots.value.none { it === candidate.slot }) return@synchronized false
        val playback = Session.manualPlayback.value ?: Session.playback.value
        val position = playback.positionAt(now) ?: return@synchronized false
        setSourceLocked(candidate.slot.source, alignedSourceCorrection(position, Session.offset.value, candidate.at))
    }
    private fun setSourceLocked(source: PlaybackSource, value: Long): Boolean {
        finishPreviewLocked()
        val identity = context(source)
        if (identity.slots.isEmpty()) return false
        val before = Session.sourceOffsets.value[source]; val after = value.coerceIn(-ManualSyncLimit, ManualSyncLimit)
        if ((before ?: 0L) == after) return true
        Session.sourceOffsets.update { it + (source to after) }
        pending.value = SyncCorrection(source, identity, before, after, ++sequence)
        return true
    }
    fun previewGlobal(value: Long) = synchronized(lock) {
        val before = Session.offset.value; val after = value.coerceIn(-ManualSyncLimit, ManualSyncLimit)
        if (before == after) return@synchronized
        val identity = context(null)
        val active = gesture?.takeIf { it.context == identity && it.after == before }
        gesture = SyncCorrection(null, identity, active?.before ?: before, after, active?.id ?: ++sequence)
        pending.value = null; Session.offset.value = after
    }
    fun finishGlobalPreview() = synchronized(lock) { finishPreviewLocked() }
    private fun finishPreviewLocked() {
        val change = gesture; gesture = null
        if (change != null && valid(change) && change.before != change.after) pending.value = change
    }
    fun undo(expectedId: Long? = null): Boolean = synchronized(lock) {
        if (expectedId != null && pending.value?.id != expectedId) return@synchronized false
        finishPreviewLocked()
        val change = pending.value ?: return@synchronized false
        pending.value = null; gesture = null
        if (!valid(change)) return@synchronized false
        val source = change.source
        if (source == null) Session.offset.compareAndSet(change.after, change.before ?: 0L)
        else {
            val restored = Session.sourceOffsets.updateAndGet { values ->
                if (values[source] != change.after) values else values.toMutableMap().apply { if (change.before == null) remove(source) else put(source, change.before) }
            }
            if (change.before == null) !restored.containsKey(source) else restored[source] == change.before
        }
    }
}

fun globalDisplayDelay(globalClockOffset: Long) = -globalClockOffset.coerceIn(-ManualSyncLimit, ManualSyncLimit)
fun alignedSourceCorrection(playbackPosition: Long, globalClockOffset: Long, markerPosition: Long): Long {
    require(playbackPosition >= 0L && markerPosition >= 0L)
    return ((playbackPosition - markerPosition).coerceIn(-2 * ManualSyncLimit, 2 * ManualSyncLimit) + globalClockOffset.coerceIn(-ManualSyncLimit, ManualSyncLimit)).coerceIn(-ManualSyncLimit, ManualSyncLimit)
}
