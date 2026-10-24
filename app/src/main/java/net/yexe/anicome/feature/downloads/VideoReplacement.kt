package net.yexe.anicome

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class VideoReplacementState(val item: SavedEpisode? = null, val target: Episode? = null, val subtitle: String = "", val query: String = "",
    val candidates: List<Candidate> = emptyList(), val selected: Candidate? = null, val searching: Boolean = false, val saving: Boolean = false,
    val completed: Boolean = false, val error: String = "")
class VideoReplacementRepository(private val comments: CommentRepository, private val resolve: suspend (SavedEpisode) -> ResolvedEpisodeMetadata? = { null },
    private val metadata: suspend (String) -> Candidate = Comments::sourceMetadata, private val changed: suspend () -> Unit = {},
    private val remembered: (Episode, Candidate) -> Unit = { _, _ -> }) {
    suspend fun target(item: SavedEpisode) = resolve(item) ?: ResolvedEpisodeMetadata("", item.episode)
    suspend fun search(query: String, target: Episode? = null) = comments.searchCandidates(query, target).filter(Candidate::isDownloadOfficial).distinctBy { it.id }
    suspend fun replace(item: SavedEpisode, candidate: Candidate, target: Episode, subtitle: String) {
        val actual = metadata(candidate.id)
        comments.replaceOfficial(item, actual, target, subtitle)
        remembered(item.episode, actual)
        if (item.episode != target) remembered(target, actual)
        changed()
    }
}
class VideoReplacementViewModel(private val repository: VideoReplacementRepository) : ViewModel() {
    private val mutable = MutableStateFlow(VideoReplacementState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    fun start(item: SavedEpisode) {
        job?.cancel()
        mutable.value = VideoReplacementState(item = item, target = item.episode, searching = true)
        job = viewModelScope.launch {
            try {
                val target = repository.target(item)
                ensureActive()
                val query = commentSearchQuery(target.episode.series, target.episode.number)
                mutable.update { it.copy(target = target.episode, subtitle = target.title, query = query) }
                val candidates = repository.search(query, target.episode)
                ensureActive()
                mutable.update { it.copy(searching = false, candidates = candidates) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(searching = false, error = "各話情報を確認できませんでした") } }
        }
    }
    fun close() { if (!mutable.value.saving) job?.cancel() }
    fun edit(value: String) { if (!mutable.value.saving) { job?.cancel(); mutable.update { it.copy(query = value.take(300), selected = null, candidates = emptyList(), searching = false) } } }
    fun query(value: String = mutable.value.query) {
        if (mutable.value.saving || value.isBlank()) return
        job?.cancel()
        mutable.update { it.copy(query = value.take(300), searching = true, selected = null, error = "", candidates = emptyList()) }
        job = viewModelScope.launch {
            try { val items = repository.search(value, mutable.value.target); ensureActive(); mutable.update { it.copy(searching = false, candidates = items) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(searching = false, error = "検索できませんでした。通信を確認してください。") } }
        }
    }
    fun choose(candidate: Candidate) { val s = mutable.value; if (!s.saving && candidate in s.candidates && s.target?.let { savedEpisodeMatches(it, candidate, s.subtitle) } == true) mutable.update { it.copy(selected = candidate) } }
    fun save() {
        val snapshot = mutable.value
        val item = snapshot.item ?: return; val target = snapshot.target ?: return; val candidate = snapshot.selected ?: return
        if (snapshot.saving) return
        mutable.update { it.copy(saving = true, error = "") }
        job = viewModelScope.launch {
            try { repository.replace(item, candidate, target, snapshot.subtitle); mutable.update { it.copy(saving = false, completed = true) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(saving = false, error = "差し替えできませんでした: ${e.message.orEmpty()}") } }
        }
    }
}
