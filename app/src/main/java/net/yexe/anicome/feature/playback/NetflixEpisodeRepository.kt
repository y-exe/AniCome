package net.yexe.anicome

import java.text.Normalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NetflixEpisodeIdentity(val title: String, val season: Int, val number: Int)
data class NetflixEpisodeMatch(val identity: NetflixEpisodeIdentity, val episode: Episode, val source: Candidate)

object NetflixEpisodeMetadata {
    fun subtitle(match: NetflixEpisodeMatch) = "第${match.episode.number}話 ${match.identity.title}"
    fun clean(value: String) = Normalizer.normalize(value.filterNot { Character.getType(it) == Character.FORMAT.toInt() }, Normalizer.Form.NFKC).trim()
    private val episode = Regex("^(?:シーズン|S(?:eason)?)\\s*(\\d+)\\s*[-:：,]\\s*(?:エピソード|E(?:pisode)?)?\\s*(\\d+)\\s*[-:：]\\s*(.+)$", RegexOption.IGNORE_CASE)
    private fun titleKey(value: String) = clean(value).filter(Char::isLetterOrDigit).lowercase(java.util.Locale.ROOT)

    fun queries(title: String): List<String> {
        val full = clean(title)
        val parts = full.split(Regex("[/|\\n]|[」』]\\s*[「『]"))
            .map { it.trim().trim('「', '」', '『', '』', '"').trimEnd('.', '…', '。', ' ') }
            .filter { titleKey(it).isNotEmpty() }
        return (listOf(full) + parts).distinct().take(4)
    }

    fun identity(fields: List<Pair<String, String?>>): NetflixEpisodeIdentity? {
        val subtitle = fields.firstOrNull { it.first == "DISPLAY_SUBTITLE" }?.second?.let(::clean) ?: return null
        val match = episode.matchEntire(subtitle) ?: return null
        val title = clean(match.groupValues[3])
        if (titleKey(title).isEmpty() || title.matches(Regex("(?:Episode|Ep\\.?|第)?\\s*[0-9一二三四五六七八九十]+\\s*話?", RegexOption.IGNORE_CASE))) return null
        return NetflixEpisodeIdentity(title, match.groupValues[1].toIntOrNull() ?: return null, match.groupValues[2].toIntOrNull() ?: return null)
            .takeIf { it.season > 0 && it.number > 0 }
    }

    fun choose(identity: NetflixEpisodeIdentity, candidates: List<Candidate>): NetflixEpisodeMatch? {
        val needle = titleKey(identity.title)
        val matches = candidates.distinctBy { it.id }.mapNotNull { candidate ->
            if ((!candidate.isNiconicoChannel() && !candidate.id.matches(Regex("so[0-9]+"))) || candidate.isCommentOnly() || ChapterTitle.matches(candidate.title)) return@mapNotNull null
            val extracted = EpisodeMatcher.extract(clean(candidate.title))
            if ((extracted.season?.number ?: extracted.seasonAlt?.number ?: 1) != identity.season) return@mapNotNull null
            val parsed = EpisodeMatcher.parse(clean(candidate.title)) ?: return@mapNotNull null
            val subtitle = extracted.subtitleStripped ?: extracted.subtitle ?: return@mapNotNull null
            if (titleKey(subtitle) != needle || parsed.number != identity.number) return@mapNotNull null
            NetflixEpisodeMatch(identity, parsed, candidate)
        }
        if (matches.map { it.episode.copy(series = EpisodeMatcher.stripTitle(it.episode.series)).key }.distinct().size != 1) return null
        return matches.sortedWith(compareBy<NetflixEpisodeMatch> { !it.source.isDownloadOfficial() }.thenByDescending { it.source.commentCount }).firstOrNull()
    }
}

class NetflixEpisodeRepository(
    private val scope: CoroutineScope,
    private val search: suspend (String) -> List<Candidate>,
    private val archives: suspend () -> List<Candidate> = { emptyList() },
    private val now: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private val mutableMatch = MutableStateFlow<NetflixEpisodeMatch?>(null)
    val match = mutableMatch.asStateFlow()
    private val known = linkedMapOf<NetflixEpisodeIdentity, NetflixEpisodeMatch>()
    private var current: NetflixEpisodeIdentity? = null
    private var job: Job? = null
    private var triedAt = Long.MIN_VALUE

    fun select(identity: NetflixEpisodeIdentity?) {
        if (current == identity && (job?.isActive == true || mutableMatch.value != null || triedAt != Long.MIN_VALUE && now() - triedAt < 60000)) return
        job?.cancel()
        current = identity
        mutableMatch.value = identity?.let(known::get)
        if (identity == null || mutableMatch.value != null) return
        triedAt = now()
        job = scope.launch {
            try {
                val local = archives()
                val queries = NetflixEpisodeMetadata.queries(identity.title)
                val initial = NetflixEpisodeMetadata.choose(identity, local)
                val primary = if (initial == null) search(queries.first()) else emptyList()
                val direct = initial ?: NetflixEpisodeMetadata.choose(identity, local + primary)
                val result = direct ?: coroutineScope {
                    val fallback = queries.drop(1).map { query -> async { search(query) } }.awaitAll().flatten()
                    NetflixEpisodeMetadata.choose(identity, local + primary + fallback)
                }
                if (current == identity && result != null) {
                    known[identity] = result
                    while (known.size > 64) known.remove(known.keys.first())
                    mutableMatch.value = result
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {}
        }
    }
}
