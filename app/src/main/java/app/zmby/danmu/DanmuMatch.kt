package app.zmby.danmu

import app.zmby.aggregate.Titles

/** One anime of a search answer: its title, type text and the episode ids it returned. */
data class DanmuCandidate(val title: String, val type: String, val episodes: List<Long>)

/** What is being played, as the search needs it. */
data class DanmuQuery(
    /** Names to search, best first (series name or film title, then the original title). */
    val names: List<String>,
    val movie: Boolean,
    val season: Int? = null,
    val episode: Int? = null,
    /** The film's year or the series' first year; null skips the year check. */
    val year: Int? = null,
    /** Parent work identity. An episode's provider IDs are never used as series IDs. */
    val workId: String? = null,
    val catalogRef: app.zmby.catalog.CatalogId? = null,
)

/**
 * Conservative matching (danmu design: 匹配): the base title must equal the searched name, the year
 * and season must agree, the original version wins over dubbed ones, and anything unclear is no
 * match. Pure, for tests.
 */
object DanmuMatch {
    /** A candidate title taken apart. */
    data class Parsed(val base: String, val year: Int?, val season: Int?, val qualifiers: List<String>)

    private val TAIL_SOURCE = Regex("\\s*from\\s+\\S+\\s*$", RegexOption.IGNORE_CASE)
    private val TAIL_TYPE = Regex("\\s*(?:【[^】]*】|〖[^〗]*〗)\\s*$")
    private val TAIL_YEAR = Regex("\\s*[(（](\\d{4})[)）]\\s*$")
    private val BRACKET = Regex("\\[([^\\]]*)]|［([^］]*)］")
    private val DASH_VERSION = Regex("[-－]\\s*([^-－]*版[^-－]*)$")
    private val SPACE_VERSION = Regex("\\s+(普通话|国语|中配版?|粤语版?|粤配|日语版?|原版|英语版?|无障碍版?)$")
    private val SEASON = Regex("(?:第\\s*([0-9一二三四五六七八九十]+)\\s*[季部])|(?:\\bSeason\\s*(\\d+))|(?:\\bS(\\d{1,2})\\b)", RegexOption.IGNORE_CASE)
    private val EPISODE_SUFFIX = Regex("\\s+第\\s*\\d+\\s*[话話集].*$")

    fun parse(title: String): Parsed {
        var t = title.trim().replace(TAIL_SOURCE, "")
        t = t.replace(TAIL_TYPE, "")
        val year = TAIL_YEAR.find(t)?.groupValues?.get(1)?.toIntOrNull()
        t = t.replace(TAIL_YEAR, "")
        // "葬送的芙莉莲 第1季[普通话版] 第01话 葬送的芙莉莲[普通话版]": an episode title glued on.
        t = t.replace(EPISODE_SUFFIX, "")
        val qualifiers = mutableListOf<String>()
        BRACKET.findAll(t).forEach { m -> (m.groupValues[1].ifEmpty { m.groupValues[2] }).trim().takeIf { it.isNotEmpty() }?.let(qualifiers::add) }
        t = t.replace(BRACKET, " ")
        DASH_VERSION.find(t)?.let { qualifiers += it.groupValues[1].trim(); t = t.substring(0, it.range.first) }
        SPACE_VERSION.find(t.trim())?.let { qualifiers += it.groupValues[1]; t = t.trim().substring(0, it.range.first) }
        var season: Int? = null
        SEASON.find(t)?.let { m ->
            season = (m.groupValues[1].ifEmpty { m.groupValues[2].ifEmpty { m.groupValues[3] } }).let(::number)
            t = t.removeRange(m.range)
        }
        return Parsed(Titles.normalize(t), year, season, qualifiers)
    }

    /** The episode id to load, or null when nothing matches clearly. */
    fun choose(query: DanmuQuery, candidates: List<DanmuCandidate>): Long? {
        val names = query.names.map(Titles::normalize).filter { it.isNotEmpty() }.toSet()
        if (names.isEmpty()) return null
        val ok = candidates.mapIndexedNotNull { index, c ->
            val p = parse(c.title)
            if (p.base !in names) return@mapIndexedNotNull null
            if (query.year != null && p.year != null && kotlin.math.abs(query.year - p.year) > 1) return@mapIndexedNotNull null
            val isMovie = c.type.contains("电影") || c.type.contains("movie", ignoreCase = true)
            if (isMovie != query.movie) return@mapIndexedNotNull null
            if (!query.movie) {
                val season = query.season ?: 1
                if ((p.season ?: 1) != season) return@mapIndexedNotNull null
            }
            if (c.episodes.size != 1) return@mapIndexedNotNull null
            Triple(rank(p.qualifiers), index, c.episodes.single())
        }
        return ok.minWithOrNull(compareBy<Triple<Int, Int, Long>> { it.first }.thenBy { it.second })?.third
    }

    /** 0 no version tag, 1 original language, 2 other tags, 3 dubbed. */
    private fun rank(qualifiers: List<String>): Int {
        if (qualifiers.isEmpty()) return 0
        val all = qualifiers.joinToString(" ")
        return when {
            listOf("普通话", "国语", "中配", "粤语", "粤配", "配音", "无障碍").any { it in all } -> 3
            listOf("日语", "原版", "原声").any { it in all } -> 1
            else -> 2
        }
    }

    private fun number(s: String): Int? = s.toIntOrNull() ?: run {
        val digits = "一二三四五六七八九"
        when {
            s == "十" -> 10
            s.length == 1 -> digits.indexOf(s[0]).takeIf { it >= 0 }?.plus(1)
            s.length == 2 && s[0] == '十' -> digits.indexOf(s[1]).takeIf { it >= 0 }?.plus(11)
            else -> null
        }
    }
}
