package net.yexe.anicome

data class SyncMarkerContext(val playbackKey: String, val binding: CommentBinding, val matchedKey: String)
data class SyncMarkerCandidate(val slot: PlaybackCommentSlot, val name: String, val at: Long, val context: SyncMarkerContext)

fun currentSyncMarkerContext(): SyncMarkerContext {
    val playback = Session.manualPlayback.value ?: Session.playback.value
    val binding = Session.commentBinding.value
    return SyncMarkerContext(playback.key, binding, if (binding == CommentBinding.MANUAL) Session.manualCommentKey.value else Session.matchedKey)
}

internal fun analyzeSyncMarkers(slots: List<PlaybackCommentSlot>, context: SyncMarkerContext): Map<PlaybackSource, Map<String, SyncMarkerCandidate>> =
    slots.groupBy { it.source }.mapValues { (_, items) ->
        val slot = items.first()
        timelineMarkers(slot.comments, slot.durationMs).mapKeys { it.key.name }.mapValues { (name, at) -> SyncMarkerCandidate(slot, name, at, context) }
    }
