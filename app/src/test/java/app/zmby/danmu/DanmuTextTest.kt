package app.zmby.danmu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmuTextTest {
    @Test fun `LogVar like and repeat counts and their hair spaces are preserved`() {
        assertEquals("追完唐诡无缝切换 ️♡6", DanmuText.clean("追完唐诡无缝切换 ️♡6"))
        assertEquals("打卡 x 27 ️♡48", DanmuText.clean("打卡 x 27 ️♡48"))
        assertEquals("火热 🔥1.2w", DanmuText.clean("火热 🔥1.2w"))
        assertEquals("家人👨‍👩‍👧‍👦❤️", DanmuText.clean("家人👨‍👩‍👧‍👦❤️"))
        assertEquals("a b", DanmuText.clean("  a b  "))
        assertNull(DanmuText.clean("​ "))
        assertNull(DanmuText.clean(null))
        assertEquals(DanmuText.MAX_CHARS, DanmuText.clean("长".repeat(200))!!.length)
    }

    @Test fun `p gives time in milliseconds and colour`() {
        assertEquals(Danmaku(12_340, "hi", 0xFF0000), DanmuText.parse("12.34,1,16711680,[qq]", "hi"))
        assertEquals(Danmaku(0, "hi", Danmaku.WHITE), DanmuText.parse("0.00,1,abc", "hi"))
        assertNull(DanmuText.parse("-1,1,0", "hi"))
        assertNull(DanmuText.parse("x", "hi"))
    }

    @Test fun `comments are read from a dandanplay answer, broken entries skipped`() {
        val body = """{"count":3,"comments":[{"cid":1,"p":"1.5,1,16777215,[qq]","m":"一"},{"cid":2,"p":"bad","m":"二"},{"cid":3,"p":"3,4,255","m":"三 ♡1"}]}"""
        assertEquals(listOf(Danmaku(1_500, "一"), Danmaku(3_000, "三 ♡1", 255)), DanmuText.comments(body))
        assertTrue(DanmuText.comments("not json").isEmpty())
    }

    @Test fun `the client keeps service repeats and small datasets unchanged`() {
        val repeats = listOf(Danmaku(1_000, "哈"), Danmaku(3_000, "哈"), Danmaku(7_000, "哈"))
        assertEquals(listOf(1_000L, 3_000L, 7_000L), DanmuText.filter(repeats, 3).map { it.timeMs })
        val crowd = (0 until 50).map { Danmaku(it * 100L, "第 $it 条") }
        val kept = DanmuText.filter(crowd, rows = 2)
        assertEquals(50, kept.size)
        assertEquals(crowd.take(kept.size), kept)
    }

    @Test fun `long messages keep count suffixes and truncation does not split supplementary characters`() {
        val suffix = " x 27 🔥1.2w"
        assertEquals("长".repeat(59) + "…" + suffix, DanmuText.clean("长".repeat(200) + suffix))
        val emoji = DanmuText.clean("😀".repeat(100) + " ️♡48")!!
        assertEquals("😀".repeat(59) + "… ️♡48", emoji)
        assertEquals("普通 间隔", DanmuText.clean("普通 间隔"))
    }

    @Test fun `large datasets cover the whole film including its final comment`() {
        val list = (0 until 30_000).reversed().map { Danmaku(it * 1_000L, "c$it") }
        val kept = DanmuText.filter(list, 3)
        assertEquals(DanmuText.MAX_TOTAL, kept.size)
        assertEquals(0L, kept.first().timeMs)
        assertEquals(29_999_000L, kept.last().timeMs)
        assertTrue(kept.zipWithNext().all { (a,b) -> a.timeMs <= b.timeMs })
        assertTrue(kept.any { it.timeMs in 14_000_000..16_000_000 })
        assertEquals(4_000, DanmuText.filter(list, 1).size)
    }

    @Test fun `search answers become candidates`() {
        val body = """{"animes":[{"animeTitle":"漫长的季节(2023)【电视剧】from tencent","type":"电视剧","episodes":[{"episodeId":10014,"episodeTitle":"第1集"}]},{"type":"x"}]}"""
        assertEquals(listOf(DanmuCandidate("漫长的季节(2023)【电视剧】from tencent", "电视剧", listOf(10014L))), DanmuText.searchResult(body))
    }
}
