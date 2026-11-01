package app.zmby.playback

import app.zmby.danmu.DanmuClock
import app.zmby.zidoo.PlayerStatusSource
import app.zmby.zidoo.ZidooResult
import app.zmby.zidoo.ZidooStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackReportQueueTest {
    @Test fun `slow start and progress writes do not stop samples and finish waits for ordered delivery`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val reporter = object : PlaybackReporter {
            override suspend fun start(positionMs: Long) { gate.await(); events += "start@$positionMs" }
            override suspend fun progress(positionMs: Long, paused: Boolean) { events += "progress@$positionMs" }
            override suspend fun stopped(positionMs: Long) { events += "stop@$positionMs" }
        }
        val sampleTimes = mutableListOf<Long>()
        val clock = DanmuClock()
        val player = object : PlayerStatusSource {
            override suspend fun status() = ZidooStatus(true, 10_000 + testScheduler.currentTime, 100_000, "a.mkv", null)
            override suspend fun seekTo(positionMs: Long) = true
        }
        val monitor = PlaybackMonitor(player, reporter, 0, 100_000,
            now = { testScheduler.currentTime }, reportScope = this,
            config = MonitorConfig(reportIntervalMs = 1_000),
            overlay = object : MonitorOverlay {
                override fun onSample(positionMs: Long, durationMs: Long, playing: Boolean) {
                    sampleTimes += testScheduler.currentTime
                    clock.sample(positionMs, playing, testScheduler.currentTime)
                }
            })
        val watching = launch { monitor.watch() }
        advanceTimeBy(6_001)
        assertEquals((0..6).map { it * 1_000L }, sampleTimes)
        assertTrue(clock.running(testScheduler.currentTime))
        assertTrue(events.isEmpty())
        watching.cancelAndJoin()
        val finishing = launch { monitor.finish(ZidooResult(16_500, 100_000, "user")) }
        runCurrent()
        assertFalse(finishing.isCompleted)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("start@10000", "progress@16000", "stop@16500"), events)
        assertTrue(finishing.isCompleted)
    }

    @Test fun `coalescing preserves seek and pause boundaries and never sends progress after stop`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val reporter = object : PlaybackReporter {
            override suspend fun start(positionMs: Long) { gate.await(); events += "start" }
            override suspend fun progress(positionMs: Long, paused: Boolean) { events += "$positionMs/$paused" }
            override suspend fun stopped(positionMs: Long) { events += "stop@$positionMs" }
            override suspend fun markPlayed() { events += "played" }
        }
        val queue = PlaybackReportQueue(this, reporter, null)
        queue.start(0, 0, false)
        runCurrent()
        queue.progress(1_000, false, null)
        queue.progress(2_000, false, null)
        queue.progress(150_000, false, null, boundary = true)
        queue.progress(151_000, false, null)
        queue.progress(152_000, false, null)
        queue.progress(152_000, true, null, boundary = true)
        queue.progress(153_000, false, null, boundary = true)
        val finishing = launch { queue.finish(160_000, 160_000, true, null) }
        runCurrent()
        queue.progress(999_000, false, null)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("start", "2000/false", "150000/false", "152000/false", "152000/true",
            "153000/false", "stop@160000", "played"), events)
        assertTrue(finishing.isCompleted)
    }

    @Test fun `slow progress and local checkpoint writes leave the poll loop running`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val reporter = object : PlaybackReporter {
            override suspend fun start(positionMs: Long) {}
            override suspend fun progress(positionMs: Long, paused: Boolean) { gate.await() }
            override suspend fun stopped(positionMs: Long) {}
        }
        val session = object : PlaySessionSink {
            override suspend fun started(at: Long, positionMs: Long) {}
            override suspend fun checkpoint(progress: SessionProgress) { gate.await() }
            override suspend fun finished(progress: SessionProgress, positionMs: Long?, runtimeMs: Long?, completed: Boolean) {}
        }
        var samples = 0
        val player = object : PlayerStatusSource {
            override suspend fun status() = ZidooStatus(true, 1_000 + testScheduler.currentTime, 100_000, "a.mkv", null)
            override suspend fun seekTo(positionMs: Long) = true
        }
        val monitor = PlaybackMonitor(player, reporter, 0, 100_000, now = { testScheduler.currentTime },
            reportScope = this, session = session, config = MonitorConfig(reportIntervalMs = 1_000),
            overlay = object : MonitorOverlay {
                override fun onSample(positionMs: Long, durationMs: Long, playing: Boolean) { samples++ }
            })
        val watching = launch { monitor.watch() }
        advanceTimeBy(10_001)
        assertEquals(11, samples)
        watching.cancelAndJoin()
        gate.complete(Unit)
        monitor.finish(ZidooResult(11_000, 100_000, "user"))
    }
}
