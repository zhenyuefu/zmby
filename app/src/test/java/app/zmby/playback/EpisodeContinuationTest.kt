package app.zmby.playback

import app.zmby.emby.BaseItemDto
import app.zmby.emby.MediaSourceInfo
import app.zmby.emby.MediaStream
import org.junit.Assert.*
import org.junit.Test

class EpisodeContinuationTest {
    private fun source(ep: Int, release: String, width: Int = 3840) = MediaSourceInfo(
        id = "$ep-$release", path = "/media/Series/$release/Show.S01E${ep.toString().padStart(2, '0')}.$release.mkv",
        mediaStreams = listOf(MediaStream(0, type = "Video", codec = "hevc", width = width, height = width * 9 / 16)),
    )
    private fun ep(number: Int, release: String, season: Int = 1) = BaseItemDto(
        id = "$season-$number-$release", type = "Episode", seriesId = "series", parentIndexNumber = season,
        indexNumber = number, mediaSources = listOf(source(number, release)),
    )

    @Test fun `same release beats the default even with different episode and source ids`() {
        val previous = source(1, "2160p.WEB-DL.TeamB")
        val default = source(2, "2160p.REMUX.TeamA")
        val wanted = source(2, "2160p.WEB-DL.TeamB")
        assertEquals(wanted, EpisodeContinuation.source(listOf(default, wanted), previous))
        assertNotEquals(previous.id, wanted.id)
    }

    @Test fun `versions represented as separate episode items are skipped and matched`() {
        val current = ep(1, "WEB-DL.TeamB")
        val wanted = ep(2, "WEB-DL.TeamB")
        val list = listOf(current, ep(1, "REMUX.TeamA"), ep(2, "REMUX.TeamA"), wanted, ep(3, "WEB-DL.TeamB"))
        assertEquals(wanted, EpisodeContinuation.next(current, list, current.mediaSources.single()))
        assertNull(EpisodeContinuation.next(current, listOf(current, ep(1, "REMUX.TeamA")), current.mediaSources.single()))
    }

    @Test fun `next season and servers ignoring start item are handled without replaying earlier episodes`() {
        val current = ep(12, "TeamB")
        val next = ep(1, "TeamB", season = 2)
        assertEquals(next, EpisodeContinuation.next(current, listOf(ep(1, "TeamA"), ep(2, "TeamB"), current, next), current.mediaSources.single()))
    }

    @Test fun `unrelated or unprobed versions leave the server default alone and virtual episodes are skipped`() {
        assertNull(EpisodeContinuation.source(listOf(MediaSourceInfo("next")), MediaSourceInfo("old")))
        val current = ep(1, "TeamB")
        val virtual = ep(2, "TeamB").copy(locationType = "Virtual")
        assertEquals(3, EpisodeContinuation.next(current, listOf(current, virtual, ep(3, "TeamB")), null)?.indexNumber)
    }
}
