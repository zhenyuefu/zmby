package app.zmby.ui

import app.zmby.catalog.*
import app.zmby.data.ServerItem
import app.zmby.emby.BaseItemDto
import app.zmby.store.EpisodeMappingRule
import app.zmby.ui.screens.mappingPreview
import org.junit.Assert.*
import org.junit.Test

class EpisodeMappingPreviewTest {
    @Test fun `preview distinguishes remappable files unknown targets and identity-protected files`() {
        val rule = EpisodeMappingRule("rule", "tmdb:tv:10", "a", "parent", 4, 1, 9, 3)
        fun ref(id: String, n: Int, ids: Map<String, String> = emptyMap()) = ServerItem("a", BaseItemDto(id, type = "Episode",
            seriesId = "parent", parentIndexNumber = 4, indexNumber = n, providerIds = ids))
        val catalog = listOf(CatalogEpisode("301", 3, 1, "One"))
        val first = ref("first", 1)
        val extra = ref("extra", 9)
        val protected = ref("exact", 2, mapOf("Tmdb" to "301"))
        val preview = mappingPreview(rule, listOf(first, extra, protected), catalog)
        assertEquals(1, preview.mapped)
        assertEquals(1, preview.remaining)
        assertEquals(1, preview.protected)
        assertTrue(preview.examples.first().contains("第 4 季 第 1 集 → 第 3 季 第 1 集"))
        val override = rule.copy(itemId = protected.item.id, targetEpisodeId = "301")
        assertEquals(1, mappingPreview(override, listOf(protected), catalog).mapped)
        assertEquals(0, mappingPreview(override, listOf(protected), catalog).protected)
    }
}
