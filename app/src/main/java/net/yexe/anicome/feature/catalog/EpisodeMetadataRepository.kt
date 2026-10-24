package net.yexe.anicome

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

fun japaneseEpisodeTitle(value: String): String? = EpisodeMatcher.normalize(value).takeIf { it.isNotBlank() && it.any { c -> c in '\u3040'..'\u30ff' || c in '\u3400'..'\u9fff' } }
fun normalizedEpisodeText(value: String) = EpisodeMatcher.normalize(value).lowercase().replace(Regex("[\\p{P}\\p{S}\\s]+"), "")

data class ResolvedEpisodeMetadata(val title: String, val episode: Episode)

class EpisodeMetadataRepository(private val settings: SettingsRepository? = null,
    private val request: suspend (String, String?) -> JSONObject = { url, body -> ApiAccess.client.request(url, body) }) {
    private data class Entry(val time: Long, val titles: Map<Int, String>, val series: String? = null, val numbers: Map<Int, Int> = emptyMap())
    private fun Entry.titleAt(number: Int): String? {
        val title = titles[number] ?: return null
        val normalized = normalizedEpisodeText(title)
        return title.takeIf { titles.values.count { normalizedEpisodeText(it) == normalized } == 1 }
    }
    private val cache = ConcurrentHashMap<String, Entry>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val jikanLock = Mutex()
    private var lastJikan = 0L
    private fun key(work: AnimeInfo) = "episode-titles-v4:${work.provider}:${work.id}:${work.title}:${work.year}:${work.episodes}"
    private fun calendarMatches(work: AnimeInfo, item: JSONObject): Boolean {
        val wanted = animeWorkIdentity(work.title)
        if (work.year != null && item.optInt("FirstYear") != work.year) return false
        return listOf(item.optString("Title"), item.optString("ShortTitle"), item.optString("TitleEN")).any { title ->
            title.isNotBlank() && animeWorkIdentity(title).let { found -> found.base == wanted.base && found.cour == wanted.cour &&
                (found.season == wanted.season || (!wanted.explicitSeason && work.year != null)) }
        }
    }
    suspend fun withEpisodeCount(work: AnimeInfo): AnimeInfo {
        if (work.episodes != null) return work
        return try {
            val items = request("https://cal.syoboi.jp/json.php?Req=TitleSearch&Search=${URLEncoder.encode(animeSearchStem(work.title), "UTF-8")}&Limit=50", null).optJSONObject("Titles")
            val match = items?.keys()?.asSequence()?.map { items.getJSONObject(it) }?.filter { calendarMatches(work, it) }?.toList()?.singleOrNull()
            val tid = match?.optInt("TID") ?: return work
            if (tid <= 0) return work
            val item = request("https://cal.syoboi.jp/json.php?Req=TitleFull&TID=$tid", null).optJSONObject("Titles")?.optJSONObject(tid.toString()) ?: return work
            val numbers = item.optString("SubTitles").lineSequence().mapNotNull { line ->
                Regex("^\\*([0-9]{1,4})\\*.+$").matchEntire(line.trim())?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
            }.toSet()
            work.copy(episodes = numbers.size.takeIf { it > 0 })
        } catch (e: CancellationException) { throw e } catch (e: TrafficLimitReached) { throw e } catch (_: Exception) { work }
    }
    suspend fun canonical(work: AnimeInfo): AnimeInfo = try {
        val items = request("https://cal.syoboi.jp/json.php?Req=TitleSearch&Search=${URLEncoder.encode(animeSearchStem(work.title), "UTF-8")}&Limit=50", null).optJSONObject("Titles")
        val selected = items?.keys()?.asSequence()?.map { items.getJSONObject(it) }?.filter { calendarMatches(work, it) }?.toList()?.singleOrNull()
        selected?.optString("Title")?.takeIf(String::isNotBlank)?.let { work.copy(title = it) } ?: work
    } catch (e: CancellationException) { throw e } catch (limit: TrafficLimitReached) { throw limit } catch (_: Exception) { work }
    suspend fun resolve(work: AnimeInfo, number: Int): ResolvedEpisodeMetadata? {
        val title = title(work, number)
        val entry = cache[key(work)] ?: return null
        if (title == null && entry.numbers[number] == null) return null
        return ResolvedEpisodeMetadata(title.orEmpty(), Episode(entry.series ?: work.title, entry.numbers[number] ?: number))
    }
    suspend fun title(work: AnimeInfo, number: Int): String? = withContext(Dispatchers.IO) {
        require(work.id >= 0 && number in 1..9999)
        val key = key(work)
        locks.getOrPut(key) { Mutex() }.withLock {
            val now = System.currentTimeMillis()
            val existing = cache[key] ?: settings?.load()?.getString(key, null)?.let { raw -> runCatching {
                val data = JSONObject(raw); val titles = data.getJSONObject("titles")
                Entry(data.getLong("time"), titles.keys().asSequence().mapNotNull { it.toIntOrNull()?.let { n -> titles.optString(it).takeIf(String::isNotBlank)?.let { t -> n to t } } }.toMap(), data.optString("series").takeIf(String::isNotBlank), data.optJSONObject("numbers")?.let { numbers -> numbers.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.let { it to numbers.getInt(k) } }.toMap() }.orEmpty())
            }.getOrNull() }
            if (existing != null && now - existing.time in 0..(if (existing.titles.isEmpty()) 60_000 else 3_600_000)) { cache[key] = existing; return@withLock existing.titleAt(number) }
            val titles = mutableMapOf<Int, String>()
            var responded = false
            var sourceSeries: String? = null
            val sourceNumbers = mutableMapOf<Int, Int>()
            val identity = animeWorkIdentity(work.title)
            try {
                val found = request("https://cal.syoboi.jp/json.php?Req=TitleSearch&Search=${URLEncoder.encode(animeSearchStem(work.title), "UTF-8")}&Limit=50", null).optJSONObject("Titles")
                val matches = found?.keys()?.asSequence()?.map { found.getJSONObject(it) }?.filter { item ->
                    calendarMatches(work, item)
                }?.toList().orEmpty()
                if (matches.size == 1) {
                    val tid = matches.single().optInt("TID")
                    if (tid > 0) {
                        val details = request("https://cal.syoboi.jp/json.php?Req=TitleFull&TID=$tid", null).optJSONObject("Titles")?.optJSONObject(tid.toString())
                        val registered = details?.optString("SubTitles")?.lineSequence()?.mapNotNull { line ->
                            Regex("^\\*(\\d{1,4})\\*(.+)$").matchEntire(line.trim())?.let { match ->
                                val n = match.groupValues[1].toInt()
                                match.groupValues[2].trim().takeIf { n in 1..9999 && it.isNotBlank() }?.let { n to it }
                            }
                        }?.toMap().orEmpty()
                        val first = registered.keys.minOrNull() ?: 1
                        val contiguous = registered.isNotEmpty() && (first..registered.keys.max()).all(registered::containsKey)
                        val offset = if (identity.cour > 1 && first > 1 && contiguous && (work.episodes == null || registered.size <= work.episodes)) first - 1 else 0
                        sourceSeries = matches.single().optString("Title").let { title ->
                            title.replace(Regex("\\(?第?[0-9〇一二三四五六七八九十]+クール\\)?"), "").trim()
                        }
                        for ((n, title) in registered) {
                            val local = n - offset
                            if (local in 1..(work.episodes ?: 9999)) { titles[local] = title; sourceNumbers[local] = n }
                        }
                    }
                }
                responded = true
            } catch (cancelled: CancellationException) { throw cancelled } catch (limit: TrafficLimitReached) { throw limit } catch (_: Exception) { }
            fun complete() = work.episodes?.takeIf { it in 1..9999 }?.let { count -> (1..count).all(titles::containsKey) } ?: (titles[number] != null)
            if (!complete() && work.provider == "kitsu") try {
                var offset = 0
                var page = 0
                while (page++ < 500) {
                    val data = request("https://kitsu.app/api/edge/anime/${work.id}/episodes?page%5Blimit%5D=20&page%5Boffset%5D=$offset", null)
                    responded = true
                    val items = data.getJSONArray("data")
                    for (i in 0 until items.length()) {
                        val item = items.getJSONObject(i).getJSONObject("attributes")
                        val languages = item.optJSONObject("titles")
                        (japaneseEpisodeTitle(languages?.optString("ja_jp").orEmpty()) ?: japaneseEpisodeTitle(languages?.optString("ja").orEmpty()))?.let { title -> item.optInt("number").takeIf { n -> n in 1..9999 }?.let { n -> titles.putIfAbsent(n, title) } }
                    }
                    if (data.optJSONObject("links")?.optString("next").let { it.isNullOrBlank() || it == "null" } || items.length() == 0) break
                    offset += items.length()
                }
            } catch (cancelled: CancellationException) { throw cancelled } catch (limit: TrafficLimitReached) { throw limit } catch (_: Exception) { }
            if (!complete() && work.provider != "syoboi") {
                val malId = resolveMalId(work)
                if (malId != null) try {
                    var page = 1
                    do {
                        currentCoroutineContext().ensureActive()
                        val data = jikan("anime/$malId/episodes?page=$page")
                        responded = true
                        val items = data.getJSONArray("data")
                        for (i in 0 until items.length()) {
                            val item = items.getJSONObject(i)
                            japaneseEpisodeTitle(item.optString("title_japanese"))?.let { title -> item.optInt("mal_id").takeIf { n -> n in 1..9999 }?.let { n -> titles.putIfAbsent(n, title) } }
                        }
                        page++
                    } while (data.optJSONObject("pagination")?.optBoolean("has_next_page") == true && page <= 100)
                } catch (cancelled: CancellationException) { throw cancelled } catch (limit: TrafficLimitReached) { throw limit } catch (_: Exception) { }
            }
            cache[key] = Entry(System.currentTimeMillis(), titles.toMap(), sourceSeries, sourceNumbers.toMap())
            if (responded) {
                settings?.putString(key, JSONObject().put("time", now).put("series", sourceSeries).put("numbers", JSONObject(sourceNumbers.mapKeys { it.key.toString() })).put("titles", JSONObject(titles.mapKeys { it.key.toString() })).toString())
            }
            cache.getValue(key).titleAt(number)
        }
    }
    private suspend fun jikan(path: String): JSONObject = jikanLock.withLock {
        delay((1050 - (System.currentTimeMillis() - lastJikan)).coerceAtLeast(0))
        lastJikan = System.currentTimeMillis()
        request("https://api.jikan.moe/v4/$path", null)
    }
    private suspend fun resolveMalId(work: AnimeInfo): Int? {
        if (work.provider == "jikan") return work.id
        try {
            if (work.provider == "anilist") {
                val body = JSONObject().put("query", "query { Media(id: ${work.id}, type: ANIME) { idMal } }").toString()
                request("https://graphql.anilist.co", body).getJSONObject("data").getJSONObject("Media").optInt("idMal").takeIf { it > 0 }?.let { return it }
            } else if (work.provider == "kitsu") {
                val items = request("https://kitsu.app/api/edge/anime/${work.id}/mappings", null).getJSONArray("data")
                for (i in 0 until items.length()) { val attributes = items.getJSONObject(i).getJSONObject("attributes")
                    if (attributes.optString("externalSite") == "myanimelist/anime") attributes.optString("externalId").toIntOrNull()?.takeIf { it > 0 }?.let { return it }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled } catch (limit: TrafficLimitReached) { throw limit } catch (_: Exception) { }
        return try {
            val items = jikan("anime?q=${URLEncoder.encode(work.title, "UTF-8")}&limit=8").getJSONArray("data")
            val matches = (0 until items.length()).map { items.getJSONObject(it) }.filter { item ->
                val aliases = listOf(item.optString("title_japanese"), item.optString("title"), item.optString("title_english")) +
                    item.optJSONArray("titles")?.let { array -> (0 until array.length()).map { array.getJSONObject(it).optString("title") } }.orEmpty()
                aliases.any { normalizedEpisodeText(it) == normalizedEpisodeText(work.title) } &&
                    (work.year == null || item.optInt("year") <= 0 || work.year == item.optInt("year")) &&
                    (work.episodes == null || item.optInt("episodes") <= 0 || work.episodes == item.optInt("episodes"))
            }
            matches.singleOrNull()?.optInt("mal_id")?.takeIf { it > 0 }
        } catch (cancelled: CancellationException) { throw cancelled } catch (limit: TrafficLimitReached) { throw limit } catch (_: Exception) { null }
    }
}
