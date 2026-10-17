package net.yexe.anicome

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test

class LocalCommentAuditTest {
    @Test fun exportedEpisodeAndPublicBroadcastKeepTheirCommentCoverage() {
        val path = System.getenv("ANICOME_COMMENT_AUDIT_DIR")
        assumeNotNull(path)
        fun read(name: String): List<Comment> {
            val rows = JSONArray(File(path!!, name).readText())
            return (0 until rows.length()).map { i -> rows.getJSONObject(i).let { Comment(it.getLong("at"), it.getString("body"), it.optString("raw"), it.optString("fork", "main")) } }
        }
        val official = PlaybackCommentSlot("so23335421", "ご注文はうさぎですか？ 第1羽", PlaybackSource.OFFICIAL, read("device-so23335421-comments.json"), true, 1417000)
        val broadcast = PlaybackCommentSlot("jk9:1397136600", official.title, PlaybackSource.JIKKYO, read("jikkyo-comments.json"), durationMs = 1800000)
        val aligned = synchronizeCommentSlots(listOf(official, broadcast), 1417000, true, false)
        assertSame(official.comments, aligned.first().comments)
        assertEquals(official.comments.size, aligned.first().comments.size)
        assertTrue(aligned.last().comments.isNotEmpty())
        val joined = continuePlaybackComments(listOf(aligned.first()), PlaybackComments(aligned))
        assertEquals(aligned.sumOf { it.comments.size }, joined.comments.size)
        val directory = java.nio.file.Files.createTempDirectory("anicome-real-offline").toFile()
        try {
            val store = CommentStore(directory)
            val episode = Episode("ご注文はうさぎですか？", 1)
            val saved = store.saveSources(episode, listOf(official.copy(amount = 20), broadcast))
            assertEquals(official.comments.size + broadcast.comments.size, saved.info.count)
            assertEquals(official.comments, store.readSource(official.id)!!.comments)
            assertEquals(broadcast.comments, store.readSource(broadcast.id)!!.comments)
            assertEquals(listOf(official.comments, broadcast.comments), store.read(episode)!!.slots.map { it.comments })
            store.validateImport(store.exportJson(episode)!!)
        } finally { directory.deleteRecursively() }
        val report = JSONObject().put("officialCount", official.comments.size).put("broadcastRawCount", broadcast.comments.size)
            .put("broadcastAlignedCount", aligned.last().comments.size).put("sync", aligned.last().sync.name)
            .put("spans", JSONArray(aligned.last().syncSpans.map { "${it.sourceStart}-${it.sourceEnd}:${it.playbackStart}" }))
            .put("officialMarkers", JSONObject(timelineMarkers(official.comments, 1417000).mapKeys { it.key.name }))
            .put("broadcastMarkers", JSONObject(timelineMarkers(broadcast.comments, 1800000).mapKeys { it.key.name }))
        File(path!!, "sync-audit.json").writeText(report.toString(2))
    }
}
