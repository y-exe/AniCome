package net.yexe.anicome

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "saved_episodes", indices = [Index("sourceId"), Index("ownerUrl")])
data class SavedEpisodeEntity(@PrimaryKey val key: String, val series: String, val episode: Int,
    val sourceId: String, val sourceTitle: String, val savedAt: Long, val count: Int, val bytes: Long,
    val amount: Int, val thumbnailUrl: String, val ownerName: String, val ownerUrl: String, @ColumnInfo(defaultValue = "'OFFICIAL'") val sources: String = "OFFICIAL") {
    fun toEpisode() = SavedEpisode(Episode(series, episode), sourceId, sourceTitle, savedAt, count, bytes, amount, thumbnailUrl, ownerName, ownerUrl, sources)
    companion object {
        fun from(info: SavedEpisode) = with(info) { SavedEpisodeEntity(episode.key, episode.series, episode.number,
            niconicoVideoId(sourceId) ?: sourceId, sourceTitle, savedAt, count, bytes, amount, thumbnailUrl, ownerName, ownerUrl, sources) }
    }
}
@Dao interface SavedEpisodeDao {
    @Query("SELECT * FROM saved_episodes ORDER BY savedAt DESC") fun all(): List<SavedEpisodeEntity>
    @Query("SELECT * FROM saved_episodes ORDER BY savedAt DESC") fun observe(): Flow<List<SavedEpisodeEntity>>
    @Query("SELECT * FROM saved_episodes WHERE sourceId = :id ORDER BY amount DESC, savedAt DESC") fun bySource(id: String): List<SavedEpisodeEntity>
    @Query("SELECT * FROM saved_episodes WHERE `key` = :key LIMIT 1") fun get(key: String): SavedEpisodeEntity?
    @Upsert fun upsert(item: SavedEpisodeEntity)
    @Query("DELETE FROM saved_episodes WHERE `key` = :key") fun delete(key: String)
    @Query("DELETE FROM saved_episodes") fun clear()
    @Transaction fun replace(items: List<SavedEpisodeEntity>) { clear(); items.forEach(::upsert) }
}
@Entity(tableName = "cached_episodes", indices = [Index("sourceId"), Index("ownerUrl")])
data class CachedEpisodeEntity(@PrimaryKey val key: String, val series: String, val episode: Int,
    val sourceId: String, val sourceTitle: String, val savedAt: Long, val count: Int, val bytes: Long,
    val amount: Int, val thumbnailUrl: String, val ownerName: String, val ownerUrl: String, @ColumnInfo(defaultValue = "'OFFICIAL'") val sources: String = "OFFICIAL") {
    fun toEpisode() = SavedEpisode(Episode(series, episode), sourceId, sourceTitle, savedAt, count, bytes, amount, thumbnailUrl, ownerName, ownerUrl, sources)
    companion object {
        fun from(info: SavedEpisode) = with(info) { CachedEpisodeEntity(episode.key, episode.series, episode.number,
            niconicoVideoId(sourceId) ?: sourceId, sourceTitle, savedAt, count, bytes, amount, thumbnailUrl, ownerName, ownerUrl, sources) }
    }
}
@Dao interface CachedEpisodeDao {
    @Query("SELECT * FROM cached_episodes ORDER BY savedAt DESC") fun all(): List<CachedEpisodeEntity>
    @Query("SELECT * FROM cached_episodes ORDER BY savedAt DESC") fun observe(): Flow<List<CachedEpisodeEntity>>
    @Query("SELECT * FROM cached_episodes WHERE sourceId = :id ORDER BY amount DESC, savedAt DESC") fun bySource(id: String): List<CachedEpisodeEntity>
    @Query("SELECT * FROM cached_episodes WHERE `key` = :key LIMIT 1") fun get(key: String): CachedEpisodeEntity?
    @Upsert fun upsert(item: CachedEpisodeEntity)
    @Query("DELETE FROM cached_episodes WHERE `key` = :key") fun delete(key: String)
    @Query("DELETE FROM cached_episodes") fun clear()
    @Transaction fun replace(items: List<CachedEpisodeEntity>) { clear(); items.forEach(::upsert) }
}
@Database(entities = [SavedEpisodeEntity::class, CachedEpisodeEntity::class, CommentHistoryEntry::class, ReservationEntry::class, TrafficDay::class], version = 6, exportSchema = true)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun episodes(): SavedEpisodeDao
    abstract fun cachedEpisodes(): CachedEpisodeDao
    abstract fun history(): CommentHistoryDao
    abstract fun reservations(): ReservationDao
    abstract fun traffic(): TrafficDao
    companion object {
        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS traffic_usage (`day` TEXT NOT NULL, `bytes` INTEGER NOT NULL, `generation` INTEGER NOT NULL, PRIMARY KEY(`day`))")
            }
        }
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS download_reservations (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `series` TEXT NOT NULL, `episode` INTEGER NOT NULL, `payload` TEXT NOT NULL, `state` TEXT NOT NULL, `rank` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `workId` TEXT NOT NULL, `output` TEXT NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_download_reservations_name ON download_reservations (`name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_download_reservations_state_rank ON download_reservations (`state`, `rank`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_download_reservations_workId ON download_reservations (`workId`)")
            }
        }
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS comment_history (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `time` INTEGER NOT NULL, `kind` TEXT NOT NULL, `series` TEXT NOT NULL, `episode` INTEGER NOT NULL, `query` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `sourceTitle` TEXT NOT NULL, `count` INTEGER NOT NULL, `previousId` TEXT NOT NULL, `previousCount` INTEGER NOT NULL, `sources` TEXT NOT NULL, `storage` TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_comment_history_time ON comment_history (`time`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_comment_history_series_episode ON comment_history (`series`, `episode`)")
            }
        }
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS cached_episodes (`key` TEXT NOT NULL, series TEXT NOT NULL, episode INTEGER NOT NULL, sourceId TEXT NOT NULL, sourceTitle TEXT NOT NULL, savedAt INTEGER NOT NULL, count INTEGER NOT NULL, bytes INTEGER NOT NULL, amount INTEGER NOT NULL, thumbnailUrl TEXT NOT NULL, ownerName TEXT NOT NULL, ownerUrl TEXT NOT NULL, sources TEXT NOT NULL DEFAULT 'OFFICIAL', PRIMARY KEY(`key`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cached_episodes_sourceId ON cached_episodes(sourceId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cached_episodes_ownerUrl ON cached_episodes(ownerUrl)")
            }
        }
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) { db.execSQL("ALTER TABLE saved_episodes ADD COLUMN sources TEXT NOT NULL DEFAULT 'OFFICIAL'") }
        }
    }
}
interface SavedEpisodeIndex {
    fun all(): List<SavedEpisode>
    fun get(key: String): SavedEpisode? = all().find { it.episode.key == key }
    fun bySource(id: String): List<SavedEpisode>
    fun upsert(info: SavedEpisode)
    fun delete(key: String)
    fun replace(items: List<SavedEpisode>)
}
class RoomEpisodeIndex(private val dao: SavedEpisodeDao) : SavedEpisodeIndex {
    override fun all() = dao.all().map(SavedEpisodeEntity::toEpisode)
    override fun get(key: String) = dao.get(key)?.toEpisode()
    override fun bySource(id: String) = dao.bySource(id).map(SavedEpisodeEntity::toEpisode)
    override fun upsert(info: SavedEpisode) = dao.upsert(SavedEpisodeEntity.from(info))
    override fun delete(key: String) = dao.delete(key)
    override fun replace(items: List<SavedEpisode>) = dao.replace(items.map(SavedEpisodeEntity::from))
}

class RoomCacheEpisodeIndex(private val dao: CachedEpisodeDao) : SavedEpisodeIndex {
    override fun all() = dao.all().map(CachedEpisodeEntity::toEpisode)
    override fun get(key: String) = dao.get(key)?.toEpisode()
    override fun bySource(id: String) = dao.bySource(id).map(CachedEpisodeEntity::toEpisode)
    override fun upsert(info: SavedEpisode) = dao.upsert(CachedEpisodeEntity.from(info))
    override fun delete(key: String) = dao.delete(key)
    override fun replace(items: List<SavedEpisode>) = dao.replace(items.map(CachedEpisodeEntity::from))
}
