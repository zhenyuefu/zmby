package app.zmby.ui

import app.zmby.emby.BaseItemDto
import app.zmby.emby.UserItemData
import app.zmby.ui.screens.currentEpisode
import app.zmby.ui.screens.seriesPlayLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The series page's main button follows the episode focus is on (ui-spec §2.3). */
class SeriesHeaderTest {
    private fun ep(n: Int, positionTicks: Long = 0) =
        BaseItemDto(id = "e$n", name = "第 $n 集", type = "Episode", userData = UserItemData(playbackPositionTicks = positionTicks))

    private val season = (1..10).map { ep(it) }

    @Test fun `the focused episode wins over the anchor`() {
        assertEquals("e8", currentEpisode(season, "e8", anchor = ep(7))?.id)
    }

    @Test fun `without focus the anchor, else the first episode`() {
        assertEquals("e7", currentEpisode(season, null, anchor = ep(7))?.id)
        assertEquals("e1", currentEpisode(season, null, anchor = null)?.id)
        // The anchor is in another season.
        assertEquals("e1", currentEpisode(season, null, anchor = ep(42))?.id)
    }

    @Test fun `an episode of another season is not current any more`() {
        assertEquals("e7", currentEpisode(season, "s2e3", anchor = ep(7))?.id)
        assertNull(currentEpisode(emptyList(), "e8", anchor = ep(7)))
    }

    @Test fun `the current episode comes from the reloaded list, with its new progress`() {
        val reloaded = season.map { if (it.id == "e8") ep(8, positionTicks = 6_000_000_000) else it }
        assertEquals(6_000_000_000, currentEpisode(reloaded, "e8", anchor = null)?.userData?.playbackPositionTicks)
    }

    @Test fun `label for resuming and for starting`() {
        assertEquals("继续 S1 E7 · 剩余 12 分钟", seriesPlayLabel("S1 E7", 600_000, "剩余 12 分钟"))
        assertEquals("播放 S1 E8", seriesPlayLabel("S1 E8", 0, "剩余 45 分钟"))
        assertEquals("播放", seriesPlayLabel(null, 0, null))
        assertEquals("继续", seriesPlayLabel(null, 1, null))
    }
}
