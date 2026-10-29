package app.zmby.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SeriesDirectoryTest {
    private fun episode(season: Int) = CatalogEpisode("${season}01", season, 1, "Episode")

    @Test fun `selected season is published first and the completed directory spans the whole series`() = runBlocking {
        val states = seriesDirectory(listOf(0, 1, 2), 2) { listOf(episode(it)) }.toList()
        assertEquals(setOf(0, 1, 2), states.first().unresolved)
        assertEquals(setOf(2), states[1].pages.keys)
        assertEquals(setOf(0, 1, 2), states.last().pages.keys)
        assertTrue(states.last().unresolved.isEmpty())
    }

    @Test fun `season switches reuse loaded pages and failed seasons remain unresolved until retry`() = runBlocking {
        val requested = mutableListOf<Int>()
        val failed = seriesDirectory(listOf(0, 1, 2), 1, mapOf(1 to listOf(episode(1)))) {
            requested += it
            if (it == 2) error("temporary failure") else listOf(episode(it))
        }.last()
        assertEquals(setOf(0, 2), requested.toSet())
        assertEquals(setOf(2), failed.failed)
        assertEquals(setOf(2), failed.unresolved)
        requested.clear()
        val retried = seriesDirectory(listOf(0, 1, 2), 2, failed.pages) { requested += it; listOf(episode(it)) }.last()
        assertEquals(listOf(2), requested)
        assertTrue(retried.failed.isEmpty())
        assertTrue(retried.unresolved.isEmpty())
    }

    @Test fun `directory requests are bounded rather than launching every season at once`() = runBlocking {
        var active = 0
        var maximum = 0
        val directory = seriesDirectory((1..10).toList(), 1) {
            active++; maximum = maxOf(active, maximum)
            try { delay(1); listOf(episode(it)) } finally { active-- }
        }.last()
        assertEquals(10, directory.pages.size)
        assertTrue(maximum in 1..3)
    }

    @Test fun `cancellation is propagated instead of turning a season into a failed or empty page`() = runBlocking {
        try {
            seriesDirectory(listOf(1), 1) { throw CancellationException("navigation") }.last()
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
    }
}
