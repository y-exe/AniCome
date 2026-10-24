package net.yexe.anicome

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel

class ArtworkViewModel(private val thumbnails: VideoThumbnailRepository? = null) : ViewModel() {
    private val values = MutableStateFlow<Map<String, AnimeInfo?>>(emptyMap())
    val state = values.asStateFlow()
    private val requested = mutableSetOf<String>()
    private val videoValues = MutableStateFlow<Map<String, String?>>(emptyMap())
    val videos = videoValues.asStateFlow()
    private val requestedVideos = mutableSetOf<String>()
    fun loadVideo(id: String, refresh: Boolean) {
        if (thumbnails == null || !requestedVideos.add("$id:$refresh")) return
        viewModelScope.launch {
            try { val url = thumbnails.thumbnail(id, refresh); videoValues.update { it + (id to url) } }
            catch (e: CancellationException) { requestedVideos.remove("$id:$refresh"); throw e }
            catch (_: Exception) { requestedVideos.remove("$id:$refresh") }
        }
    }
    fun load(series: String, source: String) {
        val key = "$series\u001f$source"
        if (!requested.add(key)) return
        viewModelScope.launch {
            try {
                val artwork = AnimeCatalog.artwork(series, source)
                values.update { it + (key to artwork) }
                if (artwork == null) requested.remove(key)
            } catch (cancelled: CancellationException) {
                requested.remove(key)
                throw cancelled
            } catch (_: Exception) {
                requested.remove(key)
            }
        }
    }
}
val LocalVideoArtwork = staticCompositionLocalOf<Map<String, String?>> { emptyMap() }
val LocalVideoArtworkLoad = staticCompositionLocalOf<(String, Boolean) -> Unit> { { _, _ -> } }
val LocalArtwork = staticCompositionLocalOf<Map<String, AnimeInfo?>> { emptyMap() }
val LocalArtworkLoad = staticCompositionLocalOf<(String, String) -> Unit> { { _, _ -> } }
@Composable fun ArtworkProvider(content: @Composable () -> Unit) {
    val model: ArtworkViewModel = koinViewModel()
    val values by model.state.collectAsStateWithLifecycle()
    val videos by model.videos.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalArtwork provides values, LocalArtworkLoad provides model::load,
        LocalVideoArtwork provides videos, LocalVideoArtworkLoad provides model::loadVideo, content = content)
}
@Composable fun rememberArtwork(series: String, source: String = ""): AnimeInfo? {
    val load = LocalArtworkLoad.current
    LaunchedEffect(series, source) { load(series, source) }
    return LocalArtwork.current["$series\u001f$source"]
}
data class AnimeSearchState(val results: List<AnimeInfo> = emptyList(), val loading: Boolean = false, val completed: Boolean = false, val failure: Boolean = false)
class AnimeSearchViewModel(private val search: suspend (String) -> List<AnimeInfo> = AnimeCatalog::search) : ViewModel() {
    private val mutable = MutableStateFlow(AnimeSearchState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    fun search(value: String, selected: String?) {
        job?.cancel(); mutable.value = AnimeSearchState()
        if (value.isBlank() || selected == value) return
        job = viewModelScope.launch {
            mutable.value = AnimeSearchState(loading = true)
            delay(350)
            try { mutable.value = AnimeSearchState(results = search(value), completed = true) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = AnimeSearchState(failure = true) }
        }
    }
    override fun onCleared() { job?.cancel() }
}
