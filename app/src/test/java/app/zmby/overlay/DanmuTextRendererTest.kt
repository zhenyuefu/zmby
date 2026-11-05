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
        renderer.configure(1f)
        renderer.prepare(comments, emptyList(), 0)
        advanceUntilIdle()
        assertTrue(ready > 0)
        assertTrue(renderer.width(comments[0])!! > 0)
        val first = renderer.texture(key)!!
        val bitmap = first.tiles.single()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue(pixels.any { (it ushr 24) > 128 && (it and 0x00FFFFFF) == 0xFF0000 })
        assertTrue(pixels.any { (it ushr 24) > 128 && (it and 0x00FFFFFF) == 0 })
        renderer.prepare(comments, emptyList(), 1_500)
        advanceUntilIdle()
        assertSame(first, renderer.texture(key))
        // The glyph carries the texture; the text origin inside it is on whole pixels.
        val glyph = renderer.glyph(comments[1], 0)!!
        assertSame(first, glyph.texture)
        assertEquals(kotlin.math.ceil(renderer.width(comments[1])!!).toInt(), glyph.widthPx)
        assertTrue(first.insetX > 0 && first.baseline > 0)
    }

    @Test fun `4K text stays within the byte budget and uncached text still has measured widths`() = runTest {
        val renderer = DanmuTextRenderer(this, StandardTestDispatcher(testScheduler)) {}
        val comments = List(60) { Danmaku(0, "弹幕".repeat(28) + it, 0xFFFFFF - it) }
        renderer.configure(2f)
        renderer.prepare(comments, emptyList(), 0)
        advanceUntilIdle()
        assertEquals(DanmuTextRenderer.MAX_BUDGET, renderer.maxBytes)
        assertTrue(renderer.bytes in 1..renderer.maxBytes)
        var cached = 0
        for (d in comments) {
            assertTrue(renderer.width(d)!! > 0)
            val texture = renderer.texture(DanmuTextKey(d.text, d.color))
            if (texture != null) cached++
            // Over budget: it enters at once as text instead of waiting for a bitmap that never comes.
            else assertNull(renderer.glyph(d, 0)!!.texture)
        }
        assertTrue(cached > 0 && cached < comments.size)
    }

    @Test fun `resize discards old work and never recycles a bitmap still used by a recorded frame`() = runTest {
        val renderer = DanmuTextRenderer(this, StandardTestDispatcher(testScheduler)) {}
        val comment = Danmaku(0, "尺寸变化")
        val key = DanmuTextKey(comment.text, comment.color)
        renderer.configure(1f)
        assertNull(renderer.glyph(comment, 0)) // Not measured yet: the comment waits.
        renderer.prepare(listOf(comment), emptyList(), 0)
        advanceUntilIdle()
        val old = renderer.texture(key)!!
        val oldWidth = renderer.width(comment)!!
        renderer.prepare(listOf(comment.copy(text = "旧任务")), emptyList(), 0)
        renderer.configure(2f)
        renderer.prepare(listOf(comment), emptyList(), 0)
        advanceUntilIdle()
        assertNull(renderer.width(comment.copy(text = "旧任务")))
        assertEquals(oldWidth * 2, renderer.width(comment)!!, 2f)
        assertTrue(renderer.texture(key)!!.width > old.width)
        renderer.clear()
        assertEquals(0L, renderer.bytes)
        assertNull(renderer.texture(key))
        assertFalse(old.tiles.any { it.isRecycled })
    }

    @Test fun `long 4K text is split into seamless tiles no wider than a safe texture`() = runTest {
        val renderer = DanmuTextRenderer(this, StandardTestDispatcher(testScheduler), fontSize = 60) {}
        val comment = Danmaku(0, "超长弹幕".repeat(15), 0x66CCFF)
        renderer.configure(2f)
        renderer.prepare(listOf(comment), emptyList(), 0)
        advanceUntilIdle()
        val texture = renderer.texture(DanmuTextKey(comment.text, comment.color))!!
        assertTrue(texture.tiles.size >= 3)
        assertTrue(texture.tiles.all { it.width <= DanmuTextRenderer.TILE_PX })
        // The same line rendered in one piece, as the painter does it.
        val fill = danmuTextPaint(120f).apply { color = 0xFF66CCFF.toInt() }
        val stroke = android.graphics.Paint(fill).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = danmuStrokeWidth(2f)
            strokeJoin = android.graphics.Paint.Join.ROUND
            color = android.graphics.Color.argb(DANMU_OUTLINE_ALPHA, 0, 0, 0)
        }
        assertEquals(kotlin.math.ceil(stroke.strokeWidth / 2).toInt() + 2, texture.insetX)
        val height = texture.tiles[0].height
        val whole = androidx.core.graphics.createBitmap(texture.width, height)
        android.graphics.Canvas(whole).apply {
            drawText(comment.text, texture.insetX.toFloat(), texture.baseline.toFloat(), stroke)
            drawText(comment.text, texture.insetX.toFloat(), texture.baseline.toFloat(), fill)
        }
        var left = 0
        for (tile in texture.tiles) {
            val expected = IntArray(tile.width * height).also { whole.getPixels(it, 0, tile.width, left, 0, tile.width, height) }
            val actual = IntArray(tile.width * height).also { tile.getPixels(it, 0, tile.width, 0, 0, tile.width, height) }
            assertArrayEquals("tile at $left", expected, actual)
            left += tile.width
        }
    }

    @Test fun `a 4K interface caches as many comments as 1080p`() = runTest {
        val comments = List(100) { Danmaku(it * 20L, "第${it}条弹幕前方高能好看", 0xFFFFFF) }
        fun cached(scale: Float): Int {
            val renderer = DanmuTextRenderer(this, StandardTestDispatcher(testScheduler)) {}
            renderer.configure(scale)
            renderer.prepare(comments, emptyList(), 0)
            advanceUntilIdle()
            assertEquals(DanmuTextRenderer.budgetFor(scale), renderer.maxBytes)
            return comments.count { renderer.texture(DanmuTextKey(it.text, it.color)) != null }
        }
        val hd = cached(1f)
        val uhd = cached(2f)
        assertEquals(hd, uhd)
        assertEquals(DanmuTextRenderer.BASE_BYTES * 4, DanmuTextRenderer.budgetFor(2f))
        assertEquals(DanmuTextRenderer.MAX_BUDGET, DanmuTextRenderer.budgetFor(4f))
    }
}
