package app.zmby.danmu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DanmuMatchTest {
    private fun c(title: String, id: Long, type: String = "电视剧") = DanmuCandidate(title, type, listOf(id))

    @Test fun `titles are taken apart into base, year, season and version tags`() {
        assertEquals(DanmuMatch.Parsed("漫长的季节", 2023, null, emptyList()), DanmuMatch.parse("漫长的季节(2023)【电视剧】from tencent"))
        assertEquals(DanmuMatch.Parsed("漫长的季节", 2023, null, listOf("卫视版", "卫视版")), DanmuMatch.parse("漫长的季节[卫视版][卫视版](2023)【电视剧】from tencent"))
        assertEquals(DanmuMatch.Parsed("葬送的芙莉莲", 2023, 1, listOf("日语版")), DanmuMatch.parse("葬送的芙莉莲 第1季[日语版](2023)【2D动漫】from tencent"))
        assertEquals(DanmuMatch.Parsed("葬送的芙莉莲", 2023, 1, listOf("普通话版")),
            DanmuMatch.parse("葬送的芙莉莲 第1季[普通话版] 第01话 葬送的芙莉莲[普通话版](2023)【动漫】from tencent"))
        assertEquals(listOf("普通话"), DanmuMatch.parse("葬送的芙莉莲 普通话(2025)【动漫】from 360").qualifiers)
        assertEquals(listOf("无障碍版(AI制作)"), DanmuMatch.parse("漫长的季节-无障碍版(AI制作)(2025)【电视剧】from 360").qualifiers)
        assertEquals(2, DanmuMatch.parse("某剧 第二季(2024)【电视剧】from x").season)
        assertEquals(12, DanmuMatch.parse("某剧 第十二季").season)
        assertEquals(3, DanmuMatch.parse("Some Show Season 3").season)
    }

    @Test fun `the plain version of the right title and year wins, in server order`() {
        val list = listOf(
            c("漫长的季节[卫视版](2023)【电视剧】from tencent", 1),
            c("漫长的季节-无障碍版(AI制作)(2025)【电视剧】from 360", 2),
            c("漫长的季节(2023)【电视剧】from tencent", 3),
            c("漫长的季节(2023)【电视剧】from 360", 4),
        )
        assertEquals(3L, DanmuMatch.choose(DanmuQuery(listOf("漫长的季节"), movie = false, season = 1, episode = 1, year = 2023), list))
    }

    @Test fun `the original language beats dubbed versions`() {
        val list = listOf(
            c("葬送的芙莉莲 第1季[普通话版] 第01话 葬送的芙莉莲[普通话版](2023)【动漫】from tencent", 10, "动漫"),
            c("葬送的芙莉莲 第1季[日语版](2023)【2D动漫】from tencent", 11, "2D动漫"),
        )
        assertEquals(11L, DanmuMatch.choose(DanmuQuery(listOf("葬送的芙莉莲"), movie = false, season = 1, episode = 3, year = 2023), list))
    }

    @Test fun `season, year, type and title must agree or nothing matches`() {
        val list = listOf(c("某剧(2020)【电视剧】from a", 1), c("某剧 第2季(2022)【电视剧】from a", 2), c("某剧(2020)【电影】from a", 3, "电影"))
        assertEquals(2L, DanmuMatch.choose(DanmuQuery(listOf("某剧"), movie = false, season = 2, episode = 1, year = 2022), list))
        assertEquals(1L, DanmuMatch.choose(DanmuQuery(listOf("某剧"), movie = false, season = 1, episode = 1, year = 2020), list))
        assertEquals(3L, DanmuMatch.choose(DanmuQuery(listOf("某剧"), movie = true, year = 2020), list))
        assertNull(DanmuMatch.choose(DanmuQuery(listOf("某剧"), movie = false, season = 3, episode = 1), list))
        assertNull(DanmuMatch.choose(DanmuQuery(listOf("某剧"), movie = false, season = 1, episode = 1, year = 2010), list))
        assertNull(DanmuMatch.choose(DanmuQuery(listOf("某剧之谜"), movie = false, season = 1, episode = 1), list))
        // An anime without exactly one episode for the asked number is not a match.
        assertNull(DanmuMatch.choose(DanmuQuery(listOf("某剧"), movie = false, season = 1, episode = 1), listOf(DanmuCandidate("某剧", "电视剧", listOf(1, 2)))))
    }
}
