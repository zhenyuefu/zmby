package app.zmby.ui

import app.zmby.catalog.CatalogEpisode
import app.zmby.catalog.CatalogId
import app.zmby.catalog.CatalogWork
import app.zmby.catalog.ResourceBatch
import app.zmby.catalog.ResourceMapping
import app.zmby.data.ServerItem
import app.zmby.emby.BaseItemDto
import app.zmby.emby.MediaSourceInfo
import app.zmby.ui.screens.episodeStatus
import app.zmby.ui.screens.unsortedCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailBlocksTest {
    private fun ep(server: String, id: String, sources: Int = 1) =
        ServerItem(server, BaseItemDto(id, type = "Episode", mediaSources = List(sources) { MediaSourceInfo("$id-$it") }))

    @Test fun `episode corner counts versions across servers`() {
        val s = episodeStatus(listOf(ep("a", "1", 2), ep("b", "2", 1)), null, "2026-10-26", searching = true, failed = false)
        assertEquals("3 个版本", s.label)
        assertFalse(s.dim)
    }

    @Test fun `episodes without resources say why and are dimmed when final`() {
        assertEquals("查找中", episodeStatus(emptyList(), "2026-01-01", "2026-10-26", searching = true, failed = false).label)
        val none = episodeStatus(emptyList(), "2026-01-01", "2026-10-26", searching = false, failed = false)
        assertEquals("无资源", none.label)
        assertTrue(none.dim)
        val future = episodeStatus(emptyList(), "2026-11-02", "2026-10-26", searching = true, failed = false)
        assertEquals("11 月 2 日播出", future.label)
        assertTrue(future.dim)
    }

    @Test fun `unsorted entries carry the server numbering and a reason`() {
        assertEquals("第 1 季 第 1 集", unsortedCode(BaseItemDto("x", parentIndexNumber = 1, indexNumber = 1)))
        assertNull(unsortedCode(BaseItemDto("x")))
        val work = CatalogWork(CatalogId("tmdb", "tv", "10"), "Test", ids = mapOf("Tmdb" to "10"))
        val catalog = listOf(CatalogEpisode("101", 1, 1, "One"))
        fun res(id: String, season: Int?, number: Int?, tmdb: String? = null) = ServerItem("a", BaseItemDto(id, type = "Episode", seriesId = "s", parentIndexNumber = season, indexNumber = number, providerIds = tmdb?.let { mapOf("Tmdb" to it) }.orEmpty()))
        val mapped = ResourceMapping.episodes(work, catalog, listOf(
            ResourceBatch("a", listOf(res("none", 1, null), res("far", 1, 9), res("wrong", 1, 1, tmdb = "999"), res("d1", 1, 1), res("d2", 1, 1)), true),
            ResourceBatch("a", listOf(res("guess", 1, 1)), false),
        ))
        val reasons = mapped.unmatched.associate { it.item.id to mapped.reasons[it.key] }
        assertEquals("没有季集编号", reasons["none"])
        assertEquals("目录里没有这一集", reasons["far"])
        assertEquals("外部 ID 对不上目录", reasons["wrong"])
        assertEquals(setOf("d1", "d2"), mapped.matched["101"]!!.map { it.item.id }.toSet())
        assertEquals("剧集身份未确认", reasons["guess"])
    }
}
