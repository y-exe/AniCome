package net.yexe.anicome

import java.net.URI

fun niconicoVideoId(input: String): String? {
    val value = input.trim()
    val pattern = Regex("(?:sm|so|nm)[0-9]+|[0-9]{10}")
    if (pattern.matches(value)) return value
    return runCatching {
        val url = URI(value)
        val host = url.host?.lowercase() ?: return null
        if (url.scheme != "https" && url.scheme != "http") return null
        if (host != "nico.ms" && host != "nicovideo.jp" && !host.endsWith(".nicovideo.jp")) return null
        url.path.substringAfterLast('/').takeIf(pattern::matches)
    }.getOrNull()
}
fun niconicoVideoUrl(input: String): String? = niconicoVideoId(input)?.let { "https://www.nicovideo.jp/watch/$it" }

fun parseNiconicoSource(inputId: String, response: org.json.JSONObject): Candidate {
    val video = response.getJSONObject("video")
    val id = niconicoVideoId(video.optString("id")) ?: niconicoVideoId(inputId) ?: error("動画IDを確認できません")
    val title = video.getString("title").trim()
    require(title.isNotEmpty())
    val thumbnail = video.optJSONObject("thumbnail")
    val image = listOf("large", "largeUrl", "middle", "middleUrl", "normal", "url").firstNotNullOfOrNull { key -> thumbnail?.optString(key)?.takeIf { it.startsWith("https://") } }.orEmpty()
    val channel = response.optJSONObject("channel")
    val owner = channel ?: response.optJSONObject("owner")
    val channelId = channel?.optString("id")?.removePrefix("ch")?.toLongOrNull()?.takeIf { it > 0 }
    val userId = if (channel == null) owner?.optString("id")?.toLongOrNull()?.takeIf { it > 0 } else null
    val ownerUrl = when {
        channelId != null -> "https://ch.nicovideo.jp/ch$channelId"
        userId != null -> "https://www.nicovideo.jp/user/$userId"
        else -> ""
    }
    return Candidate(id, title, channelId = channelId, duration = video.optLong("duration"), userId = userId, thumbnailUrl = image,
        ownerName = owner?.optString(if (channel != null) "name" else "nickname").orEmpty().ifBlank { owner?.optString("name").orEmpty() }, ownerUrl = ownerUrl)
}

fun SavedEpisode.libraryTitle(): String = ownerName.ifBlank { episode.series }
fun SavedEpisode.libraryKey(): String = ownerUrl.ifBlank { ownerName.ifBlank { episode.series } }
fun SavedEpisode.libraryGroupKey(library: List<SavedEpisode>): String = if (ownerUrl.isNotBlank() || ownerName.isNotBlank()) libraryKey() else library.firstOrNull { it.episode.series == episode.series && (it.ownerUrl.isNotBlank() || it.ownerName.isNotBlank()) }?.libraryKey() ?: episode.series
fun SavedEpisode.videoEpisodeNumber(): Int = EpisodeMatcher.extract(sourceTitle).episode?.number ?: episode.number
fun SavedEpisode.matchesPlaybackEpisode(value: Episode): Boolean = episode.key == value.key ||
    (videoEpisodeNumber() == value.number && savedEpisodeMatches(value, Candidate(sourceId, sourceTitle)))
fun Candidate.isNiconicoChannel(): Boolean = channelId?.let { it > 0 } == true
