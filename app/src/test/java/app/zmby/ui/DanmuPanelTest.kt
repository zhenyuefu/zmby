package app.zmby.ui

import app.zmby.danmu.DanmuSelection
import app.zmby.danmu.DanmuTarget
import app.zmby.emby.BaseItemDto
import app.zmby.ui.screens.DanmuStatus
import app.zmby.ui.screens.danmuEpisodeLabel
import app.zmby.ui.screens.danmuGroups
import app.zmby.ui.screens.danmuRowLabel
import app.zmby.ui.screens.danmuStatus
import app.zmby.ui.screens.danmuStatusSource
import app.zmby.ui.screens.danmuTip
import app.zmby.ui.screens.rowIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DanmuPanelTest {
    private fun sel(anime: String, ep: Long, title: String, manual: Boolean = false) =
        DanmuSelection(DanmuTarget(episodeId = ep, animeId = anime, animeTitle = anime, episodeTitle = title), searchTitle = "漫长的季节", manual = manual)

    @Test fun `button states and their name tags`() {
        assertEquals(DanmuStatus.Matching, danmuStatus(sel("a", 1, "x"), matching = true))
        assertEquals(DanmuStatus.Unmatched, danmuStatus(null, matching = false))
        assertEquals(DanmuStatus.Manual, danmuStatus(sel("a", 1, "x", manual = true), matching = false))
        assertEquals(DanmuStatus.Auto, danmuStatus(sel("a", 1, "x"), matching = false))
        assertEquals("弹幕 · 自动匹配", danmuTip(DanmuStatus.Auto))
        assertEquals("弹幕 · 已手动选择", danmuTip(DanmuStatus.Manual))
        assertEquals("弹幕 · 未匹配", danmuTip(DanmuStatus.Unmatched))
    }

    @Test fun `panel title and status card wording`() {
        assertEquals("第 1 季第 7 集", danmuEpisodeLabel(BaseItemDto(id = "e", type = "Episode", parentIndexNumber = 1, indexNumber = 7)))
        assertEquals("第 7 集", danmuEpisodeLabel(BaseItemDto(id = "e", type = "Episode", indexNumber = 7)))
        assertNull(danmuEpisodeLabel(BaseItemDto(id = "m", type = "Movie")))
        assertEquals("按服务器季集识别 · 下次播放生效", danmuStatusSource(DanmuStatus.Auto, null))
        assertEquals("搜索「漫长的季节」后选择 · 下次播放生效", danmuStatusSource(DanmuStatus.Manual, sel("a", 1, "x", manual = true)))
    }

    @Test fun `results are grouped by anime in server order and the chosen row is found`() {
        val results = listOf(sel("腾讯", 6, "第 6 集"), sel("腾讯", 7, "第 7 集"), sel("爱奇艺", 16, "第 6 集"), sel("爱奇艺", 17, ""))
        val groups = danmuGroups(results)
        assertEquals(listOf("腾讯", "爱奇艺"), groups.map { it.title })
        assertEquals(listOf(2, 2), groups.map { it.episodes.size })
        // Group titles are list items too: 腾讯(0) 6(1) 7(2) 爱奇艺(3) 6(4) 7(5).
        assertEquals(2, rowIndex(groups, results[1].target.sourceKey))
        assertEquals(5, rowIndex(groups, results[3].target.sourceKey))
        assertNull(rowIndex(groups, "missing"))
        // Without an episode title the row falls back to the whole label.
        assertEquals("爱奇艺", danmuRowLabel(sel("爱奇艺", 17, "")))
        assertEquals("第 6 集", danmuRowLabel(results[0]))
    }
}
