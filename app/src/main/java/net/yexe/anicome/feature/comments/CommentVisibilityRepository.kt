package net.yexe.anicome

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class TemporaryCommentVisibility(val remainingSeconds: Int = 0, val episodeHidden: Boolean = false, val canHideEpisode: Boolean = false) {
    val active get() = remainingSeconds > 0 || episodeHidden
}
private data class VisibilityInput(val until: Long, val episode: String, val current: String)
@OptIn(ExperimentalCoroutinesApi::class)
class CommentVisibilityRepository(scope: CoroutineScope, private val clock: () -> Long = android.os.SystemClock::elapsedRealtime) {
    private fun currentEpisode(): String {
        val p = Session.manualPlayback.value ?: Session.playback.value
        return p.key.takeIf { p.title.isNotBlank() || p.subtitle.isNotBlank() || p.mediaId.isNotBlank() }.orEmpty()
    }
    val state: StateFlow<TemporaryCommentVisibility> = combine(Session.hiddenUntil, Session.hiddenEpisode, Session.playback, Session.manualPlayback) { until, episode, _, _ ->
        VisibilityInput(until, episode, currentEpisode())
    }.distinctUntilChanged().flatMapLatest { input -> flow {
        if (input.episode.isNotBlank() && input.current.isNotBlank() && input.episode != input.current) {
            if (Session.hiddenEpisode.value == input.episode) Session.hiddenEpisode.value = ""
            emit(TemporaryCommentVisibility(canHideEpisode = true))
            return@flow
        }
        while (currentCoroutineContext().isActive) {
            val left = (input.until.coerceAtLeast(0L) - clock().coerceAtLeast(0L)).coerceAtLeast(0L)
            if (left == 0L && input.until > 0L && Session.hiddenUntil.value == input.until) Session.hiddenUntil.value = 0L
            emit(TemporaryCommentVisibility((left / 1000L + if (left % 1000L > 0L) 1 else 0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), input.episode.isNotBlank(), input.current.isNotBlank()))
            if (left == 0L) break
            delay(minOf(left, 1000L))
        }
    } }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, TemporaryCommentVisibility())
    fun hide(mode: String) {
        require(mode in setOf("15", "30", "60", "300", "episode", "none"))
        val current = currentEpisode()
        if (mode == "episode" && current.isBlank()) return
        val seconds = mode.toLongOrNull() ?: 0L
        val now = clock().coerceAtLeast(0L)
        when {
            seconds > 0L -> { Session.hiddenUntil.value = now + minOf(seconds * 1000L, Long.MAX_VALUE - now); Session.hiddenEpisode.value = "" }
            mode == "episode" -> { Session.hiddenEpisode.value = current; Session.hiddenUntil.value = 0L }
            else -> { Session.hiddenUntil.value = 0L; Session.hiddenEpisode.value = "" }
        }
    }
}

internal fun temporaryHideLabel(state: TemporaryCommentVisibility): String = when {
    state.episodeHidden -> "この話のコメントを非表示中"
    state.remainingSeconds > 0 -> "${state.remainingSeconds}秒後にコメントを再開"
    else -> "一時非表示にしていません"
}
