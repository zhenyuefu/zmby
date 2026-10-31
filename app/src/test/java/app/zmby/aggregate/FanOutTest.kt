package app.zmby.aggregate

import app.zmby.emby.BaseItemDto
import app.zmby.emby.EmbyException
import app.zmby.emby.ItemsResult
import app.zmby.emby.MediaSourceInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class FanOutTest {
    @Test fun `memory peek uses the same server scope expiry and invalidation as shared lookups`() = runBlocking {
        var now = 0L
        var scope = "original"
        val fan = FanOut(Book(FailureTracker()), clock = { now }, cacheContext = { scope })
        fun peek(context: String = "file-a") = fan.cached<String>(listOf("a"), 1000, "episode", 500) { context }
        assertTrue(peek().isEmpty())
        fan.run(listOf("a"), 1000, share = "episode", cacheMs = 500, context = { "file-a" }) { "versions" }.toList()
        assertEquals("versions", peek()["a"]?.value)
        assertTrue(peek("file-b").isEmpty())
        scope = "changed-login"
        assertTrue(peek().isEmpty())
        scope = "original"
        now = 500
        assertTrue(peek().isEmpty())
        now = 0
        fan.run(listOf("a"), 1000, share = "episode", cacheMs = 500, context = { "file-a" }) { "new" }.toList()
        fan.invalidate("episode", listOf("a"))
        assertTrue(peek().isEmpty())
    }
    private class Book(val tracker: FailureTracker) : HealthBook {
        override fun isCooling(serverId: String) = tracker.isCooling(serverId)
        override fun onSuccess(serverId: String) = tracker.success(serverId)
        override fun onFailure(serverId: String, error: Throwable, timedOut: Boolean) { tracker.failure(serverId, error.message.orEmpty(), timedOut) }
    }

    @Test fun `never more than the limit in flight`() = runTest {
        val now = AtomicInteger()
        var peak = 0
        val fan = FanOut(Book(FailureTracker({ testScheduler.currentTime })), maxConcurrent = 8)
        val out = fan.all((1..20).map { "s$it" }, 20_000) {
            val n = now.incrementAndGet()
            synchronized(this@FanOutTest) { peak = maxOf(peak, n) }
            delay(100)
            now.decrementAndGet()
            it
        }
        assertEquals(20, out.filterIsInstance<Outcome.Ok<String>>().size)
        assertTrue("peak $peak", peak <= 8)
    }

    @Test fun `a slow server times out without holding the others, failures are reported apart`() = runTest {
        val fan = FanOut(Book(FailureTracker({ testScheduler.currentTime })))
        val order = fan.run(listOf("fast", "slow", "broken"), 20_000) { s ->
            when (s) {
                "slow" -> { delay(60_000); "late" }
                "broken" -> throw EmbyException("HTTP 500", 500)
                else -> "ok"
            }
        }.toList()
        assertTrue(order.single { it.server == "fast" } is Outcome.Ok)
        assertTrue(order.single { it.server == "slow" }.let { it is Outcome.Failed && it.timedOut })
        assertTrue(order.single { it.server == "broken" }.let { it is Outcome.Failed && !it.timedOut })
    }

    @Test fun `two failures in a row briefly cool a server down then it is asked again`() = runTest {
        var clock = 0L
        val book = Book(FailureTracker({ clock }))
        val fan = FanOut(book, clock = { clock })
        repeat(2) { fan.all(listOf("x"), 1_000) { throw RuntimeException("down") } }
        assertTrue(fan.all(listOf("x"), 1_000) { "never" }.single() is Outcome.Skipped)
        clock += FailureTracker.COOL_MS + 1
        assertTrue(fan.all(listOf("x"), 1_000) { "back" }.single() is Outcome.Ok)
    }

    @Test fun `identical requests in flight are shared`() = runBlocking {
        val calls = AtomicInteger()
        val fan = FanOut(Book(FailureTracker()))
        val slowCall: suspend (String) -> String = { calls.incrementAndGet(); delay(200); "r" }
        val a = async { fan.all(listOf("s1", "s2"), 5_000, share = "search:星际", call = slowCall) }
        val b = async { fan.all(listOf("s1", "s2"), 5_000, share = "search:星际", call = slowCall) }
        assertEquals(2, a.await().size)
        assertEquals(2, b.await().size)
        assertEquals(2, calls.get())
    }
}

/** A fake multi-server Emby for the search and version tests. */
private class FakeServers : SearchSource, VersionSource {
    val items = HashMap<String, MutableList<BaseItemDto>>()
    /** Servers that ignore AnyProviderIdEquals and return their whole library. */
    val ignoresIds = HashSet<String>()
    val requests = mutableListOf<String>()

    fun add(server: String, vararg i: BaseItemDto) { items.getOrPut(server) { mutableListOf() } += i }

    override suspend fun search(server: String, term: String) =
        items[server].orEmpty().filter { (it.type == "Movie" || it.type == "Series") && it.name.orEmpty().contains(term) }.also { requests += "search $server $term" }

    override suspend fun byProviderIds(server: String, type: String, ids: List<String>): List<BaseItemDto> {
        requests += "ids $server $type ${ids.joinToString(",")}"
        val all = items[server].orEmpty()
        if (server in ignoresIds) return all
        return all.filter { it.type == type && ids.any { id -> val (k, v) = id.split('.', limit = 2); it.providerId(k) == v } }
    }

    override suspend fun searchType(server: String, term: String, type: String, limit: Int) =
        items[server].orEmpty().filter { it.type == type && it.name.orEmpty().contains(term) }.take(limit).also { requests += "name $server $type $term" }

    override suspend fun item(server: String, id: String) = items[server].orEmpty().first { it.id == id }
    override suspend fun seasons(server: String, seriesId: String) = items[server].orEmpty().filter { it.type == "Season" && it.seriesId == seriesId }

    override suspend fun episodePage(server: String, seriesId: String, seasonId: String, start: Int, limit: Int): ItemsResult {
        requests += "episodes $server $start"
        val eps = items[server].orEmpty().filter { it.type == "Episode" && it.seriesId == seriesId && it.seasonId == seasonId }.sortedBy { it.indexNumber }
        return ItemsResult(eps.drop(start).take(limit), eps.size)
    }

    override suspend fun sources(server: String, itemId: String) = listOf(MediaSourceInfo(id = "$itemId-src"))
}

class AggregateSearchTest {
    private fun movie(id: String, name: String, tmdb: String? = null) =
        BaseItemDto(id = id, name = name, type = "Movie", providerIds = tmdb?.let { mapOf("Tmdb" to it) }.orEmpty())

    @Test fun `step two finds translated titles by tmdb and filters servers that ignore the filter`() = runBlocking {
        val fake = FakeServers()
        fake.add("a", movie("a1", "星际穿越", "157336"))
        fake.add("b", movie("b1", "星际效应", "157336"), movie("b2", "无关的片", "999"))
        fake.add("c", movie("c1", "Interstellar", "157336"), movie("c2", "整库里的别的片", "123"))
        fake.ignoresIds += "c"
        val search = AggregateSearch(fake, FanOut(object : HealthBook {
            override fun isCooling(serverId: String) = false
            override fun onSuccess(serverId: String) {}
            override fun onFailure(serverId: String, error: Throwable, timedOut: Boolean) {}
        }))
        val last = search.search(listOf("a" to 0, "b" to 1, "c" to 2), "星际穿越").toList().last()
        assertTrue(last.done)
        val ids = last.hits.map { it.item.id }.toSet()
        assertEquals(setOf("a1", "b1", "c1"), ids)
        val merged = Merger.merge(last.hits)
        assertEquals(1, merged.works.size)
        assertEquals(3, merged.works.single().servers)
        assertTrue(fake.requests.any { it.startsWith("ids b Movie tmdb.157336") })
    }

    @Test fun `a server that fails is marked failed, not empty`() = runBlocking {
        val fake = object : SearchSource {
            override suspend fun search(server: String, term: String): List<BaseItemDto> = if (server == "down") throw EmbyException("HTTP 502", 502) else emptyList()
            override suspend fun byProviderIds(server: String, type: String, ids: List<String>) = emptyList<BaseItemDto>()
        }
        val search = AggregateSearch(fake, FanOut(object : HealthBook {
            override fun isCooling(serverId: String) = false
            override fun onSuccess(serverId: String) {}
            override fun onFailure(serverId: String, error: Throwable, timedOut: Boolean) {}
        }))
        val last = search.search(listOf("up" to 0, "down" to 1), "x").toList().last()
        assertEquals(SearchState.Done, last.states["up"])
        assertEquals(SearchState.Failed, last.states["down"])
    }
}

class VersionFinderTest {
    private val healthy = object : HealthBook {
        override fun isCooling(serverId: String) = false
        override fun onSuccess(serverId: String) {}
        override fun onFailure(serverId: String, error: Throwable, timedOut: Boolean) {}
    }

    @Test fun `film found by tmdb on a server that ignores the filter`() = runBlocking {
        val fake = FakeServers()
        fake.add("b", BaseItemDto(id = "x", name = "整库里的别的片", type = "Movie", providerIds = mapOf("Tmdb" to "1")), BaseItemDto(id = "y", name = "沙丘：第二部", type = "Movie", providerIds = mapOf("TMDB" to "693134")))
        fake.ignoresIds += "b"
        val r = VersionFinder(fake, FanOut(healthy)).findMovie("b", BaseItemDto(id = "a", name = "沙丘2", type = "Movie", providerIds = mapOf("Tmdb" to "693134")))
        assertTrue(r is ServerVersions.Found)
        r as ServerVersions.Found
        assertEquals("y", r.item.id)
        assertEquals("TMDB 一致", r.verdict.reason)
    }

    @Test fun `two items with the same id on one server are not guessed between`() = runBlocking {
        val fake = FakeServers()
        fake.add("b", BaseItemDto(id = "x", name = "沙丘", type = "Movie", providerIds = mapOf("Tmdb" to "1")), BaseItemDto(id = "y", name = "沙丘", type = "Movie", providerIds = mapOf("Tmdb" to "1")))
        val r = VersionFinder(fake, FanOut(healthy)).findMovie("b", BaseItemDto(id = "a", name = "沙丘", type = "Movie", providerIds = mapOf("Tmdb" to "1")))
        assertTrue(r is ServerVersions.NotFound)
    }

    @Test fun `episode two-step lookup pages past episode 200 and reports a missing one`() = runBlocking {
        val fake = FakeServers()
        fake.add("b", BaseItemDto(id = "S", name = "长篇番剧", type = "Series", providerIds = mapOf("Tmdb" to "77")))
        fake.add("b", BaseItemDto(id = "S1", name = "第 1 季", type = "Season", seriesId = "S", indexNumber = 1))
        for (e in 1..230) fake.add("b", BaseItemDto(id = "e$e", name = "第 $e 话", type = "Episode", seriesId = "S", seasonId = "S1", parentIndexNumber = 1, indexNumber = e))
        val refSeries = BaseItemDto(id = "R", name = "长篇番剧", type = "Series", providerIds = mapOf("tmdb" to "77"))
        val finder = VersionFinder(fake, FanOut(healthy))
        val found = finder.findEpisode("b", BaseItemDto(id = "r205", name = "第 205 话", type = "Episode", seriesId = "R", parentIndexNumber = 1, indexNumber = 205), refSeries)
        assertTrue(found is ServerVersions.Found && found.item.id == "e205" && found.verdict.confidence == Confidence.Strong)
        assertEquals(listOf("episodes b 0", "episodes b 100", "episodes b 200"), fake.requests.filter { it.startsWith("episodes") })
        val missing = finder.findEpisode("b", BaseItemDto(id = "r", type = "Episode", parentIndexNumber = 2, indexNumber = 1), refSeries)
        assertTrue(missing is ServerVersions.Missing)
    }

    @Test fun `series found by name among at most three namesakes, never by episode title`() = runBlocking {
        val fake = FakeServers()
        fake.add("b", BaseItemDto(id = "S", name = "某剧", type = "Series", productionYear = 2020))
        fake.add("b", BaseItemDto(id = "S1", type = "Season", seriesId = "S", indexNumber = 1))
        fake.add("b", BaseItemDto(id = "E", name = "第 35 集", type = "Episode", seriesId = "S", seasonId = "S1", parentIndexNumber = 1, indexNumber = 35))
        val r = VersionFinder(fake, FanOut(healthy)).findEpisode(
            "b",
            BaseItemDto(id = "x", name = "第 35 集", type = "Episode", seriesName = "某剧", parentIndexNumber = 1, indexNumber = 35),
            BaseItemDto(id = "R", name = "某剧", type = "Series", productionYear = 2020),
        )
        assertTrue(r is ServerVersions.Found)
        assertEquals(Confidence.Possible, (r as ServerVersions.Found).verdict.confidence)
        assertTrue(fake.requests.none { it.contains("第 35 集") })
    }
}
