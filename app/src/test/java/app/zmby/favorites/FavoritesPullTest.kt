package app.zmby.favorites

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.zmby.SessionStore
import app.zmby.aggregate.FanOut
import app.zmby.data.FavoriteSync
import app.zmby.data.Library
import app.zmby.data.MemoryCapsStore
import app.zmby.data.MemoryRegistryStore
import app.zmby.data.ServerConfig
import app.zmby.data.ServerLine
import app.zmby.data.ServerRegistry
import app.zmby.emby.ClientInfo
import app.zmby.emby.EmbyClient
import app.zmby.store.LocalStore
import app.zmby.store.ZmbyDatabase
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class FavoritesPullTest {
    private val server = MockWebServer()
    private lateinit var db: ZmbyDatabase
    private lateinit var store: LocalStore
    private lateinit var favorites: Favorites
    private val previous = FavoritesState(
        favorites = listOf(Favorite("item:a:301", "Movie 301", type = "Movie", addedAt = 1, updatedAt = 1,
            instances = listOf(FavoriteInstance("a", "301", true), FavoriteInstance("b", "other", true)))),
        lastPull = mapOf("a" to 10L),
    )

    @Before fun start() = runBlocking {
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val line = ServerLine("line", "test", server.url("/").toString().trimEnd('/'))
        val source = ServerConfig("a", "source", listOf(line), "line", "user", "test")
        // Leave the peer offline so the persisted write queue is observable after drain().
        val peer = source.copy(id = "b", enabled = false, favoriteSync = FavoriteSync.PushOnly)
        val registry = ServerRegistry(MemoryRegistryStore(listOf(source, peer)))
        val emby = EmbyClient(OkHttpClient(), ClientInfo(device = "test", deviceId = "d", version = "1"))
        db = Room.inMemoryDatabaseBuilder(context, ZmbyDatabase::class.java).build()
        store = LocalStore(db, emby.json)
        val library = Library(context, emby, registry, SessionStore(context), MemoryCapsStore(), store)
        favorites = Favorites(context, library, FanOut(registry), this, store, clock = { 20L })
        store.saveFavorites(previous)
        favorites.load()
    }

    @After fun stop() {
        db.close()
        server.shutdown()
    }

    private fun page(ids: IntRange, total: Int): MockResponse {
        val items = ids.joinToString(",") { """{"Id":"$it","Type":"Movie"}""" }
        return MockResponse().setBody("""{"Items":[$items],"TotalRecordCount":$total}""")
    }

    private suspend fun pull(vararg pages: MockResponse): FavoritesState {
        pages.forEach(server::enqueue)
        favorites.pull(minGapMs = 0)
        assertEquals(pages.size, server.requestCount)
        return requireNotNull(store.loadFavorites())
    }

    @Test fun `301st favourite on page two stays active`() = runBlocking {
        val state = pull(page(1..300, 301), page(301..301, 301))
        val offsets = List(2) {
            val url = server.takeRequest().requestUrl!!
            assertEquals("IsFavorite", url.queryParameter("Filters"))
            assertEquals("Movie,Series", url.queryParameter("IncludeItemTypes"))
            assertEquals("300", url.queryParameter("Limit"))
            url.queryParameter("StartIndex")
        }
        assertEquals(listOf("0", "300"), offsets)
        assertEquals(301, state.favorites.count { it.active })
        assertTrue(state.favorites.single { it.workKey == "item:a:301" }.active)
        assertTrue(state.queue.isEmpty())
        assertEquals(20L, state.lastPull["a"])
    }

    @Test fun `failure on page two keeps the previous state`() = runBlocking {
        assertEquals(previous, pull(page(1..300, 301), MockResponse().setResponseCode(404)))
    }

    @Test fun `empty second page before the reported end keeps the previous state`() = runBlocking {
        assertEquals(previous, pull(page(1..300, 301), page(IntRange.EMPTY, 301)))
    }

    @Test fun `repeated item cannot stand in for a missing favourite`() = runBlocking {
        assertEquals(previous, pull(page(1..300, 301), page(300..300, 301)))
    }

    @Test fun `shrinking total cannot confirm a removal`() = runBlocking {
        assertEquals(previous, pull(page(1..300, 301), page(IntRange.EMPTY, 300)))
    }

    @Test fun `omitted total cannot confirm an empty list`() = runBlocking {
        assertEquals(previous, pull(MockResponse().setBody("""{"Items":[]}""")))
    }

    @Test fun `omitted items cannot confirm an empty list`() = runBlocking {
        assertEquals(previous, pull(MockResponse().setBody("""{"TotalRecordCount":0}""")))
    }

    @Test fun `confirmed empty snapshot removes the synced favourite on its peer`() = runBlocking {
        val state = pull(page(IntRange.EMPTY, 0))
        assertEquals(20L, state.favorites.single().removedAt)
        assertEquals(listOf(PendingWrite("b", "other", false)), state.queue)
        assertEquals(20L, state.lastPull["a"])
    }
}
