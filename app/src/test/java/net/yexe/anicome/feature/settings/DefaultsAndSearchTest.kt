package net.yexe.anicome

import androidx.datastore.preferences.core.*
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultsAndSearchTest {
    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }
    @Test fun freshSettingsUseTheRequestedDefaultsButStoredValuesAreKept() {
        val p = mutablePreferencesOf(); val profile = CommentDisplayProfile.read(p)
        assertEquals(14f, profile.size); assertEquals(.75f, profile.opacity); assertEquals(.7f, profile.speed)
        assertEquals(5, profile.amount); assertEquals(60, profile.fps); assertEquals("balanced", profile.renderMode)
        val advanced = AdvancedSettings.from(SettingsSnapshot(p))
        assertEquals(DefaultCommentSources, advanced.sourceTargets)
        assertEquals(1f, advanced.customY); assertEquals(1f, profile.customY)
        assertTrue(advanced.notificationProgress && advanced.notificationCount && advanced.notificationPlayback && advanced.notificationCompletion)
        p[floatPreferencesKey("commentSize")] = 19f; p[floatPreferencesKey("speedScale")] = 1.1f
        p[booleanPreferencesKey("sourceJikkyo")] = true; p[booleanPreferencesKey("notificationCount")] = false
        assertEquals(19f, CommentDisplayProfile.read(p).size); assertEquals(1.1f, CommentDisplayProfile.read(p).speed)
        assertTrue(PlaybackSource.JIKKYO in AdvancedSettings.from(SettingsSnapshot(p)).sourceTargets)
        assertFalse(AdvancedSettings.from(SettingsSnapshot(p)).notificationCount)
    }
    @Test fun profilesStartInTheRequestedPositionsAndKeepIndependentCustomCoordinates() {
        assertEquals("center", defaultServiceProfile(StreamingService.U_NEXT).position)
        assertEquals("center", defaultServiceProfile(StreamingService.DMM_TV).position)
        assertEquals("top", defaultServiceProfile(StreamingService.ABEMA).position)
        assertEquals("top", defaultServiceProfile(StreamingService.TVER).position)
        val custom = defaultServiceProfile(StreamingService.ABEMA).copy(position = "custom", customY = .27f)
        assertEquals(custom, CommentDisplayProfile.parse(custom.json()))
        val p = mutablePreferencesOf(); custom.write(p); assertEquals(custom, CommentDisplayProfile.read(p))
        val malformed = org.json.JSONObject(custom.json()).put("customY", "NaN").toString()
        assertEquals(1f, CommentDisplayProfile.parse(malformed)!!.customY)
    }
    @Test fun selectedSaveSourcesSurviveReopeningAndRecreatingTheScreen() = runTest {
        val settings = SettingsRepository(MemoryPreferences(), backgroundScope)
        val repo = SaveCommentRepository(settings = settings)
        val first = SaveCommentViewModel(repo); runCurrent()
        assertEquals(DefaultCommentSources, first.state.value.sources)
        first.source(PlaybackSource.LIVE_ARCHIVE); runCurrent()
        val wanted = DefaultCommentSources + PlaybackSource.LIVE_ARCHIVE
        assertEquals(wanted, first.state.value.sources)
        first.start(""); runCurrent(); assertEquals(wanted, first.state.value.sources)
        val second = SaveCommentViewModel(SaveCommentRepository(settings = settings)); runCurrent()
        assertEquals(wanted, second.state.value.sources)
        wanted.forEach(second::source); runCurrent(); second.start(""); runCurrent()
        assertTrue(second.state.value.sources.isEmpty()); assertFalse(second.state.value.canSave)
        first.viewModelScope.cancel(); second.viewModelScope.cancel()
    }
    @Test fun customPositionIsAnAtomicSettingAndSurvivesRecreation() = runTest {
        val settings = SettingsRepository(MemoryPreferences(), backgroundScope)
        val advanced = AdvancedSettingsRepository(settings, backgroundScope)
        advanced.customPosition(.23f); runCurrent()
        assertEquals("custom", settings.load().getString("overlayPosition", ""))
        assertEquals(.23f, AdvancedSettings.from(settings.load()).customY)
        assertThrows(IllegalArgumentException::class.java) { advanced.customPosition(Float.NaN) }
        assertFalse(portableKey("proxyPassword")); assertTrue(portableKey("overlayCustomY"))
        validatePortableSetting("overlayCustomY", org.json.JSONObject().put("type", "float").put("value", .23))
    }
    @Test fun settingsSearchAcceptsReadingsKanaTyposAccentsAndKoreanInitials() {
        assertTrue(searchSettingMatches("もじ おおきさ", listOf("文字サイズ")))
        assertTrue(searchSettingMatches("ふとうめい", listOf("不透明度")))
        assertTrue(searchSettingMatches("ふろーてぃぐ", listOf("フローティング表示")))
        assertTrue(searchSettingMatches("ネットワーク", listOf("ネットワーク")))
        assertTrue(searchSettingMatches("opacite", listOf("Opacité")))
        assertTrue(searchSettingMatches("notifcation", listOf("Notifications")))
        assertTrue(searchSettingMatches("ㄴㅌㅇㅋ", listOf("네트워크")))
        assertTrue(searchSettingMatches("wangluo", listOf("ネットワーク", "网络")))
        assertFalse(searchSettingMatches("qwertyzxcv", listOf("文字サイズ", "Notifications")))
    }
}
