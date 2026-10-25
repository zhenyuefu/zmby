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
        SegmentMarker::class, DataSourceRow::class, SyncState::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class ZmbyDatabase : RoomDatabase() {
    abstract fun works(): WorkDao
    abstract fun lists(): ListDao
    abstract fun people(): PersonDao
    abstract fun versions(): VersionDao
    abstract fun favorites(): FavoriteDao
    abstract fun watchlist(): WatchlistDao
    abstract fun sources(): SourceDao

    companion object {
        const val NAME = "zmby.db"

        /** Every migration, oldest first. */
        val MIGRATIONS: Array<Migration> = arrayOf()

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
