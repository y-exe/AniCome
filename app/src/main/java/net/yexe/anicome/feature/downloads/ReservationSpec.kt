package net.yexe.anicome

import androidx.work.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

data class ReservationSpec(val episode: Episode, val origin: String = "manual", val catalog: AnimeInfo? = null, val channel: Long? = null,
    val wifi: Boolean = true, val charging: Boolean = false, val sources: Set<PlaybackSource> = OfflineSources, val reviewed: SaveEpisodeReview? = null,
    val amount: Int = 0, val estimatedBytes: Long = 0L, val maxComments: Int = 0) {
    val name get() = DownloadWorker.name(episode, origin)
    fun tags() = buildSet {
        add(DownloadWorker.TAG); add(origin); add("series:${episode.series}"); add("episode:${episode.number}"); add("label:${episode.label.take(300)}")
        add("sources:${sources.map { it.name }.sorted().joinToString(",")}")
        if (amount > 0) add("amount:$amount")
        if (maxComments > 0) add("maxComments:$maxComments")
        if (estimatedBytes > 0) add("estimatedBytes:$estimatedBytes")
        channel?.let { add("channel:$it") }
        catalog?.let { work -> add("catalog:${work.provider}:${work.id}:${work.year ?: 0}:${work.episodes ?: 0}"); if (work.imageUrl.isNotBlank()) add("artwork:${work.imageUrl}"); work.niconicoChannel?.let { add("catalogChannel:$it") } }
        reviewed?.let { add("reviewNumber:${it.searchEpisode.number}"); add("reviewSeries:${it.searchEpisode.series}"); add("reviewSubtitle:${it.subtitle}"); it.candidate?.let { candidate -> add("reviewVideo:${candidate.id}") } }
    }
    fun input() = workDataOf("series" to episode.series, "number" to episode.number, "origin" to origin,
        "catalogChannel" to catalog?.niconicoChannel.orEmpty(), "catalogId" to (catalog?.id ?: 0), "catalogProvider" to catalog?.provider.orEmpty(), "catalogYear" to (catalog?.year ?: 0), "catalogEpisodes" to (catalog?.episodes ?: 0),
        "channelId" to (channel ?: 0L), "sources" to sources.map { it.name }.sorted().toTypedArray(), "reviewed" to (reviewed != null),
        "reviewVideo" to reviewed?.candidate?.id.orEmpty(), "reviewSeries" to reviewed?.searchEpisode?.series.orEmpty(), "reviewNumber" to (reviewed?.searchEpisode?.number ?: episode.number), "reviewSubtitle" to reviewed?.subtitle.orEmpty(), "amount" to amount, "estimatedBytes" to estimatedBytes, "maxComments" to maxComments)
    fun request(id: UUID, reservation: String) = OneTimeWorkRequestBuilder<DownloadWorker>().setId(id).setInputData(input())
        .setConstraints(Constraints.Builder().setRequiresCharging(charging).setRequiredNetworkType(if (wifi) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).apply { tags().forEach(::addTag); addTag("managed-reservation:$reservation") }.build()
    fun encode() = JSONObject().put("series", episode.series).put("episode", episode.number).put("origin", origin).put("wifi", wifi).put("charging", charging)
        .put("tags", JSONArray(tags().toList())).toString()
    companion object {
        fun decode(text: String): ReservationSpec {
            val root = JSONObject(text)
            val tags = root.getJSONArray("tags").let { rows -> (0 until rows.length()).map { rows.getString(it) }.toSet() }
            val episode = Episode(root.getString("series"), root.getInt("episode"))
            require(episode.series.isNotBlank() && episode.series.length <= 300 && episode.number in 0..9999)
            return fromTags(tags, root.getBoolean("wifi"), root.getBoolean("charging"))!!.also { require(it.episode == episode && it.origin == root.getString("origin")) }
        }
        fun fromTags(tags: Set<String>, wifi: Boolean, charging: Boolean): ReservationSpec? {
            val episode = reservationEpisode(tags) ?: return null
            val origin = listOf("manual", "playback", "notification", "prefetch").firstOrNull { it in tags } ?: "manual"
            val sources = tags.firstOrNull { it.startsWith("sources:") }?.removePrefix("sources:")?.split(",")?.mapNotNull { name -> PlaybackSource.entries.find { it.name == name } }?.toSet() ?: OfflineSources
            val amount = tags.firstOrNull { it.startsWith("amount:") }?.removePrefix("amount:")?.toIntOrNull() ?: 0
            val maximum = tags.firstOrNull { it.startsWith("maxComments:") }?.removePrefix("maxComments:")?.toIntOrNull() ?: 0
            require(maximum >= 0)
            val bytes = tags.firstOrNull { it.startsWith("estimatedBytes:") }?.removePrefix("estimatedBytes:")?.toLongOrNull() ?: 0L
            require(amount >= 0 && bytes in 0..MAX_DOWNLOAD_ESTIMATE)
            return ReservationSpec(episode, origin, reservationCatalog(tags, episode), tags.firstOrNull { it.startsWith("channel:") }?.removePrefix("channel:")?.toLongOrNull(), wifi, charging, sources, reservationReview(tags), amount, bytes, maximum)
        }
    }
}
internal data class ReservationOutcome(val result: ListenableWorker.Result, val state: WorkInfo.State? = null, val output: Data = Data.EMPTY) {
    companion object {
        fun success(data: Data = Data.EMPTY) = ReservationOutcome(ListenableWorker.Result.success(data), WorkInfo.State.SUCCEEDED, data)
        fun failure(data: Data = Data.EMPTY) = ReservationOutcome(ListenableWorker.Result.failure(data), WorkInfo.State.FAILED, data)
        fun retry() = ReservationOutcome(ListenableWorker.Result.retry())
    }
}
fun reservationPaused(work: WorkInfo) = work.progress.getBoolean("reservationPaused", false)
fun reservationManaged(work: WorkInfo) = work.progress.getBoolean("reservationManaged", false)

internal fun reservationOutput(data: Data) = JSONObject().apply {
    listOf("message", "failureKind", "sources").forEach { key -> data.getString(key)?.let { put(key, it) } }
    data.getStringArray("missingSources")?.let { put("missingSources", JSONArray(it.toList())) }
    listOf("elapsedMs", "bytes").forEach { key -> if (data.keyValueMap.containsKey(key)) put(key, data.getLong(key, 0L)) }
    if (data.keyValueMap.containsKey("amount")) put("amount", data.getInt("amount", 0))
    if (data.keyValueMap.containsKey("downloadedNew")) put("downloadedNew", data.getBoolean("downloadedNew", false))
}.toString()
internal fun readReservationOutput(text: String): Data = if (text.isBlank()) Data.EMPTY else runCatching {
    val root = JSONObject(text)
    Data.Builder().apply {
        listOf("message", "failureKind", "sources").forEach { key -> if (root.has(key)) putString(key, root.getString(key)) }
        root.optJSONArray("missingSources")?.let { rows -> putStringArray("missingSources", Array(rows.length()) { rows.getString(it) }) }
        listOf("elapsedMs", "bytes").forEach { key -> if (root.has(key)) putLong(key, root.getLong(key)) }
        if (root.has("amount")) putInt("amount", root.getInt("amount"))
        if (root.has("downloadedNew")) putBoolean("downloadedNew", root.getBoolean("downloadedNew"))
    }.build()
}.getOrDefault(Data.EMPTY)
