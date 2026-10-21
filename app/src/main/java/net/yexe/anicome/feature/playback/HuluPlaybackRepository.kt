package net.yexe.anicome

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class HuluScreenSnapshot(val series: String, val episodeTitle: String, val episodeDetail: String,
    val currentTime: String, val totalTime: String, val playerVisible: Boolean = true)
data class HuluScreenPlayback(val title: String, val episode: String, val position: Long, val duration: Long)

object HuluScreenParser {
    private val clock = Regex("(?:(\\d+):)?(\\d{1,2}):(\\d{2})")
    private val season = Regex("(?:シーズン|Season)\\s*(\\d+)", RegexOption.IGNORE_CASE)
    private val episode = Regex("第\\s*(\\d+)\\s*[^\\d\\s/,，:：]+|(?:Episode|Ep\\.?)\\s*(\\d+)", RegexOption.IGNORE_CASE)
    fun parse(screen: HuluScreenSnapshot): HuluScreenPlayback? {
        if (!screen.playerVisible || screen.series.isBlank() || screen.episodeTitle.isBlank()) return null
        val number = episode.find(screen.episodeDetail)?.groupValues?.drop(1)?.firstOrNull(String::isNotEmpty)?.toIntOrNull()?.takeIf { it in 0..9999 } ?: return null
        val seasonNumber = season.find(screen.episodeDetail)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val position = time(screen.currentTime) ?: return null
        val duration = time(screen.totalTime)?.takeIf { it in 1..86_400_000L && position <= it } ?: return null
        val title = screen.series.trim().let { if (seasonNumber > 1 && EpisodeMatcher.extract(it).season == null) "$it 第${seasonNumber}期" else it }
        return HuluScreenPlayback(title, "第${number}話 ${screen.episodeTitle.trim()}", position, duration)
    }
    private fun time(value: String): Long? {
        val match = clock.matchEntire(value.trim()) ?: return null
        val hours = match.groupValues[1].toLongOrNull() ?: 0
        val minutes = match.groupValues[2].toLong()
        val seconds = match.groupValues[3].toLong()
        if (hours > 24 || seconds >= 60 || hours > 0 && minutes >= 60) return null
        return (hours * 3600 + minutes * 60 + seconds) * 1000
    }
}

class HuluPlaybackRepository(private val now: () -> Long) {
    private val mutablePlayback = MutableStateFlow<Playback?>(null)
    val playback = mutablePlayback.asStateFlow()
    private var lastPosition = -1L
    private var lastSample = 0L
    private var lastAdvanced = 0L
    private var absentSince: Long? = null

    fun observe(screen: HuluScreenPlayback) {
        absentSince = null
        val time = now()
        val previous = mutablePlayback.value
        val changed = previous?.title != screen.title || previous.subtitle != screen.episode
        val moved = !changed && lastPosition != screen.position
        val delta = screen.position - lastPosition
        val seek = moved && (delta < 0 || delta > maxOf(2000L, (time - lastSample).coerceAtLeast(0) + 1000))
        if (changed) lastAdvanced = time
        val playing = when {
            changed -> false
            moved && !seek -> true
            seek -> previous?.playing == true
            else -> previous?.playing == true && time - lastAdvanced < 1800
        }
        if (moved && playing) lastAdvanced = time
        val reanchor = changed || moved || previous?.playing != playing
        val position = when {
            changed || seek -> screen.position
            moved -> alignQuantizedPlaybackTime(screen.position, previous?.positionAt(time)?.takeIf { previous.playing && playing })
            else -> previous?.position ?: screen.position
        }
        lastPosition = screen.position
        lastSample = time
        mutablePlayback.value = Playback(connected = true, title = screen.title, subtitle = screen.episode,
            searchTitle = "${screen.title} ${screen.episode}", mediaId = "hulu:${EpisodeMatcher.normalize(screen.title)}:${screen.episode}",
            position = position, updatedAt = if (reanchor) time else previous?.updatedAt ?: time, speed = 1f, playing = playing, duration = screen.duration)
    }
    fun absent() {
        val time = now()
        val since = absentSince ?: time.also { absentSince = it }
        if (time - since >= 1800) clear()
    }
    fun clear() { mutablePlayback.value = null; lastPosition = -1; lastSample = 0; lastAdvanced = 0; absentSince = null }
}
