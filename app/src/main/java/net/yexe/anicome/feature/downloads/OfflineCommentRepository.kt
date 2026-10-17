package net.yexe.anicome

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

val OfflineSources = PlaybackSource.entries.toSet()

class OfflineCommentRepository(private val official: CommentRepository, private val playback: PlaybackSourceRepository,
    private val sourceMetadata: suspend (String) -> Candidate = Comments::sourceMetadata) {
    private val permits = Semaphore(2)
    suspend fun save(episode: Episode, searchEpisode: Episode = episode, amount: Int = 1, selected: Set<PlaybackSource> = OfflineSources,
        candidate: Candidate? = null, channel: Long? = null, subtitle: String = "", refresh: Boolean = false, onFetched: (Set<String>) -> Unit = {}, progress: (String, Int, Int) -> Unit = { _, _, _ -> }): SavedComments = withContext(Dispatchers.IO) { permits.withPermit {
        require(selected.isNotEmpty())
        if (!refresh) official.promoteCached(episode, selected)
        var officialError: Exception? = null
        if (PlaybackSource.OFFICIAL in selected) {
            try {
                val previous = official.store.read(episode)
                val saved = if (refresh && previous?.info?.sources?.split(",")?.contains("OFFICIAL") == true) official.refresh(previous.info)
                    else official.obtain(episode, source = candidate, amount = amount, episodeTitle = subtitle, strictMatch = channel == null,
                        requiredChannelId = channel, sourceEpisode = searchEpisode, forceSearch = !official.store.isAutomaticCache, progress = progress)
                if (!saved.fromArchive && saved.info.amount >= amount) onFetched(setOf(saved.info.sourceId))
                if (saved.durationMs <= 0) {
                    try { official.store.updateSource(episode, saved.info.sourceId, sourceMetadata(saved.info.sourceId)) }
                    catch (e: CancellationException) { throw e } catch (_: Exception) { }
                }
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { officialError = e }
        }
        try {
            val obtained = playback.obtain(episode, 0, "", amount, {}, progress, offlineSave = true, requestedSources = selected, searchEpisode = searchEpisode, skipOfficial = true, refresh = refresh)
            onFetched(obtained.slots.filter { !it.fromArchive && (it.source in setOf(PlaybackSource.JIKKYO, PlaybackSource.LIVE_ARCHIVE) || it.amount >= amount) }.map { it.id }.toSet())
        } catch (e: CancellationException) { throw e }
        catch (e: MatchUnavailable) { throw officialError ?: e }
        if (officialError is TrafficLimitReached) throw officialError
        checkNotNull(official.store.read(episode))
    } }
}
