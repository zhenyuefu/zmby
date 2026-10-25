package app.zmby.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkDao {
    @Upsert suspend fun upsertInstances(rows: List<WorkInstance>)
    @Upsert suspend fun upsertMeta(meta: WorkMeta)

    @Query("SELECT * FROM work_instance WHERE server_id = :server AND item_id = :item")
    suspend fun instance(server: String, item: String): WorkInstance?

    @Query("SELECT * FROM work_instance WHERE server_id = :server AND item_id IN (:items)")
    suspend fun instances(server: String, items: List<String>): List<WorkInstance>

    @Query("SELECT * FROM work_instance WHERE server_id = :server AND item_id = :item")
    fun instanceFlow(server: String, item: String): Flow<WorkInstance?>

    @Query("SELECT * FROM work_instance WHERE work_key = :key")
    suspend fun instancesOfWork(key: String): List<WorkInstance>

    @Query("SELECT * FROM work_meta WHERE work_key = :key")
    suspend fun meta(key: String): WorkMeta?

    @Query("SELECT * FROM work_meta WHERE work_key = :key")
    fun metaFlow(key: String): Flow<WorkMeta?>

    @Query("UPDATE work_instance SET accessed_at = :now WHERE server_id = :server AND item_id = :item")
    suspend fun touchInstance(server: String, item: String, now: Long)

    @Query("UPDATE work_meta SET accessed_at = :now WHERE work_key = :key")
    suspend fun touchMeta(key: String, now: Long)

    @Query("UPDATE work_instance SET work_key = :to WHERE work_key = :from")
    suspend fun moveWork(from: String, to: String)

    @Query("UPDATE work_instance SET position_ticks = :ticks, played = :played WHERE server_id = :server AND item_id = :item")
    suspend fun setUserData(server: String, item: String, ticks: Long, played: Boolean)

    @Query("DELETE FROM work_meta WHERE work_key = :key")
    suspend fun deleteMeta(key: String)

    @Query("SELECT COUNT(*) FROM work_instance") suspend fun instanceCount(): Int
    @Query("SELECT COUNT(*) FROM work_meta") suspend fun metaCount(): Int

    /**
     * LRU (local-data spec, 容量): the least recently used instances beyond the limit, except what
     * favourites, the watchlist or a resume snapshot point at.
     */
    @Query(
        """DELETE FROM work_instance WHERE rowid IN (
            SELECT wi.rowid FROM work_instance wi
            WHERE NOT EXISTS (SELECT 1 FROM favorite_instance f WHERE f.server_id = wi.server_id AND f.item_id = wi.item_id)
              AND NOT EXISTS (SELECT 1 FROM watchlist w WHERE w.work_key = wi.work_key)
              AND NOT EXISTS (SELECT 1 FROM favorite fv WHERE fv.work_key = wi.work_key AND fv.removed_at IS NULL)
              AND NOT EXISTS (SELECT 1 FROM list_item li WHERE li.server_id = wi.server_id AND li.item_id = wi.item_id
                              AND (li.list_key LIKE 'resume:%' OR li.list_key LIKE 'nextup:%'))
            ORDER BY wi.accessed_at ASC LIMIT :count)""",
    )
    suspend fun trimInstances(count: Int): Int

    @Query(
        """DELETE FROM work_meta WHERE rowid IN (
            SELECT m.rowid FROM work_meta m
            WHERE NOT EXISTS (SELECT 1 FROM favorite f WHERE f.work_key = m.work_key AND f.removed_at IS NULL)
              AND NOT EXISTS (SELECT 1 FROM watchlist w WHERE w.work_key = m.work_key)
            ORDER BY m.accessed_at ASC LIMIT :count)""",
    )
    suspend fun trimMeta(count: Int): Int

    @Query("DELETE FROM work_instance WHERE server_id = :server") suspend fun deleteServerInstances(server: String)
}

@Dao
interface ListDao {
    @Upsert suspend fun upsertList(list: ListCache)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertItems(items: List<ListItem>)
    @Query("DELETE FROM list_item WHERE list_key = :key") suspend fun clearItems(key: String)

    @Transaction
    suspend fun replace(list: ListCache, items: List<ListItem>) {
        clearItems(list.listKey)
        insertItems(items)
        upsertList(list)
    }

    @Query("SELECT * FROM list_cache WHERE list_key = :key") suspend fun list(key: String): ListCache?
    @Query("SELECT * FROM list_cache WHERE list_key = :key") fun listFlow(key: String): Flow<ListCache?>
    @Query("SELECT * FROM list_cache WHERE list_key IN (:keys)") fun listsFlow(keys: List<String>): Flow<List<ListCache>>

    /** The list's items in order, joined with what is known about each. */
    @Query(
        """SELECT li.list_key AS listKey, li.ord AS ord, wi.* FROM list_item li
           JOIN work_instance wi ON wi.server_id = li.server_id AND wi.item_id = li.item_id
           WHERE li.list_key IN (:keys) ORDER BY li.list_key, li.ord""",
    )
    fun entriesFlow(keys: List<String>): Flow<List<ListEntry>>

    @Query(
        """SELECT li.list_key AS listKey, li.ord AS ord, wi.* FROM list_item li
           JOIN work_instance wi ON wi.server_id = li.server_id AND wi.item_id = li.item_id
           WHERE li.list_key IN (:keys) ORDER BY li.list_key, li.ord""",
    )
    suspend fun entries(keys: List<String>): List<ListEntry>

    @Query("DELETE FROM list_cache WHERE list_key LIKE :prefix || '%'") suspend fun deleteListsLike(prefix: String)
    @Query("DELETE FROM list_item WHERE list_key LIKE :prefix || '%'") suspend fun deleteItemsLike(prefix: String)
    @Query("DELETE FROM list_item WHERE server_id = :server") suspend fun deleteServerItems(server: String)
}

/** A list item with its instance. */
data class ListEntry(
    val listKey: String,
    val ord: Int,
    @androidx.room.Embedded val instance: WorkInstance,
)

@Dao
interface PersonDao {
    @Upsert suspend fun upsertPerson(p: Person)
    @Upsert suspend fun upsertAliases(rows: List<PersonAlias>)
    @Query("SELECT * FROM person WHERE person_key = :key") suspend fun person(key: String): Person?
    @Query("SELECT * FROM person WHERE person_key = :key") fun personFlow(key: String): Flow<Person?>
    @Query("SELECT * FROM person_alias WHERE person_key = :key") suspend fun aliases(key: String): List<PersonAlias>
    @Query("SELECT * FROM person_alias WHERE server_id = :server AND person_id = :id") suspend fun alias(server: String, id: String): PersonAlias?
    @Query("UPDATE person_alias SET person_key = :to WHERE person_key = :from") suspend fun moveAliases(from: String, to: String)
    @Query("UPDATE OR REPLACE work_person SET person_key = :to WHERE person_key = :from") suspend fun moveWorkPeople(from: String, to: String)
    @Query("DELETE FROM person WHERE person_key = :key") suspend fun deletePerson(key: String)
    @Query("UPDATE person SET accessed_at = :now WHERE person_key = :key") suspend fun touch(key: String, now: Long)

    @Query("DELETE FROM work_person WHERE work_key = :work") suspend fun clearWork(work: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertWorkPeople(rows: List<WorkPerson>)

    @Transaction
    suspend fun replaceWorkPeople(work: String, rows: List<WorkPerson>) {
        clearWork(work)
        insertWorkPeople(rows)
    }

    @Query(
        """SELECT wp.kind AS kind, wp.role AS role, wp.ord AS ord, p.* FROM work_person wp
           JOIN person p ON p.person_key = wp.person_key WHERE wp.work_key = :work ORDER BY wp.ord""",
    )
    fun castFlow(work: String): Flow<List<CastEntry>>

    @Query("SELECT * FROM work_person WHERE person_key = :key") suspend fun worksOf(key: String): List<WorkPerson>

    /** People not opened or listed for a week, and no longer in any cached cast (local-data spec). */
    @Query("DELETE FROM person WHERE accessed_at < :before AND person_key NOT IN (SELECT person_key FROM work_person)")
    suspend fun prune(before: Long): Int
    @Query("DELETE FROM person_alias WHERE person_key NOT IN (SELECT person_key FROM person)") suspend fun pruneAliases(): Int
    @Query("DELETE FROM person_alias WHERE server_id = :server") suspend fun deleteServerAliases(server: String)
}

/** A cast member of a work: the person and their part in it. */
data class CastEntry(
    val kind: String,
    val role: String?,
    val ord: Int,
    @androidx.room.Embedded val person: Person,
)

@Dao
interface VersionDao {
    @Upsert suspend fun upsert(rows: List<VersionSummary>)
    @Query("SELECT * FROM version_summary WHERE for_key = :key") suspend fun versions(key: String): List<VersionSummary>
    @Query("DELETE FROM version_summary WHERE for_key = :key AND server_id = :server") suspend fun delete(key: String, server: String)
    @Query("DELETE FROM version_summary WHERE server_id = :server") suspend fun deleteServer(server: String)
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorite") suspend fun favorites(): List<FavoriteRow>
    @Query("SELECT * FROM favorite_instance ORDER BY ord") suspend fun instances(): List<FavoriteInstanceRow>
    @Query("SELECT * FROM favorite_write ORDER BY ord") suspend fun writes(): List<FavoriteWriteRow>
    @Query("DELETE FROM favorite") suspend fun clearFavorites()
    @Query("DELETE FROM favorite_instance") suspend fun clearInstances()
    @Query("DELETE FROM favorite_write") suspend fun clearWrites()
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertFavorites(rows: List<FavoriteRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertInstances(rows: List<FavoriteInstanceRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertWrites(rows: List<FavoriteWriteRow>)

    /** The whole favourites state in one transaction (hundreds of rows at most). */
    @Transaction
    suspend fun replaceAll(favorites: List<FavoriteRow>, instances: List<FavoriteInstanceRow>, writes: List<FavoriteWriteRow>) {
        clearFavorites(); clearInstances(); clearWrites()
        insertFavorites(favorites); insertInstances(instances); insertWrites(writes)
    }
}

@Dao
interface WatchlistDao {
    @Query("SELECT * FROM watchlist ORDER BY added_at DESC") fun allFlow(): Flow<List<WatchlistRow>>
    @Query("SELECT * FROM watchlist") suspend fun all(): List<WatchlistRow>
    @Upsert suspend fun upsert(row: WatchlistRow)
    @Query("DELETE FROM watchlist WHERE work_key IN (:keys)") suspend fun delete(keys: List<String>)
}

@Dao
interface SourceDao {
    @Query("SELECT * FROM data_source ORDER BY feature, ord") suspend fun sources(): List<DataSourceRow>
    @Upsert suspend fun upsertSources(rows: List<DataSourceRow>)
    @Query("DELETE FROM data_source WHERE source = :source") suspend fun deleteSource(source: String)

    @Query("SELECT * FROM sync_state WHERE feature = :feature AND server_id = :server") suspend fun sync(feature: String, server: String): SyncState?
    @Query("SELECT * FROM sync_state WHERE feature = :feature") suspend fun syncs(feature: String): List<SyncState>
    @Upsert suspend fun upsertSync(row: SyncState)
    @Query("DELETE FROM sync_state WHERE server_id = :server") suspend fun deleteServerSync(server: String)

    @Upsert suspend fun upsertMarkers(rows: List<SegmentMarker>)
    @Query("SELECT * FROM segment_marker WHERE work_key = :key") suspend fun markers(key: String): List<SegmentMarker>
}
