package app.zmby.catalog

import androidx.room.withTransaction
import app.zmby.store.ZmbyDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

/**
 * Serializes each write and eviction in one Room transaction; user-owned tables are untouched. A row
 * stays well below Android's cursor window (about 2 MB): larger replies are shown but not stored, and
 * a row that cannot be read is dropped and treated as a miss.
 */
class LimitedCatalogCache(private val db: ZmbyDatabase, private val limitBytes: () -> Long,
    private val clock: () -> Long = System::currentTimeMillis) : CatalogStorage {
    private val dao = db.catalog()
    val size = dao.sizeFlow().distinctUntilChanged()

    override suspend fun get(key: String): CatalogCache? = withContext(Dispatchers.IO) {
        try {
            db.withTransaction { dao.get(key)?.also { dao.touch(key, nextAccess()) } }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            app.zmby.log.ZLog.w(TAG, "dropping an unreadable cache row: ${e.javaClass.simpleName}")
            runCatching { dao.delete(listOf(key)) }
            null
        }
    }

    /** bangumi-data used to be cached here whole (about 8 MB, unreadable); its index now lives in a file. */
    suspend fun dropLegacyRows(): Int = withContext(Dispatchers.IO) { dao.deleteLike("%bangumi-data%") }

    override suspend fun put(row: CatalogCache) = withContext(Dispatchers.IO) {
        val bytes = row.key.toByteArray(Charsets.UTF_8).size.toLong() + row.json.toByteArray(Charsets.UTF_8).size
        db.withTransaction {
            val limit = limitBytes().coerceAtLeast(0)
            if (bytes <= limit && bytes <= MAX_ROW_BYTES) dao.put(row.copy(sizeBytes = bytes, accessedAt = nextAccess()))
            else dao.delete(listOf(row.key)) // A single oversized reply must not displace the whole cache.
            trim(limit)
        }
    }

    override suspend fun prune(before: Long) = withContext(Dispatchers.IO) {
        db.withTransaction { dao.prune(before); trim(limitBytes().coerceAtLeast(0)) }
    }

    /** Used on preference changes as well as startup; no further network write is required. */
    suspend fun trimToLimit() = withContext(Dispatchers.IO) {
        db.withTransaction { trim(limitBytes().coerceAtLeast(0)) }
    }

    // Multiple accesses can share a millisecond; preserve their actual order across restarts too.
    private suspend fun nextAccess(): Long = maxOf(clock(), dao.lastAccess() + 1)

    private suspend fun trim(limit: Long) {
        var excess = dao.sizeBytes() - limit
        if (excess <= 0) return
        val drop = mutableListOf<String>()
        for (row in dao.oldestFirst()) {
            drop += row.key
            excess -= row.bytes
            if (excess <= 0) break
        }
        drop.chunked(500).forEach { dao.delete(it) }
    }

    companion object {
        private const val TAG = "Catalog"
        /** Largest stored row; Android's default cursor window is 2 MB and a row must fit whole. */
        const val MAX_ROW_BYTES = 1024 * 1024L
    }
}
