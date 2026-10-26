package app.zmby.catalog

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Provider responses only. Keys include endpoint and the full regional language preference. */
@Entity(tableName = "catalog_cache", indices = [Index("accessed_at")])
data class CatalogCache(
    @PrimaryKey val key: String,
    val json: String,
    val fetchedAt: Long,
    @ColumnInfo(name = "size_bytes", defaultValue = "0") val sizeBytes: Long = 0,
    @ColumnInfo(name = "accessed_at", defaultValue = "0") val accessedAt: Long = 0,
)

/** Storage contract also used by the network client's in-memory test double. */
interface CatalogStorage {
    suspend fun get(key: String): CatalogCache?
    suspend fun put(row: CatalogCache)
    suspend fun prune(before: Long)
}

data class CatalogCacheSize(val key: String, @ColumnInfo(name = "size_bytes") val bytes: Long)

@Dao
interface CatalogDao : CatalogStorage {
    @Query("SELECT * FROM catalog_cache WHERE `key` = :key") override suspend fun get(key: String): CatalogCache?
    @Insert(onConflict = OnConflictStrategy.REPLACE) override suspend fun put(row: CatalogCache)
    @Query("DELETE FROM catalog_cache WHERE fetchedAt < :before") override suspend fun prune(before: Long)
    @Query("UPDATE catalog_cache SET accessed_at = :now WHERE `key` = :key") suspend fun touch(key: String, now: Long)
    @Query("SELECT COALESCE(MAX(accessed_at), 0) FROM catalog_cache") suspend fun lastAccess(): Long
    @Query("SELECT COALESCE(SUM(size_bytes), 0) FROM catalog_cache") suspend fun sizeBytes(): Long
    @Query("SELECT COALESCE(SUM(size_bytes), 0) FROM catalog_cache") fun sizeFlow(): Flow<Long>
    @Query("SELECT `key`, size_bytes FROM catalog_cache ORDER BY accessed_at ASC, `key` ASC") suspend fun oldestFirst(): List<CatalogCacheSize>
    @Query("DELETE FROM catalog_cache WHERE `key` IN (:keys)") suspend fun delete(keys: List<String>)
    @Query("DELETE FROM catalog_cache WHERE `key` LIKE :pattern") suspend fun deleteLike(pattern: String): Int
}
