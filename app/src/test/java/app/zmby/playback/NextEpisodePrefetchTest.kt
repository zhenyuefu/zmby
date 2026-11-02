package app.zmby.playback

import app.zmby.danmu.DanmuEndpoint
import app.zmby.danmu.Danmaku
import app.zmby.emby.BaseItemDto
import app.zmby.segments.SegmentLookup
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NextEpisodePrefetchTest {
    private val endpoint = DanmuEndpoint("https://danmu.example")
    private val next = BaseItemDto("next", type = "Episode")

    @Test fun `lookup and both loads start before current preparation completes and the next card does not wait for loads`() = runTest {
        val currentGate = CompletableDeferred<Unit>()
        val body = CompletableDeferred<List<Danmaku>?>()
        val marks = CompletableDeferred<SegmentLookup>()
        var lookedUp = false
        var bodyStarted = false
        var marksStarted = false
        val currentPreparation = async { currentGate.await() }
        val prefetch = NextEpisodePrefetch(this, "s", endpoint, 3,
            lookup = { lookedUp = true; next },
            comments = { bodyStarted = true; body.await() },
            segments = { marksStarted = true; marks.await() }, dispatcher = StandardTestDispatcher(testScheduler))
        runCurrent()
        assertTrue(lookedUp && bodyStarted && marksStarted)
        assertFalse(currentPreparation.isCompleted)
        val content = prefetch.ready.await()!!
        assertEquals(next, content.episode)
        assertFalse(content.comments!!.isCompleted)
        assertFalse(content.segments.isCompleted)
        assertNotNull(prefetch.completedFor("s", "next"))
        assertNull(prefetch.completedFor("other-server", "next"))
        assertNull(prefetch.completedFor("s", "another-episode"))
        body.complete(listOf(Danmaku(1_000, "next comments")))
        marks.complete(SegmentLookup())
        currentGate.complete(Unit)
        assertEquals("next comments", content.comments.await()!!.single().text)
        content.segments.await(); currentPreparation.await()
    }

    @Test fun `cancelling a speculative next item cancels both loads`() = runTest {
        val prefetch = NextEpisodePrefetch(this, "s", endpoint, 3, lookup = { next },
            comments = { CompletableDeferred<List<Danmaku>?>().await() },
            segments = { CompletableDeferred<SegmentLookup>().await() }, dispatcher = StandardTestDispatcher(testScheduler))
        runCurrent()
        val content = prefetch.ready.await()!!
        prefetch.cancel()
        runCurrent()
        assertTrue(content.comments!!.isCancelled)
        assertTrue(content.segments.isCancelled)
    }

    @Test fun `no next episode issues no downloads`() = runTest {
        val prefetch = NextEpisodePrefetch(this, "s", endpoint, 3, lookup = { null },
            comments = { error("must not download") }, segments = { error("must not query") },
            dispatcher = StandardTestDispatcher(testScheduler))
        assertNull(prefetch.ready.await())
    }
}
