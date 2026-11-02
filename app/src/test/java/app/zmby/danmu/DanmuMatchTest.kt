package app.zmby.danmu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DanmuMatchTest {
    private fun c(title: String, id: Long, type: String = "电视剧") = DanmuCandidate(title, type, listOf(id))

    @Test fun `full search selects explicit episode numbers and ignores trailers music and similarly named works`() {
        val q = DanmuQuery(listOf("末日地堡"), false, 2, 3, 2023)
        val group = listOf(
            DanmuTarget(1, "one", "末日地堡 第二季(2024)【电视剧】from source", "预告片", "电视剧"),
            DanmuTarget(2, "one", "末日地堡 第二季(2024)【电视剧】from source", "【source】 第01集", "电视剧"),
            DanmuTarget(3, "one", "末日地堡 第二季(2024)【电视剧】from source", "【source】 第03集", "电视剧"),
            DanmuTarget(4, "two", "末日地堡，被豢养的良心(2026)【电视剧】", "第3集", "电视剧"),
        )
        assertEquals(3L, DanmuMatch.chooseTarget(q, group)?.episodeId)
        assertNull(DanmuMatch.chooseTarget(q.copy(season = 4), group))
        assertNull(DanmuMatch.chooseTarget(q.copy(episode = 4), group))
        assertNull(DanmuMatch.chooseTarget(q.copy(names = listOf("Lupin"), season = 1, episode = 1, year = null),
            listOf(DanmuTarget(9, "mv", "Lupin", "第1话", "musicvideo"))))
    }

    @Test fun `ambiguous episode numbers in one source are not guessed from order or transient IDs`() {
        val q = DanmuQuery(listOf("某剧"), false, 1, 2)
        val rows = listOf(DanmuTarget(100, "a", "某剧", "EP02", "电视剧"), DanmuTarget(5, "a", "某剧", "第2集", "电视剧"))
        assertNull(DanmuMatch.chooseTarget(q, rows))
        assertEquals(2, DanmuMatch.episodeNumber("【source】 第02集：标题"))
        assertEquals(3, DanmuMatch.episodeNumber("Episode 03"))
        assertNull(DanmuMatch.episodeNumber("预告"))
    }

    @Test fun `identical transient episode IDs in different groups cannot replace the accepted source`() {
        val wrong = DanmuTarget(100, "wrong", "另一个作品", "第1集", "电视剧")
        val right = DanmuTarget(100, "right", "某剧", "第1集", "电视剧")
        assertEquals(right, DanmuMatch.chooseTarget(DanmuQuery(listOf("某剧"), false, 1, 1), listOf(wrong, right)))
    }

    @Test fun `a manual season follows the chosen platform within a merged multi platform group`() {
        val q = DanmuQuery(listOf("某剧"), false, 1, 1, workId = "tmdb:tv:1")
        val anchor = DanmuTarget(100, "group", "某剧 from aggregator", "【platform-a】 第01集", "电视剧", "https://a.example/1")
        val next = anchor.copy(episodeId = 103, episodeTitle = "【platform-a】 第02集", url = "https://a.example/2")
        val other = next.copy(episodeId = 102, episodeTitle = "【platform-b】 第02集", url = "https://b.example/2")
        val rule = DanmuSeasonChoice.of(q, DanmuSelection(anchor, "某剧"))!!
        assertEquals(next, rule.select(2, listOf(anchor, other, next)).target)
        try {
            rule.select(2, listOf(anchor, other))
            org.junit.Assert.fail("must not switch platforms")
        } catch (_: java.io.IOException) { }
    }

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
