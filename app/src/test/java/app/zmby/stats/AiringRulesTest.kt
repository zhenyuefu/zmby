package app.zmby.stats

import app.zmby.catalog.CatalogEpisode
import app.zmby.catalog.CatalogSeason
import app.zmby.data.ServerItem
import app.zmby.emby.BaseItemDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AiringRulesTest {
    private val today = LocalDate.of(2026, 10, 31)
    private fun ep(title: String, date: String, season: Int = 1, number: Int? = 3, key: String = "series:tmdb:1") =
        AiringEpisode(key, key.substringAfterLast(':'), title, season, number, date, "$key:$season:$number")

    @Test fun `keys give TMDB ids or lookups, junk gives neither`() {
        assertEquals("202750", AiringRules.tmdbId("series:tmdb:202750"))
        assertNull(AiringRules.tmdbId("series:tmdb:0"))
        assertNull(AiringRules.tmdbId("movie:tmdb:603"))
        assertNull(AiringRules.tmdbId("item:a:1"))
        assertEquals("Imdb" to "tt1", AiringRules.externalId("series:imdb:tt1"))
        assertEquals("Tvdb" to "42", AiringRules.externalId("series:tvdb:42"))
        assertNull(AiringRules.externalId("series:tmdb:1"))
        assertNull(AiringRules.externalId("item:a:1"))
    }

    @Test fun `each series once, keeping a known server copy, capped`() {
        val copy = ServerItem("a", BaseItemDto(id = "s1", type = "Series"))
        val list = AiringRules.distinct(listOf(CalendarSeries("series:tmdb:1", "收藏里的名字"), CalendarSeries("series:tmdb:1", "在看", copy), CalendarSeries("series:tmdb:2", "B")))
        assertEquals(listOf("收藏里的名字", "B"), list.map { it.title })
        assertEquals(copy, list.first().instance)
        assertEquals(AiringRules.MAX_SERIES, AiringRules.distinct((1..60).map { CalendarSeries("series:tmdb:$it", "$it") }).size)
    }

    @Test fun `the last two regular seasons, specials skipped`() {
        assertEquals(listOf(2, 3), AiringRules.seasonsToFetch(listOf(0, 1, 2, 3).map { CatalogSeason(it, "", 10) }))
        assertEquals(listOf(1), AiringRules.seasonsToFetch(listOf(CatalogSeason(0, "", 1), CatalogSeason(1, "", 12))))
        assertTrue(AiringRules.seasonsToFetch(emptyList()).isEmpty())
    }

    @Test fun `episodes are kept around today only, with a readable date`() {
        assertEquals(LocalDate.of(2026, 11, 2), AiringRules.keep(CatalogEpisode("1", 1, 1, "", date = "2026-11-02"), today))
        assertNull(AiringRules.keep(CatalogEpisode("1", 1, 1, "", date = "2023-04-22"), today))
        assertNull(AiringRules.keep(CatalogEpisode("1", 1, 1, "", date = null), today))
        assertNull(AiringRules.keep(CatalogEpisode("1", 1, 1, "", date = "soon"), today))
    }

    @Test fun `the week is Monday first, a day lists its episodes by title`() {
        assertEquals(LocalDate.of(2026, 10, 26), AiringRules.week(today).first())
        assertEquals(LocalDate.of(2026, 11, 1), AiringRules.week(today).last())
        val all = listOf(ep("三体", "2026-10-31", key = "series:tmdb:2"), ep("曼达洛人", "2026-10-31", key = "series:tmdb:3"), ep("瑞克和莫蒂", "2026-11-01"))
        assertEquals(listOf("三体", "曼达洛人"), AiringRules.on(all, today).map { it.seriesTitle })
        assertTrue(AiringRules.on(all, today.minusDays(1)).isEmpty())
    }

    @Test fun `lines name the episode and day, no slashes, no air time`() {
        assertEquals("第 3 集 · 今天", AiringRules.line(ep("漫长的季节", "2026-10-31"), today))
        assertEquals("第 8 季第 6 集 · 11 月 2 日", AiringRules.line(ep("瑞克和莫蒂", "2026-11-02", season = 8, number = 6), today))
        assertEquals("明天", AiringRules.line(ep("特别篇", "2026-11-01", number = null), today))
        assertTrue(AiringRules.aired(ep("x", "2026-10-31"), today))
        assertFalse(AiringRules.aired(ep("x", "2026-11-01"), today))
    }
}
