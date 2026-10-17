package net.yexe.anicome

import androidx.room.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@Entity(tableName = "comment_history", indices = [Index("time"), Index(value = ["series", "episode"])])
data class CommentHistoryEntry(@PrimaryKey(autoGenerate = true) val id: Long = 0L, val time: Long, val kind: String,
    val series: String = "", val episode: Int = -1, val query: String = "", val sourceId: String = "", val sourceTitle: String = "",
    val count: Int = 0, val previousId: String = "", val previousCount: Int = 0, val sources: String = "", val storage: String = "manual") {
    @get:Ignore val isSearch get() = kind.endsWith("SEARCH")
    @get:Ignore val target get() = series.takeIf(String::isNotBlank)?.let { title -> episode.takeIf { it in 0..9999 }?.let { Episode(title, it) } }
}
@Dao interface CommentHistoryDao {
    @Query("SELECT * FROM comment_history ORDER BY id DESC LIMIT 500") fun observe(): Flow<List<CommentHistoryEntry>>
    @Query("SELECT * FROM comment_history ORDER BY id DESC LIMIT 1") fun latest(): CommentHistoryEntry?
    @Insert fun insert(entry: CommentHistoryEntry)
    @Query("DELETE FROM comment_history WHERE id NOT IN (SELECT id FROM comment_history ORDER BY id DESC LIMIT 500)") fun prune()
    @Query("DELETE FROM comment_history WHERE id = :id") fun delete(id: Long)
    @Query("DELETE FROM comment_history") fun clear()
    @Transaction fun append(entry: CommentHistoryEntry) {
        val latest = latest()
        if (suppressHistoryDuplicate(latest, entry)) return
        insert(entry); prune()
    }
}
fun suppressHistoryDuplicate(latest: CommentHistoryEntry?, entry: CommentHistoryEntry): Boolean {
    if (latest == null || latest.kind != entry.kind || entry.time - latest.time !in 0..60_000L) return false
    if (latest.series != entry.series || latest.episode != entry.episode || latest.count != entry.count) return false
    return if (entry.isSearch) latest.query == entry.query else entry.kind == "USE" && latest.sourceId == entry.sourceId && latest.sources == entry.sources
}
interface CommentHistoryIndex {
    fun observe(): Flow<List<CommentHistoryEntry>>
    fun append(entry: CommentHistoryEntry)
    fun delete(id: Long)
    fun clear()
}
class RoomCommentHistoryIndex(private val dao: CommentHistoryDao) : CommentHistoryIndex {
    override fun observe() = dao.observe()
    override fun append(entry: CommentHistoryEntry) = dao.append(entry)
    override fun delete(id: Long) = dao.delete(id)
    override fun clear() = dao.clear()
}
fun historyQuery(value: String): String {
    val text = value.filterNot(Char::isISOControl).trim().take(300)
    return if (text.startsWith("https://", true) || text.startsWith("http://", true)) runCatching {
        val url = java.net.URI(text)
        "${url.scheme.lowercase()}://${requireNotNull(url.host)}${if (url.port >= 0) ":${url.port}" else ""}${url.rawPath.orEmpty()}"
    }.getOrDefault("") else text
}
fun savedHistory(before: SavedEpisode?, after: SavedEpisode, storage: String, now: Long): CommentHistoryEntry = CommentHistoryEntry(
    time = now, kind = if (before == null) "SAVE" else if (before.sourceId != after.sourceId) "REPLACE" else "UPDATE", series = after.episode.series,
    episode = after.episode.number, sourceId = after.sourceId, sourceTitle = after.sourceTitle, count = after.count,
    previousId = before?.sourceId.orEmpty(), previousCount = before?.count ?: 0, sources = after.sources, storage = storage)
suspend fun <T> trackedSearch(query: String, episode: Episode? = null, record: suspend (String, Int, Episode?) -> Unit, request: suspend () -> List<T>): List<T> {
    val result = try { request() } catch (e: CancellationException) { throw e } catch (e: Exception) { record(query, -1, episode); throw e }
    record(query, result.size, episode)
    return result
}
fun archivedUseHistory(episode: Episode, comments: PlaybackComments): CommentHistoryEntry? {
    if (comments.slots.isEmpty() || comments.slots.any { !it.fromArchive }) return null
    val primary = comments.slots.firstOrNull { it.source == PlaybackSource.OFFICIAL } ?: comments.slots.first()
    return CommentHistoryEntry(time = 0L, kind = "USE", series = episode.series, episode = episode.number, sourceId = primary.id, sourceTitle = primary.title,
        count = comments.comments.size, sources = comments.slots.map { it.source.name }.distinct().joinToString(","), storage = "archive")
}
class CommentHistoryRepository(private val index: CommentHistoryIndex, scope: CoroutineScope, private val clock: () -> Long = System::currentTimeMillis) {
    private val failure = MutableStateFlow(false)
    val failed = failure.asStateFlow()
    val entries = index.observe().catch { if (it is CancellationException) throw it; failure.value = true; emit(emptyList()) }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    private fun record(block: () -> Unit) { try { block(); failure.value = false } catch (e: CancellationException) { throw e } catch (_: Exception) { failure.value = true } }
    fun saved(before: SavedEpisode?, after: SavedEpisode, storage: String) = record { index.append(savedHistory(before, after, storage, clock())) }
    suspend fun search(kind: String, query: String, count: Int, episode: Episode? = null) = withContext(Dispatchers.IO) {
        val clean = historyQuery(query)
        if (clean.isNotBlank()) record { index.append(CommentHistoryEntry(time = clock(), kind = kind, series = episode?.series.orEmpty(), episode = episode?.number ?: -1, query = clean, count = count)) }
    }
    suspend fun used(entry: CommentHistoryEntry) = withContext(Dispatchers.IO) { record { index.append(entry.copy(time = clock())) } }
    suspend fun used(episode: Episode, comments: PlaybackComments) { archivedUseHistory(episode, comments)?.let { used(it) } }
    suspend fun delete(id: Long) = withContext(Dispatchers.IO) { record { index.delete(id) } }
    suspend fun clear() = withContext(Dispatchers.IO) { record { index.clear() } }
}
class CommentHistoryViewModel(private val repository: CommentHistoryRepository) : ViewModel() {
    val entries = repository.entries
    val failed = repository.failed
    fun delete(id: Long) { viewModelScope.launch { repository.delete(id) } }
    fun clear() { viewModelScope.launch { repository.clear() } }
}
