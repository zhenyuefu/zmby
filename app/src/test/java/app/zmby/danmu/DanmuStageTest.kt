package app.zmby.danmu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmuStageTest {
    private val period = DanmuTicker.DEFAULT_PERIOD_NS

    private fun stage(comments: List<Danmaku>, rows: Int = 3, width: (Danmaku) -> Int? = { 400 }) =
        DanmuStage(comments, { d, _ -> width(d) }, { it }, rows).apply {
            layout(1920, 48, 0)
            retime(DanmuStage.speedFor(1920, period), period / 1e6, 0)
        }

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
        c.seek(5_000, 72_000)
        assertEquals(5_000L, c.position(72_000))
    }

    @Test fun `the ticker counts whole refreshes, catches up late frames and ignores repeated timestamps`() {
        val t = DanmuTicker(period)
        assertEquals(0L, t.frame(1_000_000_000))
        assertEquals(1L, t.frame(1_000_000_000 + period))
        assertEquals(0L, t.frame(1_000_000_000 + period))
        // A frame that arrives a little early or late still counts one refresh.
        assertEquals(1L, t.frame(1_000_000_000 + 2 * period + 3_000_000))
        // Two refreshes went by without a frame.
        assertEquals(3L, t.frame(1_000_000_000 + 5 * period))
        assertEquals(5L, t.ticks)
        assertEquals(2L, t.skipped)
        // Paused: the first frame after resuming does not count the pause.
        t.stop()
        assertEquals(0L, t.frame(60_000_000_000))
        assertEquals(1L, t.frame(60_000_000_000 + period))
        assertEquals(6L, t.ticks)
    }

    @Test fun `every common refresh rate gets an even step pattern close to eight seconds a screen, sixteen below 40 Hz`() {
        val rates = listOf(23.976f, 24f, 25f, 29.97f, 30f, 48f, 50f, 59.94f, 60f, 72f, 75f, 90f, 100f,
            119.88f, 120f, 144f, 165f, 240f, 360f)
        for (width in listOf(1920, 3840)) for (hz in rates) {
            val period = DanmuTicker.periodOf(hz)
            val speed = DanmuStage.speedFor(width, period)
            val crossSeconds = width / speed.perMs(period) / 1000
            // Within a quarter of the target everywhere; exact at the 60/120 Hz families.
            val range = if (hz < DanmuStage.LOW_RATE_HZ) 14.0..18.0 else 6.0..10.0
            assertTrue("$width@$hz: ${speed} crosses in $crossSeconds s", crossSeconds in range)
            // A pattern longer than one refresh still repeats at least 60 times a second.
            assertTrue("$width@$hz: $speed", speed.ticks == 1 || hz / speed.ticks >= 59.9f)
            // The steps within one pattern differ by at most one pixel.
            val steps = (0 until speed.ticks * 4).map { speed.travelled(it.toLong(), it + 1L) }
            assertTrue("$width@$hz: $steps", steps.max() - steps.min() <= 1)
        }
        val p = { hz: Float -> DanmuTicker.periodOf(hz) }
        assertEquals(DanmuSpeed(4, 1), DanmuStage.speedFor(1920, p(60f)))
        assertEquals(DanmuSpeed(4, 1), DanmuStage.speedFor(1920, p(59.94f)))
        assertEquals(DanmuSpeed(5, 1), DanmuStage.speedFor(1920, p(23.976f)))
        assertEquals(DanmuSpeed(10, 1), DanmuStage.speedFor(3840, p(23.976f)))
        assertEquals(DanmuSpeed(5, 1), DanmuStage.speedFor(1920, p(25f)))
        assertEquals(DanmuSpeed(4, 1), DanmuStage.speedFor(1920, p(30f)))
        assertEquals(DanmuSpeed(5, 1), DanmuStage.speedFor(1920, p(50f)))
        assertEquals(DanmuSpeed(2, 1), DanmuStage.speedFor(1920, p(120f)))
        assertEquals(DanmuSpeed(2, 1), DanmuStage.speedFor(1920, p(119.88f)))
        assertEquals(DanmuSpeed(3, 2), DanmuStage.speedFor(1920, p(144f)))
        assertEquals(DanmuSpeed(3, 2), DanmuStage.speedFor(1920, p(165f)))
        assertEquals(DanmuSpeed(1, 1), DanmuStage.speedFor(1920, p(240f)))
        assertEquals(DanmuSpeed(2, 3), DanmuStage.speedFor(1920, p(360f)))
        assertEquals(DanmuSpeed(4, 1), DanmuStage.speedFor(3840, p(120f)))
        assertEquals(DanmuSpeed(8, 1), DanmuStage.speedFor(3840, p(60f)))
        assertEquals(DanmuTicker.DEFAULT_PERIOD_NS, DanmuTicker.periodOf(0f))
    }

    @Test fun `at 144 Hz comments started on different refreshes step together`() {
        val period = DanmuTicker.periodOf(144f)
        val s = DanmuStage(listOf(Danmaku(0, "a"), Danmaku(0, "b"), Danmaku(10, "c")), { _, _ -> 400 }, { it }, 3).apply {
            layout(1920, 48, 0)
            retime(DanmuStage.speedFor(1920, period), period / 1e6, 0)
        }
        s.update(0, 0)
        s.update(10, 1) // "c" enters one refresh later, on the other phase of the 2-1 pattern.
        val (a, _, c) = s.active
        var gap = s.x(c, 1) - s.x(a, 1)
        for (tick in 2L..300L) {
            s.update(10, tick)
            val step = s.x(a, tick - 1) - s.x(a, tick)
            assertTrue(step == 1 || step == 2)
            assertEquals("tick $tick", gap, s.x(c, tick) - s.x(a, tick))
            gap = s.x(c, tick) - s.x(a, tick)
        }
        assertEquals(1920 - 450, s.x(a, 300)) // 1.5 px a refresh.
    }

    @Test fun `a 120 Hz panel reported as 60 Hz is measured and corrected, and back again`() {
        val t = DanmuTicker(DanmuTicker.periodOf(60f))
        val p120 = DanmuTicker.periodOf(120f)
        var now = 1_000_000_000L
        val counted = mutableListOf<Long>()
        repeat(2 * DanmuTicker.WINDOW + 1) { counted += t.frame(now); now += p120 }
        assertEquals(1, t.periodChanges)
        assertEquals(p120.toDouble(), t.periodNs.toDouble(), p120 * 0.01)
        // From then on every 120 Hz frame is exactly one refresh.
        val after = (0 until 50).map { t.frame(now).also { now += p120 } }
        assertEquals(List(50) { 1L }, after)
        // The app falls to 60 frames a second on the same panel: measured again, two refreshes a frame
        // until then, one refresh of the 60 Hz period after.
        val p60 = DanmuTicker.periodOf(60f)
        repeat(2 * DanmuTicker.WINDOW + 1) { t.frame(now); now += p60 }
        assertEquals(2, t.periodChanges)
        assertEquals(List(10) { 1L }, (0 until 10).map { t.frame(now).also { now += p60 } })
    }

    @Test fun `jitter, dropped frames and pauses do not change the measured period`() {
        val p = DanmuTicker.periodOf(120f)
        val t = DanmuTicker(p)
        var now = 1_000_000_000L
        val random = java.util.Random(7)
        repeat(20 * DanmuTicker.WINDOW) { i ->
            // ±0.6 ms jitter, every fourth frame dropped, a long pause now and then.
            now += p * (if (i % 4 == 3) 2 else 1) + random.nextInt(1_200_000) - 600_000
            if (i % 97 == 96) { t.stop(); now += 5_000_000_000L }
            t.frame(now)
        }
        assertEquals(0, t.periodChanges)
        assertEquals(p, t.periodNs)
        // 59.94 Hz reported as 60 is close enough to keep.
        val ntsc = DanmuTicker(DanmuTicker.periodOf(60f))
        now = 1_000_000_000L
        repeat(10 * DanmuTicker.WINDOW) { ntsc.frame(now); now += DanmuTicker.periodOf(59.94f) }
        assertEquals(0, ntsc.periodChanges)
        // A reported change resets measurement and is adopted at once.
        ntsc.reported(DanmuTicker.periodOf(23.976f))
        assertEquals(1, ntsc.periodChanges)
        assertEquals(DanmuTicker.periodOf(23.976f), ntsc.periodNs)
    }

    @Test fun `scrolling text moves the same whole pixels every refresh`() {
        val s = stage(listOf(Danmaku(0, "a")))
        s.update(0, 0)
        val p = s.active.single()
        val xs = (0L..10L).map { s.update(it * 16, it); s.x(p, it) }
        assertEquals(1920, xs.first())
        assertEquals(List(10) { 4 }, xs.zipWithNext { a, b -> a - b })
    }

    @Test fun `comments take the first free lane and never overlap within it`() {
        val s = stage((0 until 5).map { Danmaku(it * 10L, "c$it") })
        s.update(40, 0)
        // Three lanes, five comments almost at once: three enter, two wait for a lane.
        assertEquals(listOf(0, 1, 2), s.active.map { it.row })
        assertTrue(s.hasWaiting)
        // Lane 0 frees once its comment has entered with the gap to spare: (400 + 48) / 4 refreshes.
        s.update(500, 111)
        assertEquals(3, s.active.size)
        s.update(520, 112)
        // Lanes 0 and 1 entered together, so both free on the same refresh.
        assertEquals(listOf(0 to "c0", 1 to "c1", 2 to "c2", 0 to "c3", 1 to "c4"), s.active.map { it.row to it.danmaku.text })
        val (first, later) = s.active.filter { it.row == 0 }
        assertTrue(s.x(later, 112) >= s.x(first, 112) + first.widthPx + 48)
        // A waiting comment enters from the right edge, not part-way across.
        assertEquals(1920, s.x(later, 112))
    }

    @Test fun `waiting is bounded in time and a seek clears everything`() {
        val s = stage(listOf(Danmaku(1_000, "early"), Danmaku(1_100, "blocked"), Danmaku(60_000, "later")), rows = 1)
        // The first frame long after a comment: it is not shown late.
        s.update(5_000, 0)
        assertTrue(s.active.isEmpty())
        s.reset(1_000)
        s.update(1_000, 0)
        s.update(1_100, 1)
        assertEquals(listOf("early"), s.active.map { it.danmaku.text })
        assertTrue(s.hasWaiting)
        // Still no lane a second later: dropped.
        s.update(2_101, 2)
        assertFalse(s.hasWaiting)
        assertEquals(60_000L, s.nextTimeMs)
        s.reset(59_000)
        assertTrue(s.active.isEmpty())
        s.update(60_000, 10)
        assertEquals(listOf("later"), s.active.map { it.danmaku.text })
        // It leaves once it has scrolled off.
        s.update(70_000, 10 + (1920 + 400) / 4 + 1)
        assertTrue(s.active.isEmpty())
    }

    @Test fun `a missing glyph waits and in-place updates keep the same objects`() {
        var measured: Int? = null
        val s = stage(listOf(Danmaku(1_000, "prepared later")), width = { measured })
        s.update(1_000, 0)
        assertTrue(s.active.isEmpty())
        assertTrue(s.hasWaiting)
        measured = 400
        s.update(1_100, 1)
        val list = s.active
        val item = list.single()
        repeat(100) { s.update(1_100L + it, 1) }
        assertSame(list, s.active)
        assertSame(item, s.active.single())
        assertEquals(1920, s.x(item, 1))
    }

    @Test fun `centred modes stay four seconds and bottom comments have their own lanes`() {
        val s = stage(listOf(
            Danmaku(0, "scroll"), Danmaku(0, "top", position = DanmuPosition.Top),
            Danmaku(0, "bottom", position = DanmuPosition.Bottom), Danmaku(10, "bottom 2", position = DanmuPosition.Bottom),
        ))
        val bottomBefore = s.bottomVersion
        s.update(10, 0)
        assertEquals(listOf(0 to "scroll", 1 to "top", 0 to "bottom", 1 to "bottom 2"), s.active.map { it.row to it.danmaku.text })
        assertTrue(s.bottomVersion != bottomBefore)
        assertEquals((1920 - 400) / 2, s.x(s.active[1], 100))
        val ends = s.ticksFor(DanmuStage.FIXED_MS)
        assertEquals(ends, s.nextFixedEnd())
        s.update(3_000, ends - 1)
        assertEquals(4, s.active.size)
        s.update(4_000, ends)
        assertEquals(listOf("scroll"), s.active.map { it.danmaku.text })
        assertNull(s.nextFixedEnd())
    }

    @Test fun `a new refresh rate keeps visible comments in place`() {
        val s = stage(listOf(Danmaku(0, "a"), Danmaku(0, "fixed", position = DanmuPosition.Top)))
        s.update(0, 0)
        s.update(1_000, 60)
        val scroll = s.active[0]
        assertEquals(1920 - 240, s.x(scroll, 60))
        // 60 Hz to 24 Hz: five pixels a refresh from now on (the slower low-rate pace). The start is
        // a whole number of refreshes, so the place holds to within half a step.
        s.retime(DanmuStage.speedFor(1920, DanmuTicker.periodOf(23.976f)), DanmuTicker.periodOf(23.976f) / 1e6, 60)
        val x = s.x(scroll, 60)
        assertTrue("$x", kotlin.math.abs(x - (1920 - 240)) <= 3)
        assertEquals(x - 5, s.x(scroll, 61))
        val fixedLeft = s.nextFixedEnd()!! - 60
        assertEquals(72L, fixedLeft) // About three seconds at 24 Hz.
    }

    @Test fun `a new layout clears the screen and continues from the position`() {
        val s = stage(listOf(Danmaku(0, "a"), Danmaku(5_000, "b")))
        s.update(0, 0)
        assertEquals(1, s.active.size)
        s.layout(3840, 96, 3_000)
        assertTrue(s.active.isEmpty())
        assertEquals(5_000L, s.nextTimeMs)
        assertEquals(3840, s.screenPx)
    }

    @Test fun `a steady frame allocates nothing, even with a full screen and a waiting queue`() {
        val comments = (0 until 40).map { Danmaku(it * 5L, "c$it") }
        val s = stage(comments, rows = 3)
        s.update(200, 0)
        assertTrue(s.hasWaiting)
        // The JDK's per-thread counter; test sources compile against android.jar, hence reflection.
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val counter = Class.forName("com.sun.management.ThreadMXBean")
            .getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        @Suppress("DEPRECATION") val thread = Thread.currentThread().id
        fun allocated() = counter.invoke(bean, thread) as Long
        repeat(10_000) { s.update(200, 1) } // Warm up.
        val before = allocated()
        repeat(100_000) { s.update(200, 2) }
        val perFrame = (allocated() - before) / 100_000.0
        assertTrue("$perFrame bytes per frame", perFrame < 1.0)
    }
}
