package app.zmby.overlay

import android.app.Activity
import android.graphics.Canvas
import android.os.SystemClock
import androidx.core.graphics.createBitmap
import app.zmby.danmu.Danmaku
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DanmuViewTest {
    @Test fun `blank playback schedules a wake instead of continuously drawing and detach cancels it`() = runTest {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val view = DanmuView(activity.get(), this, listOf(Danmaku(30_000, "later")), 3, 1f,
            StandardTestDispatcher(testScheduler))
        try {
            activity.get().setContentView(view)
            view.layout(0, 0, 1920, 1080)
            assertTrue(view.isAttachedToWindow)
            view.sample(0, true, SystemClock.uptimeMillis())
            advanceUntilIdle()
            view.draw(Canvas(createBitmap(1920, 1080)))
            assertFalse(view.hasPendingFrame)
            assertTrue(view.nextWakeMs > SystemClock.uptimeMillis())
            ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
            view.sample(4_000, true, SystemClock.uptimeMillis())
            advanceUntilIdle()
            view.draw(Canvas(createBitmap(1920, 1080)))
            assertFalse(view.hasPendingFrame)
            assertEquals(26_000L, view.nextWakeMs - SystemClock.uptimeMillis())
            activity.get().setContentView(android.view.View(activity.get()))
            assertFalse(view.hasPendingFrame)
            assertEquals(0L, view.nextWakeMs)
        } finally {
            view.release()
            activity.pause().stop().destroy()
        }
    }

    @Test fun `a pause sample with a small rollback preserves the text pixels and schedules no animation`() = runTest {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val view = DanmuView(activity.get(), this, listOf(Danmaku(0, "暂停时保持弹幕")), 3, .75f,
            StandardTestDispatcher(testScheduler))
        try {
            activity.get().setContentView(view)
            view.layout(0, 0, 1920, 1080)
            view.sample(0, true, SystemClock.uptimeMillis())
            advanceUntilIdle()
            view.draw(Canvas(createBitmap(1920, 1080)))
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
            view.sample(800, false, SystemClock.uptimeMillis())
            advanceUntilIdle()
            fun topPixels(): IntArray {
                val bitmap = createBitmap(1920, 1080)
                view.draw(Canvas(bitmap))
                return IntArray(1920 * 300).also { bitmap.getPixels(it, 0, 1920, 0, 0, 1920, 300) }
            }
            val frozen = topPixels()
            assertTrue(frozen.any { it != 0 })
            assertFalse(view.hasPendingFrame)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
            assertArrayEquals(frozen, topPixels())
            // Repeated unchanged paused samples must not wake the animation or preparer again.
            view.sample(800, false, SystemClock.uptimeMillis())
            advanceUntilIdle()
            view.draw(Canvas(createBitmap(1920, 1080)))
            view.sample(800, false, SystemClock.uptimeMillis())
            advanceUntilIdle()
            assertFalse(view.hasPendingFrame)
            assertEquals(0L, view.nextWakeMs)
        } finally {
            view.release()
            activity.pause().stop().destroy()
        }
    }
}
