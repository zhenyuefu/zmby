package app.zmby.aggregate

import app.zmby.data.ServerItem
import app.zmby.emby.BaseItemDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Matching cases from the aggregation spec and the verification strategy (宁缺毋滥). */
class MatchTest {
    private fun movie(id: String, name: String, year: Int? = null, ids: Map<String, String> = emptyMap(), path: String? = null) =
        BaseItemDto(id = id, name = name, type = "Movie", productionYear = year, providerIds = ids, path = path)

    private fun series(id: String, name: String, year: Int? = null, ids: Map<String, String> = emptyMap()) =
        BaseItemDto(id = id, name = name, type = "Series", productionYear = year, providerIds = ids)

    private fun ep(id: String, series: String, s: Int, e: Int, ids: Map<String, String> = emptyMap(), path: String? = null) =
        BaseItemDto(id = id, name = "第 $e 集", type = "Episode", seriesName = series, parentIndexNumber = s, indexNumber = e, providerIds = ids, path = path)

    @Test fun `title normalization drops brackets, years and punctuation`() {
        assertEquals("某剧", Titles.normalize("[某字幕组] 某剧 (2024)"))
        assertEquals("哈利 波特与魔法石", Titles.normalize("哈利·波特与魔法石"))
        assertEquals("dune part two", Titles.normalize("Dune: Part Two"))
        assertEquals("星际穿越", Titles.normalize("【4K】星际穿越（重制版）"))
    }

    @Test fun `file fingerprint takes the file name of either slash`() {
        assertEquals("dune part two 2024 2160p", Titles.fileFingerprint("/movies/Dune.Part.Two.2024.2160p.mkv"))
        assertEquals("dune part two 2024 2160p", Titles.fileFingerprint("D:\\Movies\\Dune.Part.Two.2024.2160p.mkv"))
        assertEquals("", Titles.fileFingerprint(null))
    }

    @Test fun `provider id keys ignore case, values are trimmed, blank is absent`() {
        assertEquals("693134", movie("a", "x", ids = mapOf("TMDB" to " 693134 ")).tmdb())
        assertEquals("693134", movie("a", "x", ids = mapOf("tmdb" to "693134")).tmdb())
        assertNull(movie("a", "x", ids = mapOf("Tmdb" to "  ")).tmdb())
        assertEquals("tt15239678", movie("a", "x", ids = mapOf("IMDB" to "TT15239678")).imdb())
    }

    @Test fun `same tmdb under different translated titles is strong`() {
        val j = Match.compare(movie("a", "星际穿越", 2014, mapOf("Tmdb" to "157336")), movie("b", "星际效应", 2014, mapOf("TMDB" to "157336")))
        assertEquals(PairLevel.Strong, j.level)
        assertEquals("TMDB 一致", j.reason)
    }

    @Test fun `only imdb on both sides`() {
        assertEquals(PairLevel.Strong, Match.compare(movie("a", "A", ids = mapOf("Imdb" to "tt1")), movie("b", "B", ids = mapOf("imdb" to "TT1"))).level)
    }

    @Test fun `different tmdb never merges even with the same title and year`() {
        assertEquals(PairLevel.None, Match.compare(movie("a", "沙丘", 2021, mapOf("Tmdb" to "1")), movie("b", "沙丘", 2021, mapOf("Tmdb" to "2"))).level)
    }

    @Test fun `no ids on either side is not the same by id`() {
        // None ≠ None: without IDs only the title rules apply, and they need a unique candidate.
        val a = movie("a", "降临", 2016)
        val b = movie("b", "降临", 2016)
        assertEquals(PairLevel.Plausible, Match.compare(a, b).level)
        assertEquals(Confidence.Possible, Match.judge(listOf(b)) { Match.compare(a, it) }!!.second.confidence)
        assertNull(Match.judge(listOf(b, movie("c", "降临", 2016))) { Match.compare(a, it) })
    }

    @Test fun `years that differ never merge (remakes)`() {
        assertEquals(PairLevel.None, Match.compare(movie("a", "沙丘", 1984), movie("b", "沙丘", 2021)).level)
    }

    @Test fun `close title with the same file name is plausible`() {
        val a = movie("a", "沙丘2", path = "/m/Dune.Part.Two.2024.2160p.mkv")
        val b = movie("b", "沙丘2 IMAX", path = "/x/Dune.Part.Two.2024.2160p.mkv")
        assertEquals("片名接近 + 文件名一致", Match.compare(a, b).reason)
    }

    @Test fun `several strong candidates pick none`() {
        val ref = movie("a", "x", ids = mapOf("Tmdb" to "1"))
        assertNull(Match.judge(listOf(movie("b", "x", ids = mapOf("Tmdb" to "1")), movie("c", "x", ids = mapOf("Tmdb" to "1")))) { Match.compare(ref, it) })
        assertEquals("2 个同 ID 候选，未合并", Match.explain(listOf(movie("b", "x", ids = mapOf("Tmdb" to "1")), movie("c", "x", ids = mapOf("Tmdb" to "1")))) { Match.compare(ref, it) })
    }

    @Test fun `a single weak candidate is judged unique again`() {
        val ref = movie("a", "流浪地球")
        val only = movie("b", "流浪地球 2")
        // 「仅片名接近」 counts only because it is the only candidate left.
        val v = Match.judge(listOf(only, movie("c", "完全不同"))) { Match.compare(ref, it) }!!
        assertEquals(Confidence.Possible, v.second.confidence)
        assertEquals("b", v.first.id)
    }

    @Test fun `films and series never match`() {
        assertEquals(PairLevel.None, Match.compare(movie("a", "三体", ids = mapOf("Tmdb" to "1")), series("b", "三体", ids = mapOf("Tmdb" to "1"))).level)
    }

    @Test fun `episodes match on series ids plus season and episode`() {
        val s1 = series("s1", "漫长的季节", 2023, mapOf("Tmdb" to "9"))
        val s2 = series("s2", "The Long Season", 2023, mapOf("tmdb" to "9"))
        assertEquals(PairLevel.Strong, Match.compareEpisode(ep("e1", "漫长的季节", 1, 7), s1, ep("e2", "The Long Season", 1, 7), s2).level)
        assertEquals(PairLevel.None, Match.compareEpisode(ep("e1", "漫长的季节", 1, 7), s1, ep("e2", "The Long Season", 1, 8), s2).level)
        // Same name, different series TMDB: another show.
        assertEquals(PairLevel.None, Match.compareEpisode(ep("e1", "X", 1, 1), series("a", "X", ids = mapOf("Tmdb" to "1")), ep("e2", "X", 1, 1), series("b", "X", ids = mapOf("Tmdb" to "2"))).level)
        // No series IDs: series name + S/E is only plausible.
        assertEquals(PairLevel.Plausible, Match.compareEpisode(ep("e1", "某剧", 2, 3), null, ep("e2", "[字幕组] 某剧", 2, 3), null).level)
        // The episode's own TMDB when the series has none.
        assertEquals(PairLevel.Strong, Match.compareEpisode(ep("e1", "A", 1, 1, mapOf("Tmdb" to "55")), null, ep("e2", "B", 1, 1, mapOf("Tmdb" to "55")), null).level)
    }

    @Test fun `merge keys`() {
        assertEquals("movie:tmdb:1", Keys.movie(movie("a", "x", ids = mapOf("TMDB" to "1"))))
        assertEquals("movie:title:某剧:year:2024", Keys.movie(movie("a", "[组] 某剧 (2024)", 2024)))
        assertEquals("series:tvdb:7:s01:e02", Keys.episode(Keys.series(series("s", "x", ids = mapOf("Tvdb" to "7"))), 1, 2))
        assertNull(Keys.movie(movie("a", "x")))
        assertEquals("item:srv:a", Keys.work("srv", movie("a", "x")))
    }
}

class MergerTest {
    private fun hit(server: String, order: Int, id: String, name: String, type: String = "Movie", year: Int? = null, ids: Map<String, String> = emptyMap(), rank: Int = 0) =
        Hit(ServerItem(server, BaseItemDto(id = id, name = name, type = type, productionYear = year, providerIds = ids)), order, rank)

    @Test fun `same tmdb across servers is one work, translated titles included`() {
        val r = Merger.merge(listOf(
            hit("a", 0, "1", "星际穿越", ids = mapOf("Tmdb" to "157336")),
            hit("b", 1, "9", "星际效应", ids = mapOf("TMDB" to "157336")),
            hit("c", 2, "3", "Interstellar", ids = mapOf("tmdb" to "157336", "Imdb" to "tt0816692")),
        ))
        assertEquals(1, r.works.size)
        assertEquals(3, r.works.single().servers)
        assertEquals("a", r.works.single().lead.server)
    }

    @Test fun `conflicting ids split works`() {
        val r = Merger.merge(listOf(
            hit("a", 0, "1", "沙丘", year = 2021, ids = mapOf("Tmdb" to "438631")),
            hit("b", 1, "2", "沙丘", year = 1984, ids = mapOf("Tmdb" to "841")),
        ))
        assertEquals(2, r.works.size)
    }

    @Test fun `a film and a series with the same tmdb number stay apart`() {
        val r = Merger.merge(listOf(hit("a", 0, "1", "X", ids = mapOf("Tmdb" to "100")), hit("b", 1, "2", "X", type = "Series", ids = mapOf("Tmdb" to "100"))))
        assertEquals(2, r.works.size)
    }

    @Test fun `id-less hits join only a single title and year match, others go to unmerged interleaved`() {
        val r = Merger.merge(listOf(
            hit("a", 0, "1", "降临", year = 2016, ids = mapOf("Tmdb" to "329865")),
            hit("b", 1, "2", "降临", year = 2016),
            hit("b", 1, "3", "星际牛仔 剧场版", rank = 1),
            hit("c", 2, "4", "星际迷航 4K 合集"),
            hit("c", 2, "5", "星际宝贝", year = 2002, rank = 1),
            hit("b", 1, "6", "星际 无 ID", rank = 2),
        ))
        assertEquals(1, r.works.size)
        assertEquals(Confidence.Possible, r.works.single().reasons["b"]!!.confidence)
        // Round-robin by server: b, c, b, c.
        assertEquals(listOf("3", "4", "6", "5"), r.unmerged.map { it.item.id })
    }

    @Test fun `two id-less items never merge with each other`() {
        val r = Merger.merge(listOf(hit("a", 0, "1", "某片", year = 2020), hit("b", 1, "2", "某片", year = 2020)))
        assertTrue(r.works.isEmpty())
        assertEquals(2, r.unmerged.size)
    }
}
