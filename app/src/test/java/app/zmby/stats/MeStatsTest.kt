package app.zmby.stats

import app.zmby.playback.SessionTracker
import app.zmby.store.PlayHistoryRow
import app.zmby.store.PlaySessionRow
import app.zmby.store.SessionSpans
import app.zmby.store.WatchSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

class MeStatsTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val min = 60_000L
    private val hour = 3_600_000L
    private fun at(m: Int, d: Int, h: Int, minute: Int = 0) = ZonedDateTime.of(2026, m, d, h, minute, 0, 0, zone).toInstant().toEpochMilli()
    /** Saturday 31 October 2026, 21:00. */
    private val now = at(10, 31, 21)

    private var nextId = 1L
    private fun session(
        spans: List<WatchSpan>,
        server: String = "a",
        work: String = "movie:tmdb:1",
        title: String = "沙丘2",
        episode: Boolean = false,
        series: String? = null,
        seriesName: String? = null,
        genres: List<String> = emptyList(),
        runtime: Long? = 2 * hour,
        end: Long? = null,
        completed: Boolean = false,
    ): PlaySessionRow {
        val start = spans.first().start
        return PlaySessionRow(
            id = nextId++, serverId = server, itemId = "i$nextId", type = if (episode) "Episode" else "Movie", workKey = work,
            seriesKey = series, seriesName = seriesName, title = title, season = if (episode) 1 else null, episode = if (episode) 7 else null,
            genres = genres, startedAt = start, endedAt = spans.last().end, watchedMs = spans.sumOf { it.length },
            spans = SessionSpans.encode(start, spans), endPositionMs = end, runtimeMs = runtime, completed = completed, open = false,
        )
    }
    private fun span(from: Long, length: Long) = WatchSpan(from, from + length)

    @Test fun `nothing recorded is empty with every chart drawn blank`() {
        val s = MeStats.summarize(emptyList(), now, zone)
        assertTrue(s.empty)
        assertEquals(0L, s.today.watchedMs)
        assertEquals(24, s.today.hours.size)
        assertEquals(7, s.week.days.size)
        assertEquals(7, s.last7.points.size)
        assertEquals(26 * 7, s.heat.size)
        // Sunday 1 November is still to come.
        assertEquals(listOf(LocalDate.of(2026, 11, 1)), s.heat.filter { it.level == -1 }.map { it.date })
        assertEquals(31, s.month.days.size)
        assertEquals(6 * hour, s.month.scaleMs)
        assertTrue(s.month.top.isEmpty())
        assertTrue(s.genres.slices.isEmpty())
        assertTrue(s.genreTrends.isEmpty())
    }

    @Test fun `a session over midnight counts on both days and in the right hours`() {
        val s = MeStats.summarize(listOf(session(listOf(span(at(10, 30, 23, 30), 75 * min)))), now, zone)
        assertEquals(45 * min, s.today.watchedMs)
        assertEquals(45 * min, s.today.hours[0])
        assertEquals(0L, s.today.hours.drop(1).sum())
        assertEquals(30 * min, s.week.days[4].movieMs) // Friday
        assertEquals(45 * min, s.week.days[5].movieMs) // Saturday
        assertEquals(1, s.today.movies)
        assertEquals(75 * min, s.month.watchedMs)
        assertEquals(30 * min, s.month.days[29].totalMs)
        assertEquals(45 * min, s.month.days[30].totalMs)
        assertEquals(30 * min, s.heat.single { it.date == LocalDate.of(2026, 10, 30) }.watchedMs)
    }

    @Test fun `a session crossing hours is split by hour`() {
        val s = MeStats.summarize(listOf(session(listOf(span(at(10, 31, 19, 40), 100 * min)))), now, zone)
        assertEquals(20 * min, s.today.hours[19])
        assertEquals(60 * min, s.today.hours[20])
        assertEquals(20 * min, s.today.hours[21])
    }

    @Test fun `pauses and seeks recorded by the tracker are not watching time`() {
        val start = at(10, 31, 20)
        val t = SessionTracker(start, 0, playing = true)
        var clock = start; var pos = 0L
        fun play(seconds: Int) = repeat(seconds) { clock += 1_000; pos += 1_000; t.sample(clock, pos, true) }
        play(600) // ten minutes
        repeat(1_200) { clock += 1_000; t.sample(clock, pos, false) } // paused twenty minutes
        play(300)
        clock += 1_000; pos += 1_800_000; t.sample(clock, pos, true) // seek half an hour ahead
        play(300)
        val p = t.snapshot()
        val row = session(p.spans)
        val s = MeStats.summarize(listOf(row), now, zone)
        // 600 + 299 (the first second after the pause has a paused end) + 300 seconds; the jump itself counts nothing.
        assertEquals((600 + 299 + 300) * 1_000L, s.today.watchedMs)
        assertEquals(p.watchedMs, s.today.watchedMs)
        assertEquals(s.today.watchedMs, s.today.hours[20])
    }

    @Test fun `the same work on two servers counts once, its time adds up`() {
        val sessions = listOf(
            session(listOf(span(at(10, 31, 14), 30 * min)), server = "a", work = "movie:tmdb:1"),
            session(listOf(span(at(10, 31, 16), 50 * min)), server = "b", work = "movie:tmdb:1", title = "沙丘：第二部"),
            session(listOf(span(at(10, 31, 18), 40 * min)), server = "a", work = "series:tmdb:5:s01:e07", title = "第 7 集", episode = true, series = "series:tmdb:5", seriesName = "漫长的季节"),
            session(listOf(span(at(10, 31, 19), 40 * min)), server = "b", work = "series:tmdb:5:s01:e07", title = "第 7 集", episode = true, series = "series:tmdb:5", seriesName = "漫长的季节"),
            session(listOf(span(at(10, 31, 20), 45 * min)), server = "b", work = "series:tmdb:5:s01:e08", title = "第 8 集", episode = true, series = "series:tmdb:5", seriesName = "漫长的季节"),
        )
        val s = MeStats.summarize(sessions, now, zone)
        assertEquals(1, s.today.movies)
        assertEquals(2, s.today.episodes)
        assertEquals(205 * min, s.today.watchedMs)
        assertEquals(1, s.genres.movies)
        assertEquals(1, s.genres.series)
        assertEquals(listOf("漫长的季节" to 125 * min, "沙丘：第二部" to 80 * min), s.month.top.map { it.title to it.watchedMs })
        assertEquals(listOf(true, false), s.month.top.map { it.episode })
    }

    @Test fun `a few seconds of a work do not count it`() {
        val s = MeStats.summarize(listOf(session(listOf(span(at(10, 31, 14), 20_000)))), now, zone)
        assertEquals(0, s.today.movies)
        assertEquals(20_000L, s.today.watchedMs)
    }

    @Test fun `the week stacks films over series per day, Monday first`() {
        val sessions = listOf(
            session(listOf(span(at(10, 26, 20), hour))),
            session(listOf(span(at(10, 28, 21), 30 * min)), work = "series:tmdb:5:s01:e01", episode = true, series = "series:tmdb:5"),
            session(listOf(span(at(10, 28, 22), 15 * min)), work = "movie:tmdb:2"),
            session(listOf(span(at(10, 25, 20), hour))), // last Sunday: not this week
        )
        val w = MeStats.summarize(sessions, now, zone).week
        assertEquals(listOf(TypeSplit(hour, 0), TypeSplit(), TypeSplit(15 * min, 30 * min), TypeSplit(), TypeSplit(), TypeSplit(), TypeSplit()), w.days)
        assertEquals(105 * min, w.watchedMs)
        assertEquals(2, w.movies)
        assertEquals(1, w.episodes)
    }

    @Test fun `the last seven days compare with the seven before`() {
        val sessions = listOf(
            session(listOf(span(at(10, 25, 20), 2 * hour))), // today − 6
            session(listOf(span(at(10, 31, 20), hour))),
            session(listOf(span(at(10, 24, 20), 30 * min))), // today − 7: the previous period
            session(listOf(span(at(10, 17, 20), 30 * min))), // today − 14: neither
        )
        val t = MeStats.summarize(sessions, now, zone).last7
        assertEquals(3 * hour, t.watchedMs)
        assertEquals(30 * min, t.previousMs)
        assertEquals(listOf(2 * hour, 0, 0, 0, 0, 0, hour), t.points)
        assertEquals(MeText.Delta("↑ 2.5", true), MeText.delta(t.watchedMs, t.previousMs))
    }

    @Test fun `the heat map has 26 Monday-first weeks ending this week, four levels`() {
        val sessions = listOf(
            session(listOf(span(at(10, 31, 20), 20 * min))),
            session(listOf(span(at(10, 30, 20), hour))),
            session(listOf(span(at(10, 29, 18), 3 * hour))),
            session(listOf(span(at(5, 4, 20), hour))), // Monday 4 May, the first cell
            session(listOf(span(at(5, 3, 20), hour))), // before the map
        )
        val heat = MeStats.summarize(sessions, now, zone).heat
        assertEquals(LocalDate.of(2026, 5, 4), heat.first().date)
        assertEquals(LocalDate.of(2026, 11, 1), heat.last().date)
        assertEquals(2, heat.first().level)
        assertEquals(1, heat.single { it.date == LocalDate.of(2026, 10, 31) }.level)
        assertEquals(2, heat.single { it.date == LocalDate.of(2026, 10, 30) }.level)
        assertEquals(3, heat.single { it.date == LocalDate.of(2026, 10, 29) }.level)
        assertEquals(4, heat.count { it.level > 0 })
        assertEquals(listOf(0, 1, 1, 2, 2, 3), listOf(0L, 1L, 44 * min, 45 * min, 2 * hour - 1, 2 * hour).map(MeStats::heatLevel))
    }

    @Test fun `this month against the same days of last month, bars scaled in 3-hour steps`() {
        val sessions = listOf(
            session(listOf(span(at(10, 15, 10), 7 * hour))),
            session(listOf(span(at(10, 31, 19), hour)), work = "series:tmdb:5:s01:e01", episode = true, series = "series:tmdb:5"),
            session(listOf(span(at(9, 30, 20), 2 * hour))),
            session(listOf(span(at(8, 31, 20), 2 * hour))), // two months back
        )
        val m = MeStats.summarize(sessions, now, zone).month
        assertEquals(YearMonth.of(2026, 10), m.month)
        assertEquals(8 * hour, m.watchedMs)
        assertEquals(2 * hour, m.previousMs)
        assertEquals(TypeSplit(7 * hour, 0), m.days[14])
        assertEquals(TypeSplit(0, hour), m.days[30])
        assertEquals(9 * hour, m.scaleMs)
        // Mid-month the comparison stops at the same day.
        val mid = MeStats.summarize(sessions + session(listOf(span(at(9, 10, 20), hour))), at(10, 15, 22), zone).month
        assertEquals(hour, mid.previousMs)
    }

    @Test fun `genres share a session's time, top three and the rest, two trends`() {
        val sessions = listOf(
            session(listOf(span(at(10, 31, 14), 4 * hour)), genres = listOf("犯罪", "动作")),
            session(listOf(span(at(10, 30, 14), 2 * hour)), work = "movie:tmdb:2", genres = listOf("悬疑")),
            session(listOf(span(at(10, 29, 14), hour)), work = "movie:tmdb:3", genres = listOf("科幻")),
            session(listOf(span(at(10, 28, 14), 3 * hour)), work = "movie:tmdb:4"),
            session(listOf(span(at(9, 20, 14), hour)), work = "movie:tmdb:5", genres = listOf("犯罪")),
        )
        val s = MeStats.summarize(sessions, now, zone)
        // Ties go by name; 科幻 falls into 其他 with the untagged film.
        assertEquals(listOf("动作", "悬疑", "犯罪", MeStats.OTHER), s.genres.slices.map { it.name })
        assertEquals(listOf(2 * hour, 2 * hour, 2 * hour, 4 * hour), s.genres.slices.map { it.watchedMs })
        assertEquals(1f, s.genres.slices.sumOf { it.fraction.toDouble() }.toFloat(), 0.001f)
        assertEquals(10 * hour, s.genres.watchedMs)
        assertEquals(4, s.genres.movies)
        assertEquals(listOf("动作", "悬疑"), s.genreTrends.map { it.name }.sorted())
        val crime = MeStats.summarize(sessions.filter { "悬疑" !in it.genres }, now, zone).genreTrends.first()
        assertEquals("动作", crime.name) // tie with 犯罪 broken by name
        assertEquals(8, crime.trend.points.size)
        assertEquals(2 * hour, crime.trend.points.last())
        val criminal = MeStats.summarize(sessions.filter { "悬疑" !in it.genres }, now, zone).genreTrends.single { it.name == "犯罪" }
        assertEquals(hour, criminal.trend.previousMs)
    }

    @Test fun `recent merges sessions and other clients' history by work, newest first`() {
        val sessions = listOf(
            session(listOf(span(at(10, 31, 20), 40 * min)), work = "series:tmdb:5:s01:e07", episode = true, series = "series:tmdb:5", seriesName = "漫长的季节", runtime = 58 * min, end = 40 * min),
            session(listOf(span(at(10, 28, 20), 50 * min)), work = "movie:tmdb:1", runtime = 100 * min, end = 43 * min),
        )
        val history = listOf(
            PlayHistoryRow("b", "x", "Episode", "series:tmdb:5:s01:e06", seriesKey = "series:tmdb:5", seriesName = "漫长的季节", playedAt = at(10, 30, 20)),
            PlayHistoryRow("b", "y", "Movie", "movie:tmdb:9", title = "奥本海默", playedAt = at(10, 30, 21)),
            PlayHistoryRow("a", "z", "Movie", "movie:tmdb:1", title = "沙丘2", playedAt = at(10, 28, 20, 50)),
            PlayHistoryRow("a", "w", "Movie", "movie:tmdb:8", title = "无日期"),
        )
        val recent = MeStats.recent(sessions, history, 10)
        assertEquals(listOf("漫长的季节", "奥本海默", "沙丘2"), recent.map { it.title })
        assertFalse(recent[0].fromHistory)
        assertTrue(recent[1].fromHistory)
        // Same time: this box's session wins, it knows where it stopped.
        assertFalse(recent[2].fromHistory)
        val today = LocalDate.of(2026, 10, 31)
        assertEquals("S1 E7 · 今天 · 剩余 18 分钟 · 家里 NAS", MeText.recentLine(recent[0], today, zone, "家里 NAS"))
        assertEquals("昨天 · 看完 · 公益服 A", MeText.recentLine(recent[1], today, zone, "公益服 A"))
        assertEquals("10 月 28 日 · 看到 43%", MeText.recentLine(recent[2], today, zone, null))
        assertEquals(1, MeStats.recent(sessions, history, 1).size)
    }

    @Test fun `wording`() {
        assertEquals("1.6", MeText.hours(96 * min))
        assertEquals("0.0", MeText.hours(0))
        assertEquals("120", MeText.hours(120 * hour))
        assertEquals("3.6 时", MeText.hoursShort((3.6 * hour).toLong()))
        assertEquals("7 时", MeText.hoursShort(7 * hour))
        assertEquals("1 分", MeText.hoursShort(20_000))
        assertEquals("5 分", MeText.hoursShort(5 * min))
        assertEquals("4 分钟", MeText.amount(4 * min))
        assertEquals("0.0 小时", MeText.amount(0))
        assertEquals("7.2 小时", MeText.amount((7.2 * hour).toLong()))
        assertEquals(MeText.Delta("↓ 1.2", false), MeText.delta(hour, (2.2 * hour).toLong()))
        assertNull(MeText.delta(hour, hour + 60_000))
        assertNull(MeText.delta(0, 0))
        assertEquals("周六", MeText.weekday(LocalDate.of(2026, 10, 31)))
        assertEquals("明天", MeText.relativeDate(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 10, 31)))
        assertEquals("11 月 2 日", MeText.relativeDate(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 10, 31)))
        assertEquals("十月", MeText.monthName(10))
        assertEquals("时长来自本机播放记录 · 自 10 月 31 日起", MeText.scope(now, zone))
        assertFalse(MeText.scope(null, zone).contains('/'))
    }
}
