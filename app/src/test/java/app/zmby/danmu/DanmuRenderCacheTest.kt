package app.zmby.danmu

import org.junit.Assert.*
import org.junit.Test

class DanmuRenderCacheTest {
    @Test fun `texture reuse ignores timestamp while a byte budget evicts the least recently drawn text`() {
        val cache = DanmuRenderCache<DanmuTextKey, String>(8, 128)
        val first = Danmaku(0, "same").let { DanmuTextKey(it.text, it.color) }
        val repeat = Danmaku(60_000, "same").let { DanmuTextKey(it.text, it.color) }
        val other = DanmuTextKey("other", Danmaku.WHITE)
        cache.put(first, "texture", 4)
        cache.put(other, "other", 4)
        assertEquals("texture", cache[repeat]) // Drawing the repeat makes it the most recently used.
        cache.put(DanmuTextKey("new", Danmaku.WHITE), "new", 4)
        assertEquals("texture", cache[first])
        assertNull(cache[other])
        assertEquals(8L, cache.bytes)
        assertFalse(cache.put(DanmuTextKey("oversized", 0), "too large", 9))
        assertEquals(8L, cache.bytes)
    }

    @Test fun `metadata has a separate entry bound and clearing a viewport drops all old entries`() {
        val cache = DanmuRenderCache<String, Float>(0, 2)
        cache.put("a", 10f, 0)
        cache.put("b", 20f, 0)
        cache.put("c", 30f, 0)
        assertNull(cache["a"])
        assertEquals(2, cache.size)
        cache.retain(setOf("c"))
        assertEquals(mapOf("c" to 30f), cache.snapshot())
        cache.clear()
        assertEquals(0, cache.size)
        assertEquals(0L, cache.bytes)
    }

    @Test fun `blank and paused video sleeps while moving text follows vsync`() {
        assertEquals(0L, nextDanmuFrameDelay(true, true, false, 5_000, 30_000, null, 0))
        assertEquals(0L, nextDanmuFrameDelay(true, false, true, 5_000, 30_000, null, 0))
        assertEquals(25_000L, nextDanmuFrameDelay(true, false, false, 5_000, 30_000, null, 0))
        assertNull(nextDanmuFrameDelay(true, false, false, 5_000, null, null, 0))
        assertNull(nextDanmuFrameDelay(false, true, true, 5_000, 30_000, 1_000, 0))
        assertEquals(4_000L, nextDanmuFrameDelay(false, false, false, 5_000, 30_000, null, 4_000))
        assertEquals(500L, nextDanmuFrameDelay(true, false, false, 5_000, 5_500, null, 4_000))
        // A centred comment leaving is the next change on an otherwise still screen.
        assertEquals(1_200L, nextDanmuFrameDelay(true, false, false, 5_000, 30_000, 1_200, 4_000))
    }
}
