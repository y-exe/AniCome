package net.yexe.anicome

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URI

val LocalServiceArtwork = staticCompositionLocalOf<Map<StreamingService, String>> { emptyMap() }

fun serviceArtworkUrl(html: String): String? {
    val meta = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html)
        .map { it.value }.firstOrNull { Regex("property=[\"']og:image[\"']", RegexOption.IGNORE_CASE).containsMatchIn(it) } ?: return null
    val value = Regex("content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(meta)?.groupValues?.get(1)?.replace("&amp;", "&") ?: return null
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    return value.takeIf { uri.scheme == "https" && uri.host in setOf("play-lh.googleusercontent.com", "lh3.googleusercontent.com") }
}

class ServiceArtworkRepository(private val settings: SettingsRepository, private val request: suspend (String) -> String) {
    private val mutable = MutableStateFlow<Map<StreamingService, String>>(emptyMap())
    val state = mutable.asStateFlow()
    private val mutex = Mutex()
    suspend fun load() = mutex.withLock {
        val prefs = settings.load()
        val saved = StreamingService.displayOrder.mapNotNull { service ->
            prefs.getString("serviceArtwork:${service.name}", null)?.let { service to it }
        }.toMap()
        mutable.value = saved
        val limit = Semaphore(3)
        supervisorScope {
            StreamingService.displayOrder.filter { it !in saved }.map { service -> async {
                limit.withPermit {
                    try {
                        val url = serviceArtworkUrl(request("https://play.google.com/store/apps/details?id=${service.packageName}&hl=en")) ?: return@withPermit
                        settings.putString("serviceArtwork:${service.name}", url)
                        mutable.update { it + (service to url) }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { }
                }
            } }.awaitAll()
        }
    }
}

class ServiceArtworkViewModel(repository: ServiceArtworkRepository) : ViewModel() {
    val state = repository.state
    init { viewModelScope.launch { repository.load() } }
}
