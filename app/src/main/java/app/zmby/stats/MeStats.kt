package app.zmby.stats

import app.zmby.store.PlayHistoryRow
import app.zmby.store.PlaySessionRow
import app.zmby.store.SessionSpans
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Watched time of one day or bar, split by type (电影 indigo on top, 剧集 amber below). */
data class TypeSplit(val movieMs: Long = 0, val episodeMs: Long = 0) {
    val totalMs: Long get() = movieMs + episodeMs
}

/** 今天: the time, the works and 24 hourly totals. */
data class TodayCard(val date: LocalDate, val watchedMs: Long, val movies: Int, val episodes: Int, val hours: List<Long>)

/** 本周 (Monday first): seven stacked bars. */
data class WeekCard(val watchedMs: Long, val movies: Int, val episodes: Int, val days: List<TypeSplit>)

/** A period compared with the one before it (最近 7 天, a 类型趋势 card). */
data class TrendCard(val watchedMs: Long, val previousMs: Long, val points: List<Long>)

/** One cell of 观看记录 · 最近 26 周; [level] 0–3, or -1 for days still to come. */
data class HeatCell(val date: LocalDate, val watchedMs: Long, val level: Int)

/** 看得最多: a film, or a series across its episodes; [episode] decides the bar colour. */
data class TopWork(val key: String, val title: String, val episode: Boolean, val watchedMs: Long)

/** 本月观看: the month's days, its total against the same days of last month, and the scale of the bars. */
data class MonthCard(
    val month: YearMonth,
    val watchedMs: Long,
    val previousMs: Long,
    val days: List<TypeSplit>,
    /** The bars' full height: at least 6 hours, rounded up to 3-hour steps (the dashed lines are at ½ and 1). */
    val scaleMs: Long,
    val top: List<TopWork>,
)

data class GenreSlice(val name: String, val watchedMs: Long, val fraction: Float)

/** 类型分布 of this month: up to three genres and 其他, the films and series behind them. */
data class GenreCard(val watchedMs: Long, val movies: Int, val series: Int, val slices: List<GenreSlice>)

data class GenreTrend(val name: String, val trend: TrendCard)

/** Everything 我的 draws from this box's sessions (local-data spec, 怎么统计). */
data class MeSummary(
    val today: TodayCard,
    val week: WeekCard,
    val last7: TrendCard,
    val heat: List<HeatCell>,
    val month: MonthCard,
    val genres: GenreCard,
    val genreTrends: List<GenreTrend>,
    /** The first session ever recorded (the charts' data range); null with none. */
    val since: Long?,
) {
    val empty: Boolean get() = since == null
}

/** One entry of 最近看过: from this box's sessions or, for other clients, the servers' play history. */
data class RecentEntry(
    val key: String,
    val server: String,
    val itemId: String,
    val title: String,
    val season: Int?,
    val episode: Int?,
    val at: Long,
    val completed: Boolean,
    /** Fraction reached and time left, for a session that stopped part-way. */
    val fraction: Float?,
    val remainingMs: Long?,
    val episodeType: Boolean,
    val fromHistory: Boolean,
)

/**
 * 我的's aggregation, without I/O (tested; runs on Dispatchers.Default). Sessions are cut at local
 * hour boundaries by their spans, so a session over midnight counts on both days; works count once
 * across servers by work key (an episode's own key, a series by its series key); time is summed.
 */
object MeStats {
    const val HEAT_WEEKS = 26
    const val TREND_WEEKS = 8
    const val TOP = 3
    const val GENRES = 3
    /** A work counts in a period's numbers once it was watched this long there. */
    const val COUNT_MS = 60_000L
    const val OTHER = "其他"
    private const val HOUR = 3_600_000L

    /** A session's time within one local hour. */
    private class Piece(val session: PlaySessionRow, val date: LocalDate, val hour: Int, val ms: Long)

    /** The earliest local midnight [summarize] looks at: the first day of the heat map. */
    fun windowStart(now: Long, zone: ZoneId): Long {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val heat = today.with(DayOfWeek.MONDAY).minusWeeks((HEAT_WEEKS - 1).toLong())
        return minOf(heat, today.withDayOfMonth(1).minusMonths(1)).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** [sessions] needs everything ending after [windowStart]; [firstSession] is the oldest one stored. */
    fun summarize(sessions: List<PlaySessionRow>, now: Long, zone: ZoneId, firstSession: Long? = sessions.minOfOrNull { it.startedAt }): MeSummary {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val pieces = sessions.flatMap { pieces(it, zone) }
        val byDate = pieces.groupBy { it.date }
        fun day(d: LocalDate) = byDate[d].orEmpty()
        fun range(from: LocalDate, to: LocalDate): List<Piece> =
            generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.flatMap { day(it) }.toList()
        fun split(list: List<Piece>) = TypeSplit(list.filter { !it.session.episodeType }.sumOf { it.ms }, list.filter { it.session.episodeType }.sumOf { it.ms })

        val todayPieces = day(today)
        val (todayMovies, todayEpisodes) = counts(todayPieces)
        val todayCard = TodayCard(today, todayPieces.sumOf { it.ms }, todayMovies, todayEpisodes,
            (0 until 24).map { h -> todayPieces.filter { it.hour == h }.sumOf { it.ms } })

        val monday = today.with(DayOfWeek.MONDAY)
        val weekPieces = range(monday, monday.plusDays(6))
        val (weekMovies, weekEpisodes) = counts(weekPieces)
        val week = WeekCard(weekPieces.sumOf { it.ms }, weekMovies, weekEpisodes, (0L until 7L).map { split(day(monday.plusDays(it))) })

        val last7 = TrendCard(range(today.minusDays(6), today).sumOf { it.ms }, range(today.minusDays(13), today.minusDays(7)).sumOf { it.ms },
            (6L downTo 0L).map { day(today.minusDays(it)).sumOf { p -> p.ms } })

        val heatStart = monday.minusWeeks((HEAT_WEEKS - 1).toLong())
        val heat = (0L until HEAT_WEEKS * 7L).map { i ->
            val d = heatStart.plusDays(i)
            val ms = day(d).sumOf { it.ms }
            HeatCell(d, ms, if (d.isAfter(today)) -1 else heatLevel(ms))
        }

        val month = YearMonth.from(today)
        val first = month.atDay(1)
        val monthPieces = range(first, month.atEndOfMonth())
        val previous = month.minusMonths(1)
        val previousPieces = range(previous.atDay(1), previous.atDay(minOf(today.dayOfMonth, previous.lengthOfMonth())))
        val monthDays = (0L until month.lengthOfMonth()).map { split(day(first.plusDays(it))) }
        val maxDay = monthDays.maxOfOrNull { it.totalMs } ?: 0
        val monthCard = MonthCard(month, monthPieces.sumOf { it.ms }, previousPieces.sumOf { it.ms }, monthDays,
            maxOf(6 * HOUR, (maxDay + 3 * HOUR - 1) / (3 * HOUR) * 3 * HOUR), top(monthPieces))

        val genreTotals = genreTime(monthPieces)
        val ranked = genreTotals.filterKeys { it != OTHER }.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
        val shown = ranked.take(GENRES)
        val monthMs = monthPieces.sumOf { it.ms }
        val rest = monthMs - shown.sumOf { it.value }
        val slices = (shown.map { it.key to it.value } + listOfNotNull((OTHER to rest).takeIf { rest >= 1.0 }))
            .map { (name, ms) -> GenreSlice(name, ms.toLong(), if (monthMs > 0) (ms / monthMs).toFloat() else 0f) }
        val countedWorks = monthPieces.groupBy { it.session.workKey }.filterValues { it.sumOf { p -> p.ms } >= COUNT_MS }.values.map { it.first().session }
        val genres = GenreCard(monthMs, countedWorks.count { !it.episodeType },
            countedWorks.filter { it.episodeType }.map { it.seriesKey ?: it.workKey }.distinct().size, slices)

        val previousGenres = genreTime(previousPieces)
        val trendStart = monday.minusWeeks((TREND_WEEKS - 1).toLong())
        val genreTrends = ranked.take(2).map { (name, ms) ->
            val weeks = (0L until TREND_WEEKS).map { w -> genreTime(range(trendStart.plusWeeks(w), trendStart.plusWeeks(w).plusDays(6)))[name]?.toLong() ?: 0L }
            GenreTrend(name, TrendCard(ms.toLong(), previousGenres[name]?.toLong() ?: 0L, weeks))
        }
        return MeSummary(todayCard, week, last7, heat, monthCard, genres, genreTrends, firstSession)
    }

    /** Distinct films and episodes watched at least [COUNT_MS] in [list], each work once across servers. */
    private fun counts(list: List<Piece>): Pair<Int, Int> {
        val works = list.groupBy { it.session.workKey }.filterValues { it.sumOf { p -> p.ms } >= COUNT_MS }
        return works.count { !it.value.first().session.episodeType } to works.count { it.value.first().session.episodeType }
    }

    private fun top(list: List<Piece>): List<TopWork> = list.groupBy { it.session.seriesKey ?: it.session.workKey }.map { (key, ps) ->
        val newest = ps.maxBy { it.session.startedAt }.session
        TopWork(key, (if (newest.episodeType) newest.seriesName else null) ?: newest.title ?: "未命名", newest.episodeType, ps.sumOf { it.ms })
    }.filter { it.watchedMs > 0 }.sortedWith(compareByDescending<TopWork> { it.watchedMs }.thenBy { it.title }).take(TOP)

    /** Each session's time shared equally by its genres; none is [OTHER]. */
    private fun genreTime(list: List<Piece>): Map<String, Double> {
        val out = HashMap<String, Double>()
        for (p in list) {
            val names = p.session.genres.ifEmpty { listOf(OTHER) }
            names.forEach { out[it] = (out[it] ?: 0.0) + p.ms.toDouble() / names.size }
        }
        return out
    }

    /** 0 none, 1 under 45 minutes, 2 under 2 hours, 3 from 2 hours. */
    fun heatLevel(ms: Long): Int = when {
        ms <= 0 -> 0
        ms < 45 * 60_000L -> 1
        ms < 2 * HOUR -> 2
        else -> 3
    }

    /** [session]'s spans cut at local hour boundaries (DST-safe: boundaries come from the zone). */
    private fun pieces(session: PlaySessionRow, zone: ZoneId): List<Piece> {
        val out = ArrayList<Piece>()
        for (span in SessionSpans.decode(session.startedAt, session.spans)) {
            var start = span.start
            while (start < span.end) {
                val local = Instant.ofEpochMilli(start).atZone(zone)
                val next = local.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
                val end = minOf(next, span.end)
                out += Piece(session, local.toLocalDate(), local.hour, end - start)
                start = end
            }
        }
        return out
    }

    /**
     * 最近看过: the newest entry per work (a series by its series key), from this box's sessions and
     * the servers' play history together; history fills in what other clients played.
     */
    fun recent(sessions: List<PlaySessionRow>, history: List<PlayHistoryRow>, limit: Int): List<RecentEntry> {
        val fromSessions = sessions.map { s ->
            val runtime = s.runtimeMs?.takeIf { it > 0 }
            val position = s.endPositionMs ?: s.startPositionMs
            RecentEntry(
                key = s.seriesKey ?: s.workKey, server = s.serverId, itemId = s.itemId,
                title = (if (s.episodeType) s.seriesName else null) ?: s.title ?: "未命名",
                season = s.season, episode = s.episode, at = s.endedAt, completed = s.completed,
                fraction = if (!s.completed && runtime != null) (position.toFloat() / runtime).coerceIn(0f, 1f) else null,
                remainingMs = if (!s.completed && runtime != null) (runtime - position).coerceAtLeast(0) else null,
                episodeType = s.episodeType, fromHistory = false,
            )
        }
        val fromHistory = history.filter { it.playedAt != null }.map { h ->
            RecentEntry(
                key = h.seriesKey ?: h.workKey, server = h.serverId, itemId = h.itemId,
                title = (if (h.type == "Episode") h.seriesName else null) ?: h.title ?: "未命名",
                season = null, episode = null, at = h.playedAt!!, completed = true, fraction = null, remainingMs = null,
                episodeType = h.type == "Episode", fromHistory = true,
            )
        }
        // A tie goes to this box's session: it knows the position.
        return (fromSessions + fromHistory).groupBy { it.key }.values
            .map { same -> same.sortedWith(compareByDescending<RecentEntry> { it.at }.thenBy { it.fromHistory }).first() }
            .sortedByDescending { it.at }
            .take(limit)
    }
}
