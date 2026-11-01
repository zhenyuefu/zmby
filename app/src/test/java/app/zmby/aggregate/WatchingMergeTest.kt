package app.zmby.aggregate

import app.zmby.emby.BaseItemDto
import app.zmby.emby.UserItemData
import app.zmby.emby.msToTicks
import app.zmby.store.EpisodeMappingRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchingMergeTest {
    private val now = WatchingMerge.time("2026-10-31T12:00:00Z")!!

    private fun movie(id: String, tmdb: String? = null, imdb: String? = null, played: String? = null, positionMin: Long = 0, done: Boolean = false) =
        BaseItemDto(id = id, name = id, type = "Movie", runTimeTicks = msToTicks(120 * 60_000L),
            providerIds = listOfNotNull(tmdb?.let { "Tmdb" to it }, imdb?.let { "Imdb" to it }).toMap(),
            userData = UserItemData(playbackPositionTicks = msToTicks(positionMin * 60_000), played = done, lastPlayedDate = played))

    private fun series(id: String, tmdb: String?, status: String? = null) =
        BaseItemDto(id = id, name = id, type = "Series", status = status, providerIds = tmdb?.let { mapOf("Tmdb" to it) }.orEmpty())

    private fun ep(server: String, series: String, e: Int, s: Int = 1, played: String? = null, positionMin: Long = 0, done: Boolean = false, premiere: String? = null, tmdb: String? = null) =
        BaseItemDto(id = "$server-$series-s${s}e$e", name = "第 $e 集", type = "Episode", seriesId = series, seriesName = series, parentIndexNumber = s, indexNumber = e,
            runTimeTicks = msToTicks(50 * 60_000L), premiereDate = premiere, providerIds = tmdb?.let { mapOf("Tmdb" to it) }.orEmpty(),
            userData = UserItemData(playbackPositionTicks = msToTicks(positionMin * 60_000), played = done, lastPlayedDate = played))

    @Test fun `two servers stopped at different episodes of one show give one card at the newer`() {
        val a = WatchSource("a", 0, resume = listOf(ep("a", "ls", 3, played = "2026-10-20T10:00:00Z", positionMin = 10)),
            series = mapOf("ls" to series("ls", "202750")))
        val b = WatchSource("b", 1, resume = listOf(ep("b", "fls", 7, played = "2026-10-23T10:00:00Z", positionMin = 30)),
            series = mapOf("fls" to series("fls", "202750")))
        val cards = WatchingMerge.merge(listOf(a, b), now = now)
        val card = cards.single()
        assertEquals("series:tmdb:202750", card.key)
        assertEquals(WatchState.Resume, card.state)
        assertEquals("b:b-fls-s1e7", card.target!!.key)
        assertEquals(30 * 60_000L, card.startMs)
        assertEquals("b", card.server)
        assertEquals(listOf("a", "b"), card.servers)
        // Clearing touches both servers' own resume points.
        assertEquals(setOf("a:a-ls-s1e3", "b:b-fls-s1e7"), card.resumePoints.map { it.key }.toSet())
    }

    @Test fun `part-way on one server and a later episode finished on another moves on to the next`() {
        val a = WatchSource("a", 0,
            resume = listOf(ep("a", "ls", 7, played = "2026-10-23T01:00:00Z", positionMin = 30)),
            nextUp = listOf(ep("a", "ls", 7, positionMin = 30)),
            series = mapOf("ls" to series("ls", "202750")),
            episodes = mapOf("ls" to (1..12).map { e -> ep("a", "ls", e, done = e < 7, positionMin = if (e == 7) 30 else 0) }))
        val b = WatchSource("b", 1,
            played = listOf(ep("b", "fls", 8, played = "2026-10-24T20:00:00Z", done = true)),
            series = mapOf("fls" to series("fls", "202750")),
            episodes = mapOf("fls" to (1..8).map { e -> ep("b", "fls", e, done = true, played = if (e == 8) "2026-10-24T20:00:00Z" else null) }))
        val card = WatchingMerge.merge(listOf(a, b), now = now).single()
        assertEquals(WatchState.Next, card.state)
        assertEquals(Slot(1, 9), card.slot)
        // B has no episode 9; A does, and it starts from the top.
        assertEquals("a:a-ls-s1e9", card.target!!.key)
        assertEquals(0L, card.startMs)
        assertEquals("b", card.server)
        // Watched is the union: 1–6 on A, 1–8 on B.
        assertEquals(8, card.bar!!.watched)
        assertEquals(12, card.bar!!.total)
        assertEquals(12, card.bar!!.segments!!.size)
    }

    @Test fun `missing or equal times take the later episode`() {
        val a = WatchSource("a", 0, resume = listOf(ep("a", "x", 3, positionMin = 10)), series = mapOf("x" to series("x", "1")))
        val b = WatchSource("b", 1, resume = listOf(ep("b", "y", 5, positionMin = 20)), series = mapOf("y" to series("y", "1")))
        val card = WatchingMerge.merge(listOf(a, b), now = now).single()
        assertEquals(Slot(1, 5), card.slot)
        assertEquals("b", card.target!!.server)
        assertNull(card.lastActivity)

        val same = "2026-10-20T10:00:00Z"
        val c = WatchSource("a", 0, played = listOf(ep("a", "x", 4, played = same, done = true)), nextUp = listOf(ep("a", "x", 5)), series = mapOf("x" to series("x", "1")))
        val d = WatchSource("b", 1, resume = listOf(ep("b", "y", 4, played = same, positionMin = 5)), series = mapOf("y" to series("y", "1")))
        // Same time and episode: finished counts as further than part-watched.
        assertEquals(WatchState.Next, WatchingMerge.merge(listOf(c, d), now = now).single().state)
    }

    @Test fun `conflicting external ids are not merged`() {
        val a = WatchSource("a", 0, resume = listOf(movie("m1", tmdb = "100", imdb = "tt1", played = "2026-10-20T10:00:00Z", positionMin = 10)))
        val b = WatchSource("b", 1, resume = listOf(movie("m2", tmdb = "200", imdb = "tt1", played = "2026-10-21T10:00:00Z", positionMin = 20)))
        val cards = WatchingMerge.merge(listOf(a, b), now = now)
        assertEquals(2, cards.size)
        assertEquals(cards.size, cards.map { it.key }.toSet().size)

        // Series agreeing on the show but not on the episode's own ID: kept apart too.
        val c = WatchSource("a", 0, resume = listOf(ep("a", "x", 3, played = "2026-10-20T10:00:00Z", positionMin = 10, tmdb = "501")), series = mapOf("x" to series("x", "9")))
        val d = WatchSource("b", 1, resume = listOf(ep("b", "y", 3, played = "2026-10-21T10:00:00Z", positionMin = 20, tmdb = "502")), series = mapOf("y" to series("y", "9")))
        val apart = WatchingMerge.merge(listOf(c, d), now = now)
        assertEquals(2, apart.size)
        assertEquals(2, apart.map { it.key }.toSet().size)

        // No IDs at all: never merged on the title.
        val e = WatchSource("a", 0, resume = listOf(movie("n1", played = "2026-10-20T10:00:00Z", positionMin = 10)))
        val f = WatchSource("b", 1, resume = listOf(movie("n1", played = "2026-10-21T10:00:00Z", positionMin = 10)))
        assertEquals(2, WatchingMerge.merge(listOf(e, f), now = now).size)
    }

    @Test fun `the same film on two servers is one card at the newer position`() {
        val a = WatchSource("a", 0, resume = listOf(movie("dune", tmdb = "693134", played = "2026-10-22T20:00:00Z", positionMin = 72)))
        val b = WatchSource("b", 1, resume = listOf(movie("f-dune", tmdb = "693134", imdb = "tt15239678", played = "2026-10-23T08:00:00Z", positionMin = 51)))
        val card = WatchingMerge.merge(listOf(a, b), now = now).single()
        assertEquals("movie:tmdb:693134", card.key)
        assertEquals("b:f-dune", card.target!!.key)
        assertEquals(51 * 60_000L, card.startMs)
        assertEquals(listOf("a:dune"), card.alternates.map { it.key })

        // Finished on one server after the other's resume point: gone.
        val c = WatchSource("b", 1, played = listOf(movie("f-dune", tmdb = "693134", played = "2026-10-24T08:00:00Z", done = true)))
        assertTrue(WatchingMerge.merge(listOf(a, c), now = now).isEmpty())
    }

    @Test fun `a series watched to the end leaves, a continuing one waits`() {
        // Part-way through episode 2 on A; B later finished the last episode, having seen them all.
        val a = WatchSource("a", 0, resume = listOf(ep("a", "x", 2, played = "2026-10-20T10:00:00Z", positionMin = 10)),
            episodes = mapOf("x" to (1..3).map { e -> ep("a", "x", e, done = e == 1, positionMin = if (e == 2) 10 else 0) }))
        val eps = (1..3).map { e -> ep("b", "y", e, done = true, played = "2026-10-2${e + 1}T10:00:00Z") }
        fun sources(status: String?) = listOf(
            a.copy(series = mapOf("x" to series("x", "7", status))),
            WatchSource("b", 1, played = listOf(eps.last()), series = mapOf("y" to series("y", "7", status)), episodes = mapOf("y" to eps)),
        )
        assertTrue(WatchingMerge.merge(sources("Ended"), now = now).isEmpty())
        val waiting = WatchingMerge.merge(sources("Continuing"), now = now).single()
        assertEquals(WatchState.Waiting, waiting.state)
        assertNull(waiting.target)
    }

    @Test fun `an episode not aired yet waits and is listed after the others`() {
        val show = WatchSource("a", 0,
            played = listOf(ep("a", "x", 7, done = true, played = "2026-10-30T10:00:00Z")),
            nextUp = listOf(ep("a", "x", 8, premiere = "2026-11-07T12:00:00Z")),
            series = mapOf("x" to series("x", "7")))
        val older = WatchSource("b", 1, resume = listOf(movie("m", tmdb = "1", played = "2026-10-01T10:00:00Z", positionMin = 5)))
        val cards = WatchingMerge.merge(listOf(show, older), now = now)
        assertEquals(listOf(WatchState.Resume, WatchState.Waiting), cards.map { it.state })
        assertEquals("2026-11-07T12:00:00Z", cards.last().airDate)
        assertEquals(Slot(1, 8), cards.last().slot)
    }

    @Test fun `next up alone is shown, hidden until new activity, and left out when the setting is off`() {
        val s = WatchSource("a", 0, played = listOf(ep("a", "x", 2, done = true, played = "2026-10-20T10:00:00Z")),
            nextUp = listOf(ep("a", "x", 3)), series = mapOf("x" to series("x", "7")))
        val card = WatchingMerge.merge(listOf(s), now = now).single()
        assertTrue(card.nextUpOnly)
        assertTrue(WatchingMerge.merge(listOf(s), includeNext = false, now = now).isEmpty())
        val hidden = mapOf(card.key to card.lastActivity!!)
        assertTrue(WatchingMerge.merge(listOf(s), hidden = hidden, now = now).isEmpty())
        val later = s.copy(played = listOf(ep("a", "x", 3, done = true, played = "2026-10-25T10:00:00Z")), nextUp = listOf(ep("a", "x", 4)))
        assertEquals(Slot(1, 4), WatchingMerge.merge(listOf(later), hidden = hidden, now = now).single().slot)
    }

    @Test fun `local season mappings line up differently numbered seasons`() {
        // Server B files the show's second season as season 4, mapped locally to season 2.
        val rule = EpisodeMappingRule(workKey = "tmdb:tv:9", serverId = "b", seriesId = "y", sourceSeason = 4, firstEpisode = 1, lastEpisode = 10, targetSeason = 2)
        val a = WatchSource("a", 0, resume = listOf(ep("a", "x", 3, s = 2, played = "2026-10-20T10:00:00Z", positionMin = 10)), series = mapOf("x" to series("x", "9")))
        val b = WatchSource("b", 1, played = listOf(ep("b", "y", 3, s = 4, played = "2026-10-22T10:00:00Z", done = true)),
            nextUp = listOf(ep("b", "y", 4, s = 4)), series = mapOf("y" to series("y", "9")))
        val card = WatchingMerge.merge(listOf(a, b), rules = listOf(rule), now = now).single()
        assertEquals(WatchState.Next, card.state)
        assertEquals(Slot(2, 4), card.slot)
        assertEquals("b", card.target!!.server)
    }

    @Test fun `an episode both in NextUp and the episode list is the next one`() {
        val eps = (1..10).map { e -> ep("a", "x", e, done = e <= 6) }
        val s = WatchSource("a", 0, played = eps.take(6), nextUp = listOf(eps[6]), series = mapOf("x" to series("x", "7")), episodes = mapOf("x" to eps))
        val card = WatchingMerge.merge(listOf(s), now = now).single()
        assertEquals(Slot(1, 7), card.slot)
        assertEquals(6, card.bar!!.watched)
    }
}
