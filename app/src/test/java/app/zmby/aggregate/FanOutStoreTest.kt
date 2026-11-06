package app.zmby.aggregate

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** The persistent half of FanOut's request cache (统一数据层 ①③). */
class FanOutStoreTest {
    private class MemoryStore(var now: () -> Long) : ResultStore {
        val rows = ConcurrentHashMap<List<String>, Pair<String, Long>>()
        override suspend fun read(kind: String, key: String, server: String, maxAgeMs: Long) =
            rows[listOf(kind, key, server)]?.takeIf { now() - it.second in 0 until maxAgeMs }?.first
        override suspend fun write(kind: String, key: String, server: String, json: String) { rows[listOf(kind, key, server)] = json to now() }
        override suspend fun drop(kind: String, key: String, servers: List<String>) { rows.keys.removeAll { it[0] == kind && it[1] == key && it[2] in servers } }
        override suspend fun dropKinds(kinds: List<String>, servers: List<String>) { rows.keys.removeAll { it[0] in kinds && it[2] in servers } }
    }
    private class Book : HealthBook {
        override fun isCooling(serverId: String) = false
        override fun onSuccess(serverId: String) = Unit
        override fun onFailure(serverId: String, error: Throwable, timedOut: Boolean) = Unit
    }

    private var now = 1_000L
    private val store = MemoryStore { now }
    private var who = "u@http://a"
    private fun fan() = FanOut(Book(), clock = { now }, scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), results = store, login = { who })
    private val persist = Persist("versions", String.serializer())

    private suspend fun ask(f: FanOut, calls: AtomicInteger, answer: String = "found", fail: Boolean = false) =
        f.run(listOf("a"), 1000, share = listOf("versions", "movie-1"), cacheMs = 500, persist = persist) {
            calls.incrementAndGet(); if (fail) throw IllegalStateException("no") else answer
        }.toList().single()

    @Test fun `an answer survives a restart within its freshness and is asked again after it`() = runBlocking {
        val calls = AtomicInteger()
        assertEquals("found", (ask(fan(), calls) as Outcome.Ok).value)
        delay(50)
        // A new FanOut (a restarted process) reads it back without asking.
        assertEquals("found", (ask(fan(), calls) as Outcome.Ok).value)
        assertEquals(1, calls.get())
        now += 500
        assertEquals("fresh", (ask(fan(), calls, "fresh") as Outcome.Ok).value)
        assertEquals(2, calls.get())
    }

    @Test fun `failures are not stored, another login asks again and invalidation drops the stored answer`() = runBlocking {
        val calls = AtomicInteger()
        assertTrue(ask(fan(), calls, fail = true) is Outcome.Failed)
        delay(50)
        assertTrue(store.rows.isEmpty())
        val f = fan()
        ask(f, calls)
        delay(50)
        who = "v@http://a"
        assertEquals("other", (ask(fan(), calls, "other") as Outcome.Ok).value)
        who = "u@http://a"
        f.invalidate(listOf("versions", "movie-1"), listOf("a"), persist)
        delay(50)
        assertEquals("again", (ask(fan(), calls, "again") as Outcome.Ok).value)
    }

    @Test fun `steps are stored too and a manual refresh drops them`() = runBlocking {
        val calls = AtomicInteger()
        val step = Persist(FanOut.KIND_SEASONS, String.serializer())
        assertEquals("s1", fan().step("a", listOf("seasons", "x"), 500, step) { calls.incrementAndGet(); "s1" })
        delay(50)
        assertEquals("s1", fan().step("a", listOf("seasons", "x"), 500, step) { calls.incrementAndGet(); "s2" })
        assertEquals(1, calls.get())
        fan().invalidateSteps(listOf("a"))
        delay(50)
        assertEquals("s3", fan().step("a", listOf("seasons", "x"), 500, step) { calls.incrementAndGet(); "s3" })
    }
}

class ServerVersionsStoredTest {
    @Test fun `a found version and the other answers round-trip through the stored form`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val found: ServerVersions = ServerVersions.Found("a", app.zmby.emby.BaseItemDto(id = "1", name = "沙丘2", type = "Movie"),
            listOf(app.zmby.emby.MediaSourceInfo(id = "s1")), Verdict(Confidence.Strong, "TMDB 一致"))
        for (v in listOf(found, ServerVersions.Missing("a", "缺此集"), ServerVersions.NotFound("b", "没有"))) {
            assertEquals(v, json.decodeFromString(ServerVersions.serializer(), json.encodeToString(ServerVersions.serializer(), v)))
        }
    }
}
