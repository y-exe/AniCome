package net.yexe.anicome

 
object PlaybackMetadata {
    fun withNotification(session: List<Pair<String, String?>>, notification: List<Pair<String, String?>>): List<Pair<String, String?>> =
        if (resolve(session).first.isBlank()) notification else session

    fun searchTitle(fields: List<Pair<String, String?>>): String =
        listOf("TITLE", "DISPLAY_TITLE", "NOTIFICATION_TITLE").firstNotNullOfOrNull { label ->
            fields.firstOrNull { it.first == label }?.second?.trim()?.takeIf(String::isNotBlank)
        } ?: resolve(fields).first

    fun isAdvertisement(fields: List<Pair<String, String?>>): Boolean {
        val text = fields.mapNotNull { it.second }.joinToString(" ").lowercase()
        return Regex("(?:^|\\s)(?:ad|ad break|advertisement|cm)(?:\\s|$)|広告|コマーシャル|スポンサー", RegexOption.IGNORE_CASE)
            .containsMatchIn(text)
    }
    fun resolve(fields: List<Pair<String, String?>>): Pair<String, String> = runCatching {
        val values = fields.mapNotNull { it.second?.trim()?.takeIf { value -> value.isNotBlank() && value != "DMM TV" } }.distinct()

         
        val specialEpisode = values.firstOrNull { EpisodeMatcher.episodeNumber(it) != null && EpisodeMatcher.parse(it) == null }
        if (specialEpisode != null) {
            val number = EpisodeMatcher.episodeNumber(specialEpisode)!!
            val series = fields.filter { it.first == "ARTIST" || it.first == "ALBUM" || it.first == "DISPLAY_SUBTITLE" }
                .mapNotNull { it.second?.trim()?.takeIf { value -> value.isNotBlank() && EpisodeMatcher.episodeNumber(value) == null } }
                .maxByOrNull { EpisodeMatcher.normalize(it).length }
            if (!series.isNullOrBlank()) {
                val tail = specialEpisode.replaceFirst(Regex("(?<![\\p{L}0-9])(?:step|episode|ep\\.?|エピソード)\\s*[0-9]+", RegexOption.IGNORE_CASE), "").trim()
                return series to "第${number}話${if (tail.isBlank()) "" else " $tail"}"
            }
        }

         
        for (series in values) for (part in values) {
            if (series == part) continue
            val episode = EpisodeMatcher.parse(series, part) ?: continue
            if (EpisodeMatcher.normalize(episode.series) == EpisodeMatcher.normalize(series)) return series to "第${episode.number}話"
        }

         
        values.forEach { value -> EpisodeMatcher.parse(value)?.let { return it.series to "第${it.number}話" } }

        values.firstOrNull().orEmpty() to values.getOrNull(1).orEmpty()
    }.getOrElse {
        fields.firstOrNull()?.second.orEmpty() to fields.getOrNull(1)?.second.orEmpty()
    }
}
