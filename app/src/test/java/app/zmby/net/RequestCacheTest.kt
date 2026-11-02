package app.zmby.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RequestCacheTest {
    @Test fun `equal concurrent requests share work while unrelated keys remain independent`() = runTest {
        val cache = RequestCache<String, String>(4, { 100 }, { testScheduler.currentTime })
        val gate = CompletableDeferred<String>()
        var calls = 0
        val a = async { cache.get("a") { calls++; gate.await() } }
        val b = async { cache.get("a") { calls++; "duplicate" } }
        runCurrent()
        assertEquals(1, calls)
        assertEquals("other", cache.get("b") { "other" })
        gate.complete("shared")
        assertEquals("shared", a.await())
        assertEquals("shared", b.await())
        testScheduler.advanceTimeBy(100)
        assertEquals("expired", cache.get("a") { calls++; "expired" })
        assertEquals(2, calls)
    }

    @Test fun `cancelling a waiter does not cancel the loader and cancelling a loader permits retry`() = runTest {
        val cache = RequestCache<String, String>(4, { 100 })
        val gate = CompletableDeferred<String>()
        val owner = async { cache.get("a") { gate.await() } }
        val waiter = async { cache.get("a") { error("must share") } }
        runCurrent()
        waiter.cancel()
        gate.complete("kept")
        assertEquals("kept", owner.await())
        val cancelled = async { cache.get("b") { CompletableDeferred<String>().await() } }
        val retry = async { cache.get("b") { "retried" } }
        runCurrent()
        cancelled.cancel()
        assertEquals("retried", retry.await())
    }

    @Test fun `invalidation during a load prevents publishing stale results`() = runTest {
        val cache = RequestCache<String, String>(4, { 100 })
        val gate = CompletableDeferred<String>()
        val pending = async { cache.get("a") { gate.await() } }
        runCurrent()
        cache.invalidate("a")
        gate.complete("old")
        assertEquals("old", pending.await())
        assertEquals("new", cache.get("a") { "new" })
        cache.clear()
        assertEquals("cleared", cache.get("a") { "cleared" })
    }

    @Test fun `nullable results obey TTL while failures and oversized values are not cached`() = runTest {
        val cache = RequestCache<String, String?>(2, { 100 }, maxWeight = 4, weight = { it?.length?.toLong() ?: 0 })
        assertNull(cache.get("null") { null })
        assertNull(cache.get("null") { error("null is a cached value") })
        assertTrue(runCatching { cache.get("error") { error("temporary") } }.isFailure)
        assertEquals("ok", cache.get("error") { "ok" })
        assertEquals("oversized", cache.get("big") { "oversized" })
        assertEquals("new", cache.get("big") { "new" })
    }

    @Test fun `byte budget evicts idle values in access order`() = runTest {
        val cache = RequestCache<String, String>(8, { 100 }, maxWeight = 4, weight = { it.length.toLong() })
        cache.get("a") { "aa" }; cache.get("b") { "bb" }
        cache.get("a") { error("must reuse") }
        cache.get("c") { "cc" }
        assertEquals("aa", cache.get("a") { error("recent entry survives") })
        assertEquals("new", cache.get("b") { "new" })
    }
}
