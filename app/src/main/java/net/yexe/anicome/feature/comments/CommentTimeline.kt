package net.yexe.anicome

import org.json.JSONObject
import kotlin.math.abs

internal enum class TimelineMarker(val expression: Regex) {
    START(Regex("^(ｷﾀ|キタ)[ｰー━].*[!！]$|^きたあ*$|^(始|はじ)まっ?た|hjmt|ｈｊｍｔ$", RegexOption.IGNORE_CASE)),
    OP(Regex("^出?(OP|ＯＰ)$", RegexOption.IGNORE_CASE)),
    A(Regex("^(A|Ａ|本編|本編開始|OP終わった|OPおわた|OP終了)$", RegexOption.IGNORE_CASE)), B(Regex("^(B|Ｂ)$")),
    ED(Regex("^(ED|ＥＤ)$", RegexOption.IGNORE_CASE)), C(Regex("^(C|Ｃ)$"))
}

enum class CommentSync(val label: String, val confirmed: Boolean) {
    NATIVE("本編時刻", true), OFFSET("開始位置を補正", true),
    CUT("CM区間を推定補正", true), START_ONLY("開始位置のみ補正", false),
    UNKNOWN("同期情報が不足", false), DISABLED("自動同期オフ", true)
}

data class CommentTimeSpan(val sourceStart: Long, val sourceEnd: Long, val playbackStart: Long)

internal fun timelineMarkers(comments: List<Comment>, duration: Long): Map<TimelineMarker, Long> {
    val result = linkedMapOf<TimelineMarker, Long>()
    for (kind in TimelineMarker.entries) {
        val matches = comments.filter {
            it.at >= 0 && kind.expression.containsMatchIn(it.body.trim()) &&
                (kind != TimelineMarker.START || it.at <= 300_000) &&
                (duration <= 0 || kind != TimelineMarker.OP || it.at < duration / 2) &&
                (duration <= 0 || kind != TimelineMarker.ED || it.at > duration / 2)
        }.sortedBy { it.at }
        var end = 0
        val clusters = mutableListOf<Pair<Int, Long>>()
        for (start in matches.indices) {
            if (start > 0 && matches[start].at - matches[start - 1].at <= 8_000) continue
            end = maxOf(end, start)
            while (end < matches.size && matches[end].at - matches[start].at <= 8_000) end++
            val window = matches.subList(start, end)
            val voters = window.map { comment ->
                val user = comment.raw?.let { runCatching { JSONObject(it).optString("userId") }.getOrNull() }.orEmpty()
                user.ifBlank { "time:${comment.at}" }
            }.distinct().size
            if (voters >= 3) clusters += voters to (window.first().at + (window.last().at - window.first().at) / 10 - 1_000).coerceAtLeast(0)
        }
        val ranked = clusters.sortedByDescending { it.first }
        val best = ranked.firstOrNull() ?: continue
        if (ranked.drop(1).any { it.first * 5 >= best.first * 4 }) continue
        result[kind] = best.second
    }
    return result
}

fun remapCommentTimeline(comments: List<Comment>, spans: List<CommentTimeSpan>, duration: Long): List<Comment> {
    require(duration > 0 && spans.isNotEmpty())
    require(spans.all { it.sourceStart >= 0 && it.sourceEnd > it.sourceStart && it.playbackStart >= 0 && it.playbackStart <= duration - (it.sourceEnd - it.sourceStart) })
    require(spans.zipWithNext().all { (a, b) -> a.sourceEnd <= b.sourceStart && a.playbackStart + a.sourceEnd - a.sourceStart <= b.playbackStart })
    val ordered = comments.sortedBy { it.at }
    var index = 0
    return ordered.mapNotNull { comment ->
        while (index < spans.size && comment.at >= spans[index].sourceEnd) index++
        val span = spans.getOrNull(index) ?: return@mapNotNull null
        if (comment.at < span.sourceStart) null else comment.copy(at = comment.at - span.sourceStart + span.playbackStart)
    }
}

private fun shifted(slot: PlaybackCommentSlot, shift: Long, duration: Long, status: CommentSync) = slot.copy(
    comments = slot.comments.mapNotNull { c -> (c.at - shift).takeIf { it >= 0 && (duration <= 0 || it < duration) }?.let { c.copy(at = it) } }, sync = status, syncOffset = -shift)

internal fun synchronizeCommentSlots(raw: List<PlaybackCommentSlot>, duration: Long, enabled: Boolean, onlyConfirmed: Boolean): List<PlaybackCommentSlot> {
    if (!enabled) return raw.map { it.copy(sync = CommentSync.DISABLED) }
    val officialDuration = raw.firstOrNull { it.source == PlaybackSource.OFFICIAL && it.durationMs > 0 }?.durationMs
    val playbackDuration = duration.takeIf { it > 0 } ?: officialDuration
        ?: raw.firstOrNull { it.source in setOf(PlaybackSource.DANIME, PlaybackSource.CHAPTER) && it.durationMs > 0 }?.durationMs ?: 0L
    val nativeDuration = officialDuration ?: playbackDuration
    val reference = raw.firstOrNull { it.source == PlaybackSource.OFFICIAL && it.durationMs > 0 && playbackDuration > 0 && abs(it.durationMs - playbackDuration) <= 5_000 }
        ?: raw.firstOrNull { it.source in setOf(PlaybackSource.DANIME, PlaybackSource.CHAPTER) && it.durationMs > 0 && playbackDuration > 0 && abs(it.durationMs - playbackDuration) <= 5_000 }
    val target = reference?.let { timelineMarkers(it.comments, playbackDuration) }.orEmpty()
    return raw.map { slot ->
        when {
            slot === reference || slot.source == PlaybackSource.OFFICIAL -> slot.copy(sync = CommentSync.NATIVE)
            else -> alignSlot(slot, target, playbackDuration, nativeDuration)
        }
    }.filter { !onlyConfirmed || it.sync.confirmed }
}

private fun alignSlot(slot: PlaybackCommentSlot, target: Map<TimelineMarker, Long>, duration: Long, nativeDuration: Long): PlaybackCommentSlot {
    val broadcast = slot.source in setOf(PlaybackSource.JIKKYO, PlaybackSource.LIVE_ARCHIVE)
    val markers = timelineMarkers(slot.comments, slot.durationMs)
    val pairs = TimelineMarker.entries.mapNotNull { k -> markers[k]?.let { a -> target[k]?.let { b -> Triple(k, a, b) } } }
    val deltas = pairs.map { it.second - it.third }.sorted()
    if (pairs.size >= 3 && pairs.any { it.first == TimelineMarker.OP } && pairs.any { it.first == TimelineMarker.ED } && deltas.last() - deltas.first() <= 2_000 && abs(deltas[deltas.size / 2]) <= 300_000) {
        return shifted(slot, deltas[deltas.size / 2], duration, CommentSync.OFFSET)
    }
    val required = listOf(TimelineMarker.START, TimelineMarker.OP, TimelineMarker.A, TimelineMarker.B, TimelineMarker.ED)
    val destination = target.toMutableMap()
    if (broadcast && TimelineMarker.B !in destination && listOf(TimelineMarker.A, TimelineMarker.ED).all { it in markers && it in target } && TimelineMarker.B in markers) {
        val extra = markers.getValue(TimelineMarker.ED) - markers.getValue(TimelineMarker.A) - (target.getValue(TimelineMarker.ED) - target.getValue(TimelineMarker.A))
        val inferred = target.getValue(TimelineMarker.A) + markers.getValue(TimelineMarker.B) - markers.getValue(TimelineMarker.A) - extra
        if (extra in 8_000..240_000 && inferred >= target.getValue(TimelineMarker.A) + 60_000 && inferred <= target.getValue(TimelineMarker.ED) - 60_000) destination[TimelineMarker.B] = inferred
    }
    if (broadcast && duration > 0 && required.all { it in markers && it in destination }) {
        val anchors = required.map { Triple(it, markers.getValue(it), destination.getValue(it)) } +
            listOfNotNull(markers[TimelineMarker.C]?.let { s -> destination[TimelineMarker.C]?.let { t -> Triple(TimelineMarker.C, s, t) } })
        val spans = mutableListOf<CommentTimeSpan>()
        var valid = anchors.first().third <= 5_000
        var removed = 0L
        for ((a, b) in anchors.zipWithNext()) {
            val length = b.third - a.third
            val surplus = b.second - a.second - length
            val cutBoundary = b.first in setOf(TimelineMarker.OP, TimelineMarker.A, TimelineMarker.B, TimelineMarker.C)
            if (length <= 0 || b.second <= a.second || surplus < -2_000 || (surplus > 2_000 && (!cutBoundary || surplus !in 8_000..240_000))) valid = false
            val actualLength = minOf(length, b.second - a.second)
            if (actualLength > 0) spans += CommentTimeSpan(a.second, a.second + actualLength, a.third)
            removed += surplus.coerceAtLeast(0)
        }
        val last = anchors.last()
        if (last.third >= duration || removed > 600_000) valid = false
        else spans += CommentTimeSpan(last.second, last.second + duration - last.third, last.third)
        if (valid && removed >= 8_000) return slot.copy(comments = remapCommentTimeline(slot.comments, spans, duration), sync = CommentSync.CUT, syncSpans = spans)
    }
    if (!broadcast && slot.source in setOf(PlaybackSource.DANIME, PlaybackSource.CHAPTER) && nativeDuration > 0 && slot.durationMs > 0 && abs(slot.durationMs - nativeDuration) <= 5_000 && pairs.size < 3) return slot.copy(sync = CommentSync.NATIVE)
    val start = markers[TimelineMarker.START]
    return if (broadcast && start != null) shifted(slot, start, 0, CommentSync.START_ONLY) else slot.copy(sync = CommentSync.UNKNOWN)
}
