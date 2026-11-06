package app.zmby.store

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.zmby.aggregate.ResultStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Server answers kept in Room under the in-memory sharing of [app.zmby.aggregate.FanOut] (统一数据层
 * ①③): version lookups, a series' match, season list and episode pages, Similar. One row per kind,
 * request identity and server login; a process restart reads them back instead of asking again
 * within the same freshness (six hours for resources). Failures are never stored.
 */
@Entity(tableName = "repo_cache", primaryKeys = ["kind", "cache_key", "server_id"], indices = [Index("fetched_at"), Index("server_id")])
data class RepoCacheRow(
    val kind: String,
    @ColumnInfo(name = "cache_key") val key: String,
    @ColumnInfo(name = "server_id") val serverId: String,
    val json: String,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
)

@Dao
interface RepoCacheDao {
    @Query("SELECT * FROM repo_cache WHERE kind = :kind AND cache_key = :key AND server_id = :server") suspend fun get(kind: String, key: String, server: String): RepoCacheRow?
    @Query("SELECT * FROM repo_cache WHERE kind = :kind AND cache_key = :key AND server_id = :server") fun observe(kind: String, key: String, server: String): Flow<RepoCacheRow?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(row: RepoCacheRow)
    @Query("DELETE FROM repo_cache WHERE kind = :kind AND cache_key = :key AND server_id IN (:servers)") suspend fun delete(kind: String, key: String, servers: List<String>)
    @Query("DELETE FROM repo_cache WHERE kind IN (:kinds) AND server_id IN (:servers)") suspend fun deleteKinds(kinds: List<String>, servers: List<String>)
    @Query("DELETE FROM repo_cache WHERE server_id = :server") suspend fun deleteServer(server: String)
    @Query("DELETE FROM repo_cache WHERE fetched_at < :before") suspend fun prune(before: Long)
}

/** Freshness of a stored answer as a screen sees it (统一数据层 ②: observe). */
enum class CacheState { Fresh, Stale }

data class Snapshot(val json: String, val fetchedAt: Long, val state: CacheState)

/** [ResultStore] over [RepoCacheDao]; expired rows are pruned at startup. */
class RoomResultStore(private val dao: RepoCacheDao, private val clock: () -> Long = System::currentTimeMillis) : ResultStore {
    override suspend fun read(kind: String, key: String, server: String, maxAgeMs: Long): String? =
        dao.get(kind, key, server)?.takeIf { clock() - it.fetchedAt in 0 until maxAgeMs }?.json

    override suspend fun write(kind: String, key: String, server: String, json: String) =
        dao.put(RepoCacheRow(kind, key, server, json, clock()))

    override suspend fun drop(kind: String, key: String, servers: List<String>) = dao.delete(kind, key, servers)

    override suspend fun dropKinds(kinds: List<String>, servers: List<String>) = dao.deleteKinds(kinds, servers)

    /** A stored answer as it changes, with whether it is still within [maxAgeMs]. */
    fun observe(kind: String, key: String, server: String, maxAgeMs: Long): Flow<Snapshot?> = dao.observe(kind, key, server).map { row ->
        row?.let { Snapshot(it.json, it.fetchedAt, if (clock() - it.fetchedAt in 0 until maxAgeMs) CacheState.Fresh else CacheState.Stale) }
    }.distinctUntilChanged()

    suspend fun prune(maxAgeMs: Long) = dao.prune(clock() - maxAgeMs)
}
