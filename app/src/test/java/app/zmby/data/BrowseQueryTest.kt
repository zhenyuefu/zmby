package app.zmby.data

import app.zmby.emby.BaseItemDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseQueryTest {
    @Test fun `default is release date descending with no filters`() {
        val q = BrowseQuery()
        assertEquals(mapOf("SortBy" to "PremiereDate,ProductionYear,SortName", "SortOrder" to "Descending"), q.params(2026))
        assertFalse(q.filtered)
    }

    @Test fun `switching the sort field takes its default direction`() {
        assertFalse(BrowseQuery().sortedBy(BrowseSort.Title).descending)
        assertTrue(BrowseQuery(descending = false).sortedBy(BrowseSort.Added).descending)
        assertEquals("SortName", BrowseQuery().sortedBy(BrowseSort.Title).params(2026)["SortBy"])
        assertEquals("Ascending", BrowseQuery().sortedBy(BrowseSort.Title).params(2026)["SortOrder"])
    }

    @Test fun `filters become Emby parameters`() {
        val q = BrowseQuery(genres = setOf("科幻", "动作"), decades = setOf(2020, 1990), watched = WatchFilter.Unplayed, favorite = true, uhd = true)
        val p = q.params(2026)
        assertEquals("IsUnplayed,IsFavorite", p["Filters"])
        assertEquals("动作|科幻", p["Genres"])
        assertEquals((1990..1999).toList() + (2020..2026).toList(), p["Years"]!!.split(',').map(String::toInt))
        assertEquals("true", p["Is4K"])
        assertTrue(q.filtered)
        assertEquals(BrowseQuery(), q.cleared().copy(sort = BrowseSort.Released))
    }

    @Test fun `chips remove one filter each`() {
        val q = BrowseQuery(genres = setOf("科幻"), watched = WatchFilter.Played)
        val chips = q.chips()
        assertEquals(listOf("科幻", "已看"), chips.map { it.first })
        assertEquals(emptySet<String>(), chips[0].second.genres)
        assertEquals(WatchFilter.All, chips[1].second.watched)
    }

    @Test fun `the cache key changes with the query and not with set order`() {
        assertEquals(BrowseQuery(genres = setOf("a", "b")).key, BrowseQuery(genres = setOf("b", "a")).key)
        assertNotEquals(BrowseQuery().key, BrowseQuery(descending = false).key)
        assertNotEquals(BrowseQuery().key, BrowseQuery(uhd = true).key)
    }

    @Test fun `a server that ignores the sort is noticed, a stray pair is not`() {
        fun dated(vararg d: String) = d.map { BaseItemDto(id = it, premiereDate = "${it}T00:00:00Z") }
        assertTrue(SortCheck.consistent(dated("2024-05-01", "2023-01-01", "2020-02-02", "2019-01-01"), BrowseSort.Released, descending = true))
        assertFalse(SortCheck.consistent(dated("2019-01-01", "2020-02-02", "2023-01-01", "2024-05-01"), BrowseSort.Released, descending = true))
        assertTrue(SortCheck.consistent(dated("2024-05-01", "2025-01-01", "2020-02-02", "2019-01-01", "2018-01-01"), BrowseSort.Released, descending = true))
        assertTrue("titles are not checked", SortCheck.consistent(dated("b", "a", "c"), BrowseSort.Title, descending = false))
    }

    @Test fun `the letter rail is hash then A to Z`() {
        assertEquals(27, LetterRail.LETTERS.size)
        assertEquals("#", LetterRail.LETTERS.first())
        assertEquals("Z", LetterRail.LETTERS.last())
    }
}
