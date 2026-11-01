package app.zmby.overlay

import app.zmby.danmu.DanmuTextKey
import app.zmby.danmu.Danmaku
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DanmuTextRendererTest {
    @Test fun `prepared text reuses a texture across timestamps and keeps its colour and outline`() = runTest {
        var ready = 0
        val renderer = DanmuTextRenderer(this, StandardTestDispatcher(testScheduler)) { ready++ }
        val key = DanmuTextKey("测试弹幕 ABC", 0xFF0000)
        val comments = listOf(Danmaku(0, key.text, key.color), Danmaku(2_000, key.text, key.color))
        renderer.configure(1f, "弹幕 · 2 条")
        renderer.prepare(comments, emptyList(), 0)
        advanceUntilIdle()
        assertTrue(ready > 0)
        assertTrue(renderer.width(comments[0])!! > 0)
        val first = renderer.texture(key)!!
        val pixels = IntArray(first.bitmap.width * first.bitmap.height)
        first.bitmap.getPixels(pixels, 0, first.bitmap.width, 0, 0, first.bitmap.width, first.bitmap.height)
        assertTrue(pixels.any { (it ushr 24) > 128 && (it and 0x00FFFFFF) == 0xFF0000 })
        assertTrue(pixels.any { (it ushr 24) > 128 && (it and 0x00FFFFFF) == 0 })
        renderer.prepare(comments, emptyList(), 1_500)
        advanceUntilIdle()
        assertSame(first, renderer.texture(key))
    }

    @Test fun `4K text stays within the byte budget and uncached text still has measured widths`() = runTest {
        val renderer = DanmuTextRenderer(this, StandardTestDispatcher(testScheduler)) {}
        val comments = List(60) { Danmaku(0, "弹幕".repeat(28) + it, 0xFFFFFF - it) }
        renderer.configure(2f, "弹幕 · 60 条")
        renderer.prepare(comments, emptyList(), 0)
        advanceUntilIdle()
        assertTrue(renderer.bytes in 1..DanmuTextRenderer.MAX_BYTES)
        var cached = 0
        for (d in comments) {
            assertTrue(renderer.width(d)!! > 0)
            if (renderer.texture(DanmuTextKey(d.text, d.color)) != null) cached++
        }
        assertTrue(cached > 0 && cached < comments.size)
    }

    @Test fun `resize discards old work and never recycles a bitmap still used by a recorded frame`() = runTest {
        val renderer = DanmuTextRenderer(this, StandardTestDispatcher(testScheduler)) {}
        val comment = Danmaku(0, "尺寸变化")
        val key = DanmuTextKey(comment.text, comment.color)
        renderer.configure(1f, "弹幕")
        renderer.prepare(listOf(comment), emptyList(), 0)
        advanceUntilIdle()
        val old = renderer.texture(key)!!
        val oldWidth = renderer.width(comment)!!
        renderer.prepare(listOf(comment.copy(text = "旧任务")), emptyList(), 0)
        renderer.configure(2f, "弹幕")
        renderer.prepare(listOf(comment), emptyList(), 0)
        advanceUntilIdle()
        assertNull(renderer.width(comment.copy(text = "旧任务")))
        assertEquals(oldWidth * 2, renderer.width(comment)!!, 2f)
        assertTrue(renderer.texture(key)!!.bitmap.width > old.bitmap.width)
        renderer.clear()
        assertEquals(0L, renderer.bytes)
        assertNull(renderer.texture(key))
        assertFalse(old.bitmap.isRecycled)
    }
}
