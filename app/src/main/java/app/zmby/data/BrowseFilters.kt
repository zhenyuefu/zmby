package app.zmby.data

import kotlinx.serialization.Serializable

/**
 * What one server's browse grid can sort and filter by (ui-spec §2.8: 按服务器实际支持的筛选项). Forks and
 * proxies (a server that serves TMDB listings, LinPlayer) ignore some or all of the query parameters:
 * a filter that does not narrow the library is not offered, nor is a sort the server does not keep.
 * Probed once a day per server, library and kind ([BrowseFilterProbe]); [known] is false until then,
 * and everything shows as before.
 */
@Serializable
data class BrowseFilters(
    val known: Boolean = false,
    /** False when the server keeps none of the checked sorts: no sort controls at all. */
    val sortable: Boolean = true,
    val badSorts: Set<BrowseSort> = emptySet(),
    val watched: Boolean = true,
    val favorite: Boolean = true,
    val uhd: Boolean = true,
    /** Decades holding titles, newest first. */
    val decades: List<Int> = BrowseQuery.DECADES,
    val genres: Boolean = true,
    val probedAt: Long = 0,
) {
    /** The sorts offered, in the panel's order; empty when the server ignores sorting. */
    val sorts: List<BrowseSort> get() = if (!sortable) emptyList() else BrowseSort.entries.filter { it !in badSorts }

    /** Anything at all for the 筛选 panel ([genreList] = the genres the server listed). */
    fun anyFilter(genreList: List<String>): Boolean = watched || favorite || uhd || decades.isNotEmpty() || (genres && genreList.isNotEmpty())

    /**
     * [q] without what this server cannot do: unsupported filters dropped and an unsupported sort
     * replaced by the first one it keeps. Unchanged until probed.
     */
    fun apply(q: BrowseQuery): BrowseQuery {
        if (!known) return q
        val keep = sorts
        val sort = if (keep.isEmpty() || q.sort in keep) q.sort else keep.first()
        return q.copy(
            sort = sort,
            descending = if (sort != q.sort) sort.descendingByDefault else q.descending,
            genres = if (genres) q.genres else emptySet(),
            decades = q.decades.filterTo(LinkedHashSet()) { it in decades },
            watched = if (watched) q.watched else WatchFilter.All,
            favorite = q.favorite && favorite,
            uhd = q.uhd && uhd,
        )
    }

    fun stale(now: Long): Boolean = !known || now - probedAt !in 0 until MAX_AGE_MS

    companion object {
        const val MAX_AGE_MS = 24 * 60 * 60 * 1000L

        /** 合集: collections sort by title, date added or release; none of the filters apply. */
        val COLLECTIONS = BrowseFilters(
            known = true,
            badSorts = setOf(BrowseSort.Rating, BrowseSort.Played, BrowseSort.PlayCount),
            watched = false, favorite = false, uhd = false, decades = emptyList(), genres = false,
        )

        fun decadesOf(years: Collection<Int>): List<Int> = years.filter { it in 1880..2200 }.map { it / 10 * 10 }.distinct().sortedDescending()

        /**
         * The rules, without I/O. A count of null is a request that failed: that filter stays (not
         * known to be unsupported). A filter whose count equals the library's total either is ignored
         * by the server or would not narrow anything; either way it is not offered.
         */
        fun from(p: BrowseFilterProbe, now: Long): BrowseFilters {
            val total = p.total
            fun narrows(n: Int?) = n == null || n != total
            if (total <= 1) return BrowseFilters(known = true, decades = p.years?.let(::decadesOf) ?: BrowseQuery.DECADES, probedAt = now)
            val decades = p.years?.let(::decadesOf) ?: BrowseQuery.DECADES
            val checked = p.sortsKept.filterKeys { it in SORT_PROBES }
            return BrowseFilters(
                known = true,
                // Every checked sort out of order: the server ignores SortBy altogether.
                sortable = checked.isEmpty() || checked.values.any { it },
                badSorts = checked.filterValues { !it }.keys,
                watched = !(p.played == total && p.unplayed == total),
                favorite = narrows(p.favorite),
                // No 4K at all would only ever show an empty grid.
                uhd = p.uhd == null || p.uhd in 1 until total,
                decades = if (narrows(p.yearCount)) decades else emptyList(),
                genres = narrows(p.genreCount),
                probedAt = now,
            )
        }

        /** The sorts checked against the order of their first page ([SortCheck] can read their fields). */
        val SORT_PROBES = listOf(BrowseSort.Released, BrowseSort.Added, BrowseSort.Rating)
    }
}

/** The answers [BrowseFilters.from] decides on: counts with Limit=0, null where the request failed. */
data class BrowseFilterProbe(
    val total: Int,
    val played: Int? = null,
    val unplayed: Int? = null,
    val favorite: Int? = null,
    val uhd: Int? = null,
    /** Years the server lists for the library (`/Items/Filters`); null when it does not answer. */
    val years: List<Int>? = null,
    /** The count with the newest decade's years. */
    val yearCount: Int? = null,
    /** The count with the first listed genre. */
    val genreCount: Int? = null,
    /** Whether each checked sort's first page came back in that order ([SortCheck]). */
    val sortsKept: Map<BrowseSort, Boolean> = emptyMap(),
)
