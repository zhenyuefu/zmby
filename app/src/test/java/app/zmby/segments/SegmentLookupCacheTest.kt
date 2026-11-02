package app.zmby.segments

import app.zmby.data.ProviderState
import app.zmby.data.SegmentProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SegmentLookupCacheTest {
    private val providers = listOf(ProviderState(SegmentProvider.Emby, true, 0))
    private val key = SegmentLookupCache.Key("server", "episode", 3_000_000, providers, 0)
    private val found = SegmentLookup(mapOf(SegmentKind.Intro to (SegmentProvider.Emby to Segment(SegmentKind.Intro, 0, 90_000))))

    @Test fun `prefetched markers survive a full episode and the editor shares the running hydration`() = runTest {
        val cache = SegmentLookupCache { testScheduler.currentTime }
        val gate = CompletableDeferred<SegmentLookup>()
        var requests = 0
        val prefetch = async { cache.get(key) { requests++; gate.await() } }
        val editor = async { cache.get(key) { requests++; error("duplicate hydration") } }
        runCurrent()
        gate.complete(found)
        assertEquals(found, prefetch.await())
        assertEquals(found, editor.await())
        testScheduler.advanceTimeBy(50 * 60_000)
        assertEquals(found, cache.get(key) { requests++; error("playback should reuse") })
        assertEquals(1, requests)
    }

    @Test fun `manual save retry provider changes runtime and server invalidate the lookup independently`() = runTest {
        val cache = SegmentLookupCache { testScheduler.currentTime }
        var requests = 0
        val keys = listOf(key, key.copy(revision = 1), key.copy(providers = emptyList()), key.copy(durationMs = 1_000_000), key.copy(server = "other"))
        for (k in keys) cache.get(k) { requests++; found }
        assertEquals(keys.size, requests)
        cache.get(key) { requests++; found }
        assertEquals(keys.size, requests)
    }

    @Test fun `unavailable answers recover quickly and explicit retry bypasses even that cache`() = runTest {
        val cache = SegmentLookupCache { testScheduler.currentTime }
        val failed = SegmentLookup(unavailable = setOf(SegmentProvider.Emby))
        cache.get(key) { failed }
        assertEquals(failed, cache.get(key) { found })
        assertEquals(found, cache.get(key.copy(revision = 1)) { found })
        testScheduler.advanceTimeBy(5_000)
        assertEquals(found, cache.get(key) { found })
    }

    @Test fun `configuration changed during a query cannot seed an answer under the previous configuration`() = runTest {
        val cache = SegmentLookupCache { testScheduler.currentTime }
        var current = true
        cache.get(key, isCurrent = { current }) { current = false; found }
        assertEquals(SegmentLookup(), cache.get(key) { SegmentLookup() })
    }
}
