package app.zmby.search

import app.zmby.aggregate.SearchSnapshot
import app.zmby.aggregate.SearchState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveSearchTest {
    private fun snapshot(done: Boolean) = SearchSnapshot(emptyList(), mapOf("s" to if (done) SearchState.Done else SearchState.Querying), done)

    @Test fun `typing is debounced and incremental results appear without pressing search`() = runTest {
        val calls = mutableListOf<String>()
        val live = LiveSearch(this, { term, _ -> flow {
            calls += term
            emit(snapshot(false)); delay(100); emit(snapshot(true))
        } })
        live.submit("f"); advanceTimeBy(200)
        live.submit("fc"); advanceTimeBy(200)
        live.submit("fcz"); advanceTimeBy(399); runCurrent()
        assertTrue(calls.isEmpty())
        advanceTimeBy(2); runCurrent()
        assertEquals(listOf("fcz"), calls)
        assertEquals(false, live.state.value.snapshot?.done)
        advanceTimeBy(100); runCurrent()
        assertEquals(true, live.state.value.snapshot?.done)
    }

    @Test fun `a new term or clearing immediately cancels stale results`() = runTest {
        val cancelled = mutableListOf<String>()
        val live = LiveSearch(this, { term, _ -> flow {
            try { delay(1000); emit(snapshot(true)) } finally { cancelled += term }
        } })
        live.submit("old", immediate = true); runCurrent()
        live.submit("new"); runCurrent()
        assertEquals(listOf("old"), cancelled)
        assertEquals("new", live.state.value.term)
        assertNull(live.state.value.snapshot)
        live.submit(""); advanceTimeBy(2000); runCurrent()
        assertEquals(LiveSearchState(), live.state.value)
    }

    @Test fun `candidate previews search Chinese and explicit submission is not repeated by typing effect`() = runTest {
        val calls = mutableListOf<String>()
        val history = mutableListOf<String>()
        val live = LiveSearch(this, { term, _ -> calls += term; flowOf(snapshot(true)) }, history::add)
        live.submit("xjcy"); advanceTimeBy(401); runCurrent()
        live.submit("星际穿越"); advanceTimeBy(401); runCurrent()
        assertEquals(listOf("xjcy", "星际穿越"), calls)
        assertTrue(history.isEmpty())
        live.submit("星际穿越", immediate = true, saveHistory = true); runCurrent()
        live.submit("星际穿越"); advanceTimeBy(401); runCurrent()
        assertEquals(3, calls.size)
        assertEquals(listOf("星际穿越"), history)
    }

    @Test fun `live candidate mode uses local titles without duplicate remote calls`() = runTest {
        var calls = 0
        val candidates = PinyinCandidates(this, { listOf(object : CandidateServer {
            override val key = "server"
            override suspend fun search(term: String, limit: Int): List<app.zmby.emby.BaseItemDto> { calls++; return emptyList() }
        }) }, { listOf(LocalTitle("星际穿越", 2014)) })
        candidates.onInput("xjcy", fetchRemote = false)
        advanceTimeBy(1000); runCurrent()
        assertEquals(0, calls)
        assertEquals(listOf("星际穿越"), candidates.candidates.value.map { it.title })
    }
}
