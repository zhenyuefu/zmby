package app.zmby.segments

import app.zmby.data.SegmentProvider
import app.zmby.emby.BaseItemDto
import app.zmby.emby.ChapterInfo
import app.zmby.emby.MediaSourceInfo
import app.zmby.emby.msToTicks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentRulesTest {
    private val intro = Segment(SegmentKind.Intro, 60_000, 150_000)
    private val credits = Segment(SegmentKind.Credits, 2_500_000, null)

    @Test fun `each kind comes from the first provider in the order that has it`() {
        val order = listOf(SegmentProvider.Local, SegmentProvider.Emby, SegmentProvider.TheIntroDB, SegmentProvider.IntroDB)
        val merged = SegmentRules.merge(order, mapOf(
            SegmentProvider.Emby to listOf(credits),
            SegmentProvider.TheIntroDB to listOf(intro, Segment(SegmentKind.Credits, 2_400_000, null)),
            SegmentProvider.IntroDB to listOf(Segment(SegmentKind.Recap, 0, 30_000)),
        ))
        assertEquals(SegmentProvider.TheIntroDB to intro, merged[SegmentKind.Intro])
        assertEquals(SegmentProvider.Emby to credits, merged[SegmentKind.Credits])
        assertEquals(SegmentProvider.IntroDB, merged[SegmentKind.Recap]?.first)
        assertNull(merged[SegmentKind.Preview])
    }

    @Test fun `the order decides, not who answered first`() {
        val other = Segment(SegmentKind.Intro, 0, 90_000)
        val a = SegmentRules.merge(listOf(SegmentProvider.IntroDB, SegmentProvider.TheIntroDB), mapOf(SegmentProvider.TheIntroDB to listOf(intro), SegmentProvider.IntroDB to listOf(other)))
        assertEquals(other, a[SegmentKind.Intro]?.second)
    }

    @Test fun `a provider timed on a cut more than five seconds longer or shorter is not used`() {
        val d = 2_700_000L
        assertEquals(listOf(intro), SegmentRules.valid(listOf(intro), d, declared = d + 5_000))
        assertTrue(SegmentRules.valid(listOf(intro), d, declared = d + 5_001).isEmpty())
        assertTrue(SegmentRules.valid(listOf(intro), d, declared = d - 6_000).isEmpty())
    }

    @Test fun `spans that cannot belong to the video are dropped and ends at the runtime run to the end`() {
        val d = 2_700_000L
        val segs = SegmentRules.valid(listOf(
            Segment(SegmentKind.Intro, 100, 50),
            Segment(SegmentKind.Recap, d + 1, null),
            Segment(SegmentKind.Credits, 2_600_000, d - 1_000),
            Segment(SegmentKind.Preview, 2_650_000, d + 60_000),
        ), d)
        assertEquals(listOf(Segment(SegmentKind.Credits, 2_600_000, null)), segs)
    }

    @Test fun `Emby markers come from chapter marker types`() {
        val item = BaseItemDto(id = "1", chapters = listOf(
            ChapterInfo("Chapter 1", 0),
            ChapterInfo("片头", msToTicks(5_000), "IntroStart"),
            ChapterInfo("片头结束", msToTicks(95_000), "IntroEnd"),
            ChapterInfo("片尾", msToTicks(2_500_000), "CreditsStart"),
        ))
        assertEquals(listOf(Segment(SegmentKind.Intro, 5_000, 95_000), Segment(SegmentKind.Credits, 2_500_000, null)), SegmentRules.fromChapters(item))
        val endOnly = BaseItemDto(id = "2", chapters = listOf(ChapterInfo(null, msToTicks(80_000), "IntroEnd")))
        assertEquals(listOf(Segment(SegmentKind.Intro, 0, 80_000)), SegmentRules.fromChapters(endOnly))
        assertTrue(SegmentRules.fromChapters(BaseItemDto(id = "3", chapters = listOf(ChapterInfo("c", 0, "Chapter")))).isEmpty())
    }

    @Test fun `another server's Emby markers are borrowed only within two seconds of runtime`() {
        fun copy(runtimeMs: Long) = BaseItemDto(
            id = "x", mediaSources = listOf(MediaSourceInfo(id = "s", runTimeTicks = msToTicks(runtimeMs))),
            chapters = listOf(ChapterInfo(null, msToTicks(90_000), "IntroEnd")),
        )
        assertTrue(SegmentRules.borrow(2_700_000, listOf(copy(2_702_001))).isEmpty())
        assertEquals(listOf(Segment(SegmentKind.Intro, 0, 90_000)), SegmentRules.borrow(2_700_000, listOf(copy(2_702_001), copy(2_698_500))))
        assertTrue(SegmentRules.borrow(null, listOf(copy(2_700_000))).isEmpty())
    }

    @Test fun `cached rows round-trip and say when a provider had nothing`() {
        val rows = SegmentRules.toRows("series:tmdb:1:s01:e02", "theintrodb", ProviderSegments(listOf(intro, credits), 2_700_000), 10)
        assertEquals(ProviderSegments(listOf(intro, credits), 2_700_000), SegmentRules.fromRows(rows))
        val none = SegmentRules.toRows("k", "introdb", ProviderSegments(emptyList()), 10)
        assertEquals(listOf(SegmentRules.KIND_NONE), none.map { it.kind })
        assertTrue(SegmentRules.fromRows(none).segments.isEmpty())
        assertTrue(SegmentRules.fresh(none, 10 + SegmentRules.EMPTY_TTL_MS - 1))
        assertFalse(SegmentRules.fresh(none, 10 + SegmentRules.EMPTY_TTL_MS))
        assertTrue(SegmentRules.fresh(rows, 10 + SegmentRules.EMPTY_TTL_MS))
        assertFalse(SegmentRules.fresh(emptyList(), 0))
    }

    @Test fun `queries use the series IDs and the episode numbers`() {
        val series = BaseItemDto(id = "s", type = "Series", providerIds = mapOf("Tvdb" to "81189", "Tmdb" to "1396", "IMDB" to "TT0903747"))
        val ep = BaseItemDto(id = "e", type = "Episode", parentIndexNumber = 1, indexNumber = 2, providerIds = mapOf("Tvdb" to "349232"))
        val q = SegmentQuery.of(ep, series, 2_800_000)
        assertEquals(SegmentQuery(false, "81189", "1396", "tt0903747", 1, 2, 2_800_000), q)
        assertTrue(q.episodeOk)
        assertFalse(SegmentQuery.of(ep.copy(indexNumber = null), series, null).episodeOk)
        val movie = SegmentQuery.of(BaseItemDto(id = "m", type = "Movie", providerIds = mapOf("Tmdb" to "603", "Imdb" to "nonsense")), null, null)
        assertEquals("603", movie.tmdb); assertNull(movie.imdb); assertTrue(movie.movie)
    }

    @Test fun `season keys`() {
        assertEquals("series:tmdb:1:s01", SegmentRepository.seasonKey("series:tmdb:1:s01:e02"))
        assertNull(SegmentRepository.seasonKey("movie:tmdb:603"))
        assertNull(SegmentRepository.seasonKey("item:a:1"))
    }

    @Test fun `remote editing of a mark starts from a default and stays inside the video`() {
        assertEquals(90_000, SegmentMarks.adjust(null, 90_000, 10_000, 2_000_000))
        assertEquals(100_000, SegmentMarks.adjust(90_000, 90_000, 10_000, 2_000_000))
        assertEquals(0, SegmentMarks.adjust(5_000, 90_000, -10_000, 2_000_000))
        assertEquals(2_000_000, SegmentMarks.adjust(1_995_000, 0, 60_000, 2_000_000))
    }
}
