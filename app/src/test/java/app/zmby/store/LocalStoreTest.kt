package app.zmby.store

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.zmby.emby.BaseItemDto
import app.zmby.emby.ImageInfo
import app.zmby.emby.PersonDto
import app.zmby.emby.UserItemData
import app.zmby.favorites.Favorite
import app.zmby.favorites.FavoriteInstance
import app.zmby.favorites.Favorites
import app.zmby.favorites.FavoritesState
import app.zmby.favorites.PendingWrite
import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class LocalStoreTest {
    private lateinit var db: ZmbyDatabase
    private lateinit var store: LocalStore
    private var now = 1_000_000L
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ZmbyDatabase::class.java).allowMainThreadQueries().build()
        store = LocalStore(db, json) { now }
    }

    @After fun close() = db.close()

    private fun movie(id: String, name: String, tmdb: String? = "27205", overview: String? = null) = BaseItemDto(
        id = id, name = name, type = "Movie", overview = overview, productionYear = 2010,
        providerIds = tmdb?.let { mapOf("Tmdb" to it) }.orEmpty(), imageTags = mapOf("Primary" to "p$id"),
    )

    @Test fun `items keep their work key and what an earlier detail fetch stored`() = runBlocking {
        store.putDetail("a", movie("1", "Inception").copy(people = listOf(PersonDto("p1", "Leonardo DiCaprio", "Cobb", "Actor"))))
        store.putItems("a", listOf(movie("1", "Inception")))
        val row = db.works().instance("a", "1")!!
        assertEquals("movie:tmdb:27205", row.workKey)
        assertNotNull("a list update must not drop the full item", row.detailJson)
        assertEquals(1, store.castFlow("movie:tmdb:27205").first().size)
    }

    @Test fun `a work first keyed by its item moves to the external key once scraped`() = runBlocking {
        store.putDetail("a", movie("1", "Some.Film.2025", tmdb = null))
        store.remerge("item:a:1", listOf("a"))
        assertNotNull(db.works().meta("item:a:1"))
        store.putItems("a", listOf(movie("1", "Some Film", tmdb = "777")))
        assertEquals("movie:tmdb:777", db.works().instance("a", "1")!!.workKey)
        assertNull("the item-only row is dropped and merged again under the work key", db.works().meta("item:a:1"))
    }

    @Test fun `two servers with the same TMDB merge into one row, Chinese first`() = runBlocking {
        store.putDetail("en", movie("1", "Inception", overview = "A thief"))
        store.putDetail("cn", movie("9", "盗梦空间", overview = "盗贼"))
        store.putImages("en", "1", listOf(ImageInfo("Primary", null, "p1", 2000, 3000)))
        store.putImages("cn", "9", listOf(ImageInfo("Primary", null, "p9", 500, 750)))
        val meta = store.remerge("movie:tmdb:27205", listOf("en", "cn"))!!
        assertEquals("盗梦空间", meta.title)
        assertEquals("盗贼", meta.overview)
        assertEquals("en", meta.primary!!.server)
        // Only metadata-source servers take part.
        assertEquals("Inception", store.remerge("movie:tmdb:27205", listOf("en"))!!.title)
    }

    @Test fun `an item without external IDs is never merged with another`() = runBlocking {
        store.putDetail("a", movie("1", "降临", tmdb = null))
        store.putDetail("b", movie("2", "降临", tmdb = null))
        assertEquals("item:a:1", db.works().instance("a", "1")!!.workKey)
        val meta = store.remerge("item:a:1", listOf("b", "a"))!!
        assertEquals("a", meta.titleServer)
    }

    @Test fun `lists come back in order and empty when never fetched`() = runBlocking {
        store.putList("latest:a", "a", listOf(movie("2", "B"), movie("1", "A")), total = 40)
        val lists = store.listsFlow(listOf("latest:a", "latest:b")).first()
        assertEquals(listOf("2", "1"), lists["latest:a"]!!.items.map { it.item.id })
        assertEquals(40, lists["latest:a"]!!.total)
        assertEquals(now, lists["latest:a"]!!.fetchedAt)
        assertEquals(0L, lists["latest:b"]!!.fetchedAt)
        // A newer answer replaces the list, it does not append.
        store.putList("latest:a", "a", listOf(movie("3", "C")))
        assertEquals(listOf("3"), store.list("latest:a")!!.items.map { it.item.id })
    }

    @Test fun `the player's report changes the cached item at once`() = runBlocking {
        store.putList("resume:a", "a", listOf(movie("1", "A").copy(userData = UserItemData(playbackPositionTicks = 10))))
        store.recordProgress("a", "1", 6_000_000_000, played = false)
        val item = store.list("resume:a")!!.items.single().item
        assertEquals(6_000_000_000, item.userData!!.playbackPositionTicks)
        assertNotNull(item.userData!!.lastPlayedDate)
    }

    @Test fun `LRU drops the oldest rows but keeps favourites, watchlist and resume`() = runBlocking {
        for (i in 1..6) { now = i.toLong(); store.putItems("a", listOf(movie("$i", "M$i", tmdb = "$i"))) }
        store.putList("resume:a", "a", listOf(movie("1", "M1", tmdb = "1"))).also { db.works().touchInstance("a", "1", 0) }
        store.saveFavorites(FavoritesState(listOf(Favorite("movie:tmdb:2", "M2", null, "Movie", 1, 1, instances = listOf(FavoriteInstance("a", "2"))))))
        db.watchlist().upsert(WatchlistRow("movie:tmdb:3", "M3", null, "Movie", addedAt = 1))
        store.trim(max = 4)
        val left = (1..6).filter { db.works().instance("a", "$it") != null }
        // Two over the cap: the oldest unreferenced ones (4 and 5) go; 1, 2 and 3 are referenced.
        assertEquals(listOf(1, 2, 3, 6), left)
    }

    @Test fun `deleting a server deletes its rows only`() = runBlocking {
        store.putList("latest:a", "a", listOf(movie("1", "A")))
        store.putList("latest:b", "b", listOf(movie("1", "A")))
        store.deleteServer("a")
        assertNull(db.works().instance("a", "1"))
        assertNotNull(db.works().instance("b", "1"))
        assertTrue(store.list("latest:a") == null)
        assertEquals(1, store.list("latest:b")!!.items.size)
    }

    @Test fun `favourites round-trip with tombstones, queue and last pulls`() = runBlocking {
        assertNull(store.loadFavorites())
        val state = FavoritesState(
            favorites = listOf(
                Favorite("movie:tmdb:1", "沙丘2", 2024, "Movie", 10, 20, null, listOf(FavoriteInstance("a", "1", synced = true), FavoriteInstance("b", "7")), "tag"),
                Favorite("movie:tmdb:2", "旧的", null, "Movie", 1, 5, removedAt = 5),
            ),
            queue = listOf(PendingWrite("b", "7", true, attempts = 2)),
            lastPull = mapOf("a" to 15L),
        )
        store.saveFavorites(state)
        val back = store.loadFavorites()!!
        assertEquals(state.favorites.sortedBy { it.workKey }, back.favorites.sortedBy { it.workKey })
        assertEquals(state.queue, back.queue)
        assertEquals(state.lastPull, back.lastPull)
    }

    @Test fun `the P2 favourites JSON still reads`() {
        val old = """{"favorites":[{"workKey":"movie:tmdb:1","title":"沙丘2","type":"Movie","addedAt":1,"updatedAt":1,"instances":[{"server":"a","itemId":"1","synced":true}]}],"queue":[],"lastPull":{"a":5}}"""
        val state = Favorites.decodeJson(old)!!
        assertEquals("沙丘2", state.favorites.single().title)
        assertEquals(5L, state.lastPull["a"])
        assertNull(Favorites.decodeJson("not json"))
    }

    @Test fun `a person page with a TMDB id makes one person of several servers`() = runBlocking {
        store.putDetail("a", movie("1", "Inception").copy(people = listOf(PersonDto("pa", "Leonardo DiCaprio", "Cobb", "Actor", "img"))))
        store.putDetail("b", movie("9", "盗梦空间").copy(people = listOf(PersonDto("pb", "莱昂纳多·迪卡普里奥", "柯布", "Actor"))))
        val k1 = store.mergePerson("a", BaseItemDto(id = "pa", name = "Leonardo DiCaprio", type = "Person", providerIds = mapOf("Tmdb" to "6193")))
        val k2 = store.mergePerson("b", BaseItemDto(id = "pb", name = "莱昂纳多·迪卡普里奥", type = "Person", providerIds = mapOf("Tmdb" to "6193"), premiereDate = "1974-11-11T00:00:00Z"))
        assertEquals("person:tmdb:6193", k1)
        assertEquals(k1, k2)
        val p = store.person(k1)!!
        assertEquals("莱昂纳多·迪卡普里奥", p.name)
        assertEquals("1974-11-11T00:00:00Z", p.birthDate)
        assertEquals(setOf("a", "b"), store.aliases(k1).map { it.serverId }.toSet())
        assertEquals(k1, store.castFlow("movie:tmdb:27205").first().single().person.personKey)
    }
}
