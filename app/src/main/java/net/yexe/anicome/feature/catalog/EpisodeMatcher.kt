package net.yexe.anicome

import java.text.Normalizer
import java.util.Locale
import kotlin.math.max

data class Episode(val series: String, val number: Int) {
    val key: String get() = "${EpisodeMatcher.normalize(series)}:$number"
    val label: String get() = "$series 第${number}話"
}

data class Season(
    val text: String,
    val number: Int,
    val numberText: String,
    val prefix: String = "",
    val suffix: String = "",
    val indices: IntRange = 0..0
)

data class EpisodeItem(
    val text: String,
    val number: Int,
    val numberText: String,
    val prefix: String = "",
    val suffix: String = "",
    val indices: IntRange = 0..0
)

data class ExtractedResult(
    val input: String,
    val title: String? = null,
    val titleStripped: String? = null,
    val season: Season? = null,
    val seasonAlt: Season? = null,
    val episode: EpisodeItem? = null,
    val episodeAlt: EpisodeItem? = null,
    val episodes: List<EpisodeItem>? = null,
    val episodesDivider: String? = null,
    val isSingleEpisode: Boolean = true,
    val subtitle: String? = null,
    val subtitleStripped: String? = null
)

 


object EpisodeMatcher {
    val episodeUnits = listOf("話目", "話", "羽", "回", "の怪", "時間目", "限目", "幕", "章", "占", "譚", "夜", "曲", "局", "品", "膳", "皿", "席", "口", "組", "撃", "戦", "条", "札", "服", "振", "駅", "匹", "件", "日目", "の巻")

    private val titleVariants = listOf(
        listOf("STEINS;GATE", "シュタインズ・ゲート"),
        listOf("その着せ替え人形は恋をする", "その着せ替え人形(ビスク・ドール)は恋をする"),
        listOf("人造昆虫カブトボーグ", "人造昆虫カブトボーグ VxV")
    )

    private val HYPHENS = charArrayOf('‐', '‑', '‒', '–', '—', '―', '⁃', '−', '﹘')
    private val TILDES = charArrayOf('~', '∼', '〰')

    private val KANSUJI_DIGITS = mapOf(
        '〇' to 0, '零' to 0, '一' to 1, '壱' to 1, '壹' to 1,
        '二' to 2, 'ニ' to 2, '弐' to 2, '弍' to 2, '貳' to 2, '貮' to 2,
        '三' to 3, '参' to 3, '參' to 3, '四' to 4, '肆' to 4,
        '五' to 5, '伍' to 5, '六' to 6, '陸' to 6, '七' to 7, '漆' to 7,
        '八' to 8, '捌' to 8, '九' to 9, '玖' to 9
    )

    private val excluded = Regex("MAD|切り抜き|リアクション|同時視聴|予告|ダイジェスト|まとめ|一挙|振り返り", RegexOption.IGNORE_CASE)

     
    fun normalize(input: String): String {
        var text = Normalizer.normalize(input, Normalizer.Form.NFKC)
        text = text.replace(Regex("[\u200B\u2060\uFEFF]"), "")
        text = text.replace("\n", " ")
        text = text.replace(Regex("(?<=\\p{IsKatakana})―(?=\\p{IsKatakana})"), "ー")
        for (c in HYPHENS) text = text.replace(c, '-')
        for (c in TILDES) text = text.replace(c, '〜')
        return text.replace(Regex("\\s+"), " ").trim()
    }

    fun removeSpaces(text: String): String = text.replace(Regex("\\s+"), "")

    fun removeSymbols(text: String): String =
        text.replace(Regex("[\\p{Punct}\\p{S}]"), " ").replace(Regex("\\s+"), " ").trim()

    fun normalizeAll(input: String): String = removeSpaces(removeSymbols(normalize(input)))

     
    fun clean(input: String): String {
        var text = input
         
        val honpenMatch = Regex("(.+)\\s本編\\s(.+)").find(text)
        if (honpenMatch != null) {
            val t1 = honpenMatch.groupValues[1]
            val t2 = honpenMatch.groupValues[2]
            text = if (t2.startsWith(t1)) t2 else t1
        }

         
        val inBrackets = Regex("【.+?】|\\(.+?\\)|\\[.+?\\]")
        text = inBrackets.replace(text) { m ->
            val v = m.value
            if (v.contains(Regex("吹き?替え?|字幕|コメント専?用|szbh方式|(prime|プライム)[\\s・]?(video|ビデオ)|(dvd|bd|blu-?ray|ブルーレイ)用?", RegexOption.IGNORE_CASE))) ""
            else v
        }

         
        text = text.replace(Regex("^(?:tv|テレビ)?アニメ(?:ーション)?\\s*「(?<t>.+?)」", RegexOption.IGNORE_CASE)) { it.groups["t"]!!.value + " " }
        text = text.replace(Regex("^(?:tv|テレビ)?アニメ(?:ーション)?\\s*『(?<t>.+?)』", RegexOption.IGNORE_CASE)) { it.groups["t"]!!.value + " " }
        text = text.replace(Regex("^(?:tv|テレビ)?アニメ(?:ーション)?\\s*(?<t>.+)", RegexOption.IGNORE_CASE)) { it.groups["t"]!!.value + " " }
        text = text.replace(Regex("(?:tv|テレビ)アニメ(?:ーション)?(?:作品)?", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("\\s本編$"), "")
        text = text.replace(Regex("\\s(?:吹き?替え?|字幕)版?$"), "")
        text = text.replace(Regex("コメント専?用(?:動画)?|szbh方式", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("(?:amazon|アマゾン)?\\s*(?:prime|プライム)[\\s・]?(?:video|ビデオ)用?", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("(?:dvd/(?:bd|blu-?ray|ブルーレイ)|(?:bd|blu-?ray|ブルーレイ)/dvd)用?", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("(?:dvd|bd|blu-?ray|ブルーレイ)用", RegexOption.IGNORE_CASE), "")

        return text.replace(Regex("\\s+"), " ").trim()
    }

     
    fun kansujiToNumber(s: String): Int {
        var total = 0
        var current = 0
        for (ch in s) {
            when (ch) {
                '十', '拾' -> { total += (if (current == 0) 1 else current) * 10; current = 0 }
                '百', '陌', '佰' -> { total += (if (current == 0) 1 else current) * 100; current = 0 }
                '千', '阡', '仟' -> { total += (if (current == 0) 1 else current) * 1000; current = 0 }
                '万', '萬' -> { total = (total + current) * 10000; current = 0 }
                else -> {
                    val digit = KANSUJI_DIGITS[ch] ?: 0
                    current = current * 10 + digit
                }
            }
        }
        return total + current
    }

    fun numberToKanji(num: Int): String {
        if (num <= 0 || num > 9999) return num.toString()
        val digits = "〇一二三四五六七八九"
        val sb = StringBuilder()
        val thousands = num / 1000
        val hundreds = (num % 1000) / 100
        val tens = (num % 100) / 10
        val ones = num % 10
        if (thousands > 0) { if (thousands > 1) sb.append(digits[thousands]); sb.append("千") }
        if (hundreds > 0) { if (hundreds > 1) sb.append(digits[hundreds]); sb.append("百") }
        if (tens > 0) { if (tens > 1) sb.append(digits[tens]); sb.append("十") }
        if (ones > 0 || sb.isEmpty()) sb.append(digits[ones])
        return sb.toString()
    }

    fun romanToNumber(s: String): Int {
        val map = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
        var total = 0
        var prev = 0
        for (i in s.indices.reversed()) {
            val curr = map[s[i].uppercaseChar()] ?: 0
            if (curr < prev) total -= curr else total += curr
            prev = curr
        }
        return total
    }

     
    fun stripText(input: String): String {
        var res = input
         
        res = res.replace(Regex("([a-zA-Z\\p{IsHiragana}\\p{IsHan}])\\s*\\(\\p{IsKatakana}+\\)")) { it.groupValues[1] }
        res = res.replace(Regex("([a-zA-Z\\p{IsKatakana}\\p{IsHan}])\\s*\\(\\p{IsHiragana}+\\)")) { it.groupValues[1] }
         
        res = res.replace(Regex("^([^\\-]+?)\\s*-[a-zA-Z][a-zA-Z'\\s]*-")) { it.groupValues[1] }
         
        res = res.replace(Regex("^([^〜]+?)\\s*〜[^〜]+〜")) { it.groupValues[1] }
        return res.trim()
    }

    fun stripTitle(title: String): String {
        var res = stripText(title)
        res = res.replace(Regex("(.+?)\\s*(?:\\s.+?版|\\(.+?版\\)|<.+?版>|【.+?版】|《.+?版》)$", RegexOption.IGNORE_CASE)) { it.groupValues[1] }
        res = res.replace(Regex("(.+?)\\s*第[\\d〇一二三四五六七八九十]クール$")) { it.groupValues[1] }
        res = res.replace(Regex("(.+?)\\s*\\((?:19|20)\\d{2}\\)$")) { it.groupValues[1] }
        return res.trim()
    }

     
    private fun extractSeasonsList(input: String): List<Season> {
        val list = mutableListOf<Season>()
        val regexes = listOf(
            Regex("(?:^|.+?)(?<prefix>第?)(?<num>\\d{1,2})(?<suffix>期)(?:$|[^\\p{L}\\p{N}])"),
            Regex("(?:^|.+?)(?<prefix>第?)(?<num>[〇一二ニ三四五六七八九十]{1,3})(?<suffix>期)(?:$|[^\\p{L}\\p{N}])"),
            Regex("(?:^|.+?)(?<num>[〇一二ニ三四五六七八九十])(?<suffix>ノ章)(?:$|[^\\p{L}\\p{N}])"),
            Regex("(?:^|.+?)(?<prefix>シーズン[.:]?)(?<num>\\d{1,2})(?:$|[^\\p{L}\\p{N}])"),
            Regex("(?:^|.+?)(?<prefix>第)(?<num>\\d{1,2})(?<suffix>シリーズ|シーズン)(?:$|[^\\p{L}\\p{N}])"),
            Regex("(?:^|.+?)(?<prefix>第)(?<num>[〇一二ニ三四五六七八九十]{1,3})(?<suffix>シリーズ|シーズン)(?:$|[^\\p{L}\\p{N}])"),
            Regex("(?:^|.+?)(?<num>\\d{1,2})(?:st|nd|rd|th)(?<suffix>\\s*season|シーズン)(?:$|[^\\p{L}\\p{N}])", RegexOption.IGNORE_CASE),
            Regex("(?:^|.+?)(?<prefix>season[\\s.:]?)(?<num>\\d{1,2})(?:$|[^\\p{L}\\p{N}])", RegexOption.IGNORE_CASE),
            Regex("(?:^|.+?)(?<prefix>[Ss]eason[\\s.:]?|SEASON[\\s.:])(?<rom>[IVX]+)(?:$|[^\\p{L}\\p{N}])"),
            Regex("(?:^|.+?)(?<word>ファースト|セカンド|サード)(?<suffix>シーズン)(?:$|[^\\p{L}\\p{N}])"),
            Regex("(?:^|.+?)(?<prefix>the\\s+)?(?<eng>first|second|third|final)(?<suffix>\\s*season)(?:$|[^\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
        )
        for (re in regexes) {
            for (m in re.findAll(input)) {
                fun group(name: String) = runCatching { m.groups[name] }.getOrNull()
                var num = 0
                var numStr = ""
                val prefix = group("prefix")?.value ?: ""
                val suffix = group("suffix")?.value ?: ""
                if (group("num") != null) {
                    numStr = group("num")!!.value
                    num = numStr.toIntOrNull() ?: kansujiToNumber(numStr)
                } else if (group("rom") != null) {
                    numStr = group("rom")!!.value
                    num = romanToNumber(numStr)
                } else if (group("word") != null) {
                    numStr = group("word")!!.value
                    num = when (numStr) { "ファースト" -> 1; "セカンド" -> 2; "サード" -> 3 else -> 0 }
                } else if (group("eng") != null) {
                    numStr = group("eng")!!.value
                    num = when (numStr.lowercase()) { "first" -> 1; "second" -> 2; "third" -> 3; "final" -> 99 else -> 0 }
                }
                if (num > 0) {
                    val groupRange = group("num")?.range ?: group("rom")?.range ?: group("word")?.range ?: group("eng")?.range ?: m.range
                    val start = (group("prefix")?.range?.first ?: groupRange.first)
                    val end = (group("suffix")?.range?.last ?: groupRange.last)
                    if (start in 0..end && end < input.length) {
                        list.add(Season(input.substring(start, end + 1), num, numStr, prefix, suffix, start..end))
                    }
                }
            }
        }

         
        val seasonFromTitleRegex = Regex("(?:^|.+?\\s)(?<rom>IV|IX|V?I{1,3}|X)(?:$|\\s|(?=第?[0-9〇一二三四五六七八九十]+話))")
        for (m in seasonFromTitleRegex.findAll(input)) {
            val rom = m.groups["rom"]!!.value
            val num = romanToNumber(rom)
            if (num > 0) {
                val range = m.groups["rom"]!!.range
                list.add(Season(rom, num, rom, "", "", range))
            }
        }

        return list.distinctBy { it.indices.first to it.indices.last }
    }

    private fun extractEpisodesList(input: String): List<EpisodeItem> {
        val list = mutableListOf<EpisodeItem>()

         
        val epRegex1 = Regex("(?:^|[\\s\\(\\[【〜\\-/・])(?<prefix>第?\\s*)(?<num>\\d{1,4}|[〇零一壱壹二ニ弐弍貳貮三参參四肆五伍六陸七漆八捌九玖十拾百陌佰千阡仟]{1,6})(?<suffix>${episodeUnits.joinToString("|") { Regex.escape(it) }})(?=$|[\\s\\)\\]】〜\\-/・「『]|(?=[「『]))")
        for (m in epRegex1.findAll(input)) {
            val prefix = m.groups["prefix"]!!.value
            val numStr = m.groups["num"]!!.value
            val suffix = m.groups["suffix"]!!.value
            val num = numStr.toIntOrNull() ?: kansujiToNumber(numStr)
            if (num in 1..9999) {
                val start = m.groups["prefix"]!!.range.first
                val end = m.groups["suffix"]!!.range.last
                if (start in 0..end && end < input.length) {
                    list.add(EpisodeItem(input.substring(start, end + 1), num, numStr, prefix, suffix, start..end))
                }
            }
        }

         
        val epRegex2 = Regex("(?:^|[\\s\\(\\[【〜\\-/・])(?<num>\\d{1,4})(?<suffix>話目?)(?=$|[\\s\\)\\]】〜\\-/・「『]|(?=[「『]))")
        for (m in epRegex2.findAll(input)) {
            val numStr = m.groups["num"]!!.value
            val suffix = m.groups["suffix"]!!.value
            val num = numStr.toIntOrNull() ?: 0
            if (num in 1..9999) {
                val start = m.groups["num"]!!.range.first
                val end = m.groups["suffix"]!!.range.last
                if (start in 0..end && end < input.length) {
                    list.add(EpisodeItem(input.substring(start, end + 1), num, numStr, "", suffix, start..end))
                }
            }
        }

         
        val epRegex3 = Regex("(?:^|[\\s\\(\\[【〜\\-/・])(?<prefix>[#＃])(?<num>\\d{1,4})(?=$|[\\s\\)\\]】〜\\-/・「『]|(?=[「『]))")
        for (m in epRegex3.findAll(input)) {
            val prefix = m.groups["prefix"]!!.value
            val numStr = m.groups["num"]!!.value
            val num = numStr.toIntOrNull() ?: 0
            if (num in 1..9999) {
                val start = m.groups["prefix"]!!.range.first
                val end = m.groups["num"]!!.range.last
                if (start in 0..end && end < input.length) {
                    list.add(EpisodeItem(input.substring(start, end + 1), num, numStr, prefix, "", start..end))
                }
            }
        }

         
        val epRegex4 = Regex("(?:^|[\\s\\(\\[【〜\\-/・])(?<prefix>(?:[Ee]pisod(?:e|io)|[Ee]p|[Ss]tep|[Cc]hapter|Lesson|Stage|Page|Scene|Track|Turn|Case|Mission|Score|Quest|Round|Record|Log|File|Level|Order|Act|Flight|Piece|エピソード)[\\s#._:\\-★☆]?)(?<num>\\d{1,4})(?<suffix>\\.?)(?=$|[\\s\\)\\]】〜\\-/・「『])", RegexOption.IGNORE_CASE)
        for (m in epRegex4.findAll(input)) {
            val prefix = m.groups["prefix"]!!.value
            val numStr = m.groups["num"]!!.value
            val suffix = m.groups["suffix"]?.value.orEmpty()
            val num = numStr.toIntOrNull() ?: 0
            if (num in 1..9999) {
                val start = m.groups["prefix"]!!.range.first
                val end = if (suffix.isNotEmpty()) m.groups["suffix"]!!.range.last else m.groups["num"]!!.range.last
                if (start in 0..end && end < input.length) {
                    list.add(EpisodeItem(input.substring(start, end + 1), num, numStr, prefix, suffix, start..end))
                }
            }
        }

         
        val epRegex5 = Regex("(?:^|\\S+\\s)(?<prefix>\\(?)(?<num>\\d{1,3})(?<suffix>\\)?)(?=\\s*(?:「.+」|『.+』)$)")
        for (m in epRegex5.findAll(input)) {
            val prefix = m.groups["prefix"]?.value.orEmpty()
            val numStr = m.groups["num"]!!.value
            val suffix = m.groups["suffix"]?.value.orEmpty()
            val num = numStr.toIntOrNull() ?: 0
            if (num in 1..9999) {
                val start = if (prefix.isNotEmpty()) m.groups["prefix"]!!.range.first else m.groups["num"]!!.range.first
                val end = if (suffix.isNotEmpty()) m.groups["suffix"]!!.range.last else m.groups["num"]!!.range.last
                if (start in 0..end && end < input.length) {
                    list.add(EpisodeItem(input.substring(start, end + 1), num, numStr, prefix, suffix, start..end))
                }
            }
        }

         
        val epRegex6 = Regex("(?:^|\\S+\\s)(?<num>\\d{1,3})(?=\\s+\\S+)")
        for (m in epRegex6.findAll(input)) {
            val numStr = m.groups["num"]!!.value
            val num = numStr.toIntOrNull() ?: 0
            val start = m.groups["num"]!!.range.first
            val beforeText = input.substring(0, start).trimEnd().lowercase()
            if (beforeText.endsWith("season") || beforeText.endsWith("part")) continue
            if (num in 1..9999) {
                val end = m.groups["num"]!!.range.last
                if (start in 0..end && end < input.length) {
                    list.add(EpisodeItem(input.substring(start, end + 1), num, numStr, "", "", start..end))
                }
            }
        }

        return list.distinctBy { it.indices.first to it.indices.last }
    }

     
    fun extract(rawInput: String): ExtractedResult = runCatching {
        val input = clean(normalize(rawInput))
        if (input.isBlank()) return ExtractedResult(rawInput)

        val seasons = extractSeasonsList(input)
        val episodes = extractEpisodesList(input)

         
        var multipleEpisodes: List<EpisodeItem>? = null
        var episodesDivider: String? = null
        if (episodes.size >= 2) {
            for (i in 0 until episodes.size - 1) {
                val e1 = episodes[i]
                val e2 = episodes[i + 1]
                if (e1.indices.last + 2 == e2.indices.first && e1.number < e2.number) {
                    val divIdx = e1.indices.last + 1
                    if (divIdx in 0 until input.length) {
                        val div = input[divIdx]
                        if (div in listOf('〜', '~', '-', '/', '・')) {
                            multipleEpisodes = listOf(e1, e2)
                            episodesDivider = div.toString()
                            break
                        }
                    }
                }
            }
        }

        val chosenEpisode = if (multipleEpisodes == null) episodes.firstOrNull() else null
        val chosenSeason = seasons.firstOrNull { s ->
            chosenEpisode == null || s.indices.last < chosenEpisode.indices.first
        }

        var title: String? = null
        var subtitle: String? = null

        val epStart = chosenEpisode?.indices?.first ?: multipleEpisodes?.firstOrNull()?.indices?.first
        val seasonStart = chosenSeason?.indices?.first

        if (seasonStart != null && (epStart == null || seasonStart < epStart)) {
            val sIdx = seasonStart.coerceIn(0, input.length)
            title = input.substring(0, sIdx).trim()
            if (epStart != null) {
                val epEnd = (chosenEpisode?.indices?.last ?: multipleEpisodes!!.last().indices.last) + 1
                val clampedEpEnd = epEnd.coerceIn(0, input.length)
                subtitle = input.substring(clampedEpEnd).trim().ifBlank { null }
            }
        } else if (epStart != null) {
            val clampedEpStart = epStart.coerceIn(0, input.length)
            val before = input.substring(0, clampedEpStart).trim()
            val epEnd = (chosenEpisode?.indices?.last ?: multipleEpisodes!!.last().indices.last) + 1
            val clampedEpEnd = epEnd.coerceIn(0, input.length)
            val after = input.substring(clampedEpEnd).trim()
             
            if (before.isBlank()) {
                if (input.startsWith("第")) {
                    title = after
                    subtitle = null
                } else {
                    title = null
                    subtitle = null
                }
            } else {
                title = before
                subtitle = after.ifBlank { null }
            }
        } else {
            title = input
        }

         
        title = title?.replace(Regex("^(?!.*」.*「)「(?<inner>[\\s\\S]*)」$")) { it.groups["inner"]!!.value.trim() }
                     ?.replace(Regex("^(?!.*』.*『)『(?<inner>[\\s\\S]*)』$")) { it.groups["inner"]!!.value.trim() }
                     ?.trim(' ', '「', '『', '【', '(', '-', '：', ':')

        subtitle = subtitle?.replace(Regex("^(?!.*」.*「)「(?<inner>[\\s\\S]*)」$")) { it.groups["inner"]!!.value.trim() }
                           ?.replace(Regex("^(?!.*』.*『)『(?<inner>[\\s\\S]*)』$")) { it.groups["inner"]!!.value.trim() }
                           ?.trim(' ', '「', '『', '【', '(', '-', '：', ':', '・', '」', '』', '】', ')')

        val titleStripped = title?.let { stripTitle(it) }
        val subtitleStripped = subtitle?.let { stripText(it) }

        ExtractedResult(
            input = input,
            title = title,
            titleStripped = titleStripped,
            season = chosenSeason,
            episode = chosenEpisode,
            episodes = multipleEpisodes,
            episodesDivider = episodesDivider,
            isSingleEpisode = multipleEpisodes == null,
            subtitle = subtitle,
            subtitleStripped = subtitleStripped
        )
    }.getOrElse {
        ExtractedResult(input = rawInput, title = rawInput.trim())
    }

     
    fun levenshteinDistance(s1: String, s2: String): Int {
        val a = s1.toCharArray()
        val b = s2.toCharArray()
        var prev = IntArray(b.size + 1) { it }
        var curr = IntArray(b.size + 1)
        for (i in a.indices) {
            curr[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                curr[j + 1] = minOf(curr[j] + 1, prev[j + 1] + 1, prev[j] + cost)
            }
            val temp = prev
            prev = curr
            curr = temp
        }
        return prev[b.size]
    }

    fun similarity(a: String, b: String): Double {
        val m = max(a.length, b.length)
        if (m == 0) return 1.0
        return (m - levenshteinDistance(a, b)).toDouble() / m
    }

    private fun compareTitleVariants(titleA: String, titleB: String): Boolean {
        val normA = normalize(titleA).uppercase()
        val normB = normalize(titleB).uppercase()
        return titleVariants.any { group ->
            val normGroup = group.map { normalize(it).uppercase() }
            normGroup.contains(normA) && normGroup.contains(normB)
        }
    }

    fun extractCoreTitle(title: String): String {
        var core = stripTitle(title)
        val separators = listOf(" : ", "：", " - ", " 〜", "〜", " ～", "～", " #")
        for (sep in separators) {
            if (core.contains(sep)) {
                val candidate = core.substringBefore(sep).trim()
                if (candidate.length >= 2) {
                    core = candidate
                    break
                }
            }
        }
        core = core.replace(Regex("(?:\\s*(?:[IVX]+|\\d+(?:st|nd|rd|th)|[1-9]|第[0-9〇一二三四五六七八九十]+)(?:期|\\s*season|シリーズ)?|新章|篇|編|章|完|続|序|破|Q).*$", RegexOption.IGNORE_CASE), "").trim()
        return core.ifBlank { title }
    }

     
    fun compare(inputA: String, inputB: String, strict: Boolean = false, allowFuzzy: Boolean = false): Boolean {
        val parsedA = extract(inputA)
        val parsedB = extract(inputB)
        return compare(parsedA, parsedB, strict, allowFuzzy)
    }

    fun compare(parsedA: ExtractedResult, parsedB: ExtractedResult, strict: Boolean = false, allowFuzzy: Boolean = false): Boolean {
        if (listOf(parsedA.title, parsedB.title).all { it?.startsWith("ご注文はうさぎですか") == true }) {
            if (animeWorkIdentity(parsedA.title!!).season != animeWorkIdentity(parsedB.title!!).season) return false
        }
        if (normalizeAll(parsedA.input) == normalizeAll(parsedB.input)) return true
        if (parsedA.title.isNullOrBlank() || parsedB.title.isNullOrBlank()) return false

        val threshold = if (strict) 1.0 else if (allowFuzzy) 0.50 else 0.85

        val tA = removeSpaces(parsedA.title)
        val tB = removeSpaces(parsedB.title)
        val tsA = removeSpaces(parsedA.titleStripped ?: parsedA.title)
        val tsB = removeSpaces(parsedB.titleStripped ?: parsedB.title)

        val titleScore = maxOf(
            similarity(tA, tB),
            similarity(tsA, tsB),
            if (compareTitleVariants(parsedA.titleStripped ?: parsedA.title, parsedB.titleStripped ?: parsedB.title)) 1.0 else 0.0
        )

        var isTitleMatch = titleScore >= threshold ||
            (allowFuzzy && (tsA.contains(tsB) || tsB.contains(tsA) || tA.contains(tB) || tB.contains(tA)))

         
        val seasonMatch: Boolean? = when {
            parsedA.season != null && parsedB.season != null -> parsedA.season.number == parsedB.season.number
            parsedA.season == null && parsedB.season == null -> null
            allowFuzzy -> null
            else -> null  
        }

         
        val epMatch: Boolean? = when {
            parsedA.isSingleEpisode && parsedB.isSingleEpisode -> {
                when {
                    parsedA.episode != null && parsedB.episode != null -> parsedA.episode.number == parsedB.episode.number
                    parsedA.episode == null && parsedB.episode == null -> null
                    else -> false
                }
            }
            !parsedA.isSingleEpisode && !parsedB.isSingleEpisode && parsedA.episodes != null && parsedB.episodes != null -> {
                parsedA.episodes.first().number == parsedB.episodes.first().number &&
                parsedA.episodes.last().number == parsedB.episodes.last().number
            }
            else -> false
        }

         
        val subMatch: Boolean? = when {
            !parsedA.subtitle.isNullOrBlank() && !parsedB.subtitle.isNullOrBlank() -> {
                val subA = removeSpaces(parsedA.subtitle)
                val subB = removeSpaces(parsedB.subtitle)
                val subsA = removeSpaces(parsedA.subtitleStripped ?: parsedA.subtitle)
                val subsB = removeSpaces(parsedB.subtitleStripped ?: parsedB.subtitle)
                similarity(subA, subB) >= threshold || similarity(subsA, subsB) >= threshold ||
                (!strict && epMatch == true && minOf(subsA.length, subsB.length) >= 6 && (subsA.startsWith(subsB) || subsB.startsWith(subsA))) ||
                allowFuzzy
            }
            parsedA.subtitle.isNullOrBlank() && parsedB.subtitle.isNullOrBlank() -> null
            allowFuzzy -> null
            else -> {
                 
                if ((titleScore >= 0.85 || titleScore == 1.0) && (seasonMatch != false) && (epMatch == true)) {
                    true
                } else {
                    false
                }
            }
        }

         
        val finalSeasonMatch = when {
            seasonMatch != null -> seasonMatch
            parsedA.season == null && parsedB.season == null -> null
            else -> {
                 
                if (epMatch == true && (subMatch == true || allowFuzzy)) true else false
            }
        }

        if (!strict && !allowFuzzy) {
             
            if (!isTitleMatch && (finalSeasonMatch != false) && epMatch == true && subMatch == true) {
                isTitleMatch = titleScore >= 0.7 || (titleScore >= 0.55 && (tsA.startsWith(tsB) || tsB.startsWith(tsA)))
            }
        }

        return isTitleMatch && (finalSeasonMatch != false) && (epMatch != false) && (subMatch != false)
    }

     
    fun parse(title: String, subtitle: String = ""): Episode? = runCatching {
        val combined = if (subtitle.startsWith(title) && title.isNotBlank()) subtitle
                       else if (subtitle.isNotBlank()) "$title $subtitle" else title
        val extracted = extract(combined)
        val series = extracted.titleStripped ?: extracted.title ?: return null
        val number = extracted.episode?.number ?: return null
        if (series.length > 300) return null
        Episode(series, number)
    }.getOrNull()

    fun episodeNumber(text: String): Int? = runCatching {
        extractEpisodesList(clean(normalize(text))).firstOrNull()?.number
    }.getOrNull()

    fun matches(
        episode: Episode,
        candidates: List<Candidate>,
        duration: Long = 0,
        allowFuzzy: Boolean = false,
        allowGeneralVideos: Boolean = false
    ): List<Candidate> {
        val targetInput = "${episode.series} 第${episode.number}話"
        val parsedTarget = extract(targetInput)

        val matched = candidates.filter { candidate ->
            val isOfficialOrChannel = candidate.channelId != null || candidate.id.startsWith("so")
            val isSzbh = candidate.userId != null && candidate.tags?.contains(Regex("(?:コメント専用動画|SZBH方式)", RegexOption.IGNORE_CASE)) == true
            if (!isOfficialOrChannel && !isSzbh && !allowGeneralVideos) return@filter false
            if (excluded.containsMatchIn(candidate.title)) return@filter false
            if (candidate.title.contains(Regex("Chapter\\.[1-6]$", RegexOption.IGNORE_CASE))) return@filter false

            val parsedCandidate = extract(candidate.title)
            val compareOk = compare(parsedTarget, parsedCandidate, allowFuzzy = allowFuzzy)
            val durationOk = duration <= 0 || candidate.duration <= 0 || kotlin.math.abs(candidate.duration - duration) <= 15
            compareOk && durationOk
        }.distinctBy { it.id }

        return matched.sortedWith(
            compareBy<Candidate> { candidate ->
                when {
                    candidate.channelId != null && candidate.channelId != 2632720L -> 0
                    candidate.id.startsWith("so") -> 0
                    candidate.channelId == 2632720L -> 1
                    candidate.tags?.contains(Regex("(?:コメント専用動画|SZBH方式)", RegexOption.IGNORE_CASE)) == true -> 2
                    else -> 3
                }
            }.thenByDescending { it.commentCount }.thenBy { it.id }
        )
    }

    fun choose(episode: Episode, candidates: List<Candidate>): Candidate? = matches(episode, candidates).firstOrNull()

    fun fromDownloadNotification(title: String, text: String): Episode? {
        val combined = "$title $text"
        if (!combined.contains("ダウンロード") || combined.contains("失敗") || combined.contains("キャンセル")) return null
        val cleaned = combined.replace(Regex("ダウンロード(?:が|を)?(?:完了しました|完了|しています|中|済み|しました)?"), " ")
            .replace(Regex("DMM\\s*TV", RegexOption.IGNORE_CASE), " ").trim()
        return parse(cleaned)
    }
}
