package app.zmby.danmu

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class DanmuProtocolTest {
    @Test fun `server-confirmed alternate title year and cross-season numbering are accepted`() {
        val body = """{"success":true,"isMatched":true,"matches":[{"episodeId":99,"animeId":7,"animeTitle":"菜鸟炊事兵 第1季(2025)","episodeTitle":"第58集","type":"电视剧","url":"https://video.example/58","shift":1.5}]}"""
        val target = DanmuProtocol.matched(body, movie = false).single()
        assertEquals(99L, target.episodeId)
        assertEquals("7", target.animeId)
        assertEquals("第58集", target.episodeTitle)
        assertEquals(1.5, target.shift, .001)
    }

    @Test fun `only invalid IDs and clear media type conflicts are rejected locally`() {
        val body = """{"isMatched":true,"matches":[{"episodeId":0},{"episodeId":-1},{"episodeId":3,"type":"电影"},{"episodeId":4,"type":"电视剧"},{"episodeId":5,"type":"2D动漫"},{"episodeId":6}]}"""
        assertEquals(listOf(4L,5L,6L), DanmuProtocol.matched(body, false).map { it.episodeId })
        assertEquals(listOf(3L,5L,6L), DanmuProtocol.matched(body, true).map { it.episodeId })
        assertTrue(DanmuProtocol.matched("""{"isMatched":false,"matches":[{"episodeId":7}]}""", false).isEmpty())
    }

    @Test fun `manual search keeps all episode choices and source identities`() {
        val body = """{"success":true,"animes":[{"animeId":7,"animeTitle":"title","type":"电视剧","episodes":[{"episodeId":1,"episodeTitle":"第1集","url":"https://video.example/1"},{"episodeId":2,"episodeTitle":"第2集","url":"https://video.example/2"},null]}]}"""
        val targets = DanmuProtocol.searched(body)
        assertEquals(2, targets.size)
        assertEquals("title · 第2集", targets.last().label)
        assertNotEquals(targets.first().sourceKey, targets.last().sourceKey)
    }

    @Test fun `invalid payloads are errors rather than cached empty results`() {
        for (body in listOf("not JSON", "[]", """{"success":false,"comments":[]}""", """{"errorCode":500,"comments":[]}""", "{}")) {
            try { DanmuProtocol.comments(body); fail("payload accepted") } catch (_: IOException) { }
        }
        assertTrue(DanmuProtocol.comments("""{"comments":[]}""").isEmpty())
    }
}
