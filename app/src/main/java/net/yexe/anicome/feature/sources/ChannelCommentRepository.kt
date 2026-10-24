package net.yexe.anicome

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import java.io.StringReader

private fun channelDocument(xml: String): org.w3c.dom.Document {
    require(!Regex("<!\\s*(?:DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(xml)) { "チャンネルを確認できませんでした" }
    val factory = DocumentBuilderFactory.newInstance()
    runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
    runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    factory.isExpandEntityReferences = false
    return factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
}
fun channelFeedId(xml: String): Long? {
    val channel = channelDocument(xml).getElementsByTagName("channel").item(0) as? org.w3c.dom.Element ?: return null
    return niconicoChannelId(channel.getElementsByTagName("link").item(0)?.textContent.orEmpty())
}
fun channelSearchTitle(xml: String): String {
    val document = channelDocument(xml)
    val items = document.getElementsByTagName("item")
    for (i in 0 until items.length) {
        val title = (items.item(i) as org.w3c.dom.Element).getElementsByTagName("title").item(0)?.textContent.orEmpty()
        val parsed = EpisodeMatcher.extract(title)
        if (parsed.isSingleEpisode && parsed.episode != null) return EpisodeMatcher.clean(EpisodeMatcher.normalize(title)).take(parsed.episode.indices.first).trim()
    }
    throw MatchUnavailable("このチャンネルに保存できる公式動画がありません")
}
fun channelFeedNumbers(xml: String): Set<Int> {
    val items = channelDocument(xml).getElementsByTagName("item")
    return (0 until items.length).mapNotNull { i ->
        val title = (items.item(i) as org.w3c.dom.Element).getElementsByTagName("title").item(0)?.textContent.orEmpty()
        EpisodeMatcher.extract(title).takeIf { it.isSingleEpisode }?.episode?.number
    }.toSet()
}

fun channelFeedCandidates(xml: String, channelId: Long): List<Candidate> {
    val items = channelDocument(xml).getElementsByTagName("item")
    return (0 until items.length).mapNotNull { i ->
        val item = items.item(i) as org.w3c.dom.Element
        val id = niconicoVideoId(item.getElementsByTagName("link").item(0)?.textContent.orEmpty()) ?: return@mapNotNull null
        val title = item.getElementsByTagName("title").item(0)?.textContent.orEmpty()
        val parsed = EpisodeMatcher.extract(title)
        if (!parsed.isSingleEpisode || parsed.episode == null) return@mapNotNull null
        val media = (item.getElementsByTagName("media:thumbnail").item(0) as? org.w3c.dom.Element)?.getAttribute("url").orEmpty()
        val description = item.getElementsByTagName("description").item(0)?.textContent.orEmpty()
        val embedded = Regex("<img\\b[^>]*?\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).findAll(description)
            .map { it.groupValues[1].replace("&amp;", "&") }.firstOrNull { value ->
                runCatching { URI(value).let { it.scheme == "https" && it.host?.let { host -> host == "nimg.jp" || host.endsWith(".nimg.jp") } == true } }.getOrDefault(false)
            }.orEmpty()
        val thumbnail = media.ifBlank { embedded }
        Candidate(id, title, channelId = channelId, thumbnailUrl = thumbnail).takeIf { it.isDownloadOfficial() }
    }
}

fun niconicoChannelId(value: String): Long? {
    val text = value.trim()
    val id = text.removePrefix("ch").toLongOrNull() ?: runCatching {
        val uri = URI(text)
        if (uri.host?.lowercase() != "ch.nicovideo.jp" || uri.userInfo != null || uri.scheme !in setOf("http", "https")) null else uri.path.trim('/').substringBefore('/').removePrefix("ch").toLongOrNull()
    }.getOrNull()
    return id?.takeIf { it in 1..Int.MAX_VALUE && it != DANIME_CHANNEL }
}
fun niconicoChannelName(value: String): String? {
    val text = value.trim()
    val name = if (text.contains("://")) runCatching {
        val uri = URI(text)
        if (uri.host?.lowercase() == "ch.nicovideo.jp" && uri.userInfo == null && uri.scheme in setOf("http", "https")) uri.path.trim('/').substringBefore('/') else null
    }.getOrNull() else text
    return name?.takeIf { Regex("[A-Za-z0-9_-]{1,100}").matches(it) }
}
data class ChannelAnime(val id: Long, val title: String, val videos: List<Candidate>) {
    val numbers get() = videos.mapNotNull { EpisodeMatcher.extract(it.title).episode?.number }.distinct().sorted()
}
class ChannelCommentRepository(private val api: ApiClient) {
    private val lock = Mutex()
    private val cache = mutableMapOf<Long, Pair<Long, ChannelAnime>>()
    suspend fun resolveId(value: String): Long = withContext(Dispatchers.IO) {
        val direct = niconicoChannelId(value)
        val id = direct ?: run {
            val name = niconicoChannelName(value) ?: throw MatchUnavailable("チャンネルのURL・ID・名前を入力してください")
            channelFeedId(api.requestText("https://ch.nicovideo.jp/$name/video?rss=2.0")) ?: throw MatchUnavailable("チャンネルを確認できませんでした")
        }
        if (id == DANIME_CHANNEL) throw MatchUnavailable("保存できるのはニコニコ公式のコメントだけです")
        id
    }
    suspend fun input(value: String): ChannelAnime = load(resolveId(value))
    suspend fun load(id: Long): ChannelAnime = withContext(Dispatchers.IO) { lock.withLock {
        require(id > 0 && id != DANIME_CHANNEL)
        cache[id]?.takeIf { System.currentTimeMillis() - it.first < 300000 }?.let { return@withLock it.second }
        val videos = mutableListOf<Candidate>()
        val feed = api.requestText("https://ch.nicovideo.jp/ch$id/video?rss=2.0")
        val sourceTitle = channelSearchTitle(feed)
        videos.addAll(channelFeedCandidates(feed, id))
        val feedNumbers = channelFeedNumbers(feed)
        val expectedNumbers = if (feedNumbers.isNotEmpty()) (1..feedNumbers.max()).toSet() else emptySet()
        val query = URLEncoder.encode(sourceTitle.substringBefore('〜').trim(), "UTF-8")
        for (page in 0 until 100) {
            if (expectedNumbers.isNotEmpty() && videos.mapNotNull { EpisodeMatcher.extract(it.title).episode?.number }.toSet().containsAll(expectedNumbers)) break
            currentCoroutineContext().ensureActive()
            val data = api.request("https://snapshot.search.nicovideo.jp/api/v2/snapshot/video/contents/search?q=$query&targets=title&fields=contentId,title,channelId,thumbnailUrl,commentCounter&_sort=%2BstartTime&_limit=100&_offset=${page * 100}&_context=AniCome").getJSONArray("data")
            for (i in 0 until data.length()) { val item = data.getJSONObject(i)
                val candidate = Candidate(item.getString("contentId"), item.getString("title"), channelId = item.optLong("channelId"), thumbnailUrl = item.optString("thumbnailUrl"), commentCount = item.optLong("commentCounter"))
                val parsed = EpisodeMatcher.extract(candidate.title)
                if (candidate.channelId == id && candidate.isDownloadOfficial() && parsed.isSingleEpisode && parsed.episode != null) videos.add(candidate)
            }
            if (data.length() < 100 || (expectedNumbers.isNotEmpty() && videos.mapNotNull { EpisodeMatcher.extract(it.title).episode?.number }.toSet().containsAll(expectedNumbers))) break
        }
        if (videos.isEmpty()) throw MatchUnavailable("このチャンネルに保存できる公式動画がありません")
        val parsed = EpisodeMatcher.extract(videos.first().title)
        val clean = EpisodeMatcher.clean(EpisodeMatcher.normalize(videos.first().title))
        val title = clean.take(parsed.episode!!.indices.first).trim()
        ChannelAnime(id, title, videos.distinctBy { normalizedEpisodeText(it.title) }).also { cache[id] = System.currentTimeMillis() to it }
    }
    }
    suspend fun episode(id: Long, number: Int): Candidate {
        val choices = load(id).videos.filter { EpisodeMatcher.extract(it.title).episode?.number == number }
        if (choices.size != 1) throw MatchUnavailable("指定した話数の動画を一つに絞れませんでした")
        return choices.single()
    }
}
