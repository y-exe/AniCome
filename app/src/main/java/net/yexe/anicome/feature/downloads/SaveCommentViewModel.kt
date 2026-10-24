package net.yexe.anicome

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class SaveCommentRepository(
    private val downloads: DownloadRepository? = null,
    private val catalogSearch: suspend (String) -> List<AnimeInfo> = AnimeCatalog::search,
    private val reserve: (Episode) -> Unit = { downloads?.enqueue(it) },
    private val reserveSelected: (Episode, AnimeInfo) -> Unit = { episode, work -> if (downloads != null) downloads.enqueue(episode, work) else reserve(episode) },
    private val metadata: EpisodeMetadataRepository? = null,
    private val channels: ChannelCommentRepository? = null,
    private val settings: SettingsRepository? = null,
    private val reviews: SaveReviewRepository = SaveReviewRepository(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onSearch: suspend (String, Int) -> Unit = { _, _ -> },
    private val channelCatalog: AnimeChannelRepository? = null,
) {
    val selectedSources: Flow<Set<PlaybackSource>> = settings?.data?.map { p ->
        val value = SettingsSnapshot(p).getString("saveCommentSources", null)
        value?.split(',')?.mapNotNull { name -> OfflineSources.find { it.name == name } }?.toSet() ?: DefaultCommentSources
    } ?: flowOf(DefaultCommentSources)
    fun rememberSources(value: Set<PlaybackSource>) { settings?.putString("saveCommentSources", value.joinToString(",") { it.name }) }
    suspend fun search(query: String): List<AnimeInfo> {
        val works = trackedSearch(query, record = { value, count, _ -> onSearch(value, count) }) {
            val registered = channelCatalog?.search(query).orEmpty()
            val external = try { catalogSearch(query) }
                catch (e: CancellationException) { throw e } catch (e: TrafficLimitReached) { throw e }
                catch (e: Exception) { if (registered.isEmpty()) throw e else emptyList() }
            val direct = registered.map { entry ->
                val identity = animeWorkIdentity(entry.title)
                val extra = external.singleOrNull { other ->
                    val candidate = animeWorkIdentity(other.title)
                    identity.base == candidate.base && identity.season == candidate.season && identity.cour == candidate.cour &&
                        (entry.year == null || other.year == null || entry.year == other.year)
                }
                if (extra == null) entry else extra.copy(title = entry.title, niconicoChannel = entry.niconicoChannel)
            }
            if (direct.isNotEmpty()) direct else external.map { metadata?.canonical(it) ?: it }
        }
        return works.map { work -> if (works.count { it.title == work.title } > 1 && work.year != null) work.copy(title = "${work.title} (${work.year})") else work }
    }
    suspend fun prepareSelection(work: AnimeInfo): AnimeInfo {
        if (work.niconicoChannel == null || work.episodes != null || channels == null) return work
        val described = metadata?.withEpisodeCount(work) ?: work
        if (described.episodes != null) return described
        val listed = try { channels.input(work.niconicoChannel) }
            catch (e: CancellationException) { throw e } catch (e: TrafficLimitReached) { throw e } catch (_: Exception) { return described }
        if (animeWorkIdentity(work.title).cour > 1) return described
        return described.copy(episodes = listed.numbers.maxOrNull(), imageUrl = work.imageUrl.ifBlank { listed.videos.firstOrNull()?.thumbnailUrl.orEmpty() })
    }
    suspend fun channel(value: String, work: AnimeInfo? = null): ChannelAnime {
        val result = checkNotNull(channels).input(value)
        if (work != null && metadata != null && work.episodes in 1..9999) {
            val wanted = animeWorkIdentity(work.title)
            val actual = animeWorkIdentity(result.title)
            if (wanted.cour > 1 && wanted.base == actual.base && wanted.season == actual.season) {
                val numbers = (1..work.episodes!!).mapNotNull { metadata.resolve(work, it)?.episode?.number }.toSet()
                if (numbers.size == work.episodes && result.numbers.containsAll(numbers)) {
                    return result.copy(videos = result.videos.filter { EpisodeMatcher.extract(it.title).episode?.number in numbers })
                }
            }
        }
        return result
    }
    suspend fun review(work: AnimeInfo, episodes: List<Episode>, sources: Set<PlaybackSource>, channel: Long?, progress: (Int, Int) -> Unit): SaveReview =
        settings?.load().let { prefs ->
            val amount = prefs?.getInt("commentAmount", 5)?.coerceAtLeast(1) ?: 1
            val maximum = if (prefs?.getBoolean("commentCountMode", true) == true) prefs.getInt("commentCountLimit", 7000).coerceAtLeast(1) else 0
            reviews.prepare(work, episodes, sources, channel, if (maximum > 0) ((maximum.toLong() + 999) / 1000).toInt() else amount, progress).copy(amount = amount, maxComments = maximum)
        }
    suspend fun rememberedChannel(work: AnimeInfo): Long? = settings?.load()?.getLong("workChannel:${CommentStore.fileKey(work.title)}", 0L)?.takeIf { it > 0L }
    fun clearChannel(work: AnimeInfo) { settings?.putLong("workChannel:${CommentStore.fileKey(work.title)}", 0L) }
    suspend fun save(episodes: List<Episode>, work: AnimeInfo? = null, channel: Long? = null, sources: Set<PlaybackSource> = OfflineSources, reviewed: List<SaveEpisodeReview> = emptyList(), estimatedBytes: Long = 0L, requestedAmount: Int? = null, requestedMaximum: Int? = null) = withContext(ioDispatcher) {
        require(sources.isNotEmpty())
        require(duplicateReviewVideoIds(reviewed.filter { it.episode in episodes }).isEmpty()) { DuplicateReviewMessage }
        if (work != null && channel != null) settings?.putLong("workChannel:${CommentStore.fileKey(work.title)}", channel)
        if (work != null) settings?.putString("selectedWork:${CommentStore.fileKey(work.title)}", org.json.JSONObject().put("id", work.id).put("title", work.title).put("image", work.imageUrl).put("year", work.year ?: 0).put("episodes", work.episodes ?: 0).put("provider", work.provider).put("niconicoChannel", work.niconicoChannel).toString())
        val preferences = settings?.load()
        val wifi = preferences?.getBoolean("wifiOnly", true) ?: true
        val amount = requestedAmount?.coerceAtLeast(1) ?: preferences?.getInt("commentAmount", 5)?.coerceAtLeast(1) ?: 1
        val maximum = requestedMaximum ?: if (preferences?.getBoolean("commentCountMode", true) == true) preferences.getInt("commentCountLimit", 7000).coerceAtLeast(1) else 0
        episodes.forEach { ensureActive(); if (downloads != null) downloads.reserve(ReservationSpec(it, catalog = work, channel = channel, sources = sources, wifi = wifi, reviewed = reviewed.firstOrNull { row -> row.episode == it }, amount = amount, estimatedBytes = estimatedBytes, maxComments = maximum)) else if (work == null) reserve(it) else reserveSelected(it, work) } }
}
data class SaveCommentState(
    val query: String = "", val results: List<AnimeInfo> = emptyList(), val work: AnimeInfo? = null,
    val loading: Boolean = false, val searched: Boolean = false, val failure: Boolean = false,
    val allEpisodes: Boolean = false, val episode: String = "1", val saving: Boolean = false,
    val error: String = "", val complete: Boolean = false,
    val channelInput: String = "", val channel: ChannelAnime? = null, val checkingChannel: Boolean = false, val channelError: String = "",
    val sources: Set<PlaybackSource> = DefaultCommentSources,
    val rememberedChannel: Long? = null,
    val review: SaveReview? = null, val reviewing: Boolean = false, val reviewCurrent: Int = 0, val reviewTotal: Int = 0, val restoringChannel: Boolean = false, val restoringSources: Boolean = false,
) {
    val selected get() = work?.takeIf { it.title == query }
    val total get() = selected?.episodes?.takeIf { it in 1..9999 }
    val canSave get() = !saving && !reviewing && !restoringSources && !restoringChannel && sources.isNotEmpty() && selected != null && if (channel != null) (allEpisodes || episode.toIntOrNull() in channel.numbers) else if (allEpisodes) total != null else episode.toIntOrNull()?.let { it in 1..(total ?: 9999) } == true
    fun episodesToSave(): List<Episode> = if (!canSave) emptyList() else (if (channel != null && allEpisodes) channel.numbers else (if (allEpisodes) 1..total!! else episode.toInt()..episode.toInt()).toList()).map { Episode(selected!!.title, it) }
}
class SaveCommentViewModel(private val repository: SaveCommentRepository) : ViewModel() {
    private val mutable = MutableStateFlow(SaveCommentState(restoringSources = true))
    val state = mutable.asStateFlow()
    private var searchJob: Job? = null
    private var channelJob: Job? = null
    private var reviewJob: Job? = null
    init { viewModelScope.launch { repository.selectedSources.collect { sources -> mutable.update { it.copy(sources = sources, restoringSources = false) } } } }
    fun start(initial: String) { channelJob?.cancel(); mutable.value = SaveCommentState(sources = mutable.value.sources, restoringSources = mutable.value.restoringSources); query(initial) }
    fun query(value: String) {
        if (mutable.value.saving || mutable.value.reviewing) return
        closeReview()
        channelJob?.cancel()
        searchJob?.cancel()
        mutable.update { it.copy(query = value.take(300), work = null, channel = null, checkingChannel = false, results = emptyList(), rememberedChannel = null, restoringChannel = false, loading = value.isNotBlank(), searched = false, failure = false, error = "") }
        if (value.isBlank()) return
        searchJob = viewModelScope.launch {
            delay(350)
            try { val results = repository.search(value.trim()); ensureActive(); mutable.update { it.copy(results = results, loading = false, searched = true) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(loading = false, failure = true, error = if (e is TrafficLimitReached) e.message.orEmpty() else "") } }
        }
    }
    fun choose(work: AnimeInfo) {
        if (mutable.value.saving || work !in mutable.value.results) return
        searchJob?.cancel()
        channelJob?.cancel()
        mutable.update { it.copy(query = work.title, work = work, results = emptyList(), searched = false, loading = false, failure = false, error = "", episode = "1", channel = null, rememberedChannel = null, restoringChannel = true) }
        channelJob = viewModelScope.launch {
            try {
                val prepared = repository.prepareSelection(work)
                val stored = repository.rememberedChannel(prepared)
                ensureActive()
                mutable.update { if (it.selected == work) it.copy(work = prepared, rememberedChannel = stored, restoringChannel = false) else it }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { if (it.selected == work) it.copy(restoringChannel = false, error = "チャンネル指定を読み込めませんでした") else it } }
        }
    }
    fun forgetChannel() { mutable.value.selected?.let(repository::clearChannel); mutable.update { it.copy(rememberedChannel = null, channel = null) } }
    fun source(value: PlaybackSource) { if (!mutable.value.saving && !mutable.value.reviewing && !mutable.value.restoringSources) { mutable.update { it.copy(sources = if (value in it.sources) it.sources - value else it.sources + value) }; repository.rememberSources(mutable.value.sources) } }
    fun all(value: Boolean) { mutable.update { it.copy(allEpisodes = value, error = "") } }
    fun episode(value: String) { mutable.update { it.copy(episode = value.filter(Char::isDigit).take(4), error = "") } }
    fun retry() = query(mutable.value.query)
    fun channelInput(value: String) { if (!mutable.value.checkingChannel) mutable.update { it.copy(channelInput = value.take(300), channelError = "") } }
    fun cancelChannel() { channelJob?.cancel(); mutable.update { it.copy(checkingChannel = false, channelError = "") } }
    fun connectChannel() {
        if (mutable.value.checkingChannel || mutable.value.saving) return
        val input = mutable.value.channelInput
        val selected = mutable.value.selected
        searchJob?.cancel()
        mutable.update { it.copy(checkingChannel = true, loading = false, channelError = "") }
        channelJob = viewModelScope.launch {
            try {
                val channel = repository.channel(input, selected)
                searchJob?.cancel()
                val work = AnimeInfo(channel.id.toInt(), channel.title, channel.videos.first().thumbnailUrl, "", null, null, channel.numbers.size, emptyList(), "channel")
                mutable.update { it.copy(query = work.title, work = work, channel = channel, checkingChannel = false, loading = false, searched = false, failure = false, error = "", channelError = "", results = emptyList(), episode = channel.numbers.first().toString()) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { mutable.update { it.copy(checkingChannel = false, channelError = e.message ?: "チャンネルを確認できませんでした") } }
        }
    }
    fun closeReview() { reviewJob?.cancel(); mutable.update { it.copy(review = null, reviewing = false) } }
    fun selectReview(episode: Episode, selected: Boolean) { mutable.update { state -> state.copy(error = "", review = state.review?.let { review -> review.copy(episodes = review.episodes.map { if (it.episode == episode) it.copy(selected = selected) else it }) }) } }
    fun save() {
        val snapshot = mutable.value
        val episodes = snapshot.episodesToSave()
        val work = snapshot.selected ?: return
        if (episodes.isEmpty()) return
        mutable.update { it.copy(reviewing = true, review = null, reviewCurrent = 0, reviewTotal = episodes.size, error = "") }
        reviewJob = viewModelScope.launch {
            try {
                val review = repository.review(work, episodes, snapshot.sources, snapshot.channel?.id ?: snapshot.rememberedChannel) { current, total -> mutable.update { it.copy(reviewCurrent = current, reviewTotal = total) } }
                ensureActive()
                mutable.update { it.copy(reviewing = false, review = review) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(reviewing = false, error = if (e is TrafficLimitReached) e.message.orEmpty() else "保存内容を確認できませんでした") } }
        }
    }
    fun confirmSave() {
        val review = mutable.value.review ?: return
        val rows = review.selected
        val episodes = rows.map { it.episode }
        val work = mutable.value.selected
        if (review.duplicateVideoIds.isNotEmpty()) { mutable.update { it.copy(error = DuplicateReviewMessage) }; return }
        if (episodes.isEmpty() || !review.enoughSpace || mutable.value.saving) return
        mutable.update { it.copy(saving = true, error = "") }
        viewModelScope.launch {
            try { repository.save(episodes, work, mutable.value.channel?.id ?: mutable.value.rememberedChannel, mutable.value.sources, rows, estimatedBytes = review.bytesPerEpisode, requestedAmount = review.amount, requestedMaximum = review.maxComments); Session.message.value = "${episodes.size}話を保存予約しました"; mutable.update { it.copy(saving = false, complete = true, review = null) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(saving = false, error = "保存予約を作成できませんでした") } }
        }
    }
}
