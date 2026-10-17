package net.yexe.anicome

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class SettingsFeaturesTest {
    @Test fun fuzzySearchAcceptsKanaTyposAndMultipleTerms() {
        assertTrue(searchSettingMatches("ぷろきし", listOf("HTTP／SOCKSプロキシ")))
        assertTrue(searchSettingMatches("コメト", listOf("コメント表示")))
        assertTrue(searchSettingMatches("pasword", listOf("password lock")))
        assertTrue(searchSettingMatches("FPS サイズ", listOf("FPS", "文字サイズ")))
        assertFalse(searchSettingMatches("ダウンロード", listOf("パターンロック")))
    }
    @Test fun proxyValidatesThenAtomicallyPersistsConfiguration() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val settings = SettingsRepository(MemoryPreferences(), scope)
        val repository = AdvancedSettingsRepository(settings, scope)
        try {
            try { repository.proxy("http", "https://proxy.example", 8080); fail() } catch (_: IllegalArgumentException) { }
            try { repository.proxy("socks", "127.0.0.1", 65536); fail() } catch (_: IllegalArgumentException) { }
            repository.proxy("socks", "127.0.0.1", 1080)
            val state = withTimeout(3000) { repository.state.first { it.proxyType == "socks" } }
            assertEquals("127.0.0.1", state.proxyHost)
            assertEquals(1080, state.proxyPort)
        } finally { scope.cancel() }
    }
    @Test fun networkClientRefreshesProxyAndTimeoutWithoutRequests() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val settings = SettingsRepository(MemoryPreferences(), scope)
        val api = ApiClient.create(settings)
        val repository = AdvancedSettingsRepository(settings, scope, api)
        try {
            repository.proxy("http", "proxy.example", 8081)
            assertEquals(java.net.Proxy.Type.HTTP, api.httpClient.proxy!!.type())
            assertEquals("proxy.example", (api.httpClient.proxy!!.address() as java.net.InetSocketAddress).hostString)
            repository.proxy("socks", "127.0.0.1", 1080)
            assertEquals(java.net.Proxy.NO_PROXY, api.httpClient.proxy)
            assertTrue(api.httpClient.socketFactory is AuthenticatedSocksFactory)
            settings.store.updateData { it.toMutablePreferences().apply { this[androidx.datastore.preferences.core.intPreferencesKey("networkTimeout")] = 45 } }
            api.prepareNetwork()
            assertEquals(45000, api.httpClient.readTimeoutMillis)
            repository.proxy("direct", "", 8080)
            assertEquals(java.net.Proxy.NO_PROXY, api.httpClient.proxy)
        } finally { scope.cancel() }
    }
    @Test fun notificationShowsProgressPlaybackAndPrivacyChoices() {
        val state = CommentNotificationState(true, FetchProgress("コメントを取得中", 500, 1000), "作品名", true, 1000, true, false)
        val progress = commentNotificationContent(state, AdvancedSettings())
        assertEquals(500, progress.progress)
        assertEquals(1000, progress.maximum)
        assertTrue(progress.text.contains("500 / 1000"))
        val playing = state.copy(busy = false)
        assertEquals("コメント再生中", commentNotificationContent(playing, AdvancedSettings()).title)
        assertFalse(commentNotificationContent(playing, AdvancedSettings(privateNotifications = true)).text.contains("作品名"))
        assertFalse(commentNotificationContent(playing, AdvancedSettings(notificationCount = false)).text.contains("1000"))
        assertEquals("広告中・コメント停止", commentNotificationContent(playing.copy(advertising = true), AdvancedSettings()).title)
    }
    @Test fun updateVersionComparisonUsesNumbers() {
        assertTrue(releaseIsNewer("v0.20.0", "0.19.0"))
        assertTrue(releaseIsNewer("v1.10.0", "1.9.9"))
        assertFalse(releaseIsNewer("v0.19.0", "0.19.0"))
        assertFalse(releaseIsNewer("v0.9.0", "0.19.0"))
    }
}
