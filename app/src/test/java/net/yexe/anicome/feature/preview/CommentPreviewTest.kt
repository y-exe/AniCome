package net.yexe.anicome

import kotlinx.coroutines.test.*
import kotlinx.coroutines.*
import androidx.lifecycle.viewModelScope
import org.junit.Test
import org.junit.Assert.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class CommentPreviewTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun sampleFile(): java.io.File = temporary.newFile().also { file ->
        java.util.zip.GZIPOutputStream(file.outputStream()).bufferedWriter().use { writer ->
            writer.appendLine(org.json.JSONObject().put("version", 3).put("order", "postedAtDescending").put("videoId", "so23335421").put("count", 30000).toString())
            repeat(30000) { i ->
                val raw = org.json.JSONObject().put("postedAt", java.time.Instant.parse("2026-01-01T00:00:00Z").minusSeconds(i.toLong()).toString()).put("commands", org.json.JSONArray().put(if (i % 10 == 0) "ue" else "naka"))
                writer.appendLine(org.json.JSONObject().put("at", 128000 + i % 1000 * 100).put("body", "sample-$i").put("raw", raw).toString())
            }
        }
    }

    @Test fun previewIncludesOnlyTheOpeningAndBothFiveSecondMargins() {
        val comments = listOf(Comment(127_999, "外"), Comment(128_000, "前余白"), Comment(133_000, "最初"), Comment(134_000, "　■\n ■　"), Comment(223_000, "終わり"), Comment(228_000, "後余白"), Comment(228_001, "外"))
        val all = previewCommentSelection(comments, 90_000, 50_000)
        assertEquals(5, all.size); assertEquals(128_000L, all.first().at); assertEquals(228_000L, all.last().at)
        assertEquals("　■\n ■　", all[2].body)
        assertEquals(2, previewCommentSelection(comments, 90_000, 2).size)
        assertTrue(previewCommentSelection(comments, 90_000, 0).isEmpty()); assertTrue(previewCommentSelection(comments, 0, 5).isEmpty())
    }
    @Test fun cachedPreviewPreservesPlaybackAndCommands() = runTest {
        val file = sampleFile()
        val repository = CommentPreviewRepository { file.inputStream() }
        val original = Session.comments.value; val slots = Session.commentSlots.value; val playback = Session.playback.value
        val sample = repository.read(5000); val comments = sample.comments
        assertEquals(30000, sample.total); assertEquals(minOf(sample.total, 5000), comments.size); assertTrue(comments.all { it.at in 128_000..228_000 && it.raw != null })
        assertEquals(comments.sortedBy { it.at }, comments)
        assertSame(original, Session.comments.value); assertSame(slots, Session.commentSlots.value); assertEquals(playback, Session.playback.value)
        assertEquals(comments, repository.read(5000).comments); assertTrue(repository.read(0).comments.isEmpty())
    }
    @Test fun smallerSamplesUseNewestPostsBeforeSortingByPlaybackTime() = runTest {
        fun comment(at: Long, year: Int) = Comment(at, "post-$year", """{"postedAt":"$year-01-01T00:00:00Z","commands":["ue"]}""")
        val selected = previewCommentSelection(listOf(comment(134000, 2015), comment(136000, 2026), comment(133000, 2025)), 90000, 2)
        assertEquals(listOf("post-2025", "post-2026"), selected.map { it.body })
        val repository = CommentPreviewRepository { sampleFile().inputStream() }
        val sample = repository.read(5000).comments
        assertTrue(sample.all { java.time.OffsetDateTime.parse(org.json.JSONObject(it.raw!!).getString("postedAt")).year >= 2023 })
        assertTrue(sample.containsAll(repository.read(100).comments))
        assertTrue(sample.any { org.json.JSONObject(it.raw!!).getJSONArray("commands").let { c -> (0 until c.length()).any { c.getString(it) in setOf("ue", "shita") } } })
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun previewObservesSavedDisplaySettingsWithoutUsingOrChangingThePlaybackSession() = runTest {
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler))
        var model: CommentPreviewViewModel? = null
        val oldSize = Session.size.value
        try {
            val settings = SettingsRepository(MemoryPreferences(), backgroundScope)
            val repository = CommentPreviewRepository { sampleFile().inputStream() }
            model = CommentPreviewViewModel(repository, settings)
            runCurrent()
            assertEquals(5000, model.limit.value)
            assertEquals(listOf(1000, 2500, 5000, 10000, 20000, 30000), PreviewCommentCounts)
            model.count(30000); assertEquals(30000, model.limit.value)
            assertThrows(IllegalArgumentException::class.java) { model.count(30001) }
            val expected = CommentDisplayProfile(size = 22f, opacity = .4f, speed = 1.2f, fps = 120, position = "custom", customY = .23f)
            settings.edit { expected.write(it); it[androidx.datastore.preferences.core.booleanPreferencesKey("flowOnly")] = true; it[androidx.datastore.preferences.core.stringPreferencesKey("ngWords")] = "NG-example" }
            runCurrent()
            assertEquals(expected.copy(position = "center"), model.settings.value!!.profile)
            assertEquals("custom", settings.load().getString("overlayPosition", "center"))
            assertEquals(.23f, settings.load().getFloat("overlayCustomY", 1f))
            assertEquals("NG-example", model.settings.value!!.ngWords)
            assertEquals(oldSize, Session.size.value)
        } finally {
            model?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
            kotlinx.coroutines.Dispatchers.resetMain()
        }
    }
    @Test fun legacyFixedCommentHidingIsIgnoredInProfilesAndRemovedOnWrite() {
        val expected = CommentDisplayProfile(size = 22f, position = "top")
        val legacy = org.json.JSONObject(expected.json()).put("flowOnly", true).put("hideOnOwnApp", true)
        assertEquals(expected, CommentDisplayProfile.parse(legacy.toString()))
        val preferences = androidx.datastore.preferences.core.mutablePreferencesOf(
            androidx.datastore.preferences.core.booleanPreferencesKey("flowOnly") to true,
            androidx.datastore.preferences.core.booleanPreferencesKey("hideOnOwnApp") to true)
        expected.write(preferences)
        assertNull(preferences[androidx.datastore.preferences.core.booleanPreferencesKey("flowOnly")])
        assertEquals(expected, CommentDisplayProfile.read(preferences))
        assertFalse(org.json.JSONObject(expected.json()).has("flowOnly"))
        assertFalse(org.json.JSONObject(expected.json()).has("hideOnOwnApp"))
        assertNull(preferences[androidx.datastore.preferences.core.booleanPreferencesKey("hideOnOwnApp")])
    }
    @Test fun playbackNotificationsShowPlayedCommentProgressAndRespectTheProgressPreference() {
        val state = CommentNotificationState(false, FetchProgress(), "作品", true, 1000, true, false, played = 250)
        val content = commentNotificationContent(state, AdvancedSettings())
        assertEquals(1000, content.maximum); assertEquals(250, content.progress); assertTrue(content.text.contains("250 / 1000"))
        assertEquals(1000, commentNotificationContent(state.copy(played = 1200), AdvancedSettings()).progress)
        assertEquals(0, commentNotificationContent(state, AdvancedSettings(notificationProgress = false)).maximum)
        assertFalse(commentNotificationContent(state, AdvancedSettings(privateNotifications = true)).text.contains("作品"))
    }
    @Test fun homeKeepsFloatingBeforeNgAndRemovesSecurityFromOtherSettings() {
        assertTrue(HomeSettings.indexOfFirst { it.page == "フローティング表示" } < HomeSettings.indexOfFirst { it.page == "NGワード" })
        assertFalse(otherSettings(false, 0).any { it.page == "セキュリティ" })
    }
}
