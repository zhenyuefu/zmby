package app.zmby.store

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The exported schemas (app/schemas) against the code (local-data spec: schema 导出与迁移测试):
 * the oldest version opens, every migration in [ZmbyDatabase.MIGRATIONS] runs to the current one,
 * and Room validates the result. Rows written before a migration must survive it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SchemaTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), ZmbyDatabase::class.java)

    @Test fun `version 1 opens and migrates to the current schema keeping favourites and the watchlist`() {
        helper.createDatabase(DB, 1).use { db ->
            db.execSQL("INSERT INTO favorite (work_key, title, year, type, added_at, updated_at, removed_at, image_tag) VALUES ('movie:tmdb:1', '沙丘2', 2024, 'Movie', 1, 1, NULL, NULL)")
            db.execSQL("INSERT INTO watchlist (work_key, title, year, type, premiere_date, added_at, instances, image_tag) VALUES ('movie:tmdb:2', '奥本海默', 2023, 'Movie', NULL, 2, 'a|2', NULL)")
        }
        val current = Room.databaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ZmbyDatabase::class.java, DB)
            .addMigrations(*ZmbyDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            assertEquals("沙丘2", current.favorites().favorites().single().title)
            assertEquals(listOf("a|2"), current.watchlist().all().single().instances)
            // Version 2: 观影统计's table exists and takes rows.
            current.playHistory().upsert(listOf(PlayHistoryRow("a", "1", "Movie", "movie:tmdb:1", playedAt = 5)))
            assertEquals(5L, current.playHistory().newest("a"))
            current.catalog().put(app.zmby.catalog.CatalogCache("view:tmdb:tv:10:zh-CN", "{}", 1))
            assertEquals("{}", current.catalog().get("view:tmdb:tv:10:zh-CN")?.json)
        }
        current.close()
    }

    @Test fun `version 2 upgrade clears legacy metadata but keeps server resources`() {
        helper.createDatabase("schema-v2.db", 2).use { db ->
            db.execSQL("INSERT INTO person (person_key, name, fetched_at, detail_at, accessed_at) VALUES ('old', 'Old', 1, 1, 1)")
            db.execSQL("INSERT INTO work_instance (server_id, item_id, work_key, type, item_json, played, position_ticks, server_favorite, fetched_at, detail_at, accessed_at) VALUES ('a', '1', 'movie:tmdb:1', 'Movie', '{}', 0, 0, 0, 1, 1, 1)")
        }
        helper.runMigrationsAndValidate("schema-v2.db", 3, true, ZmbyDatabase.MIGRATION_2_3).use { db ->
            db.query("SELECT COUNT(*) FROM person").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            db.query("SELECT COUNT(*) FROM work_instance").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        }
    }

    @Test fun `version 3 upgrade preserves metadata and initializes byte counts and LRU timestamps`() {
        helper.createDatabase("schema-v3-cache.db", 3).use { db ->
            db.execSQL("INSERT INTO catalog_cache (`key`, json, fetchedAt) VALUES ('中', '你好', 42)")
            db.execSQL("INSERT INTO favorite (work_key, title, year, type, added_at, updated_at, removed_at, image_tag) VALUES ('movie:tmdb:1', 'Keep', 2024, 'Movie', 1, 1, NULL, NULL)")
        }
        helper.runMigrationsAndValidate("schema-v3-cache.db", 4, true, ZmbyDatabase.MIGRATION_3_4).use { db ->
            db.query("SELECT json, size_bytes, accessed_at FROM catalog_cache").use {
                it.moveToFirst(); assertEquals("你好", it.getString(0)); assertEquals(9L, it.getLong(1)); assertEquals(42L, it.getLong(2))
            }
            db.query("SELECT title FROM favorite").use { it.moveToFirst(); assertEquals("Keep", it.getString(0)) }
        }
    }

    private companion object { const val DB = "schema-test.db" }
}
