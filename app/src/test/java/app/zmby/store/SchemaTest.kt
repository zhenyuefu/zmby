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
        }
        current.close()
    }

    private companion object { const val DB = "schema-test.db" }
}
