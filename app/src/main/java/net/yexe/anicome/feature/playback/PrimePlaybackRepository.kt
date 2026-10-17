package net.yexe.anicome

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PrimeScreenNode(val text: String = "", val description: String = "", val id: String = "", val visible: Boolean = true)
data class PrimeScreenPlayback(val title: String, val episode: String, val position: Long? = null, val duration: Long = 0, val speed: Float? = null)

object PrimeScreenParser {
    private val episodePattern = Regex("(?:シーズン|Season)\\s*(\\d+)\\s*[,：:・-]?\\s*(?:エピソード|Episode|Ep\\.?)\\s*(\\d+)", RegexOption.IGNORE_CASE)
    private val explicitEpisode = Regex("第\\s*(\\d+)\\s*話")
    private val clock = Regex("(?:(\\d+):)?(\\d{1,2}):(\\d{2})")
    private val japaneseTime = Regex("(?:(\\d+)時間)?\\s*(?:(\\d+)分)?[,、\\s]*(\\d+)秒")
    private val englishTime = Regex("(?:(\\d+)\\s*hours?[,\\s]*)?(?:(\\d+)\\s*minutes?[,\\s]*)?(\\d+)\\s*seconds?", RegexOption.IGNORE_CASE)

    fun parse(nodes: List<PrimeScreenNode>): PrimeScreenPlayback? {
        if (nodes.none { it.description in listOf("ビデオプレーヤー", "Video player") }) return null
        val episodeIndex = nodes.indexOfFirst { episodePattern.containsMatchIn(it.text) }
        if (episodeIndex < 1) return null
        val rawEpisode = nodes[episodeIndex].text.trim()
        val match = episodePattern.find(rawEpisode) ?: return null
        val title = nodes.take(episodeIndex).lastOrNull { it.text.isNotBlank() && it.description.isBlank() && !it.text.contains("イントロをスキップ") }?.text?.trim() ?: return null
        val season = match.groupValues[1].toIntOrNull() ?: return null
        val tail = rawEpisode.substring(match.range.last + 1).trim()
        val episode = if (explicitEpisode.containsMatchIn(tail)) tail else "第${match.groupValues[2]}話${if (tail.isBlank()) "" else " $tail"}"
        val progress = nodes.firstOrNull { it.visible && (it.description.startsWith("プレーヤーの進捗状況バー") || it.description.startsWith("Player progress", true)) }?.description
        val times = progress?.let { text ->
            japaneseTime.findAll(text).map(::unitTime).toList().ifEmpty {
                englishTime.findAll(text).map(::unitTime).toList().ifEmpty { clock.findAll(text).map(::clockTime).toList() }
            }
        }.orEmpty()
        val speed = nodes.firstNotNullOfOrNull { node ->
            if (!node.visible || !node.id.endsWith("topBar_speedButton")) null
            else Regex("([0-9]+(?:[.,][0-9]+)?)\\s*[x×]", RegexOption.IGNORE_CASE).find(node.description)?.groupValues?.get(1)?.replace(',', '.')?.toFloatOrNull()?.takeIf { it in .25f..4f }
        }
        return PrimeScreenPlayback(if (season > 1 && EpisodeMatcher.extract(title).season == null) "$title 第${season}期" else title, episode, times.firstOrNull(), if (times.size == 2) times.sum() else 0, speed)
    }

    private fun unitTime(match: MatchResult): Long = ((match.groupValues[1].toLongOrNull() ?: 0) * 3600 + (match.groupValues[2].toLongOrNull() ?: 0) * 60 + match.groupValues[3].toLong()) * 1000
    private fun clockTime(match: MatchResult): Long = unitTime(match)
}

class PrimePlaybackRepository(private val now: () -> Long) {
    private val mutablePlayback = MutableStateFlow<Playback?>(null)
    val playback = mutablePlayback.asStateFlow()
    private var lastPosition = -1L
    private var lastAdvanced = 0L
    private var absentSince: Long? = null

    fun observe(screen: PrimeScreenPlayback) {
        absentSince = null
        val time = now()
        val previous = mutablePlayback.value
        val changed = previous?.title != screen.title || previous?.subtitle != screen.episode
        if (changed) { lastPosition = -1; lastAdvanced = time }
        var playing = !changed && previous?.playing == true
        if (screen.position != null) {
            if (lastPosition >= 0 && screen.position != lastPosition) {
                playing = true
                lastAdvanced = time
            } else if (time - lastAdvanced >= 1800) playing = false
        }
        val running = !changed && previous?.playing == true && playing
        val moved = screen.position != null && screen.position != lastPosition
        val speed = screen.speed ?: previous?.speed?.takeIf { !changed } ?: 1f
        val reanchor = changed || moved || previous?.playing != playing || previous?.speed != speed
        val position = when {
            changed -> screen.position
            screen.position != null && (!running || moved) -> alignQuantizedPlaybackTime(screen.position, previous?.positionAt(time)?.takeIf { running })
            reanchor -> previous?.positionAt(time)
            else -> previous?.position
        }
        if (screen.position != null) lastPosition = screen.position
        mutablePlayback.value = Playback(
            connected = true, title = screen.title, subtitle = screen.episode,
            searchTitle = "${screen.title} ${screen.episode}", mediaId = "prime:${EpisodeMatcher.normalize(screen.title)}:${screen.episode}",
            position = position ?: -1, updatedAt = if (reanchor) time else previous?.updatedAt ?: time,
            speed = speed, playing = playing,
            duration = screen.duration.takeIf { it > 0 } ?: previous?.duration?.takeIf { !changed } ?: 0
        )
    }

    fun absent() {
        val time = now()
        val since = absentSince ?: time.also { absentSince = it }
        val previous = mutablePlayback.value ?: return
        if (time - since >= 2000 && previous.playing) {
            mutablePlayback.value = previous.copy(position = previous.positionAt(time) ?: -1, updatedAt = time, playing = false)
            lastPosition = -1
        }
    }

    fun resetClock() {
        lastPosition = -1
        lastAdvanced = now()
        absentSince = null
        mutablePlayback.value = mutablePlayback.value?.copy(position = -1, duration = 0, updatedAt = lastAdvanced, playing = false)
    }

    fun clear() { mutablePlayback.value = null; lastPosition = -1; absentSince = null }
}
