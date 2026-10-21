package net.yexe.anicome

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ManualCommentState(val candidates: List<Candidate> = emptyList(), val searching: Boolean = false, val loadingId: String? = null, val error: String = "", val completed: Boolean = false)
class ManualCommentViewModel(private val controller: AppCoordinator, private val playbackSources: PlaybackSourceRepository? = null) : ViewModel() {
    private val mutable = MutableStateFlow(ManualCommentState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    fun reset() { job?.cancel(); mutable.value = ManualCommentState() }
    fun search(series: String, episode: Int, videoId: String, query: String = "") {
        job?.cancel()
        job = viewModelScope.launch {
            mutable.value = ManualCommentState(searching = true)
            try {
                val candidates = if (videoId.isNotBlank()) listOf(Candidate(videoId.trim(), "手動指定の動画")) else if (query.isNotBlank()) controller.repository.searchCandidates(query, Episode(series, episode)) else controller.repository.searchEpisodeCandidates(series, episode)
                ensureActive()
                mutable.value = ManualCommentState(candidates = candidates)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { mutable.value = ManualCommentState(error = if (e is TrafficLimitReached) e.message.orEmpty() else "検索できませんでした") }
        }
    }
    fun obtain(series: String, episode: Int, candidate: Candidate) {
        job?.cancel()
        job = viewModelScope.launch {
            mutable.update { it.copy(loadingId = candidate.id, error = "") }
            try {
                controller.busy.value = true
                val saved = playbackSources?.manual(Episode(series.trim(), episode), candidate, controller.commentAmount.value, controller.effectiveCommentLimit) { label, current, target -> controller.fetchProgress.value = FetchProgress(label, current, target) }
                    ?: controller.repository.obtain(Episode(series.trim(), episode), source = candidate, amount = controller.commentAmount.value, maxComments = controller.effectiveCommentLimit).let { PlaybackComments(listOf(PlaybackCommentSlot(it.info.sourceId, it.info.sourceTitle, PlaybackSource.OFFICIAL, it.comments, it.fromArchive))) }
                if (saved.slots.all { it.fromArchive }) controller.recordArchiveUse(Episode(series.trim(), episode), saved)
                Session.commentSyncReports.value = saved.syncReports
                Session.commentsFromArchive.value = saved.fromArchive
                Session.commentSlots.value = saved.slots
                Session.comments.value = saved.comments
                controller.convenience?.rememberCandidate(Episode(series.trim(), episode), candidate)
                Session.commentSources.value = saved.slots.map { it.source }.distinct()
                Session.matchedTitle = saved.title
                Session.commentBinding.value = CommentBinding.MANUAL
                Session.manualCommentKey.value = (Session.manualPlayback.value ?: Session.playback.value).key
                Session.matchedKey = ""
                Session.offset.value = 0
                controller.refreshLibrary()
                if (!saved.fromArchive) controller.completedFetches.emit(FetchCompletion(saved.title, saved.comments.size))
                controller.status.value = "手動で選択したコメントを使用中"
                Session.message.value = if (saved.fromArchive) "保存済みコメントを使用" else "コメントを${saved.comments.size}件取得しました"
                mutable.update { it.copy(completed = true) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.update { it.copy(error = "取得失敗: ${error.message ?: "動画を確認してください"}") } }
            finally { controller.busy.value = false; mutable.update { it.copy(loadingId = null) } }
        }
    }
}
