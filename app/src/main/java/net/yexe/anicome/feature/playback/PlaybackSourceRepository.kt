package net.yexe.anicome

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import java.io.StringReader
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

const val DANIME_CHANNEL = 2632720L
enum class PlaybackSource(val label: String, val settingKey: String?) {
    OFFICIAL("ニコニコ公式", "sourceOfficial"), DANIME("dアニメ", "sourceDanime"), CHAPTER("dアニメ（分割）", "sourceChapter"), COMMENT_ONLY("コメント専用", "sourceCommentOnly"), JIKKYO("ニコニコ実況", "sourceJikkyo"), LIVE_ARCHIVE("ニコニコ生放送", "sourceLive"), VIDEO("ニコニコ動画", null)
}
fun Candidate.isDownloadOfficial() = isNiconicoChannel() && channelId != DANIME_CHANNEL && !isCommentOnly()
fun Candidate.isCommentOnly() = tags?.contains(Regex("コメント専用動画|SZBH方式", RegexOption.IGNORE_CASE)) == true
data class PlaybackCommentSlot(val id: String, val title: String, val source: PlaybackSource, val comments: List<Comment>, val fromArchive: Boolean = false, val durationMs: Long = 0, val sync: CommentSync = CommentSync.UNKNOWN, val syncOffset: Long = 0L, val syncSpans: List<CommentTimeSpan> = emptyList(), val amount: Int = 1, val countLimit: Int = 0)
data class PlaybackComments(val slots: List<PlaybackCommentSlot>, val syncReports: List<Pair<PlaybackSource, CommentSync>> = slots.filter { it.source != PlaybackSource.OFFICIAL }.map { it.source to it.sync }.distinct()) {
    val comments = slots.flatMap { it.comments }.sortedBy { it.at }
    val title = slots.firstOrNull()?.title.orEmpty()
    val fromArchive = slots.any { it.fromArchive }
}
internal fun continuePlaybackComments(previous: List<PlaybackCommentSlot>, incoming: PlaybackComments, reports: List<Pair<PlaybackSource, CommentSync>> = emptyList()): PlaybackComments {
    if (previous.isEmpty()) return incoming
    val slots = previous.associateByTo(linkedMapOf()) { it.id }
    incoming.slots.filter { it.comments.isNotEmpty() }.forEach { fresh ->
        val old = slots[fresh.id]
        slots[fresh.id] = if (old == null || old.syncOffset != fresh.syncOffset || old.syncSpans != fresh.syncSpans) fresh else {
            val merged = mergeCommentUpdates(old.comments, fresh.comments)
            if (merged === fresh.comments) fresh else fresh.copy(comments = merged, fromArchive = old.fromArchive || fresh.fromArchive)
        }
    }
    val states = reports.toMap().toMutableMap().apply { putAll(incoming.syncReports) }
    return PlaybackComments(slots.values.toList(), states.toList())
}
internal fun mergeCommentUpdates(previous: List<Comment>, fresh: List<Comment>): List<Comment> {
    if (previous === fresh || previous.isEmpty()) return fresh
    val available = fresh.groupingBy(::playbackCommentIdentity).eachCount().toMutableMap()
    val retained = previous.filter { comment ->
        val key = playbackCommentIdentity(comment)
        val count = available[key] ?: 0
        if (count > 0) { available[key] = count - 1; false } else true
    }
    return if (retained.isEmpty()) fresh else (retained + fresh).sortedBy { it.at }
}
internal fun playbackCommentIdentity(comment: Comment): Any {
    val raw = comment.raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return comment
    if (raw.optLong("no") <= 0) return comment
    return listOf(comment.fork, raw.optString("id"), raw.optString("no"), raw.optString("postedAt"), raw.optString("source"))
}
val ChapterTitle = Regex("^(.*?)\\s*Chapter\\.([1-6])\\s*$", RegexOption.IGNORE_CASE)

fun matchingPlaybackVideos(episode: Episode, candidates: List<Candidate>, duration: Long): List<Pair<PlaybackSource, List<Candidate>>> {
    val input = EpisodeMatcher.extract(commentSearchQuery(episode.series, episode.number))
    val unique = candidates.distinctBy { it.id }
    val result = mutableListOf<Pair<PlaybackSource, List<Candidate>>>()
    val chapters = unique.filter { it.channelId == DANIME_CHANNEL && ChapterTitle.matches(it.title) }
        .filter { EpisodeMatcher.compare(input, EpisodeMatcher.extract(ChapterTitle.matchEntire(it.title)!!.groupValues[1])) }
        .groupBy { ChapterTitle.matchEntire(it.title)!!.groupValues[1] }
        .values.map { it.sortedBy { c -> ChapterTitle.matchEntire(c.title)!!.groupValues[2].toInt() } }
        .firstOrNull { parts -> parts.map { ChapterTitle.matchEntire(it.title)!!.groupValues[2].toInt() } == (1..parts.size).toList() && parts.all { it.duration > 0 } && (duration <= 0 || kotlin.math.abs(parts.sumOf { it.duration } - duration) <= 5) }
    if (chapters != null) result += PlaybackSource.CHAPTER to chapters
    unique.filter { !ChapterTitle.matches(it.title) }.groupBy {
        when { it.channelId == DANIME_CHANNEL -> PlaybackSource.DANIME; it.isCommentOnly() -> PlaybackSource.COMMENT_ONLY; it.isDownloadOfficial() -> PlaybackSource.OFFICIAL; it.userId != null -> PlaybackSource.VIDEO; else -> null }
    }.forEach { (kind, videos) ->
        if (kind != null) {
            val matches = EpisodeMatcher.matches(episode, videos, duration, allowGeneralVideos = kind == PlaybackSource.VIDEO)
            if (matches.size in 1..3) matches.forEach { result += kind to listOf(it) }
        }
    }
    return result
}
fun joinChapterComments(parts: List<Pair<Long, List<Comment>>>): List<Comment> {
    var offset = 0L
    return parts.flatMap { (seconds, comments) -> comments.map { it.copy(at = it.at + offset) }.also { offset += seconds * 1000 } }.sortedBy { it.at }
}

fun parseRemoteXml(xml: String): org.w3c.dom.Document {
    require(!Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(xml)) { "XMLの形式を確認できません" }
    return DocumentBuilderFactory.newInstance().newDocumentBuilder().apply { setEntityResolver { _, _ -> InputSource(StringReader("")) } }.parse(InputSource(StringReader(xml)))
}
fun legacyComment(at: Long, body: String, thread: String, no: String, mail: String, user: String, premium: String, date: Long, fork: String): Comment? {
    if (at < 0 || body.isBlank() || Regex("^/(?:gift|info|nicoad|spi|emotion)(?:\\s|$)").containsMatchIn(body)) return null
    val raw = JSONObject().put("id", "$thread:$no").put("no", no.toIntOrNull() ?: 0).put("commands", JSONArray(mail.split(' ').filter(String::isNotBlank)))
        .put("userId", user).put("isPremium", premium == "1").put("postedAt", java.time.Instant.ofEpochSecond(date.coerceAtLeast(0)).toString()).put("source", fork)
    return Comment(at, body, raw.toString(), "main")
}
fun parseNicologComments(xml: String): List<Comment> {
    require(!Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(xml)) { "XMLの形式を確認できません" }
    val result = mutableListOf<Comment>()
    var attributes: Map<String, String>? = null
    val body = StringBuilder()
    val handler = object : org.xml.sax.helpers.DefaultHandler() {
        override fun startElement(uri: String?, local: String?, name: String?, attrs: org.xml.sax.Attributes) {
            if (name == "chat") { attributes = (0 until attrs.length).associate { attrs.getQName(it) to attrs.getValue(it) }; body.setLength(0) }
        }
        override fun characters(chars: CharArray, start: Int, length: Int) { if (attributes != null) body.append(chars, start, length) }
        override fun endElement(uri: String?, local: String?, name: String?) {
            if (name != "chat") return
            val a = attributes ?: return
            if (a["deleted"] != "1") legacyComment((a["vpos"]?.toLongOrNull() ?: -1) * 10, body.toString(), a["thread"].orEmpty(), a["no"].orEmpty(), a["mail"].orEmpty(), a["user_id"].orEmpty(), a["premium"].orEmpty(), a["date"]?.toLongOrNull() ?: 0, "nicolog")?.let(result::add)
            attributes = null
        }
        override fun resolveEntity(publicId: String?, systemId: String?) = InputSource(StringReader(""))
    }
    javax.xml.parsers.SAXParserFactory.newInstance().newSAXParser().parse(InputSource(StringReader(xml.removePrefix("\uFEFF"))), handler)
    return result.sortedBy { it.at }
}
fun parseJikkyoComments(data: JSONObject, start: Long): List<Comment> {
    val packet = data.optJSONArray("packet") ?: return emptyList()
    return (0 until packet.length()).mapNotNull { i ->
        val item = packet.getJSONObject(i).optJSONObject("chat") ?: return@mapNotNull null
        if (item.optInt("deleted") != 0) null else legacyComment(item.optLong("date") * 1000 + item.optLong("date_usec") / 1000 - start * 1000,
            item.optString("content"), item.optString("thread"), item.optString("no"), item.optString("mail"), item.optString("user_id"), item.optString("premium"), item.optLong("date"), "jikkyo")
    }.sortedBy { it.at }
}
data class BroadcastSlot(val channel: String, val start: Long, val end: Long)
val JikkyoChannels = mapOf("1" to "jk1", "2" to "jk2", "4" to "jk4", "6" to "jk5", "5" to "jk6", "7" to "jk7", "3" to "jk8", "19" to "jk9", "14" to "jk10", "8" to "jk11", "13" to "jk12", "58" to "jk13", "66" to "jk14", "9" to "jk101", "179" to "jk103", "71" to "jk141", "18" to "jk151", "16" to "jk161", "15" to "jk171", "17" to "jk181", "204" to "jk191", "97" to "jk192", "76" to "jk193", "285" to "jk200", "286" to "jk201", "128" to "jk211", "129" to "jk222", "197" to "jk236", "251" to "jk252", "271" to "jk260", "273" to "jk263", "272" to "jk265", "20" to "jk333")
fun broadcastSlots(xml: String, now: Long): List<BroadcastSlot> {
    val nodes = parseRemoteXml(xml).getElementsByTagName("ProgItem")
    fun Element.field(name: String) = getElementsByTagName(name).item(0)?.textContent.orEmpty()
    fun time(value: String) = LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(ZoneId.of("Asia/Tokyo")).toEpochSecond()
    return (0 until nodes.length).mapNotNull { i -> runCatching {
        val node = nodes.item(i) as Element
        val channel = JikkyoChannels[node.field("ChID")] ?: return@runCatching null
        BroadcastSlot(channel, time(node.field("StTime")), time(node.field("EdTime"))).takeIf { it.end <= now && it.start < it.end }
    }.getOrNull() }.distinct().sortedByDescending { it.start }.take(3)
}

class PlaybackSourceRepository(
    private val official: CommentRepository,
    private val api: ApiClient,
    private val settings: SettingsRepository,
    private val videoSearch: suspend (String) -> List<Candidate> = Comments::search,
    private val videoLoad: suspend (String, Int) -> List<Comment> = Comments::load,
    private val detailedVideoLoad: (suspend (String, Int, (Int, Int) -> Unit) -> List<Comment>)? = null,
    private val countVideoLoad: suspend (String, Int, Int, (Int, Int) -> Unit) -> List<Comment> = Comments::loadToCount,
) {
    fun forOfficial(repository: CommentRepository) = PlaybackSourceRepository(repository, api, settings, videoSearch, videoLoad, detailedVideoLoad, countVideoLoad)
    val targets = settings.data.map { val p = SettingsSnapshot(it); Triple(AdvancedSettings.from(p).sourceTargets, p.getBoolean("autoCommentSync", true), p.getBoolean("syncOnlyConfirmed", true)) }.distinctUntilChanged()
    private var directories: List<String> = emptyList()
    private var directoryTime = 0L
    private val permits = Semaphore(3)
    private val missing = MissingSourceBackoff()
    fun clearMissing() { missing.clear(); official.clearMissing() }
    private suspend fun loadVideo(id: String, amount: Int, maxComments: Int = 0, progress: (Int, Int) -> Unit): List<Comment> = if (maxComments > 0) countVideoLoad(id, amount, maxComments, progress) else detailedVideoLoad?.invoke(id, amount, progress) ?: videoLoad(id, amount)
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    private suspend fun <T> attempt(trace: (String) -> Unit, block: suspend () -> T): T? = try { block() } catch (e: CancellationException) { throw e } catch (e: TrafficLimitReached) { throw e } catch (e: Exception) { trace(e.message.orEmpty().take(160)); null }
    suspend fun obtain(episode: Episode, duration: Long, searchTitle: String, amount: Int, trace: (String) -> Unit, progress: (String, Int, Int) -> Unit, offlineSave: Boolean = false, requestedSources: Set<PlaybackSource>? = null, searchEpisode: Episode = episode, skipOfficial: Boolean = false, refresh: Boolean = false, maxComments: Int = 0, onPartial: (PlaybackComments) -> Unit = {}): PlaybackComments = withContext(Dispatchers.IO) {
        supervisorScope {
            val prefs = settings.load()
            val failures = java.util.concurrent.ConcurrentLinkedQueue<Exception>()
            suspend fun <T> attemptSource(block: suspend () -> T): T? = try { block() }
                catch (e: CancellationException) { throw e } catch (e: TrafficLimitReached) { throw e }
                catch (e: Exception) { failures.add(e); trace(e.message.orEmpty().take(160)); null }
            val saved = if (offlineSave) official.store.read(episode) else official.store.readForPlayback(episode)
            val selected = requestedSources ?: (PlaybackSource.entries.filter { it.settingKey != null && (offlineSave || prefs.getBoolean(it.settingKey!!, it in DefaultCommentSources)) }.toSet() + if (saved?.slots?.any { it.source == PlaybackSource.VIDEO } == true) setOf(PlaybackSource.VIDEO) else emptySet())
            val completeCountCache = maxComments > 0 && saved != null && saved.countLimit >= maxComments && saved.slots.map { it.source }.toSet() == selected
            val archived = saved?.slots?.ifEmpty { listOf(PlaybackCommentSlot(saved.info.sourceId, saved.info.sourceTitle, PlaybackSource.OFFICIAL, saved.comments, true, durationMs = saved.durationMs, amount = saved.info.amount, countLimit = saved.countLimit)) }.orEmpty()
                .filter { it.source in selected && (if (maxComments > 0) completeCountCache || it.comments.size >= maxComments || it.countLimit >= maxComments else it.countLimit == 0 && (it.source in setOf(PlaybackSource.JIKKYO, PlaybackSource.LIVE_ARCHIVE) || it.amount >= Comments.availableAmount(amount))) }
            val slots = archived.associateByTo(linkedMapOf()) { it.id }
            fun has(kind: PlaybackSource) = archived.any { it.source == kind } && archived.none { it.source == kind && saved?.isIncompleteSource(it.id) == true }
            val missingKey = "${searchEpisode.key}:$duration"
            val throttle = !offlineSave && !refresh
            fun enabled(kind: PlaybackSource) = kind in selected && (refresh || !has(kind)) && (!throttle || !missing.blocked(missingKey, kind))
            fun synchronized(): PlaybackComments {
                if (offlineSave) return PlaybackComments(slots.values.toList()).limited(maxComments)
                val all = synchronizeCommentSlots(slots.values.toList(), duration * 1000, prefs.getBoolean("autoCommentSync", true), false)
                return PlaybackComments(if (prefs.getBoolean("syncOnlyConfirmed", true)) all.filter { it.sync.confirmed } else all,
                    all.filter { it.source != PlaybackSource.OFFICIAL }.map { it.source to it.sync }.distinct()).limited(maxComments)
            }
            val slotMutex = kotlinx.coroutines.sync.Mutex()
            suspend fun publish(slot: PlaybackCommentSlot?) {
                if (slot == null || slot.comments.isEmpty()) return
                slotMutex.lock()
                try {
                    ensureActive()
                    val partial = slots[slot.id]?.takeIf { saved?.isIncompleteSource(slot.id) == true }
                    slots[slot.id] = if (partial == null) slot else slot.copy(comments = mergeRecoveryComments(partial.comments, slot.comments))
                    if (!offlineSave) onPartial(synchronized())
                }
                finally { slotMutex.unlock() }
            }
            if (archived.isNotEmpty() && !offlineSave) onPartial(synchronized())
            val officialJob = async {
                if (!skipOfficial && enabled(PlaybackSource.OFFICIAL)) attemptSource {
                    official.obtain(episode, sourceEpisode = searchEpisode, duration = duration, searchTitle = searchTitle, amount = amount, trace = trace, progress = progress, maxComments = maxComments)
                        .also { publish(PlaybackCommentSlot(it.info.sourceId, it.info.sourceTitle, PlaybackSource.OFFICIAL, it.comments, it.fromArchive, it.durationMs, amount = it.info.amount, countLimit = it.countLimit)) }
                } else null
            }
            val videoJob = async {
                if (setOf(PlaybackSource.OFFICIAL, PlaybackSource.DANIME, PlaybackSource.CHAPTER, PlaybackSource.COMMENT_ONLY, PlaybackSource.VIDEO).none { enabled(it) && (!skipOfficial || it != PlaybackSource.OFFICIAL) }) return@async
                val query = Comments.buildSearchQuery(EpisodeMatcher.extract(commentSearchQuery(searchEpisode.series, searchEpisode.number)), 0).first
                val firstSearch = attemptSource { videoSearch(query) }
                var completeSearch = firstSearch != null
                var videos = firstSearch.orEmpty()
                val wanted = setOf(PlaybackSource.DANIME, PlaybackSource.CHAPTER, PlaybackSource.COMMENT_ONLY, PlaybackSource.VIDEO).filter { enabled(it) }
                val found = matchingPlaybackVideos(searchEpisode, videos, duration).map { it.first }.toSet()
                if (wanted.any { it !in found }) {
                    val secondSearch = attemptSource { videoSearch(searchEpisode.series) }
                    completeSearch = completeSearch && secondSearch != null
                    videos = (videos + secondSearch.orEmpty()).distinctBy { it.id }
                }
                if (throttle && completeSearch) {
                    val available = matchingPlaybackVideos(searchEpisode, videos, duration).map { it.first }.toSet()
                    wanted.filter { it !in available }.forEach { missing.record(missingKey, it) }
                }
                matchingPlaybackVideos(searchEpisode, videos, duration).filter { (kind, _) -> enabled(kind) && (!skipOfficial || kind != PlaybackSource.OFFICIAL) }.map { (kind, items) -> async {
                    if (kind == PlaybackSource.OFFICIAL) {
                        val saved = officialJob.await()
                        if (saved != null && items.any { it.id == saved.info.sourceId }) {
                            publish(PlaybackCommentSlot(saved.info.sourceId, saved.info.sourceTitle, kind, saved.comments, saved.fromArchive, items.first { it.id == saved.info.sourceId }.duration * 1000, amount = saved.info.amount, countLimit = saved.countLimit))
                            return@async
                        }
                    }
                    permits.withPermit { attemptSource {
                        var fromArchive = true
                        val parts = items.map { item ->
                            val archived = if (refresh) null else official.store.readSource(item.id)?.takeIf { it.satisfiesCommentTarget(amount, maxComments) }
                            if (archived == null) fromArchive = false
                            val comments = archived?.comments ?: loadVideo(item.id, amount, maxComments) { current, target -> progress("${kind.label}：コメントを取得中", current, target.coerceAtLeast(current)) }
                            val actualDuration = Comments.knownSource(item.id)?.duration?.takeIf { it > 0 } ?: item.duration
                            actualDuration to comments
                        }
                        publish(PlaybackCommentSlot(if (kind == PlaybackSource.CHAPTER) "chapters:${items.joinToString(",") { it.id }}" else items.first().id, items.first().title, kind, if (kind == PlaybackSource.CHAPTER) joinChapterComments(parts) else parts.first().second, fromArchive, parts.sumOf { it.first } * 1000, amount = Comments.availableAmount(amount), countLimit = maxComments))
                    } }
                } }.awaitAll()
            }
            val jikkyoJob = async { if (enabled(PlaybackSource.JIKKYO)) attemptSource { progress("${PlaybackSource.JIKKYO.label}：コメントを取得中", 0, 0); val fetched = jikkyo(searchEpisode); if (throttle && fetched.isEmpty()) missing.record(missingKey, PlaybackSource.JIKKYO); fetched.forEach { publish(it) } } }
            val liveJob = async { if (enabled(PlaybackSource.LIVE_ARCHIVE)) attemptSource { progress("${PlaybackSource.LIVE_ARCHIVE.label}：コメントを取得中", 0, 0); val fetched = nicolog(searchEpisode); if (throttle && (fetched == null || fetched.comments.isEmpty())) missing.record(missingKey, PlaybackSource.LIVE_ARCHIVE); publish(fetched) } }
            try { awaitAll(officialJob, videoJob, jikkyoJob, liveJob) }
            catch (e: TrafficLimitReached) {
                listOf(officialJob, videoJob, jikkyoJob, liveJob).forEach { it.cancel() }
                listOf(officialJob, videoJob, jikkyoJob, liveJob).joinAll()
                if (slots.isNotEmpty() && (offlineSave || official.store.isAutomaticCache)) official.store.saveSources(episode, limitCommentSlots(slots.values.toList(), maxComments), selected, maxComments = maxComments)
                throw e
            }
            if ((offlineSave || official.store.isAutomaticCache) && slots.isNotEmpty() && (!official.store.isAutomaticCache || slots.values.any { !it.fromArchive })) official.store.saveSources(episode, limitCommentSlots(slots.values.toList(), maxComments), selected, maxComments = maxComments)
            val result = synchronized()
            if (result.comments.isEmpty() && slots.isEmpty()) (failures.firstOrNull { it !is MatchUnavailable } ?: failures.firstOrNull())?.let { throw it }
            if (result.comments.isEmpty()) throw MatchUnavailable(if (slots.isNotEmpty()) "同期に必要な情報がありません。取得元の同期設定を確認してください" else "一致するコメントがありません。取得元・作品名・話数を確認してください")
            progress("取得完了", result.comments.size, result.comments.size)
            result
        }
    }
    suspend fun cached(episode: Episode, duration: Long, maxComments: Int = 0): PlaybackComments? = withContext(Dispatchers.IO) {
        val saved = official.store.readForPlayback(episode) ?: return@withContext null
        val prefs = settings.load()
        val raw = saved.slots.ifEmpty { listOf(PlaybackCommentSlot(saved.info.sourceId, saved.info.sourceTitle, PlaybackSource.OFFICIAL, saved.comments, true, saved.durationMs)) }
            .filter { slot -> slot.source.settingKey?.let { key -> prefs.getBoolean(key, slot.source in DefaultCommentSources) } ?: true }
        val all = synchronizeCommentSlots(raw, duration, prefs.getBoolean("autoCommentSync", true), false)
        PlaybackComments(if (prefs.getBoolean("syncOnlyConfirmed", true)) all.filter { it.sync.confirmed } else all,
            all.filter { it.source != PlaybackSource.OFFICIAL }.map { it.source to it.sync }.distinct()).limited(maxComments).takeIf { it.comments.isNotEmpty() }
    }
    suspend fun manual(episode: Episode, candidate: Candidate, amount: Int, maxComments: Int = 0, progress: (String, Int, Int) -> Unit): PlaybackComments = withContext(Dispatchers.IO) {
        val cached = official.store.readSource(candidate.id)?.takeIf { it.satisfiesCommentTarget(amount, maxComments) }?.limited(maxComments)
        progress(if (cached != null) "保存済みコメントを使用" else "コメントを取得中", cached?.comments?.size ?: 0, cached?.comments?.size ?: (if (maxComments > 0) maxComments else commentTarget(Comments.availableAmount(amount))))
        val comments = cached?.comments ?: loadVideo(candidate.id, amount, maxComments) { current, target -> progress("コメントを取得中", current, target.coerceAtLeast(current)) }
        val source = Comments.knownSource(candidate.id) ?: candidate
        val slot = PlaybackCommentSlot(source.id, source.title, when { source.channelId == DANIME_CHANNEL -> PlaybackSource.DANIME; source.isCommentOnly() -> PlaybackSource.COMMENT_ONLY; source.isDownloadOfficial() -> PlaybackSource.OFFICIAL; else -> PlaybackSource.VIDEO }, comments, cached != null, source.duration * 1000, if (source.isDownloadOfficial()) CommentSync.NATIVE else CommentSync.UNKNOWN, amount = cached?.info?.amount ?: Comments.availableAmount(amount), countLimit = maxComments)
        if (cached == null) {
            if (source.isDownloadOfficial()) official.store.save(episode, source, comments, Comments.availableAmount(amount), countLimit = maxComments)
            else official.store.saveSources(episode, listOf(slot))
        }
        PlaybackComments(listOf(slot)).also { progress(if (cached != null) "保存済みコメントを使用" else "取得完了", comments.size, comments.size) }
    }
    private suspend fun jikkyo(episode: Episode): List<PlaybackCommentSlot> {
        val titles = api.request("https://cal.syoboi.jp/json.php?Req=TitleSearch&Search=${encode(episode.series)}&Limit=15").optJSONObject("Titles") ?: return emptyList()
        val tid = titles.keys().asSequence().firstOrNull { id ->
            val title = titles.getJSONObject(id).optString("Title").replace(Regex("\\(第?[2-9]クール\\)$"), "")
            EpisodeMatcher.compare(EpisodeMatcher.extract("${episode.series} #0"), EpisodeMatcher.extract("$title #0"))
        } ?: return emptyList()
        val xml = api.requestText("https://cal.syoboi.jp/db.php?Command=ProgLookup&TID=${encode(tid)}&Count=${episode.number}&ChID=${JikkyoChannels.keys.joinToString(",")}&JOIN=SubTitles")
        return broadcastSlots(xml, System.currentTimeMillis() / 1000).mapNotNull { slot ->
            attempt({}) {
                val data = api.request("https://jikkyo.tsukumijima.net/api/kakolog/${slot.channel}?starttime=${slot.start}&endtime=${slot.end}&format=json")
                PlaybackCommentSlot("${slot.channel}:${slot.start}-${slot.end}", episode.label, PlaybackSource.JIKKYO, parseJikkyoComments(data, slot.start), durationMs = (slot.end - slot.start) * 1000)
            }
        }
    }
    private suspend fun nicolog(episode: Episode): PlaybackCommentSlot? {
        suspend fun list(path: String): JSONObject = api.request("https://nicolog.oyasumi.today/api/fs/list", JSONObject().put("path", path).put("per_page", 0).toString()).getJSONObject("data")
        if (directories.isEmpty() || System.currentTimeMillis() - directoryTime > 900000) {
            val rows = list("/nico-live-anime").getJSONArray("content")
            directories = (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.optBoolean("is_dir") }.map { it.getString("name") }
            directoryTime = System.currentTimeMillis()
        }
        val directory = directories.firstOrNull { EpisodeMatcher.compare(EpisodeMatcher.extract("${episode.series} #0"), EpisodeMatcher.extract("$it #0")) } ?: return null
        val path = "/nico-live-anime/${directory.replace('/', ' ')}"
        val rows = list(path).getJSONArray("content")
        val name = (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { item ->
            val name = item.optString("name")
            !item.optBoolean("is_dir") && !listOf("_raw.xml", "振り返り上映会.xml", "一挙放送.xml").any(name::endsWith) && EpisodeMatcher.extract(name.replace(Regex("(?:上映会)?\\.xml$"), "")).episode?.number == episode.number
        }?.getString("name") ?: return null
        val detail = api.request("https://nicolog.oyasumi.today/api/fs/get", JSONObject().put("path", "$path/$name").toString()).getJSONObject("data")
        val url = detail.getString("raw_url").let { if (it.startsWith("http://nicolog.oyasumi.today/")) it.replaceFirst("http:", "https:") else it }
        return PlaybackCommentSlot("nicolog:$path/$name", episode.label, PlaybackSource.LIVE_ARCHIVE, parseNicologComments(api.requestText(url)))
    }
}
