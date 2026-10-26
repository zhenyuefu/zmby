package app.zmby.catalog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.zmby.store.ZmbyDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class LimitedCatalogCacheTest {
    private lateinit var db: ZmbyDatabase
    private lateinit var cache: LimitedCatalogCache
    private var limit = 100L
    private var now = 1000L

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ZmbyDatabase::class.java).allowMainThreadQueries().build()
        cache = LimitedCatalogCache(db, { limit }, { now })
    }
    @After fun close() = db.close()
    private suspend fun put(key: String, payload: String = "x".repeat(9)) { cache.put(CatalogCache(key, payload, now++)) }

    @Test fun `writes count UTF-8 bytes and replacement does not double count`() = runBlocking {
        put("中", "文")
        assertEquals(6L, db.catalog().sizeBytes())
        put("中", "你好")
        assertEquals(9L, db.catalog().sizeBytes())
        assertEquals(9L, cache.size.first())
    }
    @Test fun `read protects an older response ahead of a newer unused one`() = runBlocking {
        limit = 20
        put("a"); put("b")
        assertNotNull(cache.get("a")); now++
        put("c")
        assertNotNull(db.catalog().get("a")); assertNull(db.catalog().get("b")); assertNotNull(db.catalog().get("c"))
        assertEquals(20L, db.catalog().sizeBytes())
    }
    @Test fun `reducing the limit immediately trims existing entries without another write`() = runBlocking {
        put("a"); put("b"); put("c")
        limit = 10
        cache.trimToLimit()
        assertEquals(10L, cache.size.first())
        assertNull(db.catalog().get("a")); assertNull(db.catalog().get("b")); assertNotNull(db.catalog().get("c"))
    }
    @Test fun `oversized response is not cached and does not discard other entries`() = runBlocking {
        limit = 20
        put("a"); put("b")
        put("too-large", "x".repeat(50))
        assertEquals(20L, db.catalog().sizeBytes())
        assertNotNull(db.catalog().get("a")); assertNotNull(db.catalog().get("b")); assertNull(db.catalog().get("too-large"))
    }
    @Test fun `concurrent writers cannot grow the cache beyond its quota`() = runBlocking {
        limit = 35
        coroutineScope { (1..20).map { n -> async(Dispatchers.Default) { cache.put(CatalogCache("key$n", "x".repeat(12), 1)) } }.awaitAll() }
        assertTrue(db.catalog().sizeBytes() <= limit)
        assertTrue(db.catalog().sizeBytes() > 0)
    }
    @Test fun `startup trim and expiration preserve favourites watchlist and play history`() = runBlocking {
        val sql = db.openHelper.writableDatabase
        sql.execSQL("INSERT INTO favorite (work_key, title, year, type, added_at, updated_at, removed_at, image_tag) VALUES ('movie:tmdb:1', 'Keep', 2024, 'Movie', 1, 1, NULL, NULL)")
        sql.execSQL("INSERT INTO watchlist (work_key, title, year, type, premiere_date, added_at, instances, image_tag) VALUES ('movie:tmdb:2', 'Watch', 2023, 'Movie', NULL, 2, 'a|2', NULL)")
        db.playHistory().upsert(listOf(app.zmby.store.PlayHistoryRow("a", "1", "Movie", "movie:tmdb:1", playedAt = 5)))
        put("a"); put("b")
        limit = 0
        cache.prune(0) // Startup also enforces the quota even when no entry is expired.
        assertEquals(0L, db.catalog().sizeBytes())
        assertEquals("Keep", db.favorites().favorites().single().title)
        assertEquals("Watch", db.watchlist().all().single().title)
        assertEquals(5L, db.playHistory().newest("a"))
    }
    @Test fun `rows over the per-row cap are not stored and legacy bangumi-data rows are dropped`() = runBlocking {
        limit = 64L * 1024 * 1024
        put("big", "x".repeat(LimitedCatalogCache.MAX_ROW_BYTES.toInt() + 1))
        assertNull(db.catalog().get("big"))
        put("small")
        assertNotNull(cache.get("small"))
        db.catalog().put(CatalogCache("https://cdn.jsdelivr.net/npm/bangumi-data@0.3/dist/data.json", "[]", now))
        assertEquals(1, cache.dropLegacyRows())
        assertNotNull(cache.get("small"))
    }
    @Test fun `a row too large for the cursor window reads as a miss and is removed`() = runBlocking {
        limit = 64L * 1024 * 1024
        // Written the way the old client stored bangumi-data: one multi-megabyte row.
        db.catalog().put(CatalogCache("huge", "x".repeat(3 * 1024 * 1024), now))
        // Reproduces the review finding with real SQLite: the plain DAO read throws.
        assertTrue(runCatching { db.catalog().get("huge") }.exceptionOrNull() is android.database.sqlite.SQLiteBlobTooBigException)
        assertNull(cache.get("huge"))
        assertNull(runCatching { db.catalog().get("huge") }.getOrNull())
    }
}
