package net.yexe.anicome

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class SaveSpace(val free: Long = Long.MAX_VALUE, val used: Long = 0L, val limit: Long = Long.MAX_VALUE, val typicalEpisodeBytes: Long = 1_048_576L)
data class SaveEpisodeReview(val episode: Episode, val searchEpisode: Episode, val subtitle: String = "", val candidate: Candidate? = null,
    val issue: String = "", val selected: Boolean = true)
data class SaveReview(val episodes: List<SaveEpisodeReview>, val bytesPerEpisode: Long, val space: SaveSpace, val amount: Int = 1, val maxComments: Int = 0) {
    val selected get() = episodes.filter { it.selected }
    val duplicateVideoIds get() = duplicateReviewVideoIds(selected)
    val estimatedBytes get() = bytesPerEpisode.coerceAtLeast(1L).coerceAtMost(Long.MAX_VALUE / maxOf(1, selected.size)) * selected.size
    val availableBytes get() = minOf((space.free - 5_242_880L).coerceAtLeast(0L), (space.limit - space.used).coerceAtLeast(0L))
    val enoughSpace get() = estimatedBytes <= availableBytes
}
internal const val DuplicateReviewMessage = "同じ公式動画が複数の話に選ばれています。重複する話のチェックを外すか、戻って作品・チャンネルを確認してください。"
internal fun duplicateReviewVideoIds(rows: List<SaveEpisodeReview>): Set<String> = rows.mapNotNull { row ->
    row.candidate?.id?.let(::niconicoVideoId)?.let { it to row.episode.key }
}.groupBy({ it.first }, { it.second }).filterValues { it.distinct().size > 1 }.keys
fun animeWorkSummary(work: AnimeInfo): String {
    val identity = animeWorkIdentity(work.title)
    return listOfNotNull(if (identity.explicitSeason) "第${identity.season}期" else null, if (identity.cour > 1) "第${identity.cour}クール" else null,
        work.year?.toString(), work.episodes?.let { "全${it}話" }).joinToString(" · ")
}
@androidx.compose.runtime.Composable
fun animeWorkSummaryText(work: AnimeInfo) = animeWorkSummary(work).split(" · ").map { uiText(it) }.joinToString(" · ")

class SaveReviewRepository(private val resolve: suspend (AnimeInfo, Int) -> ResolvedEpisodeMetadata? = { _, _ -> null },
    private val candidates: suspend (Episode, String) -> List<Candidate> = { _, _ -> emptyList() },
    private val channelEpisode: suspend (Long, Int) -> Candidate? = { _, _ -> null },
    private val storage: suspend () -> SaveSpace = { SaveSpace() },
    private val officialChannel: suspend (AnimeInfo) -> Long? = { null }) {
    suspend fun prepare(work: AnimeInfo, episodes: List<Episode>, sources: Set<PlaybackSource>, channel: Long?, amount: Int,
        progress: (Int, Int) -> Unit): SaveReview = withContext(Dispatchers.IO) { coroutineScope {
        val automaticChannel = if (channel == null && PlaybackSource.OFFICIAL in sources) officialChannel(work) else null
        val permits = Semaphore(3)
        val count = java.util.concurrent.atomic.AtomicInteger()
        val rows = episodes.map { episode -> async { permits.withPermit {
            ensureActive()
            var issue = ""
            val metadata = if (channel == null) try { resolve(work, episode.number) }
                catch (e: CancellationException) { throw e } catch (limit: TrafficLimitReached) { throw limit } catch (_: Exception) { issue = "各話情報を確認できませんでした"; null } else null
            val search = metadata?.episode ?: episode
            val subtitle = metadata?.title.orEmpty()
            val resolvedChannel = channel ?: automaticChannel?.takeIf { metadata != null || animeWorkIdentity(work.title).cour == 1 }
            val found = if (PlaybackSource.OFFICIAL !in sources) emptyList() else try {
                if (channel != null) listOfNotNull(channelEpisode(channel, episode.number))
                else {
                    val registered = if (resolvedChannel != null) try { listOfNotNull(channelEpisode(resolvedChannel, search.number)) }
                        catch (e: CancellationException) { throw e } catch (e: TrafficLimitReached) { throw e } catch (_: Exception) { emptyList() } else emptyList()
                    if (registered.isNotEmpty()) registered else candidates(search, subtitle)
                }
            } catch (e: CancellationException) { throw e } catch (limit: TrafficLimitReached) { throw limit } catch (_: Exception) { issue = "通信に失敗しました。再確認してください"; emptyList() }
            val accepted = found.filter { it.isDownloadOfficial() && (if (channel != null) it.channelId == channel else (it.channelId == resolvedChannel && resolvedChannel != null && EpisodeMatcher.extract(it.title).episode?.number == search.number) || savedEpisodeMatches(search, it, subtitle)) }
                .sortedByDescending { it.commentCount }
            val candidate = accepted.firstOrNull()
            if (PlaybackSource.OFFICIAL in sources && candidate == null && issue.isBlank()) issue = if (found.isNotEmpty()) "期・話数が一致する公式動画がありません" else "公式動画が見つかりませんでした"
            if (channel == null && metadata == null && animeWorkIdentity(work.title).cour > 1 && issue.isBlank()) issue = "通算話数を確認できませんでした"
            val selectable = episode.number > 0 && (candidate != null || PlaybackSource.OFFICIAL !in sources || sources.size > 1)
            ensureActive()
            progress(count.incrementAndGet(), episodes.size)
            SaveEpisodeReview(episode, search, subtitle, candidate, issue, selectable)
        } } }.awaitAll()
        val space = storage()
        val estimate = space.typicalEpisodeBytes.coerceIn(262_144L, 134_217_728L) * amount.coerceAtLeast(1) * sources.size.coerceAtLeast(1)
        SaveReview(rows, estimate, space, amount.coerceAtLeast(1))
    } }
}

fun canReuseReviewedArchive(saved: SavedComments?, videoId: String?, expected: Episode, subtitle: String): Boolean =
    saved != null && videoId != null && niconicoVideoId(videoId) != null && "OFFICIAL" in saved.info.sources.split(",") &&
        niconicoVideoId(saved.info.sourceId) == niconicoVideoId(videoId) && savedEpisodeMatches(expected, Candidate(saved.info.sourceId, saved.info.sourceTitle), subtitle)
