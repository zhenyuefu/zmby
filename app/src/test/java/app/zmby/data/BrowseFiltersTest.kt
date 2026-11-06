package app.zmby.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseFiltersTest {
    private val now = 1_000_000L

    @Test fun `a server that honours everything keeps every option`() {
        val f = BrowseFilters.from(
            BrowseFilterProbe(total = 100, played = 30, unplayed = 70, favorite = 4, uhd = 12, years = listOf(2024, 2015, 1999, 2010),
                yearCount = 20, genreCount = 40, sortsKept = BrowseFilters.SORT_PROBES.associateWith { true }),
            now,
        )
        assertTrue(f.known && f.sortable && f.watched && f.favorite && f.uhd && f.genres)
        assertEquals(BrowseSort.entries, f.sorts)
        assertEquals(listOf(2020, 2010, 1990), f.decades)
        assertTrue(f.anyFilter(listOf("科幻")))
    }

    @Test fun `a server that ignores the query offers no sort and no filter`() {
        // A proxy listing TMDB titles: every count is the whole library, every sort comes back unsorted.
        val f = BrowseFilters.from(
            BrowseFilterProbe(total = 500, played = 500, unplayed = 500, favorite = 500, uhd = 500, years = null,
                yearCount = 500, genreCount = 500, sortsKept = BrowseFilters.SORT_PROBES.associateWith { false }),
            now,
        )
        assertFalse(f.sortable)
        assertTrue(f.sorts.isEmpty())
        assertFalse(f.watched || f.favorite || f.uhd || f.genres)
        assertTrue(f.decades.isEmpty())
        assertFalse(f.anyFilter(listOf("科幻")))
    }

    @Test fun `one sort out of order is dropped, the rest stay`() {
        val f = BrowseFilters.from(BrowseFilterProbe(total = 50, sortsKept = mapOf(BrowseSort.Released to true, BrowseSort.Added to true, BrowseSort.Rating to false)), now)
        assertTrue(f.sortable)
        assertEquals(setOf(BrowseSort.Rating), f.badSorts)
        assertFalse(BrowseSort.Rating in f.sorts)
    }

    @Test fun `failed requests leave their filters offered`() {
        val f = BrowseFilters.from(BrowseFilterProbe(total = 50), now)
        assertTrue(f.sortable && f.watched && f.favorite && f.uhd && f.genres)
        assertEquals(BrowseQuery.DECADES, f.decades)
    }

    @Test fun `no 4K in the library hides the 4K switch`() {
        assertFalse(BrowseFilters.from(BrowseFilterProbe(total = 50, uhd = 0), now).uhd)
        assertTrue(BrowseFilters.from(BrowseFilterProbe(total = 50, uhd = 3), now).uhd)
    }

    @Test fun `nothing watched still offers the watch filter`() {
        assertTrue(BrowseFilters.from(BrowseFilterProbe(total = 50, played = 0, unplayed = 50), now).watched)
    }

    @Test fun `apply drops what the server cannot do`() {
        val f = BrowseFilters(known = true, badSorts = setOf(BrowseSort.Rating), watched = false, favorite = true, uhd = false, decades = listOf(2020, 2010), genres = false)
        val q = BrowseQuery(sort = BrowseSort.Rating, descending = false, genres = setOf("科幻"), decades = setOf(2020, 1990), watched = WatchFilter.Played, favorite = true, uhd = true)
        val out = f.apply(q)
        assertEquals(BrowseSort.Released, out.sort)
        assertTrue(out.descending)
        assertEquals(emptySet<String>(), out.genres)
        assertEquals(setOf(2020), out.decades)
        assertEquals(WatchFilter.All, out.watched)
        assertTrue(out.favorite)
        assertFalse(out.uhd)
    }

    @Test fun `apply leaves the query alone until probed and keeps the sort when sorting is ignored`() {
        val q = BrowseQuery(sort = BrowseSort.Rating, uhd = true)
        assertEquals(q, BrowseFilters().apply(q))
        assertEquals(BrowseSort.Rating, BrowseFilters(known = true, sortable = false).apply(q).sort)
    }

    @Test fun `collections sort by title, date added or release and filter nothing`() {
        val f = BrowseFilters.COLLECTIONS
        assertEquals(listOf(BrowseSort.Released, BrowseSort.Added, BrowseSort.Title), f.sorts)
        assertFalse(f.anyFilter(listOf("科幻")))
    }

    @Test fun `stale after a day or when never probed`() {
        assertTrue(BrowseFilters().stale(now))
        assertFalse(BrowseFilters(known = true, probedAt = now).stale(now + 1000))
        assertTrue(BrowseFilters(known = true, probedAt = now).stale(now + BrowseFilters.MAX_AGE_MS))
        // A clock set back is not trusted either.
        assertTrue(BrowseFilters(known = true, probedAt = now).stale(now - 1))
    }

    @Test fun `identical ratings say nothing about the order`() {
        val same = (1..5).map { app.zmby.emby.BaseItemDto("$it", communityRating = 8.1) }
        assertEquals(null, SortCheck.verdict(same, BrowseSort.Rating, true))
        assertTrue(SortCheck.consistent(same, BrowseSort.Rating, true))
        val shuffled = listOf(2016, 2023, 2002, 2019).map { app.zmby.emby.BaseItemDto("$it", productionYear = it) }
        assertEquals(false, SortCheck.verdict(shuffled, BrowseSort.Released, true))
        // Only the checks that could tell count: two sorts out of order and nothing else known = no sorting.
        assertFalse(BrowseFilters.from(BrowseFilterProbe(total = 3, sortsKept = mapOf(BrowseSort.Released to false, BrowseSort.Added to false)), now).sortable)
    }

    @Test fun `decades come from the listed years`() {
        assertEquals(listOf(2020, 1980), BrowseFilters.decadesOf(listOf(1985, 2021, 2029, 0, 1989)))
    }
}
