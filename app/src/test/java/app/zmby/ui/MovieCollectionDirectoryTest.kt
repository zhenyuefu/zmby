package app.zmby.ui

import app.zmby.aggregate.FanOut
import app.zmby.aggregate.HealthBook
import app.zmby.data.ServerItem
import app.zmby.emby.*
import app.zmby.ui.screens.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.*
import org.junit.Test

class MovieCollectionDirectoryTest {
    private fun film(server: String, id: String, tmdb: String) =
        ServerItem(server, BaseItemDto(id, type = "Movie", providerIds = mapOf("Tmdb" to tmdb)))
    private fun box(server: String, tmdb: String?, vararg films: ServerItem) =
        MovieCollection(ServerItem(server, BaseItemDto("b", name = "同名合集", type = "BoxSet",
            providerIds = tmdb?.let { mapOf("Tmdb" to it) }.orEmpty())), films.toList())

    @Test fun `server collections use external identity and current film keeps its server address`() {
        val a = film("A", "same", "1")
        val b = film("B", "same", "2")
        val alternate = film("B", "alt", "1")
        val groups = mergeServerCollections(listOf(box("A", "7", a), box("B", "7", b, alternate),
            box("C", null, b), box("D", null, b)), setOf(alternate.key))
        assertEquals(3, groups.size)
        assertEquals(listOf("A", "B"), groups.first().sources)
        assertEquals(listOf(alternate, b), groups.first().films)
        assertEquals(alternate.key, groups.first().currentKey)
    }

    @Test fun `conflicting or placeholder member identities keep both original addresses`() {
        fun member(server: String, tmdb: String, imdb: String) = ServerItem(server,
            BaseItemDto("same", type = "Movie", providerIds = mapOf("Tmdb" to tmdb, "Imdb" to imdb)))
        for ((left, right) in listOf(member("A", "0", "") to member("B", "0", ""),
            member("A", "123", "tt1") to member("B", "123", "tt2"))) {
            val merged = mergeServerCollections(listOf(box("A", "7", left), box("B", "7", right)), setOf(right.key)).single()
            assertEquals(listOf(left, right), merged.films)
            assertEquals(right.key, merged.currentKey)
        }
    }

    @Test fun `catalog covers reordered equal and partial boxes while extra or unknown works keep a separate entry`() {
        val catalog = box("catalog", null, film("catalog", "first", "1"), film("catalog", "second", "2"))
        val a = film("A", "first", "1")
        val b = film("B", "first", "1")
        val missingId = ServerItem("D", BaseItemDto("first", name = "first", type = "Movie"))
        val equal = box("A", null, film("A", "second", "2"), a, a)
        val partial = box("B", null, b)
        val extra = box("C", null, film("C", "first", "1"), film("C", "other", "3"))
        val unknown = box("D", null, missingId)
        val mixed = box("E", null, ServerItem("E", BaseItemDto("show", type = "Series", providerIds = mapOf("Tmdb" to "1"))))
        val enriched = catalog.copy(matches = mapOf(catalog.films.first().key to listOf(a, b)))
        val result = distinctCollections(enriched, listOf(equal, partial, extra, unknown, mixed))
        assertEquals(listOf(catalog.key, extra.key, unknown.key, mixed.key), result.map { it.key })
        assertTrue(result.first().represents(partial.key))
        assertTrue(result.first().represents(equal.key))
        assertEquals(listOf(a, b), result.first().available(catalog.films.first()))
    }

    @Test fun `identical custom rosters merge across servers with or without TMDB and preserve navigation`() {
        val first = box("A", null, film("A", "2", "2"), film("A", "3", "3"))
        val second = box("B", "99", film("B", "extra", "3"), film("B", "another", "2"))
        val different = box("C", null, film("C", "2", "2"), film("C", "4", "4"))
        for (catalog in listOf(null, box("catalog", null, film("catalog", "2", "2")))) {
            val custom = distinctCollections(catalog, listOf(first, second, different)).filterNot { it.catalog }
            assertEquals(listOf(first.key, different.key), custom.map { it.key })
            assertEquals(listOf("A", "B"), custom.first().sources)
            assertTrue(custom.first().represents(second.key))
            assertEquals(first.films, custom.first().films)
        }
    }

    @Test fun `confirmed copies resolve missing TMDB ids but conflicting ids or incomplete pages stay visible`() {
        val canonicalFilm = film("catalog", "c", "1")
        val confirmed = ServerItem("A", BaseItemDto("current", type = "Movie", providerIds = mapOf("Imdb" to "tt11")))
        val catalog = box("catalog", null, canonicalFilm).copy(matches = mapOf(canonicalFilm.key to listOf(confirmed)))
        val alias = box("B", null, confirmed.copy(server = "B"))
        val conflict = box("C", null, ServerItem("C", confirmed.item.copy(providerIds = mapOf("Imdb" to "tt11", "Tmdb" to "2"))))
        val partialPage = serverCollection(alias.box.copy(server = "D"),
            app.zmby.store.CachedList("test", alias.films, total = 2, fetchedAt = 1))
        assertEquals(listOf(catalog.key, conflict.key, partialPage.key),
            distinctCollections(catalog, listOf(alias, conflict, partialPage)).map { it.key })
    }

    @Test fun `unrelated and truncated results cannot prove missing members`() {
        val right = film("A", "x", "1")
        val wrong = film("A", "y", "9")
        val wanted = setOf("1", "2")
        val mixed = collectionMatches("A", wanted, ItemsResult(listOf(right.item, wrong.item), 2))
        assertEquals(listOf(right), mixed.items)
        assertFalse(mixed.complete)
        assertFalse(collectionMatches("A", wanted, ItemsResult(listOf(right.item), 3)).complete)
        assertTrue(collectionMatches("A", wanted, ItemsResult()).complete)
        val placeholder = collectionMatches("A", wanted, ItemsResult(listOf(right.item.copy(locationType = "Virtual")), 1))
        assertTrue(placeholder.complete)
        assertTrue(placeholder.items.isEmpty())
    }

    @Test fun `two servers use one HTTP request per batch and fast results appear while slow is pending`() = runBlocking {
        val health = object : HealthBook {
            override fun isCooling(serverId: String) = false
            override fun onSuccess(serverId: String) {}
            override fun onFailure(serverId: String, error: Throwable, timedOut: Boolean) {}
        }
        val fan = FanOut(health)
        val series = box("catalog", null, *(1..4).map { film("catalog", "movie:$it", "$it") }.toTypedArray())
        MockWebServer().use { http ->
            val api = EmbyClient(OkHttpClient(), ClientInfo(device = "TV", deviceId = "test", version = "1"))
            val release = CompletableDeferred<Unit>()
            val partial = CompletableDeferred<MovieCollection>()
            fun read() = collectionResources(series, listOf("A", "B"), fan) { server, ids ->
                if (server == "B") release.await()
                api.byProviderIds(ServerSession(http.url("/emby").toString(), server, "mock"), "Movie", ids.map { "tmdb.$it" })
            }
            fun response(vararg ids: Int) = MockResponse().setBody("""{"Items":[${ids.joinToString(",") {
                """{"Id":"file$it","Type":"Movie","ProviderIds":{"Tmdb":"$it"}}"""
            }}],"TotalRecordCount":${ids.size}}""")
            http.enqueue(response(1, 2))
            http.enqueue(response(2, 3))
            val first = async { read().onEach { if (it.foundCount == 2) partial.complete(it) }.last() }
            val second = async { read().last() }
            try {
                val fast = withTimeout(5000) { partial.await() }
                assertEquals("待确认", fast.status(series.films.last()))
                assertEquals(1, http.requestCount)
            } finally { release.complete(Unit) }
            val full = first.await()
            assertEquals(3, full.foundCount)
            assertEquals(listOf("A", "B"), full.available(series.films[1]).map { it.server })
            assertEquals("暂无片源", full.status(series.films.last()))
            assertEquals(full, second.await())
            assertEquals(full, read().last())
            assertEquals(2, http.requestCount)
            repeat(2) { assertEquals("tmdb.1,tmdb.2,tmdb.3,tmdb.4", http.takeRequest().requestUrl!!.queryParameter("AnyProviderIdEquals")) }
        }
    }
}
