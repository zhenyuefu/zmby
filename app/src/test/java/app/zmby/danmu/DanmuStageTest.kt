package app.zmby.danmu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmuStageTest {
    @Test fun `the clock runs while playing, freezes when paused or stale, and spots jumps`() {
        val c = DanmuClock()
        assertFalse(c.sample(10_000, playing = true, nowMs = 0))
        assertEquals(10_500, c.position(500))
        assertTrue(c.running(500))
        assertFalse(c.sample(11_000, playing = true, nowMs = 1_000))
        // No sample for a while: it stops at the stale limit instead of running away.
        assertEquals(11_000 + DanmuClock.STALE_MS, c.position(60_000))
        assertFalse(c.running(60_000))
        c.sample(11_200, playing = false, nowMs = 61_000)
        assertEquals(11_200, c.position(70_000))
        assertTrue(c.sample(90_000, playing = true, nowMs = 71_000))
    }

    @Test fun `comments take the first free row and never overlap within it`() {
        val comments = (0 until 5).map { Danmaku(it * 100L, "c$it") }
        val stage = DanmuStage(comments, { 400f }, screenPx = 1920f, rows = 3, gapPx = 40f)
        stage.frame(0)
        val placed = stage.frame(400).map { it.first }
        // Three rows, five comments almost at once: the first three get a row each, the rest are dropped.
        assertEquals(listOf(0, 1, 2), placed.map { it.row })
        assertEquals(listOf("c0", "c1", "c2"), placed.map { it.danmaku.text })
        // Same speed in a row: a later comment's left edge stays right of the earlier one's tail.
        val later = DanmuStage(listOf(Danmaku(0, "a"), Danmaku(3_000, "b")), { 400f }, 1920f, 1, 40f)
        later.frame(0)
        later.frame(3_000)
        val frame = later.frame(5_000)
        val (a, b) = frame.sortedBy { it.first.startMs }
        assertTrue(b.second >= a.second + 400f + 40f - 0.01f)
    }

    @Test fun `late comments are dropped and a jump clears the screen`() {
        val comments = listOf(Danmaku(1_000, "early"), Danmaku(60_000, "later"))
        val stage = DanmuStage(comments, { 200f }, 1920f, 3, 40f)
        // First frame long after the first comment: it is not shown late.
        assertTrue(stage.frame(5_000).isEmpty())
        assertEquals(listOf("later"), stage.frame(60_100).map { it.first.danmaku.text })
        // Back to before it: the screen is cleared and it comes again in time.
        stage.reset(59_000)
        assertTrue(stage.frame(59_000).isEmpty())
        assertEquals(listOf("later"), stage.frame(60_000).map { it.first.danmaku.text })
        // Comments leave once they have scrolled off.
        assertTrue(stage.frame(60_000 + DanmuStage.CROSS_MS.toLong() + 2_000).isEmpty())
    }

    @Test fun `small backward and forward corrections preserve visible text and its movement`() {
        val clock = DanmuClock()
        val stage = DanmuStage(listOf(Danmaku(10_000, "visible")), { 400f }, 1920f, 3, 48f)
        clock.sample(10_000, true, 0)
        stage.update(clock.position(0), clock.renderTime(0))
        val visible = stage.active.single()
        stage.update(clock.position(984), clock.renderTime(984))
        assertFalse(clock.sample(10_900, true, 1_000))
        stage.update(clock.position(1_000), clock.renderTime(1_000))
        assertSame(visible, stage.active.single())
        assertEquals(1680f, stage.x(visible, clock.renderTime(1_000)), .01f)
        assertFalse(clock.sample(12_300, true, 2_000))
        stage.update(clock.position(2_000), clock.renderTime(2_000))
        assertSame(visible, stage.active.single())
        assertEquals(1440f, stage.x(visible, clock.renderTime(2_000)), .01f)
    }

    @Test fun `pause freezes visible comments without rewinding and resume does not replay admissions`() {
        val clock = DanmuClock()
        val stage = DanmuStage(listOf(Danmaku(10_000, "visible")), { 400f }, 1920f, 3, 48f)
        clock.sample(10_000, true, 0)
        stage.update(10_000, clock.renderTime(0))
        val visible = stage.active.single()
        assertFalse(clock.sample(10_800, false, 1_000))
        stage.update(clock.position(1_000), clock.renderTime(1_000))
        assertSame(visible, stage.active.single())
        assertEquals(1_000L, clock.renderTime(60_000))
        assertEquals(1680f, stage.x(visible, clock.renderTime(60_000)), .01f)
        clock.sample(10_800, true, 60_000)
        stage.update(clock.position(60_500), clock.renderTime(60_500))
        assertSame(visible, stage.active.single())
        assertEquals(1_500L, clock.renderTime(60_500))
    }

    @Test fun `a true seek clears the cursor while stale animation stops until a fresh sample`() {
        val clock = DanmuClock()
        clock.sample(10_000, true, 0)
        assertEquals(2_500L, clock.renderTime(60_000))
        assertFalse(clock.running(60_000))
        assertTrue(clock.sample(70_000, true, 60_000))
        assertEquals(2_500L, clock.renderTime(60_000))
        assertEquals(3_000L, clock.renderTime(60_500))
        clock.seek(5_000, 61_000)
        assertEquals(5_000L, clock.position(61_000))
        assertEquals(3_500L, clock.renderTime(61_000))
    }

    @Test fun `a missing measurement waits and in-place updates keep the same active objects`() {
        var measured: Float? = null
        val stage = DanmuStage(listOf(Danmaku(1_000, "prepared later")), { measured }, 1920f, 3, 48f)
        stage.update(1_000)
        assertTrue(stage.active.isEmpty())
        assertEquals(1_000L, stage.nextTimeMs)
        measured = 400f
        stage.update(1_100)
        val list = stage.active
        val item = list.single()
        repeat(100) { stage.update(1_100 + it.toLong()) }
        assertSame(list, stage.active)
        assertSame(item, stage.active.single())
        assertEquals(1896f, stage.x(item, 1_100), .01f)
    }

    @Test fun `resizing keeps the row and proportional horizontal position`() {
        val stage = DanmuStage(listOf(Danmaku(0, "visible")), { 400f }, 1920f, 3, 48f)
        stage.update(0)
        stage.update(2_000)
        val item = stage.active.single()
        val x = stage.x(item, 2_000)
        stage.resize(3840f, 96f)
        assertSame(item, stage.active.single())
        assertEquals(x * 2, stage.x(item, 2_000), .01f)
        assertEquals(800f, item.widthPx, .01f)
        assertEquals(0, item.row)
    }

    // A collecting adapter is useful for assertions; the production renderer reads the active list.
    private fun DanmuStage.frame(positionMs: Long): List<Pair<Placed, Float>> {
        update(positionMs)
        return active.map { it to x(it, positionMs) }
    }
}
