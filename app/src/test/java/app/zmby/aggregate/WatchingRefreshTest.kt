package app.zmby.aggregate

import app.zmby.data.ServerItem
import app.zmby.emby.BaseItemDto
import app.zmby.emby.UserItemData
import app.zmby.store.CachedList
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchingRefreshTest {
    private val now = 10 * WatchingFeed.EPISODES_MS
    private fun ep(id: String, played: Boolean = false, ticks: Long = 0) =
        BaseItemDto(id = id, type = "Episode", seriesId = "s", userData = UserItemData(played = played, playbackPositionTicks = ticks))
    private fun stored(vararg eps: BaseItemDto, at: Long = now - 60_000) = CachedList("k", eps.map { ServerItem("a", it) }, eps.size, at)

    @Test fun `a series' episode list is kept while what the servers say about it matches`() {
        val list = stored(ep("e1", played = true), ep("e2", ticks = 500), ep("e3"))
        assertFalse(WatchingFeed.episodesDue(list, listOf(ep("e2", ticks = 500), ep("e3")), now))
    }

    @Test fun `new progress, a newly played episode or one not in the list asks again`() {
        val list = stored(ep("e1", played = true), ep("e2", ticks = 500), ep("e3"))
        assertTrue(WatchingFeed.episodesDue(list, listOf(ep("e2", ticks = 900)), now))
        assertTrue(WatchingFeed.episodesDue(list, listOf(ep("e2", played = true)), now))
        assertTrue(WatchingFeed.episodesDue(list, listOf(ep("e4")), now))
    }

    @Test fun `never fetched or older than six hours asks again`() {
        assertTrue(WatchingFeed.episodesDue(null, emptyList(), now))
        assertTrue(WatchingFeed.episodesDue(stored(ep("e1"), at = now - WatchingFeed.EPISODES_MS), emptyList(), now))
    }
}
