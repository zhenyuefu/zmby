package app.zmby.overlay

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import androidx.core.graphics.createBitmap
import app.zmby.danmu.DanmuPosition
import app.zmby.danmu.DanmuStyle
import app.zmby.danmu.DanmuTicker
import app.zmby.danmu.Danmaku
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import kotlin.math.ceil

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DanmuEngineTest {
    private val period = DanmuTicker.DEFAULT_PERIOD_NS

    private class Setup(val activity: ActivityController<Activity>, val engine: DanmuEngine) {
        fun close() {
            engine.release()
            activity.pause().stop().destroy()
        }
    }

    private fun TestScope.setup(comments: List<Danmaku>, style: DanmuStyle = DanmuStyle(), onBottomIdle: () -> Unit = {},
                                refreshHz: Float = 60f, width: Int = 1920): Setup {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val engine = DanmuEngine(activity.get(), this as CoroutineScope, comments, style,
            StandardTestDispatcher(testScheduler), onBottomIdle) { refreshHz }
        activity.get().setContentView(engine.top)
        engine.top.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(width * 9 / 16, View.MeasureSpec.AT_MOST))
        engine.top.layout(0, 0, width, engine.top.measuredHeight)
        assertTrue(engine.top.isAttachedToWindow)
        return Setup(activity, engine)
    }

    private fun nowNs() = SystemClock.uptimeMillis() * 1_000_000

    private fun DanmuEngine.topPixels(): IntArray {
        val w = top.width
        val h = top.height
        val bitmap = createBitmap(w, h)
        top.draw(Canvas(bitmap))
        return IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
    }

    @Test fun `the top band is a strip and its rows fit the measured text at every size`() = runTest {
        for (width in listOf(1920, 3840)) for (size in listOf(20, 34, 48, 60)) {
            val scale = width / 1920f
            val s = setup(listOf(Danmaku(0, "x")), DanmuStyle(rows = 3, fontSize = size, lineGap = 0, topMargin = 12), width = width)
            try {
                val height = s.engine.top.measuredHeight
                val top = (12 * scale).toInt()
                // Ink of tall CJK, Latin descenders and accents, plus the outline on both sides.
                val bounds = Rect()
                danmuTextPaint(size * scale).getTextBounds("国gÅy", 0, 4, bounds)
                val ink = bounds.height() + 2 * ceil(danmuStrokeWidth(scale) / 2).toInt()
                assertTrue("$width/$size: ${height - top} < 3 × $ink", height - top >= 3 * ink)
                assertTrue("$width/$size: band $height is not a strip", height < width * 9 / 16 / 3)
            } finally { s.close() }
        }
    }

    @Test fun `scrolling moves exactly four whole pixels a refresh and the pixels shift unresampled`() = runTest {
        val s = setup(listOf(Danmaku(0, "整像素步进 ABC", 0xFFDD00)))
        try {
            val e = s.engine
            e.sample(0, true, SystemClock.uptimeMillis())
            advanceUntilIdle()
            val t0 = nowNs()
            e.doFrame(t0)
            e.doFrame(t0 + period)
            assertEquals(1, e.activeCount)
            val xs = mutableListOf<Int>()
            var frame = t0 + period
            repeat(60) {
                frame += period
                e.doFrame(frame)
                xs += e.activeX(0)
            }
            assertEquals(List(59) { 4 }, xs.zipWithNext { a, b -> a - b })
            assertTrue(e.hasPendingFrame)
            val before = e.topPixels()
            frame += period
            e.doFrame(frame)
            val after = e.topPixels()
            val h = e.top.height
            var ink = 0
            for (y in 0 until h) for (x in 1000 until 1900) {
                assertEquals("($x,$y)", before[y * 1920 + x + 4], after[y * 1920 + x])
                if (after[y * 1920 + x] != 0) ink++
            }
            assertTrue(ink > 0)
            // A frame two refreshes late catches up by exactly two steps.
            val x = e.activeX(0)
            frame += 2 * period
            e.doFrame(frame)
            assertEquals(x - 8, e.activeX(0))
        } finally { s.close() }
    }

    @Test fun `blank playback schedules a wake instead of continuously drawing and detach cancels it`() = runTest {
        val s = setup(listOf(Danmaku(30_000, "later")))
        try {
            val e = s.engine
            e.sample(0, true, SystemClock.uptimeMillis())
            advanceUntilIdle()
            e.doFrame(nowNs())
            assertFalse(e.hasPendingFrame)
            assertTrue(e.nextWakeMs > SystemClock.uptimeMillis())
            ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
            e.sample(4_000, true, SystemClock.uptimeMillis())
            advanceUntilIdle()
            e.doFrame(nowNs())
            assertFalse(e.hasPendingFrame)
            assertEquals(26_000L, e.nextWakeMs - SystemClock.uptimeMillis())
            s.activity.get().setContentView(View(s.activity.get()))
            assertFalse(e.hasPendingFrame)
            assertEquals(0L, e.nextWakeMs)
        } finally { s.close() }
    }

    @Test fun `a pause sample with a small rollback preserves the text pixels and schedules no animation`() = runTest {
        val s = setup(listOf(Danmaku(0, "暂停时保持弹幕")), DanmuStyle(opacityPercent = 75))
        try {
            val e = s.engine
            e.sample(0, true, SystemClock.uptimeMillis())
            advanceUntilIdle()
            var frame = nowNs()
            repeat(30) { e.doFrame(frame); frame += period }
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
            e.sample(800, false, SystemClock.uptimeMillis())
            advanceUntilIdle()
            e.doFrame(nowNs())
            val frozen = e.topPixels()
            assertTrue(frozen.any { it != 0 })
            assertFalse(e.hasPendingFrame)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
            e.doFrame(nowNs())
            assertArrayEquals(frozen, e.topPixels())
            // Repeated unchanged paused samples neither animate nor schedule a wake.
            e.sample(800, false, SystemClock.uptimeMillis())
            advanceUntilIdle()
            e.doFrame(nowNs())
            assertFalse(e.hasPendingFrame)
            assertEquals(0L, e.nextWakeMs)
            assertArrayEquals(frozen, e.topPixels())
        } finally { s.close() }
    }

    @Test fun `the bottom window goes once the chip expires unless there are bottom comments`() = runTest {
        for (withBottom in listOf(false, true)) {
            var idle = 0
            val comments = listOf(Danmaku(0, "滚动")) +
                if (withBottom) listOf(Danmaku(60_000, "底部", position = DanmuPosition.Bottom)) else emptyList()
            val s = setup(comments, onBottomIdle = { idle++ })
            try {
                val e = s.engine
                e.sample(0, false, SystemClock.uptimeMillis())
                e.doFrame(nowNs())
                assertEquals(0, idle)
                ShadowSystemClock.advanceBy(Duration.ofSeconds(5))
                e.doFrame(nowNs())
                e.doFrame(nowNs() + period)
                assertEquals(if (withBottom) 0 else 1, idle)
                assertTrue(e.bandHeight(DanmuBand.Bottom, 1920) >= 154)
            } finally { s.close() }
        }
    }

    @Test fun `bitmaps are drawn without filtering at whole pixels`() = runTest {
        val s = setup(listOf(Danmaku(0, "清晰")))
        try {
            val e = s.engine
            e.sample(0, true, SystemClock.uptimeMillis())
            advanceUntilIdle()
            var frame = nowNs()
            repeat(40) { e.doFrame(frame); frame += period }
            val pixels = e.topPixels()
            // Opaque white fill survives untouched: nothing was resampled into grey.
            assertTrue(pixels.any { it == -1 })
        } finally { s.close() }
    }

    /** The last simulated vsync, so a test can continue after [steps]. */
    private var lastFrameNs = 0L

    /** Runs [frames] vsyncs [periodNs] apart and returns the first comment's per-frame steps. */
    private fun DanmuEngine.steps(frames: Int, periodNs: Long): List<Int> {
        val startNs = nowNs()
        var frame = startNs
        doFrame(frame)
        val xs = ArrayList<Int>()
        repeat(frames) { i ->
            frame += periodNs
            // Keep the player samples coming about once a second, as the monitor does.
            if (i % 60 == 59) sample((frame - startNs) / 1_000_000, true, frame / 1_000_000)
            doFrame(frame)
            if (activeCount > 0) xs += activeX(0)
        }
        lastFrameNs = frame
        return xs.zipWithNext { a, b -> a - b }
    }

    @Test fun `120 Hz moves two whole pixels every refresh`() = runTest {
        val s = setup(listOf(Danmaku(0, "一百二十赫兹")), refreshHz = 120f)
        try {
            val e = s.engine
            assertEquals(DanmuTicker.periodOf(120f), e.periodNs)
            e.sample(0, true, SystemClock.uptimeMillis())
            advanceUntilIdle()
            val steps = e.steps(240, DanmuTicker.periodOf(120f))
            assertTrue(steps.size > 200)
            assertEquals(List(steps.size) { 2 }, steps)
        } finally { s.close() }
    }

    @Test fun `a 120 Hz panel that reports 60 Hz settles on two pixels every frame`() = runTest {
        val s = setup(listOf(Danmaku(0, "报告与实际不符")), refreshHz = 60f)
        try {
            val e = s.engine
            e.sample(0, true, SystemClock.uptimeMillis())
            advanceUntilIdle()
            val steps = e.steps(240, DanmuTicker.periodOf(120f))
            assertEquals(DanmuTicker.periodOf(120f).toDouble(), e.periodNs.toDouble(), 100_000.0)
            // Measured within about 0.4 s; even from then on, never a frame that stands still.
            val settled = steps.drop(2 * DanmuTicker.WINDOW + 2)
            assertEquals(List(settled.size) { 2 }, settled)
        } finally { s.close() }
    }

    @Test fun `144 Hz alternates one and two pixels at 1_5 a refresh`() = runTest {
        val s = setup(listOf(Danmaku(0, "一百四十四")), refreshHz = 144f)
        try {
            val e = s.engine
            e.sample(0, true, SystemClock.uptimeMillis())
            advanceUntilIdle()
            val steps = e.steps(288, DanmuTicker.periodOf(144f))
            assertTrue(steps.all { it == 1 || it == 2 })
            // Every two refreshes move exactly three pixels: no slow beat in the pattern.
            assertTrue(steps.chunked(2).filter { it.size == 2 }.all { it.sum() == 3 })
        } finally { s.close() }
    }

    @Test fun `a 4K interface doubles every size and moves eight whole pixels at 60 Hz`() = runTest {
        val hd = setup(listOf(Danmaku(0, "x")))
        val hdHeight = try { hd.engine.top.measuredHeight } finally { hd.close() }
        val s = setup(listOf(Danmaku(0, "四K界面".repeat(12), 0x66CCFF)), width = 3840)
        try {
            val e = s.engine
            // Same layout at twice the pixels; each metric edge rounds up to whole pixels separately.
            assertEquals(hdHeight * 2.0, e.top.measuredHeight.toDouble(), hdHeight * 2 * 0.05)
            assertTrue(e.bandHeight(DanmuBand.Bottom, 3840) >= 2 * 154)
            e.sample(0, true, SystemClock.uptimeMillis())
            advanceUntilIdle()
            val steps = e.steps(300, DanmuTicker.DEFAULT_PERIOD_NS)
            assertEquals(List(steps.size) { 8 }, steps)
            // The comment is wider than one texture tile: tiles shift as one, without seams.
            var frame = lastFrameNs
            val before = e.topPixels()
            frame += DanmuTicker.DEFAULT_PERIOD_NS
            e.doFrame(frame)
            val after = e.topPixels()
            val x = e.activeX(0)
            assertTrue("the first tile join should be on screen, x=$x", x + DanmuTextRenderer.TILE_PX in 16 until 3800)
            var ink = 0
            for (y in 0 until e.top.height) for (px in x.coerceAtLeast(0) until 3832) {
                assertEquals("($px,$y)", before[y * 3840 + px + 8], after[y * 3840 + px])
                if (after[y * 3840 + px] != 0) ink++
            }
            assertTrue(ink > 0)
        } finally { s.close() }
    }

    @Test fun `a 4K interface at 120 Hz moves four whole pixels every refresh`() = runTest {
        val s = setup(listOf(Danmaku(0, "四K一百二十")), refreshHz = 120f, width = 3840)
        try {
            val e = s.engine
            e.sample(0, true, SystemClock.uptimeMillis())
            advanceUntilIdle()
            val steps = e.steps(240, DanmuTicker.periodOf(120f))
            assertEquals(List(steps.size) { 4 }, steps)
        } finally { s.close() }
    }
}
