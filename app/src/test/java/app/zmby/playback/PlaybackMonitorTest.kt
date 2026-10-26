package app.zmby.playback

import app.zmby.zidoo.PlayerStatusSource
import app.zmby.zidoo.ZidooResult
import app.zmby.zidoo.ZidooStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackMonitorTest {

    private class FakePlayer : PlayerStatusSource {
        var status: ZidooStatus? = null
        val seeks = mutableListOf<Long>()
        override suspend fun status() = status
        override suspend fun seekTo(positionMs: Long): Boolean {
            seeks += positionMs
            status = status?.copy(positionMs = positionMs)
            return true
        }
    }

    private class Recorder : PlaybackReporter {
        val events = mutableListOf<String>()
        override suspend fun start(positionMs: Long) { events += "start@$positionMs" }
        override suspend fun progress(positionMs: Long, paused: Boolean) { events += "progress@$positionMs${if (paused) " paused" else ""}" }
        override suspend fun stopped(positionMs: Long) { events += "stopped@$positionMs" }
        override suspend fun markPlayed() { events += "played" }
    }

    private fun playing(pos: Long, path: String = "a.mkv") = ZidooStatus(true, pos, 9_000_000, path, null)

    private fun TestScope.monitor(player: FakePlayer, reporter: Recorder, start: Long = 0, runtime: Long? = 9_000_000, percent: Int = 90) =
        PlaybackMonitor(player, reporter, start, runtime, percent, now = { testScheduler.currentTime })

    @Test
    fun reportsStartThenProgressEveryTenSeconds() = runTest {
        val player = FakePlayer()
        val reporter = Recorder()
        val m = monitor(player, reporter)
        val job = launch { m.watch() }
        advanceTimeBy(2_000)
        player.status = playing(1_000)
        advanceTimeBy(1_000)
        assertEquals(listOf("start@1000"), reporter.events)

        player.status = playing(11_000)
        advanceTimeBy(10_500)
        assertEquals("progress@11000", reporter.events.last())
        job.cancel()

        m.finish(ZidooResult(positionMs = 12_345, durationMs = null, endBy = "user"))
        assertEquals("stopped@12345", reporter.events.last())
    }

    @Test
    fun seeksWhenThePlayerIgnoredTheRequestedStart() = runTest {
        val player = FakePlayer().apply { status = playing(0) }
        val reporter = Recorder()
        val job = launch { monitor(player, reporter, start = 600_000).watch() }
        advanceTimeBy(100)
        assertEquals(listOf(600_000L), player.seeks)
        assertEquals("start@600000", reporter.events.first())
        job.cancel()
    }

    @Test
    fun skipsTheIntroThroughThePlayerAndReportsTheJump() = runTest {
        val player = FakePlayer().apply { status = playing(58_000) }
        val reporter = Recorder()
        val skipper = kotlinx.coroutines.CompletableDeferred(
            app.zmby.segments.SegmentSkipper(listOf(app.zmby.segments.Segment(app.zmby.segments.SegmentKind.Intro, 60_000, 150_000)), skipIntro = true, skipRecap = true, skipEnd = true),
        )
        val m = PlaybackMonitor(player, reporter, 0, 9_000_000, 90, now = { testScheduler.currentTime }, skipper = skipper)
        val job = launch { m.watch() }
        advanceTimeBy(1_100)
        player.status = playing(59_000)
        advanceTimeBy(1_000)
        player.status = playing(60_500)
        advanceTimeBy(1_000)
        assertEquals(listOf(150_000L), player.seeks)
        assertEquals("progress@150000", reporter.events.last())
        assertEquals(1, m.skips)
        job.cancel()
    }

    @Test
    fun pauseIsReportedImmediately() = runTest {
        val player = FakePlayer().apply { status = playing(5_000) }
        val reporter = Recorder()
        val job = launch { monitor(player, reporter).watch() }
        advanceTimeBy(1_500)
        player.status = playing(6_000).copy(playing = false)
        advanceTimeBy(1_000)
        assertEquals("progress@6000 paused", reporter.events.last())
        job.cancel()
    }

    @Test
    fun stopsWatchingWhenThePlayerOpensAnotherFile() = runTest {
        val player = FakePlayer().apply { status = playing(5_000) }
        val reporter = Recorder()
        val m = monitor(player, reporter)
        val job = launch { m.watch() }
        advanceTimeBy(1_500)
        player.status = playing(1_000, path = "b.mkv")
        advanceTimeBy(30_000)
        assertTrue(job.isCompleted)
        assertEquals(5_000L, m.lastPositionMs)
    }

    @Test
    fun completionReportsTheFullDuration() = runTest {
        val player = FakePlayer().apply { status = playing(8_990_000) }
        val reporter = Recorder()
        val m = monitor(player, reporter)
        val job = launch { m.watch() }
        advanceTimeBy(500)
        job.cancel()
        m.finish(ZidooResult(positionMs = null, durationMs = 9_001_000, endBy = "playback_completion"))
        assertEquals(listOf("stopped@9001000", "played"), reporter.events.takeLast(2))
    }

    @Test
    fun resultAloneStillReportsWhenTheStatusApiNeverAnswered() = runTest {
        val reporter = Recorder()
        val m = monitor(FakePlayer(), reporter, start = 30_000)
        m.finish(ZidooResult(positionMs = 95_000, durationMs = null, endBy = "user"))
        assertEquals(listOf("start@30000", "stopped@95000"), reporter.events)
    }

    @Test
    fun nothingKnownLeavesTheResumePointAlone() = runTest {
        val reporter = Recorder()
        val m = monitor(FakePlayer(), reporter, start = 30_000)
        m.finish(null)
        m.finish(ZidooResult(1, null, null)) // second call is ignored
        assertEquals(emptyList<String>(), reporter.events)
    }

    @Test
    fun finalPositionRules() {
        assertEquals(9_000_000L, resolveFinalPosition(ZidooResult(null, null, "playback_completion"), 8_000_000, 9_000_000))
        assertEquals(42L, resolveFinalPosition(ZidooResult(42, null, "user"), 10, null))
        assertEquals(10L, resolveFinalPosition(ZidooResult(0, null, "user"), 10, null))
        assertEquals(null, resolveFinalPosition(null, null, 9_000_000))
    }

    @Test
    fun stoppingPastTheThresholdMarksPlayed() = runTest {
        val player = FakePlayer()
        val reporter = Recorder()
        val m = monitor(player, reporter, runtime = 1_000_000, percent = 90)
        val job = launch { m.watch() }
        player.status = playing(950_000)
        advanceTimeBy(2_000)
        job.cancel()
        val outcome = m.finish(ZidooResult(positionMs = 950_000, durationMs = 1_000_000, endBy = "user"))
        assertEquals(PlaybackOutcome(950_000, watched = true), outcome)
        assertEquals(listOf("start@950000", "stopped@950000", "played"), reporter.events)
    }

    @Test
    fun stoppingBeforeTheThresholdOnlyReportsThePosition() = runTest {
        val player = FakePlayer()
        val reporter = Recorder()
        val m = monitor(player, reporter, runtime = 1_000_000, percent = 90)
        val outcome = m.finish(ZidooResult(positionMs = 500_000, durationMs = 1_000_000, endBy = "user"))
        assertEquals(false, outcome?.watched)
        assertTrue("played" !in reporter.events)
    }

    @Test
    fun unscrapedItemUsesThePlayersDuration() = runTest {
        // The server has no runtime yet; the player's duration decides.
        val player = FakePlayer()
        val reporter = Recorder()
        val m = monitor(player, reporter, runtime = null, percent = 90)
        val outcome = m.finish(ZidooResult(positionMs = 590_000, durationMs = 600_000, endBy = "user"))
        assertEquals(true, outcome?.watched)
        assertEquals("played", reporter.events.last())
    }
}
