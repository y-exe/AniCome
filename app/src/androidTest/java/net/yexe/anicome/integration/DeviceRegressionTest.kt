package net.yexe.anicome

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.*
import androidx.room.Room
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DeviceRegressionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun publicNiconicoWatchAndCommentsCanBeFetchedWithout406() = runBlocking {
        val api = ApiClient.create()
        val watch = api.request("https://www.nicovideo.jp/watch/so22048157?responseType=json").getJSONObject("data").getJSONObject("response")
        val metadata = parseNiconicoSource("so22048157", watch)
        assertTrue(metadata.ownerName.isNotBlank())
        val comment = watch.getJSONObject("comment").getJSONObject("nvComment")
        val server = java.net.URI(comment.getString("server"))
        require(server.scheme == "https" && server.host.endsWith(".nicovideo.jp"))
        val body = JSONObject().put("params", comment.getJSONObject("params")).put("threadKey", comment.getString("threadKey")).put("additionals", JSONObject())
        val response = api.request(server.resolve("/v1/threads").toString(), body.toString())
        assertEquals(200, response.getJSONObject("meta").getInt("status"))
        val threads = response.getJSONObject("data").getJSONArray("threads")
        assertTrue((0 until threads.length()).sumOf { threads.getJSONObject(it).getJSONArray("comments").length() } > 0)
    }

    @Test fun oldPreferencesMigrateWithAllValueTypes() = runBlocking {
        val name = "migration-test-${UUID.randomUUID()}"
        val file = File(context.cacheDir, "$name.preferences_pb")
        val old = context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
        assertTrue(old.edit().putBoolean("auto", false).putInt("fps", 144).putFloat("commentSize", 17f).putLong("storageLimitMb", 0).putString("activeService", "NETFLIX").commit())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope, migrations = listOf(SharedPreferencesMigration(context, name)), produceFile = { file })
            val snapshot = SettingsSnapshot(store.data.first())
            assertFalse(snapshot.getBoolean("auto", true))
            assertEquals(144, snapshot.getInt("fps", 60))
            assertEquals(17f, snapshot.getFloat("commentSize", 5f))
            assertEquals(0L, snapshot.getLong("storageLimitMb", -1))
            assertEquals("NETFLIX", snapshot.getString("activeService", null))
            store.edit { it[intPreferencesKey("fps")] = 120 }
            assertEquals(120, SettingsSnapshot(store.data.first()).getInt("fps", 60))
        } finally { scope.coroutineContext[Job]?.cancelAndJoin(); file.delete(); context.deleteSharedPreferences(name) }
    }

    @Test fun roomImportsArchivesUpdatesAndDeletesWithoutChangingBodies() = runBlocking(Dispatchers.IO) {
        val directory = File(context.cacheDir, "room-test-${UUID.randomUUID()}").apply { mkdirs() }
        val database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val episode = Episode("テスト作品", 1)
            val comments = listOf(Comment(1000, "保存済み"))
            CommentStore(directory).save(episode, Candidate("so123", "テスト作品 第1話"), comments)
            val original = directory.listFiles()!!.single().readBytes()
            val store = CommentStore(directory, index = RoomEpisodeIndex(database.episodes()))
            store.reconcileIndex()
            assertEquals(1, database.episodes().all().size)
            assertEquals(comments, store.readSource("https://nico.ms/so123")!!.comments)
            assertArrayEquals(original, directory.listFiles()!!.single().readBytes())
            store.updateSource(episode, "so123", Candidate("so123", "第1話", ownerName = "公式チャンネル"))
            assertEquals("公式チャンネル", database.episodes().all().single().ownerName)
            store.delete(episode)
            assertTrue(database.episodes().all().isEmpty())
        } finally {
            database.close()
            require(directory.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator + "room-test-"))
            directory.deleteRecursively()
        }
    }

    @Test fun lockVerifierPersistsAndRequiresAuthenticationAfterRestart() = runBlocking {
        val name = "lock-test-${UUID.randomUUID()}"
        val file = File(context.cacheDir, "$name.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
            val settings = SettingsRepository(store, scope)
            val original = AppLockRepository(settings, scope)
            original.configure("pin", "123456", false)
            withTimeout(5000) { original.state.first { it.mode == "pin" } }
            val restarted = AppLockRepository(settings, scope)
            withTimeout(5000) { restarted.state.first { it.loaded } }
            assertTrue(restarted.state.value.locked)
            assertFalse(restarted.verify("000000"))
            assertTrue(restarted.verify("123456"))
            assertFalse(restarted.state.value.locked)
            restarted.disable()
            assertNull(store.data.first()[stringPreferencesKey("lockHash")])
        } finally { scope.coroutineContext[Job]?.cancelAndJoin(); file.delete() }
    }
}
