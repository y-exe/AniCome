package net.yexe.anicome

private fun episodeSubtitleAgrees(expected: String, actual: String, sameEpisodeNumber: Boolean): Boolean {
    if (expected == actual) return true
    if (!sameEpisodeNumber || minOf(expected.length, actual.length) < 12 || kotlin.math.abs(expected.length - actual.length) > 1) return false
    var left = 0
    var right = 0
    var edits = 0
    while (left < expected.length && right < actual.length) {
        if (expected[left] == actual[right]) { left++; right++; continue }
        if (++edits > 1) return false
        if (expected.length >= actual.length) left++
        if (actual.length >= expected.length) right++
    }
    return edits + (expected.length - left) + (actual.length - right) <= 1
}

fun savedEpisodeMatches(episode: Episode, candidate: Candidate, subtitle: String = ""): Boolean {
    val expected = EpisodeMatcher.extract(commentSearchQuery(episode.series, episode.number))
    val actual = EpisodeMatcher.extract(candidate.title)
    if (!actual.isSingleEpisode || actual.episode?.number?.let { it != episode.number } == true) return false
    val wantedWork = animeWorkIdentity(episode.series)
    val cleanTitle = EpisodeMatcher.clean(EpisodeMatcher.normalize(candidate.title))
    val videoWork = animeWorkIdentity(actual.episode?.let { cleanTitle.take(it.indices.first) } ?: cleanTitle.removeSuffix(subtitle).trim())
    if ((videoWork.explicitSeason || wantedWork.explicitSeason) && wantedWork.season != videoWork.season) return false
    if (wantedWork.cour != videoWork.cour && videoWork.cour != 1) return false
    val expectedTitle = normalizedEpisodeText(expected.titleStripped ?: expected.title ?: episode.series)
    val actualTitle = normalizedEpisodeText(actual.titleStripped ?: actual.title.orEmpty())
    val wantedSubtitle = normalizedEpisodeText(subtitle)
    val fullTitle = normalizedEpisodeText(candidate.title)
    val parsedSubtitle = actual.subtitleStripped ?: actual.subtitle
    val subtitleMatches = wantedSubtitle.isNotBlank() && if (!parsedSubtitle.isNullOrBlank())
        episodeSubtitleAgrees(wantedSubtitle, normalizedEpisodeText(parsedSubtitle), actual.episode?.number == episode.number) else fullTitle.endsWith(wantedSubtitle)
    val withoutSubtitle = if (subtitleMatches) fullTitle.removeSuffix(wantedSubtitle) else fullTitle
    val titleMatches = expectedTitle.isNotBlank() && (wantedWork.base == videoWork.base || expectedTitle == actualTitle || EpisodeMatcher.compare(expected, actual, strict = true) ||
        withoutSubtitle == normalizedEpisodeText(episode.series))
    if (!titleMatches) return false
    if (wantedWork.season > 1 && !videoWork.explicitSeason && !subtitleMatches) return false
    if (wantedWork.cour > 1 && videoWork.cour == 1 && !subtitleMatches) return false
    if (actual.episode == null) return subtitleMatches && withoutSubtitle == normalizedEpisodeText(episode.series)
    if (wantedSubtitle.isNotBlank() && !actual.subtitle.isNullOrBlank() && !subtitleMatches) return false
    return true
}
