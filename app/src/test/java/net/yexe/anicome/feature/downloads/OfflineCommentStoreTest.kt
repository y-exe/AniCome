package net.yexe.anicome

import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OfflineCommentStoreTest {
    @Test fun nonVideoSourcesSurviveExportRelabelAndOfficialRefreshWithoutMixingClocks() {
        val directory = Files.createTempDirectory("anicome-offline").toFile()
        val restoredDirectory = Files.createTempDirectory("anicome-restored").toFile()
        try {
            val store = CommentStore(directory)
            val episode = Episode("作品", 1)
            val raw = listOf(Comment(1800000, "broadcast", "{\"commands\":[\"ue\"],\"userId\":\"aa\"}"))
            val slots = listOf(PlaybackCommentSlot("nicolog:/archive/one.xml", "作品 第1話", PlaybackSource.LIVE_ARCHIVE, raw, durationMs = 1800000))
            store.saveSources(episode, slots)
            assertEquals("LIVE_ARCHIVE", store.list().single().sources)
            assertEquals(raw, store.readSource(slots.single().id)!!.comments)
            val restored = CommentStore(restoredDirectory)
            restored.importJson(store.exportJson(episode)!!)
            assertEquals(slots.single().comments, restored.read(episode)!!.slots.single().comments)
            val renamed = Episode("作品", 13)
            restored.relabel(episode, renamed)
            assertNull(restored.read(episode))
            assertEquals(raw, restored.read(renamed)!!.slots.single().comments)
            restored.save(renamed, Candidate("so999999", "作品 第13話", 123, 1400), listOf(Comment(1000, "official")))
            assertEquals(2, restored.read(renamed)!!.slots.size)
            assertEquals(raw, restored.readSource(slots.single().id)!!.comments)
            restored.save(renamed, Candidate("so888888", "作品 第13話 別版", 123, 1400), listOf(Comment(2000, "correct edition")))
            restored.saveSources(renamed, restored.read(renamed)!!.slots)
            assertEquals("so888888", restored.read(renamed)!!.info.sourceId)
            assertNull(restored.readSource("so999999"))
            assertEquals(raw, restored.readSource(slots.single().id)!!.comments)
        } finally { directory.deleteRecursively(); restoredDirectory.deleteRecursively() }
    }
    @Test fun rejectsCorruptAdditionalSourcesBeforeOverwritingAnything() {
        val directory = Files.createTempDirectory("anicome-corrupt").toFile()
        try {
            val store = CommentStore(directory)
            val episode = Episode("作品", 1)
            store.saveSources(episode, listOf(PlaybackCommentSlot("jk9:1-2", "作品", PlaybackSource.JIKKYO, listOf(Comment(1000, "saved")))))
            val original = store.exportJson(episode)!!
            val corrupted = JSONObject(original)
            corrupted.getJSONArray("sources").getJSONObject(0).getJSONArray("comments").getJSONObject(0).put("at", -1)
            try { store.importJson(corrupted.toString()); fail() } catch (_: IllegalArgumentException) { }
            assertEquals(original, store.exportJson(episode))
        } finally { directory.deleteRecursively() }
    }
    @Test fun saveRequiresAtLeastOneSelectedSource() {
        val work = AnimeInfo(1, "作品", "", "", null, null, 12, emptyList())
        assertFalse(SaveCommentState(query = work.title, work = work, sources = emptySet()).canSave)
        assertTrue(SaveCommentState(query = work.title, work = work, sources = setOf(PlaybackSource.JIKKYO)).canSave)
        val official = SavedEpisode(Episode("作品", 1), "so1", "作品 第1話", 0, 1, 1, ownerName = "公式", ownerUrl = "https://ch.nicovideo.jp/series")
        val archive = SavedEpisode(Episode("作品", 2), "nicolog:two", "作品 第2話", 0, 1, 1, sources = "LIVE_ARCHIVE")
        assertEquals(official.libraryGroupKey(listOf(official, archive)), archive.libraryGroupKey(listOf(official, archive)))
    }
    @Test fun continuousCourNumbersFindTheOriginalOfflineBundleWithoutPickingAnotherSeason() {
        val directory = Files.createTempDirectory("anicome-cour-cache").toFile()
        try {
            val store = CommentStore(directory)
            val episode = Episode("作品 第2クール", 2)
            store.save(episode, Candidate("so13", "作品 第13話", 123, 1400), listOf(Comment(1000, "official")))
            store.saveSources(episode, listOf(PlaybackCommentSlot("jk9:13", "作品 第13話", PlaybackSource.JIKKYO, listOf(Comment(5000, "broadcast")))))
            assertEquals(2, store.readForPlayback(Episode("作品", 13))!!.slots.size)
            assertNull(store.readForPlayback(Episode("作品 第2期", 13)))
        } finally { directory.deleteRecursively() }
    }
    @Test fun restoringAddsMissingSourcesWithoutReplacingCurrentComments() {
        val directory = Files.createTempDirectory("anicome-merge-backup").toFile()
        val backupDirectory = Files.createTempDirectory("anicome-merge-source").toFile()
        try {
            val episode = Episode("作品", 1)
            val source = Candidate("so123", "作品 第1話", 123, 1400)
            val store = CommentStore(directory)
            val backup = CommentStore(backupDirectory)
            store.save(episode, source, listOf(Comment(1000, "current")))
            backup.save(episode, source, listOf(Comment(1000, "old backup")))
            backup.saveSources(episode, listOf(PlaybackCommentSlot("so456", source.title, PlaybackSource.DANIME, listOf(Comment(1000, "danime")), durationMs = 1400000)))
            store.importJson(backup.exportJson(episode)!!)
            assertEquals(listOf("current"), store.read(episode)!!.comments.map { it.body })
            assertEquals(listOf("danime"), store.readSource("so456")!!.comments.map { it.body })
        } finally { directory.deleteRecursively(); backupDirectory.deleteRecursively() }
    }
}
