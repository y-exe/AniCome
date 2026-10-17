package net.yexe.anicome

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

data class AnimeInfo(
    val id: Int,
    val title: String,
    val imageUrl: String,
    val synopsis: String,
    val score: Double?,
    val year: Int?,
    val episodes: Int?,
    val genres: List<String>,
    val provider: String = "jikan"
)
data class AnimeEpisode(val number: Int, val title: String, val imageUrl: String = "")

 
object AnimeCatalog {
    private val cache = ConcurrentHashMap<String, AnimeInfo>()
    private val episodeCache = ConcurrentHashMap<String, AnimeEpisode>()
    private val _featured = MutableStateFlow<List<AnimeInfo>>(emptyList())
    val featured = _featured.asStateFlow()
    private var preferences: SettingsRepository? = null

    fun initialize(repository: SettingsRepository) { preferences = repository }

    suspend fun search(query: String): List<AnimeInfo> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val providers = if (query.any { it.code > 127 }) listOf<suspend () -> List<AnimeInfo>>({ kitsu(query) }, { aniList(query) }, { response("anime?q=${URLEncoder.encode(query.trim(), "UTF-8")}&limit=8&sfw=true").getJSONArray("data").mapAnime() })
        else listOf<suspend () -> List<AnimeInfo>>({ response("anime?q=${URLEncoder.encode(query.trim(), "UTF-8")}&limit=8&sfw=true").getJSONArray("data").mapAnime() }, { kitsu(query) }, { aniList(query) })
        searchWithFallback(providers)
    }

    suspend fun find(query: String): AnimeInfo? {
        val key = EpisodeMatcher.normalize(query)
        if (key.isBlank()) return null
        cache[key]?.let { return it }
        readInfo(key)?.let { return it.also { cache[key] = it } }
        val result = withContext(Dispatchers.IO) {
            val providers = listOf<suspend () -> List<AnimeInfo>>({ kitsu(query) }, { aniList(query) }, { response("anime?q=${URLEncoder.encode(query.trim(), "UTF-8")}&limit=8&sfw=true").getJSONArray("data").mapAnime() })
            var selected: AnimeInfo? = null
            for (provider in providers) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                try {
                    selected = bestMatch(query, provider().filter { it.imageUrl.isNotBlank() })
                    if (selected != null) break
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (limit: TrafficLimitReached) { throw limit }
                catch (_: Exception) { }
            }
            selected
        }
        result?.let { cache[key] = it; writeInfo(key, it) }
        return result
    }
    suspend fun artwork(series: String, sourceTitle: String = ""): AnimeInfo? {
        val queries = listOf(series, sourceTitle).filter(String::isNotBlank).flatMap { title ->
            val parsed = EpisodeMatcher.extract(title)
            listOfNotNull(parsed.titleStripped, parsed.title, title)
        }.map(String::trim).filter(String::isNotBlank).distinct()
        for (query in queries) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            find(query)?.takeIf { it.imageUrl.isNotBlank() }?.let { return it }
        }
        return null
    }
    suspend fun loadFeatured() = withContext(Dispatchers.IO) {
        if (_featured.value.isNotEmpty()) return@withContext
        val popular = runCatching { response("top/anime?filter=bypopularity&limit=12").getJSONArray("data").mapAnime() }.getOrElse { kitsu(null, popular = true) }
         
        val cute = listOf("ご注文はうさぎですか", "ゆるキャン", "ぼっち・ざ・ろっく", "ひだまりスケッチ")
            .mapNotNull { query -> runCatching { bestMatch(query, kitsu(query)) }.getOrNull() }
            .distinctBy { it.id }
        _featured.value = if (cute.isEmpty()) popular else buildList {
            popular.forEachIndexed { index, anime ->
                add(anime)
                add(cute[index % cute.size])
            }
        }.distinctBy { it.provider to it.id }
    }
    suspend fun episodeFor(series: String, number: Int): AnimeEpisode? = withContext(Dispatchers.IO) {
        val key = "${EpisodeMatcher.normalize(series)}:$number"
        episodeCache[key]?.let { return@withContext it }
        readEpisode(key)?.let { return@withContext it.also { episodeCache[key] = it } }
        val episode = runCatching {
            val kitsuInfo = bestMatch(series, kitsu(series)) ?: return@runCatching null
            kitsuEpisodes(kitsuInfo).firstOrNull { it.number == number }
        }.getOrElse { if (it is kotlinx.coroutines.CancellationException || it is TrafficLimitReached) throw it; null }
        episode?.let { episodeCache[key] = it; writeEpisode(key, it) }
        episode
    }
    private fun bestMatch(query: String, choices: List<AnimeInfo>): AnimeInfo? {
        val normalized = EpisodeMatcher.normalize(query)
        fun score(candidate: AnimeInfo): Int {
            val title = EpisodeMatcher.normalize(candidate.title)
            return when {
                title == normalized -> 10_000
                title.contains(normalized) || normalized.contains(title) -> 5_000 + minOf(title.length, normalized.length)
                else -> title.commonPrefixWith(normalized).length
            }
        }
        return choices.maxByOrNull(::score)?.takeIf { score(it) >= 2 }
    }
    private suspend fun readInfo(key: String): AnimeInfo? = preferences?.load()?.getString("info:$key", null)?.let { raw -> runCatching {
        val item = JSONObject(raw)
        AnimeInfo(item.getInt("id"), item.getString("title"), item.getString("image"), item.optString("synopsis"), item.optDouble("score").takeIf { !it.isNaN() }, item.optInt("year").takeIf { it > 0 }, item.optInt("episodes").takeIf { it > 0 }, item.optJSONArray("genres")?.let { array -> List(array.length()) { array.getString(it) } }.orEmpty(), item.optString("provider", "jikan"))
    }.getOrNull() }
    private fun writeInfo(key: String, info: AnimeInfo) {
        preferences?.putString("info:$key", JSONObject().put("id", info.id).put("title", info.title).put("image", info.imageUrl).put("synopsis", info.synopsis).put("score", info.score).put("year", info.year).put("episodes", info.episodes).put("genres", JSONArray(info.genres)).put("provider", info.provider).toString())
    }
    private suspend fun readEpisode(key: String): AnimeEpisode? = preferences?.load()?.getString("episode:$key", null)?.let { raw -> runCatching { JSONObject(raw).let { AnimeEpisode(it.getInt("number"), it.getString("title"), it.optString("image")) } }.getOrNull() }
    private fun writeEpisode(key: String, episode: AnimeEpisode) { preferences?.putString("episode:$key", JSONObject().put("number", episode.number).put("title", episode.title).put("image", episode.imageUrl).toString()) }
    private suspend fun response(path: String) = ApiAccess.client.request("https://api.jikan.moe/v4/$path")
    private suspend fun aniList(query: String?, popular: Boolean = false): List<AnimeInfo> {
        val graph = """query (${ '$' }search: String, ${ '$' }sort: [MediaSort]) { Page(perPage: 12) { media(search: ${ '$' }search, type: ANIME, isAdult: false, sort: ${ '$' }sort) { id title { native romaji } coverImage { extraLarge large } description averageScore episodes seasonYear genres } } }"""
        val body = JSONObject().put("query", graph).put("variables", JSONObject().put("search", query).put("sort", JSONArray().put(if (popular) "POPULARITY_DESC" else "SEARCH_MATCH"))).toString()
        val media = ApiAccess.client.request("https://graphql.anilist.co", body).getJSONObject("data").getJSONObject("Page").getJSONArray("media")
        return List(media.length()) { index ->
            val item = media.getJSONObject(index); val titles = item.getJSONObject("title"); val cover = item.optJSONObject("coverImage")
            AnimeInfo(item.getInt("id"), titles.optString("native").ifBlank { titles.optString("romaji") }, cover?.optString("extraLarge").orEmpty().ifBlank { cover?.optString("large").orEmpty() }, item.optString("description").replace(Regex("<[^>]*>"), "").replace("\n", " "), item.optDouble("averageScore").takeIf { it > 0 }?.div(10), item.optInt("seasonYear").takeIf { it > 0 }, item.optInt("episodes").takeIf { it > 0 }, item.optJSONArray("genres")?.let { array -> List(array.length()) { array.optString(it) } }.orEmpty(), "anilist")
        }
    }
    private suspend fun kitsu(query: String?, popular: Boolean = false): List<AnimeInfo> {
        val parameters = if (popular) "page%5Blimit%5D=12&sort=popularityRank" else "filter%5Btext%5D=${URLEncoder.encode(query.orEmpty(), "UTF-8")}&page%5Blimit%5D=8"
        val data = ApiAccess.client.request("https://kitsu.app/api/edge/anime?$parameters").getJSONArray("data")
        return List(data.length()) { index ->
            val item = data.getJSONObject(index); val attributes = item.getJSONObject("attributes"); val titles = attributes.optJSONObject("titles"); val poster = attributes.optJSONObject("posterImage")
            AnimeInfo(item.getString("id").toInt(), titles?.optString("ja_jp").orEmpty().ifBlank { attributes.optString("canonicalTitle") }, poster?.optString("large").orEmpty().ifBlank { poster?.optString("original").orEmpty() }, attributes.optString("synopsis").replace("\n", " "), attributes.optString("averageRating").toDoubleOrNull()?.div(10), attributes.optString("startDate").take(4).toIntOrNull(), attributes.optInt("episodeCount").takeIf { it > 0 }, emptyList(), "kitsu")
        }
    }
    private suspend fun kitsuEpisodes(info: AnimeInfo): List<AnimeEpisode> {
        val data = ApiAccess.client.request("https://kitsu.app/api/edge/anime/${info.id}/episodes?page%5Blimit%5D=20").getJSONArray("data")
        return List(data.length()) { index ->
            val attributes = data.getJSONObject(index).getJSONObject("attributes"); val titles = attributes.optJSONObject("titles")
            AnimeEpisode(attributes.optInt("number"), titles?.optString("ja_jp").orEmpty().ifBlank { attributes.optString("canonicalTitle") }, attributes.optJSONObject("thumbnail")?.optString("original").orEmpty())
        }.filter { it.number > 0 }
    }
    private fun JSONArray.mapAnime() = List(length()) { index -> getJSONObject(index).toAnime() }
    private fun JSONObject.toAnime(): AnimeInfo {
        val images = optJSONObject("images")?.optJSONObject("webp") ?: optJSONObject("images")?.optJSONObject("jpg")
        return AnimeInfo(
            id = getInt("mal_id"), title = optString("title_japanese").ifBlank { optString("title") },
            imageUrl = images?.optString("large_image_url").orEmpty().ifBlank { images?.optString("image_url").orEmpty() },
            synopsis = optString("synopsis").replace("\n", " "), score = optDouble("score").takeIf { !it.isNaN() },
            year = optInt("year").takeIf { it > 0 }, episodes = optInt("episodes").takeIf { it > 0 },
            genres = optJSONArray("genres")?.let { array -> List(array.length()) { array.getJSONObject(it).optString("name") }.filter { it.isNotBlank() } }.orEmpty()
        )
    }
}

suspend fun <T> searchWithFallback(providers: List<suspend () -> List<T>>): List<T> {
    var failure: Throwable? = null
    var responded = false
    for (provider in providers) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        try {
            val results = provider()
            responded = true
            if (results.isNotEmpty()) return results
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException || error is TrafficLimitReached) throw error
            failure = error
        }
    }
    if (!responded && failure != null) throw IllegalStateException("作品を検索できませんでした。通信を確認して再試行してください。", failure)
    return emptyList()
}
