package net.yexe.anicome

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.core.okio.OkioStorage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import okio.Path.Companion.toPath
import okio.FileSystem
import okio.ForwardingFileSystem
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class ModernStorageTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun settingsPersistOrderedWritesAndAllLegacyValueTypes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val windowsFileSystem = object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun atomicMove(source: okio.Path, target: okio.Path) {
                Files.move(source.toFile().toPath(), target.toFile().toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        val store = PreferenceDataStoreFactory.create(scope = scope, storage = OkioStorage(windowsFileSystem, PreferencesSerializer,
            producePath = { folder.root.resolve("settings.preferences_pb").absolutePath.toPath() }))
        val repository = SettingsRepository(store, scope)
        try {
            repository.putBoolean("auto", false)
            repository.putInt("fps", 144)
            repository.putFloat("commentSize", 17f)
            repository.putString("language", "ja")
            repository.putLong("storageLimitMb", 200)
            repository.putLong("storageLimitMb", 0)
            try { withTimeout(5000) {
                store.data.first { SettingsSnapshot(it).getString("language", null) == "ja" && SettingsSnapshot(it).getLong("storageLimitMb", -1) == 0L }
            } } catch (timeout: TimeoutCancellationException) {
                throw AssertionError("Settings writes failed=${repository.saveFailed.value}; files=${folder.root.listFiles()?.map { it.name }}", timeout)
            }
            val loaded = repository.load()
            assertFalse(loaded.getBoolean("auto", true))
            assertEquals(144, loaded.getInt("fps", 60))
            assertEquals(17f, loaded.getFloat("commentSize", 5f))
            assertEquals(0L, loaded.getLong("storageLimitMb", -1))
        } finally { scope.cancel() }
    }

    @Test fun rebuildsMetadataFromExistingArchivesAndKeepsFilesIntact() {
        val directory = folder.newFolder()
        val episode = Episode("旧作品名", 2)
        val comments = listOf(Comment(1000, "保存済み"))
        val oldStore = CommentStore(directory)
        val original = oldStore.save(episode, Candidate("so123", "第2話"), comments)
        val bytes = directory.listFiles()!!.single().readBytes()
        val index = MemoryIndex()
        val migrated = CommentStore(directory, index = index)
        migrated.reconcileIndex()
        assertEquals(listOf(original.info), migrated.list())
        assertArrayEquals(bytes, directory.listFiles()!!.single().readBytes())
        assertEquals(comments, migrated.readSource("https://nico.ms/so123")!!.comments)
        migrated.updateSource(episode, "so123", Candidate("so123", "正式題名", ownerName = "公式チャンネル"))
        assertEquals("公式チャンネル", index.all().single().ownerName)
        migrated.delete(episode)
        assertTrue(index.all().isEmpty())
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    private class MemoryIndex : SavedEpisodeIndex {
        private val rows = linkedMapOf<String, SavedEpisode>()
        override fun all() = rows.values.toList()
        override fun bySource(id: String) = all().filter { it.sourceId == id }
        override fun upsert(info: SavedEpisode) { rows[info.episode.key] = info }
        override fun delete(key: String) { rows.remove(key) }
        override fun replace(items: List<SavedEpisode>) { rows.clear(); items.forEach(::upsert) }
    }
}
