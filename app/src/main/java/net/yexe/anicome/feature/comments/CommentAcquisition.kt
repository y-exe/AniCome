package net.yexe.anicome

internal fun commentTarget(amount: Int): Int = (amount.coerceAtLeast(1).toLong() * 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

internal fun newestComments(comments: List<Comment>, limit: Int): List<Comment> {
    if (limit <= 0 || comments.size <= limit) return comments
    return comments.mapIndexed { index, comment -> Triple(comment,
        runCatching { org.json.JSONObject(comment.raw.orEmpty()).optString("postedAt") }.getOrDefault(""), index)
    }.sortedWith(compareByDescending<Triple<Comment, String, Int>> { it.second }.thenByDescending { it.third })
        .take(limit).map { it.first }.sortedBy { it.at }
}

internal fun limitCommentSlots(slots: List<PlaybackCommentSlot>, limit: Int): List<PlaybackCommentSlot> {
    if (limit <= 0 || slots.sumOf { it.comments.size.toLong() } <= limit) return slots
    val counts = IntArray(slots.size)
    var remaining = limit
    while (remaining > 0) {
        val active = slots.indices.filter { counts[it] < slots[it].comments.size }
        if (active.isEmpty()) break
        val share = (remaining / active.size).coerceAtLeast(1)
        for (index in active) {
            val take = minOf(share, remaining, slots[index].comments.size - counts[index])
            counts[index] += take
            remaining -= take
            if (remaining == 0) break
        }
    }
    return slots.mapIndexedNotNull { i, slot -> if (counts[i] == 0) null else slot.copy(comments = newestComments(slot.comments, counts[i]), countLimit = counts[i]) }
}

internal fun PlaybackComments.limited(limit: Int) = if (limit <= 0) this else copy(slots = limitCommentSlots(slots, limit))

internal fun SavedComments.satisfiesCommentTarget(amount: Int, maximum: Int): Boolean {
    val sourceLimit = slots.firstOrNull { it.id == info.sourceId }?.countLimit ?: countLimit
    return if (maximum > 0) comments.size >= maximum || sourceLimit >= maximum else sourceLimit == 0 && info.amount >= Comments.availableAmount(amount)
}

internal fun SavedComments.limited(maximum: Int): SavedComments {
    if (maximum <= 0) return this
    val limitedSlots = limitCommentSlots(slots, maximum)
    val primary = limitedSlots.firstOrNull { it.id == info.sourceId } ?: limitedSlots.firstOrNull()
    val limitedComments = primary?.comments ?: newestComments(comments, maximum)
    return copy(info = info.copy(count = if (limitedSlots.isEmpty()) limitedComments.size else limitedSlots.sumOf { it.comments.size },
        sourceId = primary?.id ?: info.sourceId, sourceTitle = primary?.title ?: info.sourceTitle), comments = limitedComments, slots = limitedSlots)
}
