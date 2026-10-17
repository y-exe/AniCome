package net.yexe.anicome

import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupArchiveTest {
    @get:Rule val folder = TemporaryFolder()
    private fun zip(entries: List<Pair<String, String>>): ByteArray = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip -> entries.forEach { (name, content) -> zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry() } } }.toByteArray()
    private val manifest = """{"format":"AniCome","version":1,"settings":{"fps":{"type":"int","value":60}}}"""
    @Test fun largeEntriesAreSpooledToFilesAndSettingsAreSeparate() {
        val content = "x".repeat(2 * 1024 * 1024)
        val directory = folder.newFolder()
        val result = readBackupArchive(zip(listOf("manifest.json" to manifest, "comments/${"a".repeat(64)}.json" to content)).inputStream(), directory)
        assertEquals(60, result.settings.getJSONObject("fps").getInt("value"))
        assertEquals(1, result.comments.size); assertEquals(content, result.comments.single().readText()); assertEquals(directory.canonicalFile, result.comments.single().parentFile!!.canonicalFile)
    }
    @Test fun zipTraversalIsRejectedBeforeCreatingFiles() {
        val directory = folder.newFolder()
        try { readBackupArchive(zip(listOf("../outside.json" to "{}")).inputStream(), directory); fail() } catch (_: IllegalArgumentException) { }
        assertTrue(directory.listFiles().orEmpty().isEmpty()); assertFalse(java.io.File(folder.root, "outside.json").exists())
    }
    @Test fun duplicateEntryNamesAreRejected() {
        val a = "a".repeat(64); val b = "b".repeat(64)
        val bytes = zip(listOf("manifest.json" to manifest, "comments/$a.json" to "{}", "comments/$b.json" to "{}"))
        val duplicate = String(bytes, Charsets.ISO_8859_1).replace(b, a).toByteArray(Charsets.ISO_8859_1)
        try { readBackupArchive(duplicate.inputStream(), folder.newFolder()); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun inflatedSizeIsBoundedEvenWhenZipHeadersOmitSize() {
        val bytes = zip(listOf("manifest.json" to manifest, "comments/${"a".repeat(64)}.json" to "x".repeat(20000)))
        try { readBackupArchive(bytes.inputStream(), folder.newFolder(), maximum = 1000); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun malformedManifestIsRejected() {
        try { readBackupArchive(zip(listOf("manifest.json" to """{"format":"other","version":1}""")).inputStream(), folder.newFolder()); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun archiveChecksCanCancelBetweenReadChunks() {
        var calls = 0
        try { readBackupArchive(zip(listOf("manifest.json" to manifest)).inputStream(), folder.newFolder(), check = { if (++calls == 2) throw kotlinx.coroutines.CancellationException() }); fail() } catch (_: kotlinx.coroutines.CancellationException) { }
    }
    @Test fun transferredCommentsPreserveTheirOriginalSaveDate() {
        val source = CommentStore(folder.newFolder())
        val target = CommentStore(folder.newFolder())
        val episode = Episode("work", 1)
        source.save(episode, Candidate("so123", "title"), listOf(Comment(100, "keep")), savedAt = 1234)
        target.importJson(source.exportJson(episode)!!)
        assertEquals(1234L, target.read(episode)!!.info.savedAt)
    }
}
