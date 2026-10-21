@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package net.yexe.anicome

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject

val PreviewCommentCounts = listOf(1000, 2500, 5000, 10000, 20000, 30000)
const val PreviewCommentStart = 133_000L
data class PreviewLoadState(val loading: Boolean = false, val current: Int = 0, val target: Int = 0, val error: String = "", val available: Int = 0)
data class PreviewPlayback(val position: Long = 0, val duration: Long = 90_000, val playing: Boolean = false, val error: Int = 0, val speed: Float = 1f)
data class PreviewRenderFrame(val comments: List<Comment>, val position: Long, val playing: Boolean, val speed: Float = 1f, val settings: PreviewDisplaySettings? = null)

data class PreviewDisplaySettings(val profile: CommentDisplayProfile = CommentDisplayProfile(), val ngWords: String = "")
fun previewDisplaySettings(p: androidx.datastore.preferences.core.Preferences) = PreviewDisplaySettings(CommentDisplayProfile.read(p).copy(position = "center"), SettingsSnapshot(p).getString("ngWords", "").orEmpty())

fun previewCommentSelection(comments: List<Comment>, duration: Long, count: Int): List<Comment> {
    if (duration <= 0) return emptyList()
    val eligible = comments.filter { it.at >= PreviewCommentStart - 5000 && it.at <= PreviewCommentStart + duration.coerceIn(0, 90_000) + 5000 }
    val size = count.coerceIn(0, eligible.size)
    if (size == eligible.size) return eligible.sortedBy { it.at }
    if (size == 0) return emptyList()
    return eligible.sortedByDescending { comment -> runCatching { java.time.OffsetDateTime.parse(JSONObject(comment.raw ?: "{}").optString("postedAt")).toInstant() }.getOrDefault(java.time.Instant.MIN) }.take(size).sortedBy { it.at }
}

data class PreviewSample(val comments: List<Comment>, val total: Int)

class CommentPreviewRepository(private val cache: PreviewCommentCache? = null, private val open: () -> java.io.InputStream) {
    val preparation get() = cache?.state
    suspend fun read(count: Int): PreviewSample = withContext(Dispatchers.IO) {
        cache?.prepare()?.await()
        java.util.zip.GZIPInputStream(open()).bufferedReader(Charsets.UTF_8).use { reader ->
            val header = JSONObject(reader.readLine())
            require(header.getInt("version") == 3 && header.getString("order") == "postedAtDescending" && header.getString("videoId") == "so23335421")
            val total = header.getInt("count"); require(total > 0)
            val wanted = count.coerceIn(0, total)
            if (wanted == 0) return@use PreviewSample(emptyList(), total)
            val result = ArrayList<Comment>(wanted)
            repeat(wanted) { index ->
                if (index % 256 == 0) ensureActive()
                val text = reader.readLine() ?: error("試し表示データを読み込めません")
                run {
                    val row = JSONObject(text)
                    val at = row.getLong("at"); require(at in PreviewCommentStart - 5000..PreviewCommentStart + 95_000)
                    result += Comment(at, row.getString("body"), row.getJSONObject("raw").toString(), row.optString("fork", "main"))
                }
            }
            PreviewSample(result.sortedBy { it.at }, total)
        }
    }
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
class CommentPreviewViewModel(private val repository: CommentPreviewRepository, settingsRepository: SettingsRepository) : ViewModel() {
    val settings = settingsRepository.data.map(::previewDisplaySettings).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val mutableLoad = MutableStateFlow(PreviewLoadState(loading = true))
    val load = mutableLoad.asStateFlow()
    private val mutableComments = MutableStateFlow<List<Comment>>(emptyList())
    val selected = mutableComments.asStateFlow()
    val playback = MutableStateFlow(PreviewPlayback())
    val limit = MutableStateFlow(5000)
    private val revision = MutableStateFlow(0)
    init {
        repository.preparation?.let { source -> viewModelScope.launch { source.collect { progress ->
            if (progress.loading) mutableLoad.update { it.copy(loading = true, current = progress.current, target = progress.target) }
        } } }
        viewModelScope.launch {
            combine(limit, revision) { count, _ -> count }.collectLatest { count ->
                mutableLoad.update { it.copy(loading = true, error = "") }
                try {
                    val sample = repository.read(count)
                    mutableComments.value = sample.comments
                    mutableLoad.value = PreviewLoadState(available = sample.total, current = sample.comments.size, target = sample.total)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { mutableLoad.update { it.copy(loading = false, error = "試し表示データを読み込めません") } }
            }
        }
    }
    fun fetch() { revision.update { it + 1 } }
    fun count(value: Int) { require(value in PreviewCommentCounts); limit.value = value }
}
