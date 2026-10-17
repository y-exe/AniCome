package net.yexe.anicome

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPOutputStream

class PreviewCommentCache(
    private val file: File,
    private val scope: CoroutineScope,
    private val fetch: suspend ((Int, Int) -> Unit) -> List<Comment>,
    linked: StateFlow<Boolean>? = null
) {
    private val mutable = MutableStateFlow(PreviewLoadState())
    val state = mutable.asStateFlow()
    private var job: Deferred<Unit>? = null

    init {
        if (linked != null) scope.launch { linked.filter { it }.collect { prepare() } }
    }

    @Synchronized fun prepare(): Deferred<Unit> {
        job?.takeIf { it.isActive }?.let { return it }
        if (file.isFile) return CompletableDeferred(Unit)
        return scope.async(Dispatchers.IO) {
            mutable.value = PreviewLoadState(loading = true, target = 30_000)
            val pending = File(file.parentFile, file.name + ".part")
            try {
                val comments = previewCommentSelection(fetch { current, target ->
                    mutable.value = PreviewLoadState(loading = true, current = current, target = target)
                }, 90_000, 30_000).sortedByDescending {
                    runCatching { java.time.OffsetDateTime.parse(JSONObject(it.raw ?: "{}").getString("postedAt")).toInstant() }.getOrDefault(java.time.Instant.MIN)
                }
                require(comments.isNotEmpty())
                file.parentFile?.mkdirs()
                GZIPOutputStream(pending.outputStream()).bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.appendLine(JSONObject().put("version", 3).put("order", "postedAtDescending").put("videoId", "so23335421").put("count", comments.size).toString())
                    comments.forEach { comment ->
                        ensureActive()
                        val raw = JSONObject(comment.raw ?: "{}")
                        require(raw.has("postedAt"))
                        writer.appendLine(JSONObject().put("at", comment.at).put("body", comment.body).put("raw", raw).put("fork", comment.fork).toString())
                    }
                }
                Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                mutable.value = PreviewLoadState(available = comments.size, current = comments.size, target = comments.size)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutable.value = PreviewLoadState(error = "試し表示データを読み込めません")
                throw error
            } finally { pending.delete() }
        }.also { job = it }
    }
}
