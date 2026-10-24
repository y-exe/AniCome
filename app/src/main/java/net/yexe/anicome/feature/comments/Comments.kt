package net.yexe.anicome

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.time.Instant

data class Comment(val at: Long, val body: String, val raw: String? = null, val fork: String = "main")
data class Candidate(
    val id: String,
    val title: String,
    val channelId: Long? = null,
    val duration: Long = 0,
    val commentCount: Long = 0,
    val userId: Long? = null,
    val tags: String? = null,
    val startTime: String? = null,
    val thumbnailUrl: String = "",
    val ownerName: String = "",
    val ownerUrl: String = ""
)

object Comments {
    private val sourceCache = java.util.concurrent.ConcurrentHashMap<String, Candidate>()
    fun knownSource(input: String): Candidate? = niconicoVideoId(input)?.let(sourceCache::get)
    suspend fun sourceMetadata(input: String): Candidate = withContext(Dispatchers.IO) {
        val id = niconicoVideoId(input) ?: error("ニコニコの動画IDを確認できません")
        sourceCache[id]?.let { return@withContext it }
        val session = NiconicoLogin.sessionCookie()
        val watch = NiconicoWatch.load(id, session)
        NiconicoLogin.recordViewer(session, watch)
        parseNiconicoSource(id, watch).also { sourceCache[id] = it; sourceCache[it.id] = it }
    }
    suspend fun refreshSourceMetadata(input: String): Candidate {
        niconicoVideoId(input)?.let(sourceCache::remove)
        return sourceMetadata(input)
    }
    const val MAX_AMOUNT = Int.MAX_VALUE

     
    fun availableAmount(requested: Int, linked: Boolean = NiconicoLogin.isLinked()): Int =
        requested.coerceIn(1, MAX_AMOUNT).coerceAtMost(if (linked) MAX_AMOUNT else 1)

    fun buildSearchQuery(
        parsed: ExtractedResult,
        duration: Long = 0,
        targets: Set<String> = setOf("official", "danime", "szbh")
    ): Pair<String, JSONObject?> {
        val keywords = mutableListOf<String>()

         
        val title = parsed.titleStripped ?: parsed.title ?: parsed.input
        val variants = listOf(
            listOf("STEINS;GATE", "シュタインズ・ゲート"),
            listOf("その着せ替え人形は恋をする", "その着せ替え人形(ビスク・ドール)は恋をする"),
            listOf("人造昆虫カブトボーグ", "人造昆虫カブトボーグ VxV")
        ).find { it.contains(title) } ?: listOf(title)

        val titleQuery = variants.map { if (it.contains(" ")) "\"$it\"" else it }.joinToString(" OR ")
        if (titleQuery.isNotBlank()) {
            keywords.add(if (variants.size > 1) "($titleQuery)" else titleQuery)
        }

         
        if (parsed.isSingleEpisode && parsed.episode != null) {
            val epNum = parsed.episode.number
            val epKansuji = EpisodeMatcher.numberToKanji(epNum)
            val epText = parsed.episode.text
            val sub = parsed.subtitleStripped ?: parsed.subtitle

            val episodeKeywords = mutableListOf(
                "${epNum}話",
                "${epKansuji}話",
                "エピソード$epNum",
                "episode$epNum",
                "ep$epNum",
                "#$epNum",
                "#%02d".format(epNum),
                if (epText.contains(" ")) "\"$epText\"" else epText
            )
            episodeKeywords.addAll(EpisodeMatcher.episodeUnits.flatMap { listOf("$epNum$it", "$epKansuji$it") })
            if (!sub.isNullOrBlank()) {
                episodeKeywords.add("\"$sub\"")
            }
            keywords.add("(${episodeKeywords.distinct().joinToString(" OR ")})")
        } else if (!parsed.isSingleEpisode && parsed.episodes != null && parsed.episodesDivider != null) {
            val epText = parsed.episodes.joinToString(parsed.episodesDivider) { it.text }
            keywords.add(if (epText.contains(" ")) "\"$epText\"" else epText)
        }

        val q = keywords.joinToString(" ").ifBlank { EpisodeMatcher.removeSymbols(parsed.input) }

         
        val orFilters = org.json.JSONArray()

         
        if (targets.contains("official") || targets.contains("danime")) {
            val andFilters = org.json.JSONArray()
            andFilters.put(JSONObject().put("type", "equal").put("field", "genre.keyword").put("value", "アニメ"))
            if (duration > 0) {
                andFilters.put(JSONObject().apply {
                    put("type", "range")
                    put("field", "lengthSeconds")
                    put("from", (duration - 15).coerceAtLeast(0))
                    put("to", duration + 15)
                    put("include_lower", true)
                    put("include_upper", true)
                })
            }
            orFilters.put(JSONObject().put("type", "and").put("filters", andFilters))
        }

         
        if (targets.contains("szbh")) {
            val szbhAnd = org.json.JSONArray()
            val szbhOr = org.json.JSONArray().apply {
                put(JSONObject().put("type", "equal").put("field", "tagsExact").put("value", "コメント専用動画"))
                put(JSONObject().put("type", "equal").put("field", "tagsExact").put("value", "SZBH方式"))
            }
            szbhAnd.put(JSONObject().put("type", "or").put("filters", szbhOr))
            if (duration > 0) {
                szbhAnd.put(JSONObject().apply {
                    put("type", "range")
                    put("field", "lengthSeconds")
                    put("from", (duration - 5).coerceAtLeast(0))
                    put("to", duration + 65)
                    put("include_lower", true)
                    put("include_upper", true)
                })
            }
            orFilters.put(JSONObject().put("type", "and").put("filters", szbhAnd))
        }

        val jsonFilter = if (orFilters.length() > 1) {
            JSONObject().put("type", "or").put("filters", orFilters)
        } else if (orFilters.length() == 1) {
            orFilters.getJSONObject(0)
        } else null

        return q to jsonFilter
    }

    suspend fun searchSnapshot(
        query: String,
        jsonFilter: JSONObject? = null,
        limit: Int = 50,
        sort: String = "-startTime",
        targets: String = "title"
    ): List<Candidate> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query, "UTF-8")
        val fields = "contentId,title,userId,channelId,lengthSeconds,commentCounter,tags,startTime,thumbnailUrl"
        val filterParam = jsonFilter?.let { "&jsonFilter=" + URLEncoder.encode(it.toString(), "UTF-8") }.orEmpty()
        val url = "https://snapshot.search.nicovideo.jp/api/v2/snapshot/video/contents/search?q=$q&targets=$targets&fields=$fields&_sort=$sort&_limit=$limit&_context=AniCome$filterParam"
        val items = request(url).getJSONArray("data")
        List(items.length()) { i ->
            val it = items.getJSONObject(i)
            Candidate(
                id = it.getString("contentId"),
                title = it.getString("title"),
                channelId = if (it.isNull("channelId")) null else it.getLong("channelId"),
                duration = it.optLong("lengthSeconds"),
                commentCount = it.optLong("commentCounter"),
                userId = if (it.isNull("userId")) null else it.getLong("userId"),
                tags = it.optString("tags"),
                startTime = it.optString("startTime"),
                thumbnailUrl = it.optString("thumbnailUrl")
            )
        }
    }

    suspend fun search(query: String): List<Candidate> = withContext(Dispatchers.IO) {
        searchSnapshot(query, limit = 100, sort = "-commentCounter")
    }
     
    suspend fun load(input: String, amount: Int = 1, progress: (current: Int, target: Int) -> Unit = { _, _ -> }): List<Comment> = loadPages(input, availableAmount(amount), progress)
    suspend fun loadToCount(input: String, amount: Int, maxComments: Int, progress: (Int, Int) -> Unit = { _, _ -> }): List<Comment> =
        if (maxComments <= 0) load(input, amount, progress) else loadPages(input, if (NiconicoLogin.isLinked()) Int.MAX_VALUE else 1, progress, wanted = maxComments)
    suspend fun loadPreview(progress: (Int, Int) -> Unit): List<Comment> {
        check(NiconicoLogin.isLinked()) { "ニコニコにログインしてください" }
        return loadPages("so23335421", 1000, progress, 128_000L..228_000L, 30_000)
    }
    private suspend fun loadPages(input: String, multiplier: Int, progress: (Int, Int) -> Unit,
        window: LongRange? = null, wanted: Int = Int.MAX_VALUE): List<Comment> = withContext(Dispatchers.IO) {
        val target = if (wanted == Int.MAX_VALUE) commentTarget(multiplier) else wanted
        progress(0, target)
        val id = niconicoVideoId(input)
            ?: error("ニコニコ動画のURLまたは動画IDを入力してください")
        val session = NiconicoLogin.sessionCookie()
        val watch = NiconicoWatch.load(id, session)
        NiconicoLogin.recordViewer(session, watch)
        runCatching { parseNiconicoSource(id, watch) }.getOrNull()?.let { sourceCache[id] = it; sourceCache[it.id] = it }
        val commentData = watch.optJSONObject("comment") ?: error("動画 $id のコメント情報がありません（非公開・視聴制限の可能性）")
        val nv = commentData.optJSONObject("nvComment") ?: error("動画 $id のコメント取得キーがありません")
        val ignored = mutableSetOf<String>()
        val threadInfo = commentData.optJSONArray("threads")
        for (i in 0 until (threadInfo?.length() ?: 0)) {
            val t = threadInfo!!.getJSONObject(i)
            if (t.optString("label").let { it.contains("easy") || it.contains("extra") || it == "ai" } || t.optString("forkLabel") == "ai") ignored += "${t.optString("forkLabel")}:${t.optString("id")}"
        }
        val params = JSONObject(nv.getJSONObject("params").toString())
        val targets = params.getJSONArray("targets")
        params.put("targets", org.json.JSONArray().apply {
            for (i in 0 until targets.length()) {
                val t = targets.getJSONObject(i)
                if ("${t.optString("fork")}:${t.optString("id")}" !in ignored) put(t)
            }
        })
        val server = URI(nv.getString("server"))
        require(server.scheme == "https" && (server.host == "nicovideo.jp" || server.host.endsWith(".nicovideo.jp"))) { "コメントサーバーを確認できません" }
        var threadKey = nv.getString("threadKey")
        suspend fun fetch(additionals: JSONObject): JSONObject {
            fun body() = JSONObject().put("params", params).put("threadKey", threadKey).put("additionals", additionals)
            var result = request(server.resolve("/v1/threads").toString(), body().toString())
            if (result.optJSONObject("meta")?.optString("errorCode") == "EXPIRED_TOKEN") {
                threadKey = request("https://nvapi.nicovideo.jp/v1/comment/keys/thread?videoId=$id", cookie = session).getJSONObject("data").getString("threadKey")
                result = request(server.resolve("/v1/threads").toString(), body().toString())
            }
            check(result.optJSONObject("meta")?.optInt("status", 200) == 200) { "コメントAPI: ${result.optJSONObject("meta")?.optString("errorCode")}" }
            return result
        }
        val additions = JSONObject().apply {
            if (multiplier > 1) put("when", System.currentTimeMillis() / 1000).put("res_from", -1000)
        }
        val result = fetch(additions)
        val threads = result.getJSONObject("data").getJSONArray("threads")
        mainThread(threads)?.let { base ->
                val fork = base.optString("fork")
                val threadId = base.optString("id")
                params.put("targets", org.json.JSONArray().apply {
                    val current = params.getJSONArray("targets")
                    for (i in 0 until current.length()) {
                        val target = current.getJSONObject(i)
                        if (target.optString("fork") == fork && target.optString("id") == threadId) put(target)
                    }
                })
        }
        collectPages(threads, multiplier, progress, window, wanted) { cursor ->
            delay(1000)
            fetch(JSONObject().put("when", cursor).put("res_from", -1000))
        }
    }
    internal suspend fun collectPages(threads: org.json.JSONArray, multiplier: Int, progress: (Int, Int) -> Unit,
        window: LongRange? = null, wanted: Int = Int.MAX_VALUE, fetchPast: suspend (Long) -> JSONObject): List<Comment> {
        val target = if (wanted == Int.MAX_VALUE) commentTarget(multiplier) else wanted
        val firstMain = mainThread(threads)?.optJSONArray("comments")?.optJSONObject(0)
        for (i in 0 until threads.length()) {
            val item = threads.getJSONObject(i)
            val all = item.getJSONArray("comments")
            val seen = mutableSetOf<String>()
            item.put("comments", org.json.JSONArray().apply {
                for (j in 0 until all.length()) {
                    val comment = all.getJSONObject(j)
                    if (comment.optString("body").isNotBlank() && comment.optLong("vposMs", -1) >= 0 &&
                        (window == null || comment.optLong("vposMs", -1) in window) &&
                        seen.add("${comment.optLong("no")}:${comment.optString("postedAt")}:${comment.optLong("vposMs")}:${comment.optString("body")}")) put(comment)
                }
            })
        }
        progress((0 until threads.length()).sumOf { index -> threads.getJSONObject(index).optJSONArray("comments")?.length() ?: 0 }.coerceAtMost(target), target)
        if (multiplier > 1) {
            val base = mainThread(threads)
            val baseComments = base?.optJSONArray("comments")
            val first = firstMain
            if (base != null && baseComments != null && first != null && first.optLong("no") >= 5) {
                val fork = base.optString("fork")
                val threadId = base.optString("id")
                var cursor = first.optString("postedAt").toEpochSeconds()
                fun identity(comment: JSONObject) = "${comment.optLong("no")}:${comment.optString("postedAt")}:${comment.optLong("vposMs")}:${comment.optString("body")}"
                val seen = (0 until baseComments.length()).map { identity(baseComments.getJSONObject(it)) }.toHashSet()
                val otherCount = (0 until threads.length()).filter { threads.getJSONObject(it) !== base }.sumOf { threads.getJSONObject(it).getJSONArray("comments").length() }
                for (batch in 1 until multiplier) {
                    if (cursor == null || baseComments.length().toLong() + otherCount >= wanted) break
                    val next = fetchPast(cursor)
                    val nextMain = thread(next.getJSONObject("data").getJSONArray("threads"), fork, threadId) ?: break
                    val nextComments = nextMain.optJSONArray("comments") ?: break
                    val nextFirst = nextComments.optJSONObject(0) ?: break
                    for (i in 0 until nextComments.length()) {
                        val comment = nextComments.getJSONObject(i)
                        if (comment.optString("body").isNotBlank() && comment.optLong("vposMs", -1) >= 0 &&
                            (window == null || comment.optLong("vposMs", -1) in window) && seen.add(identity(comment))) {
                            baseComments.put(comment)
                        }
                    }
                    progress((baseComments.length().toLong() + otherCount).coerceAtMost(target.toLong()).toInt(), target)
                    if (nextFirst.optLong("no") < 5) break
                    val nextCursor = nextFirst.optString("postedAt").toEpochSeconds()
                    if (nextCursor == null || nextCursor >= cursor) break
                    cursor = nextCursor
                }
            }
        }
        val parsed = buildList {
            for (i in 0 until threads.length()) {
                val comments = threads.getJSONObject(i).getJSONArray("comments")
                for (j in 0 until comments.length()) {
                    val c = comments.getJSONObject(j)
                    val text = c.optString("body")
                    if (text.isNotBlank() && c.getLong("vposMs") >= 0) add(Comment(c.getLong("vposMs"), text, c.toString(), threads.getJSONObject(i).optString("fork", "main")))
                }
            }
        }.distinct().sortedBy { it.at }
        require(parsed.isNotEmpty()) { "取得できるコメントがありません" }
        val limited = newestComments(parsed, wanted)
        progress(limited.size, limited.size)
        return limited
    }
    private fun mainThread(threads: org.json.JSONArray): JSONObject? {
        var result: JSONObject? = null
        for (i in 0 until threads.length()) {
            val candidate = threads.getJSONObject(i)
            if (candidate.optString("fork") == "main" && (result == null || candidate.optLong("commentCount") > result!!.optLong("commentCount"))) result = candidate
        }
        return result
    }
    private fun thread(threads: org.json.JSONArray, fork: String, id: String): JSONObject? =
        (0 until threads.length()).asSequence().map { threads.getJSONObject(it) }
            .firstOrNull { it.optString("fork") == fork && it.optString("id") == id }
    private fun String.toEpochSeconds(): Long? = runCatching { Instant.parse(this).epochSecond }.getOrNull()
    private suspend fun request(url: String, body: String? = null, cookie: String? = null) = ApiAccess.client.request(url, body, cookie)
}
