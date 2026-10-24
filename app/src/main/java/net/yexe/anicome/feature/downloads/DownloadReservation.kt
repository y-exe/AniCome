package net.yexe.anicome

fun reservationEpisode(tags: Set<String>): Episode? {
    val series = tags.firstOrNull { it.startsWith("series:") }?.removePrefix("series:")
    val number = tags.firstOrNull { it.startsWith("episode:") }?.removePrefix("episode:")?.toIntOrNull()
    if (!series.isNullOrBlank() && number != null && number in 0..9999) return Episode(series, number)
    return tags.firstOrNull { it.startsWith("label:") }?.removePrefix("label:")?.let { EpisodeMatcher.parse(it) }
}
fun reservationCatalog(tags: Set<String>, episode: Episode): AnimeInfo? {
    val parts = tags.firstOrNull { it.startsWith("catalog:") }?.split(':') ?: return null
    if (parts.size != 5 || parts[1] !in setOf("jikan", "kitsu", "anilist", "channel", "bangumi-data")) return null
    val id = parts[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
    return AnimeInfo(id, episode.series, tags.firstOrNull { it.startsWith("artwork:") }?.removePrefix("artwork:").orEmpty(), "", null, parts[3].toIntOrNull()?.takeIf { it > 0 }, parts[4].toIntOrNull()?.takeIf { it > 0 }, emptyList(), parts[1], tags.firstOrNull { it.startsWith("catalogChannel:") }?.removePrefix("catalogChannel:")?.let(::niconicoChannelName))
}

fun reservationReview(tags: Set<String>): SaveEpisodeReview? {
    val episode = reservationEpisode(tags) ?: return null
    fun field(name: String) = tags.firstOrNull { it.startsWith(name + ":") }?.removePrefix(name + ":")
    val number = field("reviewNumber")?.toIntOrNull()?.takeIf { it in 0..9999 } ?: return null
    val series = field("reviewSeries")?.takeIf { it.isNotBlank() } ?: return null
    val video = field("reviewVideo")?.takeIf { niconicoVideoId(it) != null }?.let { Candidate(it, "") }
    return SaveEpisodeReview(episode, Episode(series, number), field("reviewSubtitle").orEmpty(), video)
}
