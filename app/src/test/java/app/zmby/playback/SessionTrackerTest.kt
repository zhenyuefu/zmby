package app.zmby.playback

import app.zmby.store.WatchSpan
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTrackerTest {
    /** Plays from [from] for [seconds], one sample a second, starting at wall time [at]. */
    private fun SessionTracker.play(at: Long, from: Long, seconds: Int, step: Long = 1_000): Pair<Long, Long> {
        var t = at; var p = from
        repeat(seconds) { t += step; p += step; sample(t, p, true) }
        return t to p
    }

    @Test fun `steady playback counts wall time as one span`() {
        val t = SessionTracker(10_000, 0, playing = true)
        t.play(10_000, 0, 60)
        val s = t.snapshot()
        assertEquals(60_000L, s.watchedMs)
        assertEquals(listOf(WatchSpan(10_000, 70_000)), s.spans)
        assertEquals(60_000L, s.positionMs)
        assertEquals(70_000L, s.lastSampleAt)
    }

    @Test fun `a pause counts nothing and splits the span`() {
        val t = SessionTracker(0, 0, playing = true)
        val (at, pos) = t.play(0, 0, 10)
        // Paused for 5 minutes: the position stands still.
        repeat(300) { t.sample(at + (it + 1) * 1_000L, pos, false) }
        t.play(at + 300_000, pos, 10)
        val s = t.snapshot()
        // The first second after the pause has a paused sample on one end, so it is not counted.
        assertEquals(19_000L, s.watchedMs)
        assertEquals(listOf(WatchSpan(0, 10_000), WatchSpan(311_000, 320_000)), s.spans)
    }

    @Test fun `the first sample after resuming from pause does not count the paused interval`() {
        val t = SessionTracker(0, 5_000, playing = false)
        t.sample(30_000, 5_000, false)
        t.sample(31_000, 6_000, true)
        t.sample(32_000, 7_000, true)
        assertEquals(1_000L, t.snapshot().watchedMs)
    }

    @Test fun `seeking forward or back is not watching, playing afterwards is`() {
        val t = SessionTracker(0, 0, playing = true)
        t.play(0, 0, 10)
        t.sample(11_000, 600_000, true) // jump ahead ten minutes in one second
        t.sample(12_000, 601_000, true)
        t.sample(13_000, 100_000, true) // jump back
        t.sample(14_000, 101_000, true)
        val s = t.snapshot()
        assertEquals(12_000L, s.watchedMs)
        assertEquals(101_000L, s.positionMs)
    }

    @Test fun `a seek zmby makes resets the reference`() {
        val t = SessionTracker(0, 0, playing = true)
        t.play(0, 0, 5)
        t.seeked(5_600, 90_000)
        t.sample(6_600, 91_000, true)
        assertEquals(6_000L, t.snapshot().watchedMs)
        assertEquals(2, t.snapshot().spans.size)
    }

    @Test fun `a stall counts only what advanced, short ones join`() {
        val t = SessionTracker(0, 0, playing = true)
        t.play(0, 0, 5)
        // Buffering: playing but stuck for three seconds.
        t.sample(6_000, 5_000, true); t.sample(7_000, 5_000, true); t.sample(8_000, 5_000, true)
        t.sample(9_000, 6_000, true)
        val s = t.snapshot()
        assertEquals(6_000L, s.watchedMs)
        assertEquals(listOf(WatchSpan(0, 5_000), WatchSpan(8_000, 9_000)), s.spans)
    }

    @Test fun `poll jitter joins into one span and never exceeds wall time`() {
        val t = SessionTracker(0, 0, playing = true)
        t.sample(1_050, 1_000, true)
        t.sample(2_120, 2_100, true)
        t.sample(3_100, 3_000, true)
        val s = t.snapshot()
        assertEquals(1, s.spans.size)
        assertEquals(3_050L, s.watchedMs) // 1 000 + 1 070 + 900, plus the 50 ms first gap the join covers
    }

    @Test fun `zero positions are ignored`() {
        val t = SessionTracker(0, 10_000, playing = true)
        t.sample(1_000, 0, true)
        t.sample(2_000, 12_000, true)
        assertEquals(2_000L, t.snapshot().watchedMs)
    }

    @Test fun `a full span list keeps counting time`() {
        val t = SessionTracker(0, 0, playing = true)
        var at = 0L; var pos = 0L
        repeat(SessionTracker.MAX_SPANS + 10) {
            at += 1_000; pos += 1_000; t.sample(at, pos, true)
            at += 5_000; t.sample(at, pos, false); at += 1_000; t.sample(at, pos, true)
        }
        val s = t.snapshot()
        assertEquals(SessionTracker.MAX_SPANS, s.spans.size)
        assertEquals((SessionTracker.MAX_SPANS + 10) * 1_000L, s.watchedMs)
    }
}
