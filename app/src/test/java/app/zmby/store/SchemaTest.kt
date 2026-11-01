package app.zmby.store

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import android.content.Context
import kotlinx.coroutines.flow.first
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

    @Test fun `version 4 upgrade adds local mappings without deleting user lists or resources`() {
        helper.createDatabase("schema-v4-mappings.db", 4).use { db ->
            db.execSQL("INSERT INTO favorite (work_key, title, year, type, added_at, updated_at, removed_at, image_tag) VALUES ('series:tmdb:10', 'Keep', 2024, 'Series', 1, 1, NULL, NULL)")
            db.execSQL("INSERT INTO work_instance (server_id, item_id, work_key, type, item_json, played, position_ticks, server_favorite, fetched_at, detail_at, accessed_at) VALUES ('a', '1', 'series:tmdb:10', 'Series', '{}', 0, 0, 0, 1, 1, 1)")
        }
        helper.runMigrationsAndValidate("schema-v4-mappings.db", 5, true, ZmbyDatabase.MIGRATION_4_5).use { db ->
            db.query("SELECT title FROM favorite").use { it.moveToFirst(); assertEquals("Keep", it.getString(0)) }
            db.query("SELECT COUNT(*) FROM work_instance").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            db.execSQL("INSERT INTO episode_mapping (id, work_key, server_id, series_id, source_season, first_episode, last_episode, target_season, `offset`) VALUES ('rule', 'tmdb:tv:10', 'a', 'parent', 4, 1, 8, 3, 0)")
            db.query("SELECT source_season, target_season FROM episode_mapping").use { it.moveToFirst(); assertEquals(4, it.getInt(0)); assertEquals(3, it.getInt(1)) }
        }
    }

    @Test fun `version 5 upgrade adds hidden 在看 cards and keeps mappings and lists`() {
        helper.createDatabase("schema-v5-watching.db", 5).use { db ->
            db.execSQL("INSERT INTO favorite (work_key, title, year, type, added_at, updated_at, removed_at, image_tag) VALUES ('series:tmdb:10', 'Keep', 2024, 'Series', 1, 1, NULL, NULL)")
            db.execSQL("INSERT INTO episode_mapping (id, work_key, server_id, series_id, source_season, first_episode, last_episode, target_season, `offset`) VALUES ('rule', 'tmdb:tv:10', 'a', 'parent', 4, 1, 8, 3, 0)")
        }
        helper.runMigrationsAndValidate("schema-v5-watching.db", 6, true, ZmbyDatabase.MIGRATION_5_6).use { db ->
            db.query("SELECT title FROM favorite").use { it.moveToFirst(); assertEquals("Keep", it.getString(0)) }
            db.query("SELECT COUNT(*) FROM episode_mapping").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            db.execSQL("INSERT INTO watching_hidden (work_key, hidden_at) VALUES ('series:tmdb:10', 42)")
            db.query("SELECT hidden_at FROM watching_hidden").use { it.moveToFirst(); assertEquals(42L, it.getLong(0)) }
        }
    }

    @Test fun `version 6 upgrade adds playback sessions and keeps history, lists and hidden cards`() {
        helper.createDatabase("schema-v6-sessions.db", 6).use { db ->
            db.execSQL("INSERT INTO favorite (work_key, title, year, type, added_at, updated_at, removed_at, image_tag) VALUES ('series:tmdb:10', 'Keep', 2024, 'Series', 1, 1, NULL, NULL)")
            db.execSQL("INSERT INTO watchlist (work_key, title, year, type, premiere_date, added_at, instances, image_tag) VALUES ('movie:tmdb:2', '奥本海默', 2023, 'Movie', NULL, 2, 'a|2', NULL)")
            db.execSQL("INSERT INTO play_history (server_id, item_id, type, work_key, played_at, play_count, fetched_at) VALUES ('a', '1', 'Movie', 'movie:tmdb:1', 5, 1, 1)")
            db.execSQL("INSERT INTO watching_hidden (work_key, hidden_at) VALUES ('series:tmdb:10', 42)")
        }
        helper.runMigrationsAndValidate("schema-v6-sessions.db", 7, true, ZmbyDatabase.MIGRATION_6_7).use { db ->
            db.query("SELECT title FROM favorite").use { it.moveToFirst(); assertEquals("Keep", it.getString(0)) }
            db.query("SELECT COUNT(*) FROM watchlist").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
            db.query("SELECT played_at FROM play_history").use { it.moveToFirst(); assertEquals(5L, it.getLong(0)) }
            db.query("SELECT hidden_at FROM watching_hidden").use { it.moveToFirst(); assertEquals(42L, it.getLong(0)) }
            db.execSQL("INSERT INTO play_session (server_id, item_id, type, work_key, genres, started_at, ended_at, watched_ms, spans, start_position_ms, completed, open) VALUES ('a', '1', 'Movie', 'movie:tmdb:1', '', 10, 70, 60, '0+60', 0, 0, 1)")
            db.query("SELECT id, watched_ms, open FROM play_session").use { it.moveToFirst(); assertEquals(1L, it.getLong(0)); assertEquals(60L, it.getLong(1)); assertEquals(1, it.getInt(2)) }
        }
    }

    @Test fun `sessions record, checkpoint, finish and close what a crash left open`() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ZmbyDatabase::class.java).allowMainThreadQueries().build()
        runBlocking {
            val dao = db.playSessions()
            val done = dao.insert(PlaySessionRow(serverId = "a", itemId = "1", type = "Movie", workKey = "movie:tmdb:1", genres = listOf("科幻", "冒险"), startedAt = 1_000, endedAt = 1_000))
            dao.update(done, 61_000, 60_000, SessionSpans.encode(1_000, listOf(WatchSpan(1_000, 61_000))), 60_000, 7_200_000, completed = false, open = false)
            val crashed = dao.insert(PlaySessionRow(serverId = "b", itemId = "9", type = "Episode", workKey = "series:tmdb:5:s01:e01", seriesKey = "series:tmdb:5", startedAt = 100_000, endedAt = 100_000))
            dao.update(crashed, 130_000, 30_000, "0+30000", 30_000, null, completed = false, open = true)
            assertEquals(1, dao.closeOpen())
            val rows = dao.since(0).first()
            assertEquals(listOf(false, false), rows.map { it.open })
            assertEquals(listOf("科幻", "冒险"), rows.first().genres)
            assertEquals(listOf(WatchSpan(1_000, 61_000)), SessionSpans.decode(rows.first().startedAt, rows.first().spans))
            assertEquals(130_000L, rows.last().endedAt)
            assertEquals(listOf("b", "a"), dao.latestPerWork(10).first().map { it.serverId })
            assertEquals(1_000L, dao.firstFlow().first())
        }
        db.close()
    }

    private companion object { const val DB = "schema-test.db" }
}
