package app.zmby.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchThresholdTest {
    private val hour = 3_600_000L

    @Test
    fun watchedFromTheThresholdOn() {
        assertFalse(WatchThreshold.isWatched(hour * 89 / 100, hour, 90))
        assertTrue(WatchThreshold.isWatched(hour * 90 / 100, hour, 90))
        assertTrue(WatchThreshold.isWatched(hour, hour, 90))
    }

    @Test
    fun thresholdIsClampedToItsRange() {
        assertEquals(50, WatchThreshold.clamp(10))
        assertEquals(100, WatchThreshold.clamp(120))
        // A 30% setting behaves as the 50% floor.
        assertFalse(WatchThreshold.isWatched(hour * 40 / 100, hour, 30))
        assertTrue(WatchThreshold.isWatched(hour * 50 / 100, hour, 30))
    }

    @Test
    fun unknownRuntimeIsNeverWatched() {
        assertFalse(WatchThreshold.isWatched(hour, null, 90))
        assertFalse(WatchThreshold.isWatched(hour, 0, 90))
    }

    @Test
    fun resumePastTheThresholdStartsOver() {
        assertEquals(0, WatchThreshold.startPosition(hour * 95 / 100, hour, 90))
        assertEquals(hour / 2, WatchThreshold.startPosition(hour / 2, hour, 90))
        // Without a runtime the resume point is kept.
        assertEquals(hour, WatchThreshold.startPosition(hour, null, 90))
    }
}
