package net.yexe.anicome

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreviewCommentCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun comments() = listOf(
        Comment(128000, "前余白", """{"postedAt":"2026-01-01T00:00:00Z","commands":["ue"]}"""),
        Comment(133000, "　■\n ■　", """{"postedAt":"2026-01-02T00:00:00Z","commands":["shita"]}"""),
        Comment(228000, "後余白", """{"postedAt":"2026-01-03T00:00:00Z","commands":[]}"""),
        Comment(229000, "対象外", """{"postedAt":"2026-01-04T00:00:00Z","commands":[]}""")
    )

    @Test fun simultaneousReadersShareDownloadAndSavedCommentsWorkOffline() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val file = temporary.newFolder().resolve("preview.bin")
            val finish = CompletableDeferred<Unit>()
            var calls = 0
            val cache = PreviewCommentCache(file, scope, { progress -> calls++; progress(2, 30000); finish.await(); comments() })
            val first = cache.prepare()
            assertSame(first, cache.prepare())
            finish.complete(Unit); first.await()
            assertEquals(1, calls)
            val repository = CommentPreviewRepository(cache) { file.inputStream() }
            assertEquals(listOf("前余白", "　■\n ■　", "後余白"), repository.read(30000).comments.map { it.body })
            assertEquals("後余白", repository.read(1).comments.single().body)
            val offline = PreviewCommentCache(file, scope, { error("Network must not be used") })
            offline.prepare().await()
            assertEquals(3, CommentPreviewRepository(offline) { file.inputStream() }.read(30000).total)
        } finally { scope.cancel() }
    }

    @Test fun loginStartsPreparationAndFailedDownloadsCanBeRetried() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val file = temporary.newFolder().resolve("preview.bin")
            val linked = MutableStateFlow(false)
            val failed = CompletableDeferred<Unit>()
            var calls = 0
            val cache = PreviewCommentCache(file, scope, {
                calls++
                if (calls == 1) { failed.complete(Unit); error("Offline") }
                comments()
            }, linked)
            assertFalse(file.exists())
            linked.value = true
            withTimeout(5000) { failed.await(); cache.state.first { it.error.isNotBlank() } }
            assertFalse(file.exists()); assertFalse(file.resolveSibling("preview.bin.part").exists())
            cache.prepare().runCatching { await() }
            if (!file.exists()) cache.prepare().await()
            assertTrue(file.isFile)
            assertEquals(2, calls)
        } finally { scope.cancel() }
    }
}
