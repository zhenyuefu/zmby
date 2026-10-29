package app.zmby.store

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class EpisodeMappingsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun rule(id: String = "one", first: Int = 1, last: Int = 5) = EpisodeMappingRule(id, "tmdb:tv:10", "a", "parent", 4, first, last, 3)

    @Test fun `rules survive reopening and deleting them restores the automatic state`() = runBlocking {
        val name = "episode-mapping-persistence.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, ZmbyDatabase::class.java, name).allowMainThreadQueries().build()
        var db = open()
        EpisodeMappings(db).save(rule())
        db.close(); db = open()
        try {
            val mappings = EpisodeMappings(db)
            assertEquals(listOf(rule()), mappings.observe("tmdb:tv:10").first())
            mappings.save(rule().copy(targetSeason = 2))
            assertEquals(2, mappings.observe("tmdb:tv:10").first().single().targetSeason)
            mappings.delete("one")
            assertTrue(mappings.observe("tmdb:tv:10").first().isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun `range validation is atomic and independent servers and parents may use the same numbering`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ZmbyDatabase::class.java).allowMainThreadQueries().build()
        try {
            val mappings = EpisodeMappings(db)
            mappings.save(rule())
            try { mappings.save(rule("overlap", 5, 8)); fail("Expected overlap rejection") } catch (_: IllegalArgumentException) { }
            assertEquals(1, mappings.observe("tmdb:tv:10").first().size)
            mappings.save(rule("second", 6, 10).copy(offset = -5))
            mappings.save(rule("other-server").copy(serverId = "b"))
            mappings.save(rule("other-parent").copy(seriesId = "second-parent"))
            assertEquals(4, mappings.observe("tmdb:tv:10").first().size)
            db.episodeMappings().deleteServer("a")
            assertEquals("b", mappings.observe("tmdb:tv:10").first().single().serverId)
        } finally { db.close() }
    }

    @Test fun `a new one-file override replaces only that file override`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ZmbyDatabase::class.java).allowMainThreadQueries().build()
        try {
            val mappings = EpisodeMappings(db)
            mappings.save(rule())
            mappings.save(rule("override").copy(itemId = "file", targetEpisodeId = "301"))
            mappings.save(rule("replacement").copy(itemId = "file", targetEpisodeId = "302"))
            val rows = mappings.observe("tmdb:tv:10").first()
            assertEquals(2, rows.size)
            assertEquals("302", rows.single { it.itemId == "file" }.targetEpisodeId)
        } finally { db.close() }
    }
}
