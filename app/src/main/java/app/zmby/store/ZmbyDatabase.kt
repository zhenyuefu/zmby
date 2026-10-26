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
    ],
    version = 4,
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
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

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
