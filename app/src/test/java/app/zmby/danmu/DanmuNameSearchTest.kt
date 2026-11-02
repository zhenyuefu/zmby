package app.zmby.danmu

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DanmuNameSearchTest {
    @Test fun `all names start concurrently without a limit and results retain preference order`() = runTest {
        val search = DanmuNameSearch()
        val release = List(12) { CompletableDeferred<String?>() }
        val started = mutableListOf<Int>()
        var active = 0
        val result = async { search.first((0..11).map(Int::toString)) {
            val index = it.toInt(); started += index; active++
            try { release[index].await() } finally { active-- }
        } }
        runCurrent()
        assertEquals((0..11).toList(), started)
        assertEquals(12, active)
        release[11].complete("last alias")
        runCurrent(); assertFalse(result.isCompleted)
        for (index in 0..9) release[index].complete(null)
        release[10].complete("preferred alias")
        assertEquals("preferred alias", result.await())
        assertEquals(0, active)
    }

    @Test fun `caller cancellation closes every lookup and errors do not mask a successful alias`() = runTest {
        val search = DanmuNameSearch()
        var active = 0
        val pending = async { search.first(listOf("a", "b", "c")) {
            active++
            try { awaitCancellation() } finally { active-- }
        } }
        runCurrent(); assertEquals(3, active)
        pending.cancelAndJoin(); assertEquals(0, active)
        assertEquals("yes", search.first(listOf("error", "miss", "hit")) {
            when (it) { "error" -> throw IOException("network"); "hit" -> "yes"; else -> null }
        })
        try {
            search.first<String>(listOf("error", "miss")) { if (it == "error") throw IOException("network") else null }
            fail("partial errors must not turn into a cacheable miss")
        } catch (_: IOException) { }
    }
}
