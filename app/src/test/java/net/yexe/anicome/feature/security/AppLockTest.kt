package net.yexe.anicome

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class AppLockTest {
    @Test fun savesOnlyVerifierAndRequiresCurrentCredentialToRemoveLock() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var elapsed = 1000L
        val store = MemoryPreferences()
        val repository = AppLockRepository(SettingsRepository(store, scope), scope, { elapsed })
        try {
            repository.configure("pin", "123456", false)
            withTimeout(3000) { repository.state.first { it.mode == "pin" } }
            assertFalse(store.data.first().asMap().values.contains("123456"))
            elapsed += 301000
            try { repository.disable(); fail("現在の認証が必要") } catch (_: IllegalStateException) { }
            assertFalse(repository.verify("654321"))
            assertTrue(repository.verify("123456"))
            repository.disable()
            assertEquals("none", SettingsSnapshot(store.data.first()).getString("lockMode", null))
            assertNull(store.data.first()[stringPreferencesKey("lockHash")])
        } finally { scope.cancel() }
    }

    @Test fun locksAfterBackgroundAndHonorsGracePeriod() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var elapsed = 1000L
        val repository = AppLockRepository(SettingsRepository(MemoryPreferences(), scope), scope, { elapsed })
        try {
            repository.configure("password", "test-password", false)
            withTimeout(3000) { repository.state.first { it.mode == "password" } }
            repository.background(); elapsed += 1000; repository.foreground()
            assertTrue(repository.state.value.locked)
            assertTrue(repository.verify("test-password"))
            repository.timeout(15)
            withTimeout(3000) { repository.state.first { it.timeout == 15 } }
            repository.background(); elapsed += 10000; repository.foreground()
            assertFalse(repository.state.value.locked)
            repository.background(); elapsed += 16000; repository.foreground()
            assertTrue(repository.state.value.locked)
        } finally { scope.cancel() }
    }

    @Test fun failedAttemptsAreRateLimitedAcrossRepositoryRestart() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = MemoryPreferences()
        var now = 1000000L
        val settings = SettingsRepository(store, scope)
        val repository = AppLockRepository(settings, scope, { 1000 }, { now })
        try {
            repository.configure("pin", "123456", false)
            repeat(5) { assertFalse(repository.verify("000000")) }
            val restarted = AppLockRepository(settings, scope, { 2000 }, { now })
            assertFalse(restarted.verify("123456"))
            now += 31000
            assertTrue(restarted.verify("123456"))
            assertEquals(0, store.data.first()[intPreferencesKey("lockFailures")])
        } finally { scope.cancel() }
    }

    @Test fun patternsFollowAndroidIntermediateNodeRules() {
        assertEquals(listOf(0, 1, 2), LockCredentials.extendPattern(listOf(0), 2))
        assertEquals(listOf(0, 4, 8), LockCredentials.extendPattern(listOf(0), 8))
        assertEquals(listOf(0, 1, 2), LockCredentials.extendPattern(listOf(0, 1, 2), 1))
        assertFalse(LockCredentials.valid("pattern", "0,1,2"))
        assertTrue(LockCredentials.valid("pattern", "0,1,2,5"))
        assertFalse(LockCredentials.valid("pin", "1234"))
        assertFalse(LockCredentials.valid("password", "short"))
    }
}

class MemoryPreferences : DataStore<Preferences> {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow<Preferences>(emptyPreferences())
    override val data = mutable.asStateFlow()
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock { transform(mutable.value).also { mutable.value = it } }
}
