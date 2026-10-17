package net.yexe.anicome

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class StoragePolicyTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun newInstallationRequiresLogin() {
        assertFalse(initialSetupReady(true, true, false, false))
        assertTrue(initialSetupReady(true, true, false, true))
    }
    @Test fun logoutAfterSetupDoesNotBlockTheApp() {
        assertTrue(initialSetupReady(true, true, true, false))
        assertFalse(initialSetupReady(false, true, true, false))
        assertFalse(initialSetupReady(true, false, true, false))
    }
    @Test fun unlimitedAndLargeCustomLimits() {
        assertEquals(Long.MAX_VALUE, storageLimitBytes(0))
        assertEquals(5_000_000L * 1048576, storageLimitBytes(5_000_000))
        assertTrue(storageLimitBytes(Long.MAX_VALUE) > 0)
    }
    @Test fun changingTheQuotaAppliesToAnExistingStoreAndDoesNotDeleteData() {
        var limit = Long.MAX_VALUE
        val store = CommentStore(folder.newFolder(), capacity = { limit })
        val episode = Episode("テスト作品", 1)
        val source = Candidate("so1", "テスト作品 第1話")
        val original = listOf(Comment(1000, "元のコメント"))
        store.save(episode, source, original)
        limit = 1
        try { store.save(episode, source, listOf(Comment(1000, "新しいコメント"))); fail("Limit must apply") } catch (_: IllegalStateException) { }
        assertEquals(original, store.read(episode)?.comments)
        limit = Long.MAX_VALUE
        val replacement = listOf(Comment(1000, "新しいコメント"))
        assertEquals(replacement, store.save(episode, source, replacement).comments)
    }
}
