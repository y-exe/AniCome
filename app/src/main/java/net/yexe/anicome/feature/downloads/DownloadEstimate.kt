package net.yexe.anicome

import androidx.work.WorkInfo
import kotlin.math.ceil

internal const val MAX_DOWNLOAD_ESTIMATE = 128L * 1024 * 1024 * 20 * 7
internal fun estimatedCommentBytes(items: List<SavedEpisode>, amount: Int, sources: Int): Long {
    val sizes = items.filter { it.bytes > 0 }.map { it.bytes / it.amount.coerceAtLeast(1) / it.sources.split(",").filter(String::isNotBlank).size.coerceAtLeast(1) }.sorted()
    val unit = if (sizes.isEmpty()) 1_048_576L else sizes[sizes.size / 2].coerceIn(262_144L, 134_217_728L)
    return (unit * amount.coerceIn(1, 20) * sources.coerceIn(1, 7)).coerceAtMost(MAX_DOWNLOAD_ESTIMATE)
}
internal class ReservationDurationHistory(rows: List<ReservationEntry>) {
    private data class Key(val series: String, val amount: Int, val sources: Set<PlaybackSource>)
    private val medians: Map<Key, Long>
    init {
        val samples = mutableMapOf<Key, MutableList<Long>>()
        rows.filter { it.state == "SUCCEEDED" }.sortedByDescending { it.createdAt }.forEach { entry ->
            val output = readReservationOutput(entry.output)
            val elapsed = output.getLong("elapsedMs", 0)
            val amount = output.getInt("amount", 0)
            if (output.getBoolean("downloadedNew", false) && elapsed in 1000..86_400_000L && amount in 1..20) {
                val sources = entry.spec.sources
                listOf(Key(entry.series, amount, sources), Key("", amount, sources)).forEach { key ->
                    samples.getOrPut(key) { mutableListOf() }.let { if (it.size < 30) it += elapsed }
                }
            }
        }
        medians = samples.mapValues { (_, values) -> values.sorted()[values.size / 2] }
    }
    fun estimate(spec: ReservationSpec) = medians[Key(spec.episode.series, spec.amount, spec.sources)] ?: medians[Key("", spec.amount, spec.sources)]
}
internal class DownloadProgressEstimator {
    private data class Sample(val time: Long, val current: Int)
    private data class Track(val target: Int, val samples: MutableList<Sample>)
    private val tracks = linkedMapOf<String, Track>()
    @Synchronized fun remaining(label: String, current: Int, target: Int, now: Long): Long? {
        if (target <= 0 || current < 0 || current >= target) { tracks.remove(label); return null }
        val old = tracks[label]
        val track = if (old == null || old.target != target || old.samples.lastOrNull()?.let { current < it.current || now < it.time } == true) Track(target, mutableListOf()).also { tracks[label] = it } else old
        if (tracks.size > 12) tracks.remove(tracks.keys.first())
        track.samples += Sample(now, current)
        while (track.samples.size > 8 || track.samples.size > 2 && now - track.samples.first().time > 10_000L) track.samples.removeAt(0)
        val first = track.samples.first()
        val duration = now - first.time
        val received = current - first.current
        if (duration < 1500 || received < 10) return null
        return ceil((target - current).toDouble() / received * duration).toLong().takeIf { it in 1..86_400_000L }
    }
}
internal fun downloadDurationLabel(milliseconds: Long): String = if (milliseconds < 60_000L) "約${((milliseconds.coerceAtLeast(1) + 4_999L) / 5_000L) * 5L}秒"
    else "約${(milliseconds + 59_999L) / 60_000L}分"
internal fun reservationTimeLabel(work: WorkInfo): String? {
    if (work.state.isFinished || reservationPaused(work)) return null
    val duration = work.progress.getLong("reservationDurationMs", 0)
    val elapsed = work.progress.getLong("downloadElapsedMs", 0)
    val phase = work.progress.getLong("phaseRemainingMs", 0)
    return when {
        work.state == WorkInfo.State.RUNNING && duration > elapsed -> "保存完了までの目安：${downloadDurationLabel(duration - elapsed)}"
        work.state == WorkInfo.State.RUNNING && phase > 0 -> "現在の取得元の残り目安：${downloadDurationLabel(phase)}"
        work.state == WorkInfo.State.RUNNING -> "残り時間を計測中"
        duration > 0 -> "保存時間の目安：${downloadDurationLabel(duration)}"
        else -> null
    }
}
