package app.zmby.stats

import app.zmby.store.PlayHistoryRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

class StatsAggregateTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(y: Int, m: Int, d: Int) = ZonedDateTime.of(y, m, d, 21, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val now = at(2026, 10, 25)

    private fun movie(server: String, id: String, key: String, played: Long?, runtime: Long = 7_200_000) =
        PlayHistoryRow(server, id, "Movie", key, runtimeMs = runtime, playedAt = played)
    private fun ep(server: String, id: String, key: String, series: String, name: String, played: Long?) =
        PlayHistoryRow(server, id, "Episode", key, seriesKey = series, seriesName = name, runtimeMs = 2_700_000, playedAt = played)

    @Test fun `a work watched on two servers counts once, dated by its latest play`() {
        val rows = listOf(
            movie("a", "1", "movie:tmdb:603", at(2026, 3, 1)),
            movie("b", "9", "movie:tmdb:603", at(2026, 9, 2)),
            movie("a", "2", "item:a:2", at(2026, 10, 3)),
            ep("a", "e1", "series:tmdb:1:s01:e01", "series:tmdb:1", "漫长的季节", at(2026, 10, 1)),
            ep("b", "x1", "series:tmdb:1:s01:e01", "series:tmdb:1", "漫长的季节", at(2026, 10, 2)),
            ep("a", "e2", "series:tmdb:1:s01:e02", "series:tmdb:1", "漫长的季节", at(2026, 10, 2)),
            ep("a", "f1", "series:tmdb:2:s01:e01", "series:tmdb:2", "繁花", null),
        )
        val s = StatsAggregate.summarize(rows, now, zone)
        assertEquals(2, s.movies)
        assertEquals(3, s.episodes)
        assertEquals(2, s.series)
        assertEquals(2 * 7_200_000L + 3 * 2_700_000L, s.totalMs)
        assertEquals(2, s.servers)
        assertEquals(1, s.undated)
        assertEquals(listOf("漫长的季节" to 2, "繁花" to 1), s.topSeries)
        assertEquals(12, s.months.size)
        assertEquals(YearMonth.of(2025, 11), s.months.first().month)
        val october = s.months.last()
        assertEquals(YearMonth.of(2026, 10), october.month)
        assertEquals(1, october.movies); assertEquals(2, october.episodes)
        assertEquals(1, s.months.first { it.month == YearMonth.of(2026, 9) }.movies)
        assertEquals(0, s.months.first { it.month == YearMonth.of(2026, 3) }.movies)
    }

    @Test fun `nothing watched is empty, dates parse`() {
        assertTrue(StatsAggregate.summarize(emptyList(), now, zone).empty)
        assertEquals(1792915121420L, StatsAggregate.parseDate("2026-10-25T07:58:41.4200000Z"))
        assertNull(StatsAggregate.parseDate("yesterday"))
    }
}
