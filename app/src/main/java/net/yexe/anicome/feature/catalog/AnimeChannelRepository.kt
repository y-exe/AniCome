package net.yexe.anicome

import java.io.File
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class AnimeChannelEntry(val title: String, val aliases: List<String>, val year: Int?, val channel: String)

fun bangumiChannelEntries(data: JSONObject): List<AnimeChannelEntry> {
    val items = data.getJSONArray("items")
    return (0 until items.length()).mapNotNull { index ->
        val item = items.getJSONObject(index)
        val sites = item.optJSONArray("sites") ?: return@mapNotNull null
        val names = (0 until sites.length()).map { sites.getJSONObject(it) }
            .filter { it.optString("site") == "nicovideo" }
            .mapNotNull { niconicoChannelName(it.optString("id")) }
            .filter { it != "ch$DANIME_CHANNEL" && it != DANIME_CHANNEL.toString() }.distinct()
        val channel = names.singleOrNull() ?: return@mapNotNull null
        val title = item.optString("title").takeIf(String::isNotBlank) ?: return@mapNotNull null
        val translations = item.optJSONObject("titleTranslate")
        val aliases = translations?.keys()?.asSequence()?.flatMap { key ->
            val values = translations.optJSONArray(key) ?: JSONArray()
            (0 until values.length()).asSequence().map { values.optString(it) }
        }?.filter(String::isNotBlank)?.toList().orEmpty()
        AnimeChannelEntry(title, aliases, item.optString("begin").take(4).toIntOrNull(), channel)
    }
}

fun selectAnimeChannel(entries: List<AnimeChannelEntry>, title: String, year: Int? = null): String? {
    val wanted = animeWorkIdentity(title)
    val eligible = entries.filter { entry ->
        (year == null || entry.year == null || year == entry.year) && (listOf(entry.title) + entry.aliases).any { alias ->
            val identity = animeWorkIdentity(alias)
            identity.base == wanted.base && identity.season == wanted.season && identity.cour == wanted.cour
        }
    }
    return eligible.map { it.channel }.distinct().singleOrNull()
}

fun calendarChannelEntry(item: JSONObject): AnimeChannelEntry? {
    val links = Regex("https?://ch\\.nicovideo\\.jp/[A-Za-z0-9_-]+")
        .findAll(item.optString("Comment")).mapNotNull { niconicoChannelName(it.value) }
        .filter { it != "ch$DANIME_CHANNEL" && it != DANIME_CHANNEL.toString() }.distinct().toList()
    val channel = links.singleOrNull() ?: return null
    return AnimeChannelEntry(item.getString("Title"), listOfNotNull(item.optString("ShortTitle").takeIf(String::isNotBlank)),
        item.optInt("FirstYear").takeIf { it > 0 }, channel)
}

class AnimeChannelRepository(private val cacheFile: File,
    private val request: suspend (String) -> JSONObject,
    private val resolveId: suspend (String) -> Long,
    private val now: () -> Long = System::currentTimeMillis) {
    private val lock = Mutex()
    private var entries: List<AnimeChannelEntry>? = null
    private var updated = 0L
    private var retryAfter = 0L
    private val resolved = mutableMapOf<String, Pair<Long, Long?>>()
    private suspend fun dataset(): List<AnimeChannelEntry> {
        if (entries == null && cacheFile.isFile) runCatching {
            val cached = JSONObject(cacheFile.readText())
            entries = bangumiChannelEntries(cached)
            updated = cached.getLong("cachedAt")
        }
        if (now() < retryAfter || entries != null && now() - updated in 0..604800000L) return entries.orEmpty()
        try {
            val data = request("https://raw.githubusercontent.com/bangumi-data/bangumi-data/master/dist/data.json")
            val parsed = bangumiChannelEntries(data)
            require(parsed.isNotEmpty())
            entries = parsed
            updated = now()
            val compact = JSONObject().put("cachedAt", updated).put("items", JSONArray(parsed.map { entry ->
                JSONObject().put("title", entry.title).put("begin", entry.year?.toString().orEmpty())
                    .put("titleTranslate", JSONObject().put("aliases", JSONArray(entry.aliases)))
                    .put("sites", JSONArray().put(JSONObject().put("site", "nicovideo").put("id", entry.channel)))
            }))
            runCatching {
                cacheFile.parentFile?.mkdirs()
                val temporary = File(cacheFile.path + ".tmp")
                temporary.writeText(compact.toString())
                java.nio.file.Files.move(temporary.toPath(), cacheFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: TrafficLimitReached) { throw e }
        catch (_: Exception) { retryAfter = now() + 60000L }
        return entries.orEmpty()
    }
    suspend fun search(query: String): List<AnimeInfo> = withContext(Dispatchers.IO) { lock.withLock {
        val needle = normalizedEpisodeText(query)
        if (needle.isBlank()) return@withLock emptyList()
        dataset().filter { entry -> (listOf(entry.title) + entry.aliases).any { normalizedEpisodeText(it).contains(needle) } }
            .sortedWith(compareByDescending<AnimeChannelEntry> { normalizedEpisodeText(it.title) == needle }.thenBy { it.year ?: 0 }.thenBy { it.title })
            .take(30).map { entry ->
                AnimeInfo(("${entry.title}:${entry.year}:${entry.channel}".hashCode() and Int.MAX_VALUE).coerceAtLeast(1),
                    entry.title, "", "", null, entry.year, null, emptyList(), "bangumi-data", entry.channel)
            }
    } }
    suspend fun official(work: AnimeInfo): Long? {
        val reference = work.niconicoChannel?.let(::niconicoChannelName)
        if (reference != null) return try { resolveId(reference).takeIf { it > 0 && it != DANIME_CHANNEL } }
            catch (e: CancellationException) { throw e } catch (e: TrafficLimitReached) { throw e } catch (_: Exception) { null }
        return official(work.title, work.year)
    }
    suspend fun official(title: String, year: Int? = null): Long? = withContext(Dispatchers.IO) { lock.withLock {
        val key = "$title:$year"
        resolved[key]?.takeIf { now() - it.first in 0..(if (it.second == null) 60000L else 3600000L) }?.let { return@withLock it.second }
        try {
            var channel = selectAnimeChannel(dataset(), title, year)
            if (channel == null) {
                val search = request("https://cal.syoboi.jp/json.php?Req=TitleSearch&Search=${URLEncoder.encode(animeSearchStem(title), "UTF-8")}&Limit=50").optJSONObject("Titles")
                val matches = search?.keys()?.asSequence()?.map { search.getJSONObject(it) }?.filter { item ->
                    val identity = animeWorkIdentity(item.optString("Title"))
                    val wanted = animeWorkIdentity(title)
                    identity.base == wanted.base && identity.season == wanted.season && identity.cour == wanted.cour &&
                        (year == null || item.optInt("FirstYear") == year)
                }?.toList().orEmpty()
                val selected = matches.singleOrNull()
                if (selected != null) {
                    val tid = selected.optInt("TID")
                    if (tid > 0) {
                        val item = request("https://cal.syoboi.jp/json.php?Req=TitleFull&TID=$tid").optJSONObject("Titles")?.optJSONObject(tid.toString())
                        channel = item?.let(::calendarChannelEntry)?.channel
                    }
                }
            }
            val id = channel?.let { resolveId(it) }?.takeIf { it > 0 && it != DANIME_CHANNEL }
            resolved[key] = now() to id
            id
        } catch (e: CancellationException) { throw e }
        catch (e: TrafficLimitReached) { throw e }
        catch (_: Exception) { resolved[key] = now() to null; null }
    } }
}
