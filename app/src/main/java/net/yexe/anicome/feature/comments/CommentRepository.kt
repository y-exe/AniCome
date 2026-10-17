package net.yexe.anicome
import kotlinx.coroutines.CancellationException

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject

open class MatchUnavailable(message: String) : Exception(message)
class OfficialMatchUnavailable(message: String) : MatchUnavailable(message)
fun commentSearchQuery(series: String, number: Int): String = "${series.trim()} 第${number}話"

data class SortedSearchData(
    val official: MutableList<Candidate> = mutableListOf(),
    val danime: MutableList<Candidate> = mutableListOf(),
    val szbh: MutableList<Candidate> = mutableListOf(),
    val general: MutableList<Candidate> = mutableListOf()
)

class CommentRepository(
    val store: CommentStore,
    private val search: suspend (String) -> List<Candidate> = Comments::search,
    private val load: suspend (String, Int) -> List<Comment> = Comments::load,
    private val detailedLoad: (suspend (String, Int, (Int, Int) -> Unit) -> List<Comment>)? = null,
    private val snapshotLoader: suspend (String, JSONObject?, Int, String, String) -> List<Candidate> = { query, filter, limit, sort, targets -> Comments.searchSnapshot(query, filter, limit, sort, targets) },
    private val reuse: CommentStore? = null,
    private val onSearch: suspend (String, Int, Episode?) -> Unit = { _, _, _ -> }
) {
    private val permits = kotlinx.coroutines.sync.Semaphore(3)
    private val episodeLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private val searchCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Candidate>>>()
    private val missing = MissingSourceBackoff()
    fun clearMissing() { missing.clear(); searchCache.entries.removeIf { it.value.second.isEmpty() } }
    private val seriesCache = java.util.concurrent.ConcurrentHashMap<String, List<Candidate>>()

    fun forPlayback(cache: CommentStore) = CommentRepository(cache, search, load, detailedLoad, snapshotLoader, onSearch = onSearch)
    private fun archivedSource(id: String) = listOfNotNull(store.readSource(id), reuse?.readSource(id)).maxWithOrNull(compareBy<SavedComments> { it.info.amount }.thenBy { it.comments.size })
    private fun promoteJson(text: String, episode: Episode, selected: Set<PlaybackSource>) {
        val root = store.validateImport(text)
        if (root.getInt("version") == 1) { if (PlaybackSource.OFFICIAL !in selected) return }
        else {
            val rows = root.getJSONArray("sources")
            val chosen = (0 until rows.length()).map { rows.getJSONObject(it) }.filter { PlaybackSource.valueOf(it.getString("source")) in selected }
            if (chosen.isEmpty()) return
            val primary = chosen.firstOrNull { it.getString("source") == "OFFICIAL" } ?: chosen.first()
            if (primary.getString("id") != root.getString("sourceId")) {
                val metadata = Comments.knownSource(primary.getString("id"))
                root.put("thumbnailUrl", metadata?.thumbnailUrl.orEmpty()).put("ownerName", metadata?.ownerName.orEmpty()).put("ownerUrl", metadata?.ownerUrl.orEmpty())
            }
            root.put("sources", org.json.JSONArray(chosen)).put("sourceId", primary.getString("id")).put("sourceTitle", primary.getString("title"))
                .put("amount", primary.optInt("amount", 1)).put("durationMs", primary.optLong("durationMs"))
        }
        root.put("series", episode.series).put("episode", episode.number).put("requestedSources", org.json.JSONArray(selected.map { it.name }))
        store.importJson(root.toString(), upgrade = true)
    }
    suspend fun promoteCached(episode: Episode, selected: Set<PlaybackSource> = OfflineSources, sourceEpisode: Episode = episode) = withContext(Dispatchers.IO) {
        val saved = reuse?.readOwn(sourceEpisode) ?: return@withContext
        reuse.exportJson(saved.info.episode)?.let { promoteJson(it, episode, selected) }
    }
    suspend fun cachedSource(id: String, amount: Int = 1) = withContext(Dispatchers.IO) { archivedSource(id)?.takeIf { it.info.amount >= amount } }
    suspend fun cached(episode: Episode, amount: Int = 1) = withContext(Dispatchers.IO) {
        listOfNotNull(store.read(episode), reuse?.read(episode)).filterNot { it.isIncompleteSource(it.info.sourceId) }
            .maxWithOrNull(compareBy<SavedComments> { it.info.amount }.thenBy { it.comments.size })?.takeIf { it.info.amount >= Comments.availableAmount(amount) }?.copy(fromArchive = true)
    }

    suspend fun obtain(
        episode: Episode,
        source: Candidate? = null,
        duration: Long = 0,
        searchTitle: String = "",
        amount: Int = 1,
        episodeTitle: String = "",
        strictMatch: Boolean = false,
        sourceEpisode: Episode? = null,
        requiredChannelId: Long? = null,
        trace: (String) -> Unit = {},
        forceSearch: Boolean = false,
        progress: (label: String, current: Int, target: Int) -> Unit = { _, _, _ -> }
    ): SavedComments = episodeLocks.getOrPut(episode.key) { Mutex() }.withLock { permits.withPermit {
        val requestedAmount = amount.coerceIn(1, Comments.MAX_AMOUNT)
        val availableAmount = Comments.availableAmount(requestedAmount)
        if (requestedAmount > availableAmount) trace("ニコニコ未ログインのため履歴取得は1倍です（2倍以上にはログイン連携が必要です）")
        val lookup = sourceEpisode ?: episode
        val accept: (Candidate) -> Boolean = { (requiredChannelId == null || it.channelId == requiredChannelId) && (!strictMatch || savedEpisodeMatches(lookup, it, episodeTitle)) }
        if (source == null) cached(episode, availableAmount)?.takeIf { it.info.sources.split(",").contains("OFFICIAL") }?.takeIf { accept(Candidate(it.info.sourceId, it.info.sourceTitle)) }?.let { progress("保存済みコメントを使用", it.comments.size, it.comments.size); return@withLock withContext(Dispatchers.IO) {
            if (reuse != null) {
                promoteCached(episode, setOf(PlaybackSource.OFFICIAL), it.info.episode)
                if (store.read(episode) == null) store.exportJson(it.info.episode)?.let { json -> promoteJson(json, episode, setOf(PlaybackSource.OFFICIAL)) }
            }
            (if (reuse != null) store.read(episode)?.copy(fromArchive = true) else null) ?: it
        } }

        val missingKey = "${lookup.key}:$duration:$searchTitle:$episodeTitle:$strictMatch:$requiredChannelId"
        if (forceSearch) clearMissing()
        if (source == null && !forceSearch && missing.blocked(missingKey, PlaybackSource.OFFICIAL)) throw OfficialMatchUnavailable("${lookup.label}：全7段階の検索を試行しましたが一致する動画が見つかりませんでした。作品名や話数をご確認ください")
        if (source == null && episodeTitle.isNotBlank()) {
            progress("各話のタイトルで動画を検索中", 0, 0)
            val query = "\"${animeSearchStem(lookup.series).replace('"', ' ')}\" \"${episodeTitle.replace('"', ' ')}\""
            val candidates = try { search(query).filter { it.isDownloadOfficial() && accept(it) }.sortedByDescending { it.commentCount }.take(3) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (limit: TrafficLimitReached) { throw limit }
                catch (_: Exception) { emptyList() }
            if (candidates.isNotEmpty()) return@withLock fetchAndSave(episode, candidates, availableAmount, trace, progress, accept, strictMatch || requiredChannelId != null)
        }
        if (source == null && strictMatch && animeWorkIdentity(lookup.series).let { it.season > 1 || it.cour > 1 }) {
            progress("動画を検索中", 0, 0)
            val candidates = try { search("${animeSearchStem(lookup.series)} ${lookup.number}").filter { it.isDownloadOfficial() && accept(it) }.sortedByDescending { it.commentCount }.take(3) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (limit: TrafficLimitReached) { throw limit }
                catch (_: Exception) { emptyList() }
            if (candidates.isNotEmpty()) return@withLock fetchAndSave(episode, candidates, availableAmount, trace, progress, accept, strictMatch || requiredChannelId != null)
        }

        val seriesKey = EpisodeMatcher.normalize(lookup.series)

         
        val cachedList = seriesCache[seriesKey]
        if (source == null && cachedList != null) {
            val cachedMatches = EpisodeMatcher.matches(lookup, cachedList.filter { it.isDownloadOfficial() && accept(it) }, duration)
            if (cachedMatches.isNotEmpty()) {
                trace("シリーズキャッシュから候補を再利用: ${cachedMatches.size}件")
                return@withLock fetchAndSave(episode, cachedMatches, availableAmount, trace, progress, accept, strictMatch || requiredChannelId != null)
            }
        }

         
        val rawSearchTitle = searchTitle.trim()
        if (source == null && rawSearchTitle.isNotBlank()) {
            trace("指定タイトルで検索: $rawSearchTitle")
            val titleResults = try { search(rawSearchTitle) } catch (e: CancellationException) { throw e } catch (e: TrafficLimitReached) { throw e } catch (_: Exception) { emptyList() }
            if (titleResults.isNotEmpty()) {
                seriesCache.compute(seriesKey) { _, previous -> (previous.orEmpty() + titleResults).distinctBy { it.id } }
                val titleMatches = EpisodeMatcher.matches(lookup, titleResults.filter { it.isDownloadOfficial() && accept(it) }, duration)
                if (titleMatches.isNotEmpty()) {
                    return@withLock fetchAndSave(episode, titleMatches, availableAmount, trace, progress, accept, strictMatch || requiredChannelId != null)
                }
            }
        }

        val targetInput = if (rawSearchTitle.isNotBlank() && rawSearchTitle.contains(Regex("[0-9話]"))) {
            rawSearchTitle
        } else {
            "${if (rawSearchTitle.isNotBlank()) rawSearchTitle else lookup.series} 第${lookup.number}話"
        }
        val parsed = EpisodeMatcher.extract(targetInput)

        trace("NCOverlay解析: 作品名=${parsed.titleStripped ?: parsed.title}, シーズン=${parsed.season?.text ?: "-"}, 話数=${parsed.episode?.number ?: lookup.number}")
        progress("動画を検索中", 0, 0)

        var matches: List<Candidate> = if (source != null) {
            listOf(source)
        } else {
            executeMultiStageSearch(parsed, lookup, duration, trace, accept, "$strictMatch:$episodeTitle")
        }

        trace("一致候補 ${matches.size}件 (通常公式 / dアニメ支店 / コメント専用 / 一般動画)")
        if (matches.isEmpty() && source == null) {
            trace("手動検索と同じタイトルでニコニコチャンネルの動画を確認")
            matches = search(commentSearchQuery(lookup.series, lookup.number)).filter { it.isDownloadOfficial() && accept(it) }.sortedByDescending { it.commentCount }.take(3)
        }
        if (matches.isEmpty()) {
            missing.record(missingKey, PlaybackSource.OFFICIAL)
            throw OfficialMatchUnavailable("${lookup.label}：全7段階の検索を試行しましたが一致する動画が見つかりませんでした。作品名や話数をご確認ください")
        }

        fetchAndSave(episode, matches, availableAmount, trace, progress, accept, strictMatch || requiredChannelId != null)
    }
    }

    private suspend fun fetchAndSave(
        episode: Episode,
        candidates: List<Candidate>,
        availableAmount: Int,
        trace: (String) -> Unit,
        progress: (label: String, current: Int, target: Int) -> Unit,
        accept: (Candidate) -> Boolean = { true },
        storeForEpisode: Boolean = false
    ): SavedComments {
        var lastError: Exception? = null
        for (candidate in candidates) {
            try {
                withContext(Dispatchers.IO) { archivedSource(candidate.id) }?.let { archived ->
                    trace("保存済みの同じ動画から再利用: ${archived.info.sourceId}")
                    progress("保存済みコメントを使用", archived.comments.size, archived.comments.size)
                    if (storeForEpisode && archived.info.episode.key != episode.key) return withContext(Dispatchers.IO) {
                        store.save(episode, candidate.copy(thumbnailUrl = candidate.thumbnailUrl.ifBlank { archived.info.thumbnailUrl },
                            ownerName = candidate.ownerName.ifBlank { archived.info.ownerName }, ownerUrl = candidate.ownerUrl.ifBlank { archived.info.ownerUrl }), archived.comments, archived.info.amount).copy(fromArchive = true)
                    }
                    return archived.copy(fromArchive = true)
                }
                require(candidate.isDownloadOfficial()) { "保存できるのはニコニコ公式のコメントだけです" }
                trace("コメント取得: ${candidate.id} ${candidate.title}")
                progress("コメントを取得中", 0, availableAmount * 1000)
                val comments = detailedLoad?.invoke(candidate.id, availableAmount) { current, target ->
                    progress("コメントを取得中", current, target)
                } ?: load(candidate.id, availableAmount).also { progress("コメントを取得中", it.size, it.size) }
                trace("コメント取得成功: ${comments.size}件 / ${availableAmount}倍 / ${candidate.id}")
                progress("取得完了", comments.size, comments.size)
                val confirmed = Comments.knownSource(candidate.id)?.let { metadata -> candidate.copy(id = metadata.id, title = metadata.title, duration = metadata.duration.takeIf { it > 0 } ?: candidate.duration, thumbnailUrl = metadata.thumbnailUrl.ifBlank { candidate.thumbnailUrl }, ownerName = metadata.ownerName, ownerUrl = metadata.ownerUrl, channelId = metadata.channelId ?: candidate.channelId, userId = metadata.userId ?: candidate.userId) } ?: candidate
                require(confirmed.isDownloadOfficial()) { "保存できるのはニコニコ公式のコメントだけです" }
                if (!accept(confirmed)) throw MatchUnavailable("作品名・話数・各話タイトルを確認できないため保存を中止しました")
                return withContext(Dispatchers.IO) { store.save(episode, confirmed, comments, availableAmount) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (limit: TrafficLimitReached) { throw limit }
            catch (e: Exception) { lastError = e; trace("${candidate.id}: ${e.message?.take(200)}") }
        }
        throw lastError ?: MatchUnavailable("コメントを取得できませんでした")
    }

    private suspend fun executeMultiStageSearch(
        parsed: ExtractedResult,
        episode: Episode,
        duration: Long,
        trace: (String) -> Unit,
        accept: (Candidate) -> Boolean = { true },
        validationKey: String = ""
    ): List<Candidate> {
        val seriesKey = EpisodeMatcher.normalize(episode.series)
        val cacheKey = "${parsed.titleStripped ?: parsed.title}:${parsed.episode?.number}:${duration}:$validationKey"
        val cached = searchCache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.first < 300_000) {
            return cached.second
        }

        var searchError: Exception? = null
        suspend fun searchOrEmpty(block: suspend () -> List<Candidate>): List<Candidate> = try { block() }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (limit: TrafficLimitReached) { throw limit }
            catch (e: Exception) { if (searchError == null) searchError = e; emptyList() }
        val allCandidates = mutableListOf<Candidate>()

         
        val directQuery1 = "${episode.series} ${episode.number}"
        val mockData1 = searchOrEmpty { search(directQuery1) }
        if (mockData1.isNotEmpty()) {
            allCandidates.addAll(mockData1)
            seriesCache.compute(seriesKey) { _, previous -> (previous.orEmpty() + mockData1).distinctBy { it.id } }
            val directMatches = EpisodeMatcher.matches(episode, mockData1.filter { it.isDownloadOfficial() && accept(it) }, duration)
            if (directMatches.isNotEmpty()) {
                return directMatches.also { cacheResult(cacheKey, it) }
            }
        }

         
        val (q1, jsonFilter1) = Comments.buildSearchQuery(parsed, duration)
        trace("Stage 1: NCOverlay高精度検索 (jsonFilter)")
        val data1 = searchOrEmpty { snapshotSearch(q1, jsonFilter1, limit = 50, sort = "-startTime") }
        allCandidates.addAll(data1)
        seriesCache.compute(seriesKey) { _, previous -> (previous.orEmpty() + data1).distinctBy { it.id } }

        var sorted = sortSearchData(parsed, allCandidates.filter(accept))
        if (sorted.official.isNotEmpty()) {
            return toResultList(sorted).also { cacheResult(cacheKey, it) }
        }

         
        val q2 = EpisodeMatcher.removeSymbols(
            listOfNotNull(
                parsed.titleStripped ?: parsed.title,
                parsed.season?.takeIf { it.number > 1 }?.text,
                if (parsed.isSingleEpisode) parsed.episode?.number?.toString() else parsed.episodes?.joinToString(parsed.episodesDivider ?: "") { it.text },
                parsed.subtitleStripped ?: parsed.subtitle
            ).joinToString(" ").ifBlank { parsed.input }
        )
        trace("Stage 2: キーワードフォールバック検索")
        val data2 = searchOrEmpty { snapshotSearch(q2, jsonFilter = null, limit = 50, sort = "-startTime") }
        allCandidates.addAll(data2)
        seriesCache.compute(seriesKey) { _, previous -> (previous.orEmpty() + data2).distinctBy { it.id } }

        sorted = sortSearchData(parsed, allCandidates.filter(accept))
        if (sorted.official.isNotEmpty()) {
            return toResultList(sorted).also { cacheResult(cacheKey, it) }
        }

         
        if (duration > 0) {
            trace("Stage 3: 動画尺制限を解除して検索拡大")
            val (q3, _) = Comments.buildSearchQuery(parsed, 0)
            val data3 = searchOrEmpty { snapshotSearch(q3, jsonFilter = null, limit = 50, sort = "-startTime") }
            allCandidates.addAll(data3)
            seriesCache.compute(seriesKey) { _, previous -> (previous.orEmpty() + data3).distinctBy { it.id } }
            sorted = sortSearchData(parsed, allCandidates.filter(accept))
            if (sorted.official.isNotEmpty()) {
                return toResultList(sorted).also { cacheResult(cacheKey, it) }
            }
        }

         
        val qWide = parsed.titleStripped ?: parsed.title ?: episode.series
        trace("Stage 4: 作品名ワイド検索 ($qWide)")
        val dataWide = searchOrEmpty { snapshotSearch(qWide, jsonFilter = null, limit = 100, sort = "-commentCounter") }
        allCandidates.addAll(dataWide)
        seriesCache.compute(seriesKey) { _, previous -> (previous.orEmpty() + dataWide).distinctBy { it.id } }
        sorted = sortSearchData(parsed, allCandidates.filter(accept))
        if (sorted.official.isNotEmpty()) {
            return toResultList(sorted).also { cacheResult(cacheKey, it) }
        }

         
        val coreTitle = EpisodeMatcher.extractCoreTitle(parsed.title ?: episode.series)
        if (coreTitle != (parsed.titleStripped ?: parsed.title)) {
            trace("Stage 5: タイトル短縮コア検索 ($coreTitle)")
            val qCore = "$coreTitle 第${parsed.episode?.number ?: episode.number}話"
            val dataCore = searchOrEmpty { snapshotSearch(qCore, jsonFilter = null, limit = 50, sort = "-commentCounter") }
            allCandidates.addAll(dataCore)
            seriesCache.compute(seriesKey) { _, previous -> (previous.orEmpty() + dataCore).distinctBy { it.id } }
            sorted = sortSearchData(parsed, allCandidates.filter(accept))
            if (sorted.official.isNotEmpty()) {
                return toResultList(sorted).also { cacheResult(cacheKey, it) }
            }
        }

         
        trace("Stage 6: タイトル＆タグ検索 (title,tags)")
        val qTag = coreTitle
        val dataTag = searchOrEmpty { snapshotSearch(qTag, jsonFilter = null, limit = 50, sort = "-commentCounter", targets = "title,tags") }
        allCandidates.addAll(dataTag)
        seriesCache.compute(seriesKey) { _, previous -> (previous.orEmpty() + dataTag).distinctBy { it.id } }
        sorted = sortSearchData(parsed, allCandidates.filter(accept))
        if (sorted.official.isNotEmpty()) {
            return toResultList(sorted).also { cacheResult(cacheKey, it) }
        }

         
        trace("Stage 7: 類似度緩和(0.5) ＆ 一般動画フォールバック照合")
        val fuzzySorted = sortSearchData(parsed, allCandidates.filter(accept), allowFuzzy = true, allowGeneralVideos = true)
        val finalResult = toResultList(fuzzySorted)

         
        if (finalResult.isEmpty()) {
            val fallbackData = searchOrEmpty { search(parsed.input) }
            if (fallbackData.isNotEmpty()) {
                seriesCache.compute(seriesKey) { _, previous -> (previous.orEmpty() + fallbackData).distinctBy { it.id } }
                val customSorted = sortSearchData(parsed, fallbackData.filter(accept), allowFuzzy = true, allowGeneralVideos = true)
                val customResult = toResultList(customSorted)
                if (customResult.isNotEmpty()) {
                    return customResult.also { cacheResult(cacheKey, it) }
                }
            }
        }

        if (finalResult.isEmpty()) searchError?.let { throw it }
        cacheResult(cacheKey, finalResult)
        return finalResult
    }

    private fun sortSearchData(
        parsed: ExtractedResult,
        data: List<Candidate>,
        allowFuzzy: Boolean = false,
        allowGeneralVideos: Boolean = false
    ): SortedSearchData {
        val result = SortedSearchData()
        val tagSzbhRegexp = Regex("(?:コメント専用動画|SZBH方式)", RegexOption.IGNORE_CASE)

        for (item in data.distinctBy { it.id }) {
            val candParsed = EpisodeMatcher.extract(item.title)
            val isMatch = EpisodeMatcher.compare(parsed, candParsed, allowFuzzy = allowFuzzy)
            if (!isMatch) continue

            if (item.channelId != null) {
                if (item.channelId == 2632720L) {
                    result.danime.add(item)
                } else {
                    result.official.add(item)
                }
            } else if (item.userId != null) {
                if (item.tags?.contains(tagSzbhRegexp) == true) {
                    result.szbh.add(item)
                } else if (allowGeneralVideos) {
                    result.general.add(item)
                }
            } else if (item.id.startsWith("so")) {
                result.official.add(item)
            } else if (allowGeneralVideos) {
                result.general.add(item)
            }
        }
        return result
    }

    private fun toResultList(sorted: SortedSearchData): List<Candidate> {
        val list = mutableListOf<Candidate>()
        list.addAll(sorted.official.sortedByDescending { it.commentCount }.take(3))
        return list
    }

    private suspend fun snapshotSearch(query: String, jsonFilter: JSONObject? = null, limit: Int = 50, sort: String = "-startTime", targets: String = "title"): List<Candidate> = snapshotLoader(query, jsonFilter, limit, sort, targets)

    private fun cacheResult(key: String, result: List<Candidate>) {
        if (searchCache.size >= 40) searchCache.clear()
        searchCache[key] = System.currentTimeMillis() to result
    }

    suspend fun searchCandidates(query: String, episode: Episode? = null): List<Candidate> = trackedSearch(query, episode, onSearch) { search(query.trim()) }

    suspend fun searchEpisodeCandidates(series: String, number: Int, subtitle: String = ""): List<Candidate> {
        val episode = Episode(series, number)
        val parsed = EpisodeMatcher.extract(commentSearchQuery(series, number))
        val query = Comments.buildSearchQuery(parsed, 0).first
        suspend fun safeSearch(value: String): Result<List<Candidate>> = try { Result.success(searchCandidates(value, episode)) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (limit: TrafficLimitReached) { throw limit }
            catch (error: Exception) { Result.failure(error) }
        val titleResult = if (subtitle.isNotBlank() && normalizedEpisodeText(subtitle).length >= 6)
            safeSearch("\"${animeSearchStem(series).replace('"', ' ')}\" \"${subtitle.replace('"', ' ')}\"") else null
        val byTitle = titleResult?.getOrDefault(emptyList()).orEmpty()
        if (byTitle.any { it.isDownloadOfficial() && savedEpisodeMatches(episode, it, subtitle) }) return byTitle.distinctBy { it.id }.sortedByDescending { it.commentCount }
        val candidates = kotlinx.coroutines.supervisorScope {
            val precise = async { safeSearch(query) }
            val broad = async { safeSearch(series.trim()) }
            val results = listOfNotNull(titleResult, precise.await(), broad.await())
            if (results.all { it.isFailure }) throw checkNotNull(results.last().exceptionOrNull())
            results.flatMap { it.getOrDefault(emptyList()) }
        }
        return candidates.distinctBy { it.id }.sortedWith(compareByDescending<Candidate> {
            EpisodeMatcher.compare(parsed, EpisodeMatcher.extract(it.title))
        }.thenByDescending { it.isNiconicoChannel() }.thenByDescending { it.commentCount })
    }

    suspend fun replaceOfficial(item: SavedEpisode, candidate: Candidate, expected: Episode = item.episode, subtitle: String = ""): SavedComments = episodeLocks.getOrPut(item.episode.key) { Mutex() }.withLock { permits.withPermit {
        require(candidate.isDownloadOfficial()) { "保存できるのはニコニコ公式のコメントだけです" }
        if (!savedEpisodeMatches(expected, candidate, subtitle)) throw MatchUnavailable("作品名・話数・各話タイトルを確認できないため保存を中止しました")
        val saved = fetchAndSave(item.episode, listOf(candidate), Comments.availableAmount(item.amount), {}, { _, _, _ -> },
            { savedEpisodeMatches(expected, it, subtitle) }, true)
        withContext(Dispatchers.IO) {
            if (store.readOwn(item.episode)?.info?.sourceId != saved.info.sourceId) store.save(item.episode, candidate, saved.comments, saved.info.amount)
            else checkNotNull(store.readOwn(item.episode))
        }
    } }

    suspend fun delete(episode: Episode) = episodeLocks.getOrPut(episode.key) { Mutex() }.withLock { withContext(Dispatchers.IO) { store.delete(episode) } }
    suspend fun refresh(item: SavedEpisode): SavedComments = episodeLocks.getOrPut(item.episode.key) { Mutex() }.withLock { permits.withPermit {
        val source = Comments.sourceMetadata(item.sourceId)
        require(source.isDownloadOfficial())
        val comments = detailedLoad?.invoke(item.sourceId, Comments.availableAmount(item.amount)) { _, _ -> } ?: load(item.sourceId, Comments.availableAmount(item.amount))
        withContext(Dispatchers.IO) { store.save(item.episode, source, comments, Comments.availableAmount(item.amount)) }
    } }
    suspend fun relabel(from: Episode, to: Episode) = episodeLocks.getOrPut(from.key) { Mutex() }.withLock { withContext(Dispatchers.IO) { store.relabel(from, to) } }
}
