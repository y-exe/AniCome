package net.yexe.anicome

data class AnimeWorkIdentity(val base: String, val season: Int, val cour: Int, val searchTitle: String, val explicitSeason: Boolean)

fun animeWorkIdentity(value: String): AnimeWorkIdentity {
    var text = EpisodeMatcher.normalize(value).replace(Regex("\\((?:19|20)\\d{2}\\)$"), "").trim()
    var season = 1
    var cour = 1
    var explicitSeason = false
    if (text.startsWith("ご注文はうさぎですか")) {
        val suffix = text.removePrefix("ご注文はうさぎですか").trim()
        if (suffix.startsWith("??")) { season = 2; explicitSeason = true }
        if (suffix.contains(Regex("(?:^|[?\\s])BLOOM(?:$|\\s)", RegexOption.IGNORE_CASE))) { season = 3; explicitSeason = true }
    }
    if (text.startsWith("邪神ちゃんドロップキック") && text.removePrefix("邪神ちゃんドロップキック").trim().firstOrNull() in listOf('\'', '’', '′', '\u0060')) {
        season = 2; explicitSeason = true; text = text.replaceFirst(Regex("['’′\\u0060]"), "")
    }
    if (text.startsWith("邪神ちゃんドロップキックX")) { season = 3; explicitSeason = true; text = text.replaceFirst("ドロップキックX", "ドロップキック") }
    text = text.replace("まちカドまぞく 2丁目", "まちカドまぞく 第2期").replace("まちカドまぞく2丁目", "まちカドまぞく 第2期")
    text = text.replace(Regex("\\s+(?:オンエア|放送|通常)(?:ver\\.|版)", RegexOption.IGNORE_CASE), "")
    val numbers = Regex("[0-9〇一二三四五六七八九十]+").pattern
    fun number(value: String) = value.toIntOrNull() ?: EpisodeMatcher.kansujiToNumber(value)
    text = Regex("第?($numbers)\\s*クール|(?:part|cour)\\s*($numbers)", RegexOption.IGNORE_CASE).replace(text) {
        cour = number(it.groupValues[1].ifBlank { it.groupValues[2] }); " "
    }
    text = Regex("第?($numbers)\\s*期|(?:season|シーズン)\\s*($numbers)|($numbers)(?:st|nd|rd|th)\\s*season", RegexOption.IGNORE_CASE).replace(text) {
        season = number(it.groupValues.drop(1).first(String::isNotBlank)); explicitSeason = true; " "
    }
    text = Regex("(?<=[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\s])(?:VIII|VII|VI|IV|III|II|IX|V|X|I)(?=$|[\\s〜~(])").replace(text) {
        season = EpisodeMatcher.romanToNumber(it.value); explicitSeason = true; " "
    }
    text = Regex("(?<=[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}!?\\s])([2-9])(?=$|[\\s〜~(])").replace(text) {
        season = it.groupValues[1].toInt(); explicitSeason = true; " "
    }
    text = text.replace(Regex("\\(\\s*\\)"), "").trim()
    return AnimeWorkIdentity(normalizedEpisodeText(text), season, cour, text, explicitSeason)
}

fun animeSearchStem(value: String): String = animeWorkIdentity(value).searchTitle.substringBefore('〜').trim().ifBlank { value }
