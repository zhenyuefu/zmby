package app.zmby.favorites

import app.zmby.data.ServerItem
import app.zmby.emby.BaseItemDto
import app.zmby.emby.UserItemData
import app.zmby.store.WatchlistRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchlistLogicTest {
    private val film = BaseItemDto(id = "dune2", name = "沙丘2", type = "Movie", productionYear = 2024, providerIds = mapOf("Tmdb" to "693134"))
    private val series = BaseItemDto(id = "ls", name = "漫长的季节", type = "Series", productionYear = 2023, providerIds = mapOf("Tmdb" to "1"))
    private fun episode(progress: Long = 0, played: Boolean = false) = BaseItemDto(
        id = "ls-e1", name = "第 1 集", type = "Episode", seriesId = "ls", parentIndexNumber = 1, indexNumber = 1,
        userData = UserItemData(playbackPositionTicks = progress, played = played),
    )

    private val filmEntry = WatchlistLogic.entry(ServerItem("a", film), emptyList(), 1)
    private val seriesEntry = WatchlistLogic.entry(ServerItem("a", series), emptyList(), 2)

    @Test fun `an entry is keyed by the work and knows where it was seen`() {
        assertEquals("movie:tmdb:693134", filmEntry.workKey)
        assertEquals(listOf("a|dune2"), filmEntry.instances)
    }

    @Test fun `the same film on another server matches by work key`() {
        assertTrue(WatchlistLogic.matches(filmEntry, ServerItem("b", film.copy(id = "other"))))
        assertFalse(WatchlistLogic.matches(filmEntry, ServerItem("b", film.copy(id = "x", providerIds = mapOf("Tmdb" to "2")))))
    }

    @Test fun `an episode stands for its series`() {
        assertTrue(WatchlistLogic.matches(seriesEntry, ServerItem("a", episode())))
        assertFalse("another server's series id is another series", WatchlistLogic.matches(seriesEntry, ServerItem("b", episode())))
    }

    @Test fun `progress or watched takes a work off, opening it does not`() {
        val entries = listOf(filmEntry, seriesEntry)
        assertEquals(emptyList<String>(), WatchlistLogic.started(entries, listOf(ServerItem("a", episode()))))
        assertEquals(listOf(seriesEntry.workKey), WatchlistLogic.started(entries, listOf(ServerItem("a", episode(progress = 50_000_000)))))
        assertEquals(listOf(filmEntry.workKey), WatchlistLogic.started(entries, listOf(ServerItem("c", film.copy(id = "z", userData = UserItemData(played = true))))))
    }

    @Test fun `sorts by added time, title or release date`() {
        val a = WatchlistRow("1", "B 片", 2020, "Movie", "2020-05-01", addedAt = 3)
        val b = WatchlistRow("2", "A 片", 2024, "Movie", null, addedAt = 1)
        val c = WatchlistRow("3", "C 片", null, "Movie", null, addedAt = 2)
        assertEquals(listOf("1", "3", "2"), WatchlistLogic.sorted(listOf(a, b, c), WatchlistSort.Added).map { it.workKey })
        assertEquals(listOf("2", "1", "3"), WatchlistLogic.sorted(listOf(a, b, c), WatchlistSort.Title).map { it.workKey })
        assertEquals(listOf("2", "1", "3"), WatchlistLogic.sorted(listOf(a, b, c), WatchlistSort.Released).map { it.workKey })
    }
}
