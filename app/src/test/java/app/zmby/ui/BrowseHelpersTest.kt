package app.zmby.ui

import app.zmby.data.ServerConfig
import app.zmby.data.ServerStatus
import app.zmby.emby.BaseItemDto
import app.zmby.emby.UserItemData
import app.zmby.ui.screens.browseCaption
import app.zmby.ui.screens.letterOf
import app.zmby.ui.screens.unwatchedBadge
import app.zmby.ui.screens.visibleServers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowseHelpersTest {
    @Test fun `series corners count unwatched episodes`() {
        assertEquals("5 集未看", unwatchedBadge(BaseItemDto("s", type = "Series", userData = UserItemData(unplayedItemCount = 5))))
        assertEquals("全部看完", unwatchedBadge(BaseItemDto("s", type = "Series", userData = UserItemData(played = true, unplayedItemCount = 0))))
        assertNull(unwatchedBadge(BaseItemDto("m", type = "Movie", userData = UserItemData(unplayedItemCount = 5))))
        assertNull(unwatchedBadge(BaseItemDto("s", type = "Series")))
    }

    @Test fun `captions show year with rating for films and seasons for series`() {
        assertEquals("2024 · 8.1", browseCaption(BaseItemDto("m", type = "Movie", productionYear = 2024, communityRating = 8.06)))
        assertEquals("2014 · 6 季", browseCaption(BaseItemDto("s", type = "Series", productionYear = 2014, childCount = 6)))
        assertEquals("4 部", browseCaption(BaseItemDto("c", type = "BoxSet", productionYear = 2021, childCount = 4)))
    }

    @Test fun `a film's collections are only those that hold it`() {
        fun film(id: String, year: Int? = null) = app.zmby.data.ServerItem("s", BaseItemDto(id, type = "Movie", productionYear = year, communityRating = 8.06))
        val box = { id: String, films: List<String> -> app.zmby.ui.screens.MovieCollection(app.zmby.data.ServerItem("s", BaseItemDto(id, type = "BoxSet")), films.map { film(it) }) }
        val kept = app.zmby.ui.screens.MovieCollectionsModel.keepContaining("m0", listOf(box("a", listOf("m0", "m1")), box("b", listOf("m2"))))
        assertEquals(listOf("a"), kept.map { it.box.item.id })
        assertEquals("2014 · 8.1", app.zmby.ui.screens.collectionCardLine(film("m0", 2014)))
    }

    @Test fun `a collection's line counts its titles and the years they span`() {
        fun film(id: String, year: Int?) = app.zmby.data.ServerItem("s", BaseItemDto(id, type = "Movie", productionYear = year))
        assertEquals("3 部 · 1999–2021", app.zmby.ui.screens.collectionMeta(listOf(film("a", 2021), film("b", 1999), film("c", null))))
        assertEquals("1 部 · 2024", app.zmby.ui.screens.collectionMeta(listOf(film("a", 2024))))
        assertEquals("", app.zmby.ui.screens.collectionMeta(emptyList()))
    }

    @Test fun `rail letters come from the sort name or the pinyin initial`() {
        assertEquals("D", letterOf(BaseItemDto("1", name = "dune")))
        assertEquals("M", letterOf(BaseItemDto("2", name = "漫长的季节")))
        assertEquals("#", letterOf(BaseItemDto("3", name = "2049")))
    }

    @Test fun `three servers show and the chosen one is never hidden`() {
        val servers = (1..6).map { ServerStatus(ServerConfig(id = "s$it", name = "S$it", lines = emptyList(), lineId = "", userId = "u", token = "t"), order = it) }
        assertEquals(listOf("s1", "s2", "s3"), visibleServers(servers, "s1").map { it.id })
        assertEquals(listOf("s1", "s2", "s5"), visibleServers(servers, "s5").map { it.id })
        assertEquals(4, visibleServers(servers.take(4), "s4").size)
    }
}
