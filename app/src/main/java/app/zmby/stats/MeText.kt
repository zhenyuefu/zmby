package app.zmby.stats

import app.zmby.media.formatDuration
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** 我的's wording (ui-spec §2.17 and §5: no slashes; dates as 「10 月 31 日」). */
object MeText {
    private const val HOUR = 3_600_000.0

    /** 「1.6」 hours, one decimal; whole hours from 100. */
    fun hours(ms: Long): String {
        val h = ms / HOUR
        return if (h >= 100) String.format(Locale.ROOT, "%.0f", h) else String.format(Locale.ROOT, "%.1f", h)
    }

    /** 「3.6 时」 for a top-work row and the bar scale; under six minutes 「4 分」, so a short watch is not 「0 时」. */
    fun hoursShort(ms: Long): String =
        if (ms < HOUR / 10) "${maxOf(1L, (ms / 60_000.0).roundToLong())} 分" else "${hours(ms).removeSuffix(".0")} 时"

    /** 「7.2 小时」, or 「4 分钟」 for a short watch that would read 「0.0 小时」. */
    fun amount(ms: Long): String =
        if (ms in 1 until HOUR.toLong() / 10) "${maxOf(1L, (ms / 60_000.0).roundToLong())} 分钟" else "${hours(ms)} 小时"

    /** The change in hours against the previous period, 「↑ 3.9」 or 「↓ 1.2」; null when it rounds to nothing. */
    fun delta(current: Long, previous: Long): Delta? {
        val tenths = ((current - previous) / HOUR * 10).roundToLong()
        if (tenths == 0L) return null
        val text = String.format(Locale.ROOT, "%.1f", abs(tenths) / 10.0)
        return Delta(if (tenths > 0) "↑ $text" else "↓ $text", tenths > 0)
    }

    data class Delta(val text: String, val up: Boolean)

    fun weekday(date: LocalDate): String = when (date.dayOfWeek!!) {
        DayOfWeek.MONDAY -> "周一"
        DayOfWeek.TUESDAY -> "周二"
        DayOfWeek.WEDNESDAY -> "周三"
        DayOfWeek.THURSDAY -> "周四"
        DayOfWeek.FRIDAY -> "周五"
        DayOfWeek.SATURDAY -> "周六"
        DayOfWeek.SUNDAY -> "周日"
    }

    fun date(date: LocalDate): String = "${date.monthValue} 月 ${date.dayOfMonth} 日"

    /** 「今天」「昨天」「明天」, else 「10 月 29 日」. */
    fun relativeDate(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        today.plusDays(1) -> "明天"
        else -> date(date)
    }

    /** 「十月」 for the month cards. */
    fun monthName(month: Int): String = listOf("一月", "二月", "三月", "四月", "五月", "六月", "七月", "八月", "九月", "十月", "十一月", "十二月")[month - 1]

    /** 「1 部电影 · 2 集剧集」. */
    fun works(movies: Int, episodes: Int): String = "$movies 部电影 · $episodes 集剧集"

    /** The data range under the title: the charts only know this box's sessions. */
    fun scope(since: Long?, zone: ZoneId): String =
        if (since == null) "时长来自本机播放记录 · 在 zmby 里播放后开始统计"
        else "时长来自本机播放记录 · 自 ${date(Instant.ofEpochMilli(since).atZone(zone).toLocalDate())}起"

    /** 最近看过's second line: 「S1 E7 · 今天 · 剩余 18 分钟 · 家里 NAS」, 「昨天 · 看完 · 家里 NAS」. */
    fun recentLine(entry: RecentEntry, today: LocalDate, zone: ZoneId, server: String?): String {
        val code = if (entry.season != null && entry.episode != null) "S${entry.season} E${entry.episode}" else null
        val day = relativeDate(Instant.ofEpochMilli(entry.at).atZone(zone).toLocalDate(), today)
        val state = when {
            entry.completed -> "看完"
            entry.episodeType -> formatDuration(entry.remainingMs)?.let { "剩余 $it" }
            else -> entry.fraction?.let { "看到 ${(it * 100).roundToInt().coerceAtMost(99)}%" }
        }
        return listOfNotNull(code, day, state, server).joinToString(" · ")
    }
}
