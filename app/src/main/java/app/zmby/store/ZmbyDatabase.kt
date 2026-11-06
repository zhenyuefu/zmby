package app.zmby.store

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration

/**
 * The local store (local-data spec). Schemas are exported to `app/schemas`; every version bump
 * adds a [Migration] to [MIGRATIONS] and a test — no destructive migration, so favourites and the
 * watchlist are never dropped.
 */
@Database(
    entities = [
        WorkMeta::class, WorkInstance::class, Person::class, PersonAlias::class, WorkPerson::class,
        VersionSummary::class, ListCache::class, ListItem::class,
        FavoriteRow::class, FavoriteInstanceRow::class, FavoriteWriteRow::class, WatchlistRow::class,
        SegmentMarker::class, DataSourceRow::class, SyncState::class, PlayHistoryRow::class, app.zmby.catalog.CatalogCache::class,
        EpisodeMappingRule::class, WatchingHiddenRow::class, PlaySessionRow::class, ProgressReportRow::class, RepoCacheRow::class,
    ],
    version = 9,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class ZmbyDatabase : RoomDatabase() {
    abstract fun catalog(): app.zmby.catalog.CatalogDao
    abstract fun works(): WorkDao
    abstract fun lists(): ListDao
    abstract fun people(): PersonDao
    abstract fun versions(): VersionDao
    abstract fun favorites(): FavoriteDao
    abstract fun watchlist(): WatchlistDao
    abstract fun sources(): SourceDao
    abstract fun playHistory(): PlayHistoryDao
    abstract fun episodeMappings(): EpisodeMappingDao
    abstract fun watchingHidden(): WatchingHiddenDao
    abstract fun playSessions(): PlaySessionDao
    abstract fun progressReports(): ProgressReportDao
    abstract fun repoCache(): RepoCacheDao

    companion object {
        const val NAME = "zmby.db"

        /** 2: 观影统计's play history (P4). */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(PLAY_HISTORY_SQL)
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_play_history_work_key` ON `play_history` (`work_key`)")
            }
        }

        /** As in app/schemas/…/2.json. */
        private const val PLAY_HISTORY_SQL = "CREATE TABLE IF NOT EXISTS `play_history` (`server_id` TEXT NOT NULL, `item_id` TEXT NOT NULL, `type` TEXT NOT NULL, `work_key` TEXT NOT NULL, `series_key` TEXT, `series_name` TEXT, `title` TEXT, `runtime_ms` INTEGER, `played_at` INTEGER, `play_count` INTEGER NOT NULL, `fetched_at` INTEGER NOT NULL, PRIMARY KEY(`server_id`, `item_id`))"

        /** Provider metadata has its own cache; server resources and user lists survive. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `catalog_cache` (`key` TEXT NOT NULL, `json` TEXT NOT NULL, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))")
                db.execSQL("DELETE FROM work_meta")
                db.execSQL("DELETE FROM work_person")
                db.execSQL("DELETE FROM person_alias")
                db.execSQL("DELETE FROM person")
                db.execSQL("DELETE FROM work_instance WHERE type = 'Person'")
                db.execSQL("DELETE FROM list_item WHERE list_key LIKE 'person:%'")
                db.execSQL("DELETE FROM list_cache WHERE list_key LIKE 'person:%'")
                db.execSQL("UPDATE work_instance SET images_json = NULL")
            }
        }

        /** 4: measured metadata sizes and LRU timestamps, preserving existing provider responses. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE catalog_cache ADD COLUMN size_bytes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE catalog_cache ADD COLUMN accessed_at INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE catalog_cache SET size_bytes = length(CAST(`key` AS BLOB)) + length(CAST(json AS BLOB)), accessed_at = fetchedAt")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_catalog_cache_accessed_at ON catalog_cache (accessed_at)")
            }
        }

        /** Every migration, oldest first. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS episode_mapping (id TEXT NOT NULL, work_key TEXT NOT NULL, server_id TEXT NOT NULL, series_id TEXT NOT NULL, source_season INTEGER NOT NULL, first_episode INTEGER NOT NULL, last_episode INTEGER NOT NULL, target_season INTEGER NOT NULL, `offset` INTEGER NOT NULL, item_id TEXT, target_episode_id TEXT, PRIMARY KEY(id))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_episode_mapping_work_key ON episode_mapping (work_key)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_episode_mapping_server_id ON episode_mapping (server_id)")
            }
        }

        /** 6: 在看 cards hidden on this box (ui-spec §2.18). */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `watching_hidden` (`work_key` TEXT NOT NULL, `hidden_at` INTEGER NOT NULL, PRIMARY KEY(`work_key`))")
            }
        }

        /** 7: 我的's playback sessions on this box (local-data spec, 我的：本机播放会话与统计口径). As in app/schemas/…/7.json. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `play_session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `server_id` TEXT NOT NULL, `item_id` TEXT NOT NULL, `type` TEXT NOT NULL, `work_key` TEXT NOT NULL, `series_key` TEXT, `series_id` TEXT, `series_name` TEXT, `title` TEXT, `season` INTEGER, `episode` INTEGER, `genres` TEXT NOT NULL, `started_at` INTEGER NOT NULL, `ended_at` INTEGER NOT NULL, `watched_ms` INTEGER NOT NULL, `spans` TEXT NOT NULL, `start_position_ms` INTEGER NOT NULL, `end_position_ms` INTEGER, `runtime_ms` INTEGER, `completed` INTEGER NOT NULL, `open` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_play_session_started_at` ON `play_session` (`started_at`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_play_session_work_key` ON `play_session` (`work_key`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_play_session_server_id_item_id` ON `play_session` (`server_id`, `item_id`)")
            }
        }

        /** 8: this box's progress reports survive a restart (统一数据层 · 观看进度). As in app/schemas/…/8.json. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `progress_report` (`server_id` TEXT NOT NULL, `item_id` TEXT NOT NULL, `position_ticks` INTEGER NOT NULL, `played` INTEGER NOT NULL, `played_at` INTEGER NOT NULL, PRIMARY KEY(`server_id`, `item_id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_progress_report_played_at` ON `progress_report` (`played_at`)")
            }
        }

        /** 9: server answers under the in-memory request sharing (统一数据层 ①③). As in app/schemas/…/9.json. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `repo_cache` (`kind` TEXT NOT NULL, `cache_key` TEXT NOT NULL, `server_id` TEXT NOT NULL, `json` TEXT NOT NULL, `fetched_at` INTEGER NOT NULL, PRIMARY KEY(`kind`, `cache_key`, `server_id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_repo_cache_fetched_at` ON `repo_cache` (`fetched_at`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_repo_cache_server_id` ON `repo_cache` (`server_id`)")
            }
        }

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)

        fun open(context: Context): ZmbyDatabase =
            Room.databaseBuilder(context, ZmbyDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}

/** Lists of short strings (genres, `server|item` pairs) as one column. */
class Converters {
    @TypeConverter fun fromList(list: List<String>): String = list.joinToString(SEP)
    @TypeConverter fun toList(raw: String): List<String> = if (raw.isEmpty()) emptyList() else raw.split(SEP)

    private companion object { const val SEP = "\u001f" }
}
