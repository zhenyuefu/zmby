package app.zmby.data

import app.zmby.emby.BaseItemDto
import kotlinx.serialization.Serializable

/** 浏览页排序 (ui-spec §2.8): Emby's SortBy for each, and which way it goes by default. */
@Serializable
enum class BrowseSort(val label: String, val sortBy: String, val descendingByDefault: Boolean) {
    Released("发行日期", "PremiereDate,ProductionYear,SortName", true),
    Added("添加时间", "DateCreated,SortName", true),
    Title("片名", "SortName", false),
    Rating("社区评分", "CommunityRating,SortName", true),
    Played("最近播放", "DatePlayed,SortName", true),
    PlayCount("播放次数", "PlayCount,SortName", true),
}

@Serializable
enum class WatchFilter(val label: String, val emby: String?) { All("全部", null), Unplayed("未看", "IsUnplayed"), Played("已看", "IsPlayed") }

/**
 * What one browse grid shows: sort, direction and filters (ui-spec §2.8). Remembered per server and
 * library; [params] are the Emby query parameters, [key] names its cached pages.
 */
@Serializable
data class BrowseQuery(
    val sort: BrowseSort = BrowseSort.Released,
    val descending: Boolean = true,
    val genres: Set<String> = emptySet(),
    /** Decades by first year (2020 = 2020–2029). */
    val decades: Set<Int> = emptySet(),
    val watched: WatchFilter = WatchFilter.All,
    val favorite: Boolean = false,
    val uhd: Boolean = false,
) {
    /** Changing the sort field resets the direction to its default (片名 ascending, the rest descending). */
    fun sortedBy(s: BrowseSort) = copy(sort = s, descending = s.descendingByDefault)

    val filtered: Boolean get() = genres.isNotEmpty() || decades.isNotEmpty() || watched != WatchFilter.All || favorite || uhd

    fun cleared() = copy(genres = emptySet(), decades = emptySet(), watched = WatchFilter.All, favorite = false, uhd = false)

    fun params(currentYear: Int = java.time.Year.now().value): Map<String, String> = buildMap {
        put("SortBy", sort.sortBy)
        put("SortOrder", if (descending) "Descending" else "Ascending")
        listOfNotNull(watched.emby, "IsFavorite".takeIf { favorite }).takeIf { it.isNotEmpty() }?.let { put("Filters", it.joinToString(",")) }
        // Emby separates genre names with a pipe (names can contain commas).
        if (genres.isNotEmpty()) put("Genres", genres.sorted().joinToString("|"))
        if (decades.isNotEmpty()) put("Years", years(currentYear).joinToString(","))
        if (uhd) put("Is4K", "true")
    }

    fun years(currentYear: Int): List<Int> = decades.sorted().flatMap { d -> (d..minOf(d + 9, currentYear)).toList() }

    /** Stable text for cache keys: the same query names the same pages. */
    val key: String
        get() = listOf(
            sort.name, if (descending) "d" else "a", genres.sorted().joinToString("+"), decades.sorted().joinToString("+"),
            watched.name, if (favorite) "f" else "", if (uhd) "4k" else "",
        ).joinToString(";")

    /** The chips after the toolbar: (label, the query without it). */
    fun chips(): List<Pair<String, BrowseQuery>> =
        genres.sorted().map { it to copy(genres = genres - it) } +
            decades.sorted().map { "${it} 年代" to copy(decades = decades - it) } +
            listOfNotNull(
                if (watched != WatchFilter.All) watched.label to copy(watched = WatchFilter.All) else null,
                if (favorite) "收藏" to copy(favorite = false) else null,
                if (uhd) "4K" to copy(uhd = false) else null,
            )

    companion object {
        val DECADES = listOf(2020, 2010, 2000, 1990, 1980, 1970, 1960)
    }
}

/**
 * Some forks ignore SortBy on some endpoints (LinPlayer): the first page of a sort is checked, and a
 * server that does not keep the order gets a note instead of a fake client-side sort of one page.
 * Only fields the list carries are checked; titles are left alone (servers sort Chinese by their own
 * collation).
 */
object SortCheck {
    fun consistent(items: List<BaseItemDto>, sort: BrowseSort, descending: Boolean): Boolean {
        val keys: List<String> = when (sort) {
            BrowseSort.Released -> items.mapNotNull { it.premiereDate?.take(10) ?: it.productionYear?.let { y -> "%04d".format(y) } }
            BrowseSort.Added -> items.mapNotNull { it.dateCreated }
            BrowseSort.Rating -> items.mapNotNull { it.communityRating?.let { r -> "%06.2f".format(r) } }
            else -> return true
        }
        if (keys.size < 3) return true
        val pairs = keys.zipWithNext()
        val wrong = pairs.count { (a, b) -> if (descending) a < b else a > b }
        // A stray pair can be a missing date filled by the server; most pairs out of order is not.
        return wrong * 4 <= pairs.size
    }
}

/** 字母轨 letters (ui-spec §2.8): # then A–Z. */
object LetterRail {
    val LETTERS: List<String> = listOf("#") + ('A'..'Z').map { it.toString() }
}
