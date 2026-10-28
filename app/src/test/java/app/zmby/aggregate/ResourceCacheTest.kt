package app.zmby.aggregate

import app.zmby.emby.EmbyException
import app.zmby.emby.BaseItemDto
import app.zmby.emby.ItemsResult
import app.zmby.emby.MediaSourceInfo
import app.zmby.emby.UserItemData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ResourceCacheTest {
    @Test fun `returning two hours later reuses versions and a transient source failure is retried`() = runTest {
        var now = 0L
        var lookups = 0
        var sourceCalls = 0
        val book = Book()
        val source = object : VersionSource {
            override suspend fun byProviderIds(server: String, type: String, ids: List<String>): List<BaseItemDto> {
                lookups++; return listOf(BaseItemDto("copy", type = "Movie", providerIds = mapOf("Tmdb" to "10")))
            }
            override suspend fun searchType(server: String, term: String, type: String, limit: Int) = emptyList<BaseItemDto>()
            override suspend fun item(server: String, id: String): BaseItemDto = error("not needed")
            override suspend fun seasons(server: String, seriesId: String) = emptyList<BaseItemDto>()
            override suspend fun episodePage(server: String, seriesId: String, seasonId: String, start: Int, limit: Int) = ItemsResult()
            override suspend fun sources(server: String, itemId: String): List<MediaSourceInfo> {
                if (++sourceCalls == 1) throw IOException("temporary reset")
                return listOf(MediaSourceInfo("source"))
            }
        }
        val finder = VersionFinder(source, FanOut(book, clock = { now }, scope = backgroundScope))
        val ref = BaseItemDto("open", type = "Movie", providerIds = mapOf("Tmdb" to "10"))
        assertTrue(finder.movie(listOf("a"), ref).toList().single() is ServerVersions.Found)
        now += 2 * 60 * 60_000L
        assertTrue(finder.movie(listOf("a"), ref.copy(userData = UserItemData(played = true))).toList().single() is ServerVersions.Found)
        assertEquals(2, lookups)
        assertEquals(2, sourceCalls)
        assertEquals(0, book.failures)
        finder.invalidate(listOf("a"), ref)
        finder.movie(listOf("a"), ref).toList()
        assertEquals(3, lookups)
    }
    private class Book : HealthBook {
        var cooling = false
        var successes = 0
        var failures = 0
        override fun isCooling(serverId: String) = cooling
        override fun onSuccess(serverId: String) { successes++ }
        override fun onFailure(serverId: String, error: Throwable, timedOut: Boolean) { failures++ }
    }

    @Test fun `resource results including empty answers expire and refresh by server`() = runTest {
        var now = 0L
        val book = Book()
        val fan = FanOut(book, clock = { now }, scope = backgroundScope)
        val calls = mutableMapOf<String, Int>()
        suspend fun lookup() = fan.run(listOf("a", "b"), 1000, "resources", FanOut.RESOURCE_CACHE_MS) {
            calls[it] = calls.getOrDefault(it, 0) + 1
            emptyList<String>()
        }.toList()
        lookup(); now = 1000; lookup()
        assertEquals(mapOf("a" to 1, "b" to 1), calls)
        assertEquals(2, book.successes)
        fan.invalidate("resources", listOf("b")); lookup()
        assertEquals(mapOf("a" to 1, "b" to 2), calls)
        now = FanOut.RESOURCE_CACHE_MS + 1001; lookup()
        assertEquals(mapOf("a" to 2, "b" to 3), calls)
    }

    @Test fun `server session changes and different work identities cannot reuse cached resources`() = runTest {
        var context = "first-session"
        var calls = 0
        val fan = FanOut(Book(), scope = backgroundScope, cacheContext = { context })
        suspend fun lookup(key: Any) = fan.run(listOf("a"), 1000, key, 5000) { ++calls }.toList()
        lookup(listOf("movie", "tmdb:1")); lookup(listOf("movie", "tmdb:2"))
        assertEquals(2, calls)
        context = "new-line-or-login"
        lookup(listOf("movie", "tmdb:1"))
        assertEquals(3, calls)
    }

    @Test fun `invalidated older request cannot replace a freshly refreshed answer`() = runTest {
        val fan = FanOut(Book(), scope = backgroundScope)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val old = async { fan.run(listOf("a"), 1000, "resources", 5000) { started.complete(Unit); release.await(); "old" }.toList() }
        started.await()
        fan.invalidate("resources", listOf("a"))
        val fresh = fan.run(listOf("a"), 1000, "resources", 5000) { "fresh" }.toList()
        release.complete(Unit); old.await()
        val cached = fan.run<String>(listOf("a"), 1000, "resources", 5000) { error("must use cache") }.toList()
        assertEquals((fresh.single() as Outcome.Ok).value, (cached.single() as Outcome.Ok).value)
    }

    @Test fun `failed skipped and cancelled requests do not become cached answers`() = runTest {
        val book = Book()
        val fan = FanOut(book, scope = backgroundScope)
        assertTrue(fan.run(listOf("a"), 1000, "resources", 5000) { error("down") }.toList().single() is Outcome.Failed)
        book.cooling = true
        assertTrue(fan.run(listOf("a"), 1000, "resources", 5000) { "wrong" }.toList().single() is Outcome.Skipped)
        book.cooling = false
        assertNull(withTimeoutOrNull(20) { fan.run(listOf("a"), 1000, "resources", 5000) { delay(100); "late" }.toList() })
        val result = fan.run(listOf("a"), 1000, "resources", 5000) { "recovered" }.toList().single()
        assertEquals("recovered", (result as Outcome.Ok).value)
        assertEquals(1, book.failures)
    }

    @Test fun `transient transport and gateway failures retry once without poisoning health`() = runTest {
        for (error in listOf(IOException("reset"), EmbyException("gateway", 503))) {
            var calls = 0
            val book = Book()
            val fan = FanOut(book, scope = backgroundScope)
            val result = fan.all(listOf("a"), 1000) { if (++calls == 1) throw error else "recovered" }.single()
            assertTrue(result is Outcome.Ok)
            assertEquals(2, calls)
            assertEquals(0, book.failures)
            assertEquals(1, book.successes)
        }
    }

    @Test fun `persistent failure counts once and credentials or rate limits are not retried`() = runTest {
        for (code in listOf(401, 403, 404, 429, 503)) {
            var calls = 0
            val book = Book()
            val fan = FanOut(book, scope = backgroundScope)
            assertTrue(fan.all(listOf("a"), 1000) { calls++; throw EmbyException("failed", code) }.single() is Outcome.Failed)
            assertEquals(if (code == 503) 2 else 1, calls)
            assertEquals(1, book.failures)
        }
    }

    @Test fun `cancelling retry backoff never counts a health failure`() = runTest {
        val book = Book()
        val fan = FanOut(book, scope = backgroundScope)
        var calls = 0
        assertNull(withTimeoutOrNull(100) { fan.all(listOf("a"), 1000) { calls++; throw IOException("reset") } })
        assertEquals(1, calls)
        assertEquals(0, book.failures)
    }

    @Test fun `cached resources have a bounded entry count`() = runTest {
        val fan = FanOut(Book(), scope = backgroundScope)
        var calls = 0
        suspend fun lookup(id: Int) = fan.run(listOf("a"), 1000, "resource:$id", 5000) { ++calls }.toList()
        repeat(65) { lookup(it) }
        lookup(64); assertEquals(65, calls)
        lookup(0); assertEquals(66, calls)
    }
}
