package app.zmby.aggregate

import app.zmby.emby.BaseItemDto

/*
 * Cross-server matching (aggregation spec §3.2). A wrong merge is worse than none: the user picks
 * "the 4K version" and gets another film, with no error anywhere. So only strong and possible
 * matches merge; several strong candidates, conflicting IDs or a year mismatch mean no merge.
 */

/** How sure a match is; only [Strong] and [Possible] are merged and shown. */
enum class Confidence { Strong, Possible, Weak, None }

/** A judged match and the reason shown on its server card (「TMDB 一致」…). */
data class Verdict(val confidence: Confidence, val reason: String) {
    val merges: Boolean get() = confidence == Confidence.Strong || confidence == Confidence.Possible
}

object Titles {
    private val BRACKETS = Regex("\\[[^\\]]*]|【[^】]*】|\\([^)]*\\)|（[^）]*）")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val SPACE = Regex("\\s+")

    /**
     * Lower-case, drop `[...]` and `(...)` (also the full-width forms), fold everything that is not a
     * letter, digit or Han character into spaces, squeeze spaces: 「[某字幕组] 某剧 (2024)」 → 「某剧」.
     */
    fun normalize(title: String?): String =
        title.orEmpty().lowercase().replace(BRACKETS, " ").replace(NON_WORD, " ").trim().replace(SPACE, " ")

    /** The file name of [path] (either slash), without extension, normalized; empty when there is none. */
    fun fileFingerprint(path: String?): String {
        val name = path?.replace('\\', '/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: return ""
        val base = if ('.' in name) name.substringBeforeLast('.') else name
        return normalize(base.replace('.', ' ').replace('_', ' '))
    }

    /** One normalized title contains the other (both at least two characters). */
    fun close(a: String, b: String): Boolean {
        if (a.length < 2 || b.length < 2) return false
        return a == b || a.contains(b) || b.contains(a)
    }
}

/** ProviderIds with case-insensitive keys (Tmdb / TMDB / tmdb), trimmed values; blank = absent. */
fun BaseItemDto.providerId(name: String): String? =
    providerIds.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.trim()?.takeIf { it.isNotEmpty() }

fun BaseItemDto.tmdb() = providerId("Tmdb")
fun BaseItemDto.imdb() = providerId("Imdb")?.lowercase()
fun BaseItemDto.tvdb() = providerId("Tvdb")

object Keys {
    /** Merge key of a film: tmdb > imdb > PresentationUniqueKey > title + year; null when only the item itself identifies it. */
    fun movie(item: BaseItemDto): String? {
        item.tmdb()?.let { return "movie:tmdb:$it" }
        item.imdb()?.let { return "movie:imdb:$it" }
        item.presentationUniqueKey?.takeIf { it.isNotBlank() }?.let { return "movie:puk:$it" }
        val t = Titles.normalize(item.name)
        val y = item.productionYear
        return if (t.isNotEmpty() && y != null) "movie:title:$t:year:$y" else null
    }

    /** Merge key of a series: tmdb > tvdb > imdb > title + year. */
    fun series(item: BaseItemDto): String? {
        item.tmdb()?.let { return "series:tmdb:$it" }
        item.tvdb()?.let { return "series:tvdb:$it" }
        item.imdb()?.let { return "series:imdb:$it" }
        val t = Titles.normalize(item.name)
        val y = item.productionYear
        return if (t.isNotEmpty() && y != null) "series:title:$t:year:$y" else null
    }

    /** `<series key>:s01:e07`; null without a series key or episode numbers. */
    fun episode(seriesKey: String?, season: Int?, episode: Int?): String? {
        if (seriesKey == null || season == null || episode == null) return null
        return "%s:s%02d:e%02d".format(seriesKey, season, episode)
    }

    /** A key that only says "this item on this server" (never matches anything else). */
    fun local(server: String, item: BaseItemDto) = "item:$server:${item.id}"

    /** The work an item belongs to, for favourites and dedupe: its strong key, else only itself. */
    fun work(server: String, item: BaseItemDto): String = when (item.type) {
        "Series" -> series(item)
        "Movie" -> movie(item)
        else -> null
    } ?: local(server, item)
}

/** What a pair says before uniqueness is known. */
enum class PairLevel { Strong, Plausible, None }

data class PairJudgement(val level: PairLevel, val reason: String)

object Match {
    /**
     * Compares a film or series ([a]) with a candidate ([b]) of the same type. External IDs decide
     * when both sides have them (equal → strong, different → none; None ≠ None). Without them:
     * title + year, or close titles with the same file name, or close titles alone are plausible —
     * they merge only when they are the only candidate ([judge]). Different years never merge.
     */
    fun compare(a: BaseItemDto, b: BaseItemDto): PairJudgement {
        if (a.type != null && b.type != null && a.type != b.type) return PairJudgement(PairLevel.None, "类型不同")
        // Most pairs are decided by IDs: do not normalize titles and paths for those pairs.
        val tmdbA = a.tmdb(); val tmdbB = b.tmdb()
        if (tmdbA != null && tmdbB != null) return if (tmdbA == tmdbB) PairJudgement(PairLevel.Strong, "TMDB 一致") else PairJudgement(PairLevel.None, "TMDB 不同")
        val imdbA = a.imdb(); val imdbB = b.imdb()
        if (imdbA != null && imdbB != null) return if (imdbA == imdbB) PairJudgement(PairLevel.Strong, "IMDb 一致") else PairJudgement(PairLevel.None, "IMDb 不同")
        val tvdbA = a.tvdb(); val tvdbB = b.tvdb()
        if (tvdbA != null && tvdbB != null) return if (tvdbA == tvdbB) PairJudgement(PairLevel.Strong, "TVDB 一致") else PairJudgement(PairLevel.None, "TVDB 不同")
        if (a.productionYear != null && b.productionYear != null && a.productionYear != b.productionYear) return PairJudgement(PairLevel.None, "年份不同")
        val titleA = Titles.normalize(a.name); val titleB = Titles.normalize(b.name)
        if (titleA.isEmpty() || titleB.isEmpty()) return PairJudgement(PairLevel.None, "缺少片名")
        if (titleA == titleB && a.productionYear != null && b.productionYear != null) return PairJudgement(PairLevel.Plausible, "片名 + 年份一致")
        val close = Titles.close(titleA, titleB)
        val fileA = if (close) Titles.fileFingerprint(a.path) else ""
        if (close && fileA.isNotEmpty() && fileA == Titles.fileFingerprint(b.path)) return PairJudgement(PairLevel.Plausible, "片名接近 + 文件名一致")
        if (close) return PairJudgement(PairLevel.Plausible, "仅片名接近")
        return PairJudgement(PairLevel.None, "片名不同")
    }

    /**
     * Compares episodes: the series' IDs (or the episode's own TMDB) plus season and episode
     * numbers. [aSeries] / [bSeries] are the series items (episodes carry no series IDs).
     */
    fun compareEpisode(a: BaseItemDto, aSeries: BaseItemDto?, b: BaseItemDto, bSeries: BaseItemDto?): PairJudgement {
        val s1 = a.parentIndexNumber
        val e1 = a.indexNumber
        if (s1 == null || e1 == null || s1 != b.parentIndexNumber || e1 != b.indexNumber) return PairJudgement(PairLevel.None, "季集号不同")
        val code = "S$s1 E$e1"
        if (aSeries != null && bSeries != null) {
            val series = compare(aSeries, bSeries)
            when {
                series.level == PairLevel.Strong -> return PairJudgement(PairLevel.Strong, "剧${series.reason} · $code")
                series.reason.endsWith("不同") && series.reason != "片名不同" -> return PairJudgement(PairLevel.None, "剧${series.reason}")
            }
        }
        val t1 = a.tmdb()
        val t2 = b.tmdb()
        if (t1 != null && t2 != null) return if (t1 == t2) PairJudgement(PairLevel.Strong, "单集 TMDB 一致 · $code") else PairJudgement(PairLevel.None, "单集 TMDB 不同")
        val n1 = Titles.normalize(aSeries?.name ?: a.seriesName)
        val n2 = Titles.normalize(bSeries?.name ?: b.seriesName)
        if (n1.isNotEmpty() && n1 == n2) return PairJudgement(PairLevel.Plausible, "剧名 + 季集号一致")
        val f1 = Titles.fileFingerprint(a.path)
        if (f1.isNotEmpty() && f1 == Titles.fileFingerprint(b.path)) return PairJudgement(PairLevel.Plausible, "文件名 + 季集号一致")
        return PairJudgement(PairLevel.None, "剧名不同")
    }

    /**
     * Picks the one candidate that matches, or none: exactly one strong match → strong; several
     * strong ones → none (could be either); no strong one, exactly one plausible → possible;
     * several plausible → weak (not merged).
     */
    fun <T> judge(candidates: List<T>, compare: (T) -> PairJudgement): Pair<T, Verdict>? {
        val judged = candidates.map { it to compare(it) }
        val strong = judged.filter { it.second.level == PairLevel.Strong }
        if (strong.size > 1) return null
        strong.singleOrNull()?.let { (c, j) -> return c to Verdict(Confidence.Strong, j.reason) }
        val plausible = judged.filter { it.second.level == PairLevel.Plausible }
        val one = plausible.singleOrNull() ?: return null
        return one.first to Verdict(Confidence.Possible, one.second.reason)
    }

    /** Like [judge] but explains a refusal (for logs and the server card). */
    fun <T> explain(candidates: List<T>, compare: (T) -> PairJudgement): String {
        val judged = candidates.map { compare(it) }
        val strong = judged.count { it.level == PairLevel.Strong }
        val plausible = judged.count { it.level == PairLevel.Plausible }
        return when {
            candidates.isEmpty() -> "没有候选"
            strong > 1 -> "$strong 个同 ID 候选，未合并"
            plausible > 1 -> "$plausible 个相近候选，未合并"
            else -> "没有匹配的候选"
        }
    }
}
