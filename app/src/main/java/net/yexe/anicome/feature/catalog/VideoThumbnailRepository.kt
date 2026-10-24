package net.yexe.anicome

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class VideoThumbnailRepository(private val settings: SettingsRepository? = null,
    private val metadata: suspend (String, Boolean) -> Candidate = { id, refresh -> if (refresh) Comments.refreshSourceMetadata(id) else Comments.sourceMetadata(id) }) {
    private val permits = kotlinx.coroutines.sync.Semaphore(3)
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    suspend fun thumbnail(input: String, refresh: Boolean = false): String? = withContext(Dispatchers.IO) {
        val id = niconicoVideoId(input) ?: return@withContext null
        locks.getOrPut(id) { Mutex() }.withLock {
            val key = "videoThumbnail:$id"
            if (!refresh) settings?.load()?.getString(key, null)?.takeIf(String::isNotBlank)?.let { return@withLock it }
            val source = permits.withPermit { metadata(id, refresh) }
            source.thumbnailUrl.takeIf(String::isNotBlank)?.also { url ->
                settings?.putString(key, url)
                settings?.putString("videoThumbnail:${source.id}", url)
            }
        }
    }
}
