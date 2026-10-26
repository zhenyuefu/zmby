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
