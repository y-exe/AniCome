package net.yexe.anicome

import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.json.JSONObject

fun storedAnimeWork(text: String): AnimeInfo? = runCatching {
    val item = JSONObject(text)
    AnimeInfo(item.getInt("id"), item.getString("title"), item.optString("image"), "", null,
        item.optInt("year").takeIf { it > 0 }, item.optInt("episodes").takeIf { it > 0 }, emptyList(), item.optString("provider", "syoboi"),
        item.optString("niconicoChannel").takeIf(String::isNotBlank))
}.getOrNull()

fun rememberedAnimeWork(prefs: Preferences, series: String): AnimeInfo? {
    val snapshot = SettingsSnapshot(prefs)
    storedAnimeWork(snapshot.getString("selectedWork:${CommentStore.fileKey(series)}", "").orEmpty())?.let { return it }
    val identity = animeWorkIdentity(series)
    val matches = prefs.asMap().entries.filter { it.key.name.startsWith("selectedWork:") }.mapNotNull { (_, value) ->
        storedAnimeWork(value.toString())?.takeIf { work -> animeWorkIdentity(work.title).let {
            it.base == identity.base && it.season == identity.season && it.cour == identity.cour
        } }
    }.distinctBy { Triple(it.niconicoChannel, it.year, it.title) }
    return matches.singleOrNull()
}

class OfficialEpisodeRepository(private val settings: SettingsRepository,
    private val channel: suspend (AnimeInfo) -> Long?, private val byTitle: suspend (String) -> Long?,
    private val videos: suspend (Long) -> List<Candidate>, private val resolve: suspend (AnimeInfo, Int) -> ResolvedEpisodeMetadata?) {
    suspend fun lookupEpisode(episode: Episode): Episode = lookupEpisode(episode, rememberedAnimeWork(settings.data.first(), episode.series))
    private suspend fun lookupEpisode(episode: Episode, work: AnimeInfo?): Episode {
        return if (work != null && animeWorkIdentity(work.title).cour > 1 && episode.number in 1..(work.episodes ?: 0)) {
            try { resolve(work, episode.number)?.episode ?: episode }
            catch (e: CancellationException) { throw e } catch (e: TrafficLimitReached) { throw e } catch (_: Exception) { episode }
        } else episode
    }
    suspend fun candidates(episode: Episode): List<Candidate> {
        val prefs = settings.data.first()
        val snapshot = SettingsSnapshot(prefs)
        val work = rememberedAnimeWork(prefs, episode.series)
        val manualChannel = snapshot.getLong("workChannel:${CommentStore.fileKey(work?.title ?: episode.series)}", 0L).takeIf { it > 0 && it != DANIME_CHANNEL }
        val id = manualChannel ?: work?.let { channel(it) } ?: byTitle(episode.series) ?: return emptyList()
        val lookup = lookupEpisode(episode, work)
        return videos(id).filter { it.channelId == id && it.isDownloadOfficial() && EpisodeMatcher.extract(it.title).episode?.number == lookup.number }
    }
}
