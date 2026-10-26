package app.zmby.ui

import app.zmby.aggregate.Confidence
import app.zmby.aggregate.ServerVersions
import app.zmby.aggregate.Verdict
import app.zmby.data.ServerConfig
import app.zmby.data.ServerLine
import app.zmby.data.ServerStatus
import app.zmby.emby.BaseItemDto
import app.zmby.emby.MediaSourceInfo
import app.zmby.aggregate.VersionPrefs
import app.zmby.ui.screens.Version
import app.zmby.ui.screens.rankCatalogVersions
import app.zmby.ui.screens.versionCards
import app.zmby.ui.screens.uniqueVersions
import org.junit.Assert.assertEquals
import org.junit.Test

class VersionCardsTest {
    @Test fun `media source ids reused by different items stay distinct while exact copies collapse`() {
        val s = server("mock", 0)
        val a = Version(s, film.copy(id = "movie-a"), MediaSourceInfo("shared-source"))
        val b = Version(s, film.copy(id = "movie-b"), MediaSourceInfo("shared-source"))
        assertEquals(2, setOf(a.key, b.key).size)
        assertEquals(listOf(a, b), uniqueVersions(listOf(a, b, a.copy())))
    }

    private fun server(id: String, order: Int) = ServerStatus(ServerConfig(id, id, listOf(ServerLine("l", "主线路", "http://$id")), "l", "u", "t"), order)
    private val film = BaseItemDto(id = "m", name = "沙丘2", type = "Movie", mediaSources = listOf(MediaSourceInfo("s1")))

    @Test fun `only servers with a playable version get a card, failures are counted`() {
        val self = server("home", 0)
        val others = listOf(server("found", 1), server("missing", 2), server("notfound", 3), server("timeout", 4), server("paused", 5), server("empty", 6), server("asking", 7))
        val results = mapOf(
            "found" to ServerVersions.Found("found", film.copy(id = "f"), listOf(MediaSourceInfo("x")), Verdict(Confidence.Possible, "片名 + 年份一致")),
            "missing" to ServerVersions.Missing("missing", "缺此集"),
            "notfound" to ServerVersions.NotFound("notfound", "没有"),
            "timeout" to ServerVersions.Failed("timeout", "timed out", timedOut = true),
            "paused" to ServerVersions.Skipped("paused"),
            "empty" to ServerVersions.Found("empty", film, emptyList(), Verdict(Confidence.Strong, "TMDB 一致")),
        )
        val (cards, unresponsive) = versionCards(self, film, others, results)
        assertEquals(listOf("home", "found"), cards.map { it.server.id })
        assertEquals("片名 + 年份一致 · 可能匹配", cards[1].note)
        assertEquals(listOf("timeout", "paused"), unresponsive)
    }

    @Test fun `a cached card stays while asking and when the server fails, but not when it no longer has it`() {
        val cached = mapOf(
            "a" to ServerVersions.Found("a", film.copy(id = "a1"), listOf(MediaSourceInfo("x")), Verdict(Confidence.Strong, "TMDB 一致")),
            "b" to ServerVersions.Found("b", film.copy(id = "b1"), listOf(MediaSourceInfo("y")), Verdict(Confidence.Strong, "TMDB 一致")),
            "c" to ServerVersions.Found("c", film.copy(id = "c1"), listOf(MediaSourceInfo("z")), Verdict(Confidence.Strong, "TMDB 一致")),
        )
        val results = mapOf("b" to ServerVersions.Failed("b", "offline", timedOut = false), "c" to ServerVersions.NotFound("c", "没有"))
        val (cards, unresponsive) = versionCards(null, film, listOf(server("a", 1), server("b", 2), server("c", 3)), results, cached)
        assertEquals(listOf("a", "b"), cards.map { it.server.id })
        assertEquals(listOf("b"), unresponsive)
    }

    @Test fun `the open server without sources gets no card either`() {
        val (cards, _) = versionCards(server("home", 0), film.copy(mediaSources = emptyList()), emptyList(), emptyMap())
        assertEquals(emptyList<String>(), cards.map { it.server.id })
    }

    @Test fun `a catalog lookup keeps the version picked by hand`() {
        // Z9X 8K, 无瑕修女: Synology's REMUX picked by hand, the page reopened on AlphaTV-PRO's copy.
        val alpha = Version(server("alpha", 0), film.copy(id = "a"), MediaSourceInfo("bluray"))
        val synology = Version(server("synology", 1), film.copy(id = "s"), MediaSourceInfo("remux"))
        assertEquals(listOf(alpha, synology), rankCatalogVersions(listOf(synology, alpha), VersionPrefs()))
        assertEquals(listOf(synology, alpha), rankCatalogVersions(listOf(alpha, synology), VersionPrefs(manual = "synology" to "remux")))
        // A pick that no longer exists changes nothing.
        assertEquals(listOf(alpha, synology), rankCatalogVersions(listOf(synology, alpha), VersionPrefs(manual = "synology" to "gone")))
    }
}
