package net.yexe.anicome

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DanimeScreenNode(val id: String, val text: String = "", val description: String = "", val visible: Boolean = true)
data class DanimeScreenPlayback(val title: String, val position: Long, val duration: Long, val playing: Boolean?)
data class DanimePlaybackAnchor(val screen: DanimeScreenPlayback, val observedAt: Long, val position: Long = screen.position)

object DanimeScreenParser {
    private val clock = Regex("(?:(\\d+):)?(\\d{1,2}):(\\d{2})")
    fun parse(nodes: List<DanimeScreenNode>): DanimeScreenPlayback? {
        val visible = nodes.filter { it.visible && it.id.startsWith("${StreamingService.D_ANIME.packageName}:id/") }
        fun text(id: String) = visible.firstOrNull { it.id.endsWith("/$id") }?.text?.trim()
        fun time(value: String?): Long? {
            val match = value?.let(clock::matchEntire) ?: return null
            val hours = match.groupValues[1].toLongOrNull() ?: 0
            val minutes = match.groupValues[2].toLong()
            val seconds = match.groupValues[3].toLong()
            if (seconds >= 60 || hours > 0 && minutes >= 60) return null
            return (hours * 3600 + minutes * 60 + seconds) * 1000
        }
        val title = text("title")?.takeIf(String::isNotBlank) ?: return null
        val position = time(text("time_current")) ?: return null
        val duration = time(text("time"))?.takeIf { it > 0 && position <= it } ?: return null
        val control = visible.firstOrNull { it.id.endsWith("/pause") }?.description
        val playing = when (control) {
            "再生一時停止", "一時停止", "Pause" -> true
            "再生", "Play" -> false
            else -> null
        }
        return DanimeScreenPlayback(title, position, duration, playing)
    }
}

class DanimePlaybackRepository(private val now: () -> Long) {
    private val mutableAnchor = MutableStateFlow<DanimePlaybackAnchor?>(null)
    val anchor = mutableAnchor.asStateFlow()
    private var lastAdvanced = 0L
    private var lastControlPlaying: Boolean? = null

    fun observe(screen: DanimeScreenPlayback) {
        val previous = mutableAnchor.value
        val time = now()
        if (previous?.screen?.title != screen.title || previous.screen.position != screen.position || lastControlPlaying == false && screen.playing == true) lastAdvanced = time
        lastControlPlaying = screen.playing
        val observed = screen.copy(playing = screen.playing != false && time - lastAdvanced < 2500)
        if (previous?.screen == observed) return
        val estimated = previous?.takeIf { it.screen.title == screen.title && it.screen.playing == true && observed.playing == true }
            ?.let { it.position + time - it.observedAt }
        val position = alignQuantizedPlaybackTime(screen.position, estimated)
        mutableAnchor.value = DanimePlaybackAnchor(observed, time, position)
    }

    fun resolve(native: Playback): Playback {
        val current = mutableAnchor.value
        val matches = current != null && episodeKey(current.screen.title) == episodeKey(native.searchTitle)
        if (!native.connected || !matches) return native.copy(position = -1, playing = false)
        val screen = current!!.screen
        return native.copy(position = current.position, updatedAt = current.observedAt, duration = screen.duration,
            playing = native.playing && screen.playing != false)
    }

    private fun episodeKey(title: String) = EpisodeMatcher.parse(title)?.key ?: EpisodeMatcher.normalize(title)
    fun clear() { mutableAnchor.value = null; lastAdvanced = 0; lastControlPlaying = null }
}
