package app.zmby.aggregate

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SchedulingTest {
    private class Book : HealthBook {
        val failures = mutableListOf<String>()
        val successes = mutableListOf<String>()
        val tracker = FailureTracker()
        override fun isCooling(serverId: String) = tracker.isCooling(serverId)
        override fun onSuccess(serverId: String) { successes += serverId; tracker.success(serverId) }
        override fun onFailure(serverId: String, error: Throwable, timedOut: Boolean) { failures += serverId; tracker.failure(serverId, error.message.orEmpty(), timedOut) }
    }

    @Test fun `queue wait does not consume a server deadline even under multiple home rows`() = runTest {
        val book = Book()
        val fan = FanOut(book, maxConcurrent = 2, scope = backgroundScope)
        val out = fan.all((1..21).map { "s$it" }, 50) { delay(30); "ok" }
        assertEquals(21, out.filterIsInstance<Outcome.Ok<String>>().size)
        assertTrue(book.failures.isEmpty())
    }

    @Test fun `a shared queued search gets its full timeout after acquiring a slot`() = runTest {
        val book = Book()
        val fan = FanOut(book, maxConcurrent = 1, scope = backgroundScope)
        val occupied = CompletableDeferred<Unit>()
        val holder = async { fan.all(listOf("busy"), 1000) { occupied.complete(Unit); delay(300); "ok" } }
        occupied.await()
        val queued = async { fan.all(listOf("healthy"), 50, share = "search:term") { delay(20); "ok" } }
        assertTrue(queued.await().single() is Outcome.Ok)
        holder.await()
        assertTrue(book.failures.isEmpty())
    }

    @Test fun `one failed shared request counts once for both consumers`() = runTest {
        val book = Book()
        val fan = FanOut(book, scope = backgroundScope)
        val broken: suspend (String) -> String = { delay(50); error("down") }
        val a = async { fan.all(listOf("s"), 100, "same", broken) }
        val b = async { fan.all(listOf("s"), 100, "same", broken) }
        assertTrue(a.await().single() is Outcome.Failed)
        assertTrue(b.await().single() is Outcome.Failed)
        assertEquals(listOf("s"), book.failures)
        assertFalse(book.isCooling("s"))
        fan.all(listOf("s"), 100, "same", broken)
        assertTrue(book.isCooling("s"))
    }

    @Test fun `one cancelled subscriber does not cancel a request still used by another`() = runTest {
        val book = Book()
        val fan = FanOut(book, scope = backgroundScope)
        var calls = 0
        val call: suspend (String) -> String = { calls++; delay(100); "ok" }
        val a = async { fan.all(listOf("s"), 500, "same", call) }
        val b = async { fan.all(listOf("s"), 500, "same", call) }
        runCurrent(); a.cancel(); a.join()
        assertTrue(b.await().single() is Outcome.Ok)
        assertEquals(1, calls)
        assertEquals(listOf("s"), book.successes)
        assertTrue(book.failures.isEmpty())
    }

    @Test fun `last subscriber leaving cancels old work and releases its slot`() = runTest {
        val book = Book()
        val fan = FanOut(book, maxConcurrent = 1, scope = backgroundScope)
        var cancelled = false
        val old = async { fan.all(listOf("old"), 5000, "old-query") { try { delay(4000); "late" } finally { cancelled = true } } }
        runCurrent(); old.cancel(); old.join(); runCurrent()
        assertTrue(cancelled)
        val fresh = fan.all(listOf("new"), 50) { "fresh" }.single()
        assertTrue(fresh is Outcome.Ok)
        assertTrue(book.failures.isEmpty())
    }

    @Test fun `outer cancellation and queued cancellation never mark a server as failed`() = runTest {
        val book = Book()
        val fan = FanOut(book, maxConcurrent = 1, scope = backgroundScope)
        assertNull(withTimeoutOrNull(20) { fan.all(listOf("s"), 500) { delay(100); "ok" } })
        val holder = async { fan.all(listOf("busy"), 500) { delay(100); "ok" } }
        runCurrent()
        var called = false
        val queued = async { fan.all(listOf("queued"), 50) { called = true; "ok" } }
        runCurrent(); queued.cancel(); queued.join(); holder.await()
        assertFalse(called)
        assertTrue(book.failures.isEmpty())
    }

    @Test fun `health is checked again after queueing and a real timeout still fails`() = runTest {
        val book = Book()
        val fan = FanOut(book, maxConcurrent = 1, scope = backgroundScope)
        val holder = async { fan.all(listOf("busy"), 500) { delay(100); "ok" } }
        runCurrent()
        var called = false
        val queued = async { fan.all(listOf("paused"), 50) { called = true; "ok" } }
        runCurrent()
        repeat(2) { book.tracker.failure("paused", "known failure", false) }
        holder.await()
        assertTrue(queued.await().single() is Outcome.Skipped)
        assertFalse(called)
        val failed = fan.all(listOf("slow"), 20) { delay(50); "late" }.single()
        assertTrue(failed is Outcome.Failed && failed.timedOut)
        assertEquals(listOf("slow"), book.failures)
    }
}
