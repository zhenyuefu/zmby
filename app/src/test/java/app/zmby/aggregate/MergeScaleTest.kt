package app.zmby.aggregate

import app.zmby.data.ServerItem
import app.zmby.emby.BaseItemDto
import org.junit.Assert.*
import org.junit.Test

class MergeScaleTest {
    private fun hit(server: String, order: Int, id: String, ids: Map<String, String>, title: String = "电影", year: Int = 2024) =
        Hit(ServerItem(server, BaseItemDto(id = id, name = title, type = "Movie", productionYear = year,
            path = "/movies/[字幕组] $title ($year) 2160p.REMUX.mkv", providerIds = ids)), order)

    @Test(timeout = 5000) fun `seven large server result sets merge by id without comparing unrelated titles`() {
        val hits = (0..6).flatMap { server -> (1..500).map { film ->
            hit("s$server", server, "$server-$film", mapOf("Tmdb" to "$film"), "[字幕组$server] 影片 $film (2024)")
        } }
        val result = Merger.merge(hits)
        assertEquals(500, result.works.size)
        assertTrue(result.unmerged.isEmpty())
        assertTrue(result.works.all { it.servers == 7 && it.entries.size == 7 })
        val latest = HomeMerge.latest(hits)
        assertEquals(40, latest.first.size)
        assertTrue(latest.second.values.all { it == 7 })
    }

    @Test fun `an id that fits two conflicting clusters remains ambiguous`() {
        val result = Merger.merge(listOf(
            hit("a", 0, "a", mapOf("Tmdb" to "1", "Imdb" to "tt1")),
            hit("b", 1, "b", mapOf("Tmdb" to "2", "Imdb" to "tt1")),
            hit("c", 2, "c", mapOf("Imdb" to "tt1")),
        ))
        assertEquals(3, result.works.size)
        assertTrue(result.works.all { it.entries.size == 1 })
    }

    @Test fun `secondary ids introduced by a merged entry are indexed`() {
        val result = Merger.merge(listOf(
            hit("a", 0, "a", mapOf("Tmdb" to "1")),
            hit("b", 1, "b", mapOf("Tmdb" to "1", "Imdb" to "tt1")),
            hit("c", 2, "c", mapOf("Imdb" to "tt1")),
        ))
        assertEquals(1, result.works.size)
        assertEquals(3, result.works.single().entries.size)
    }

    @Test fun `indexed title matching still refuses ambiguous remakes and conflicting years`() {
        val result = Merger.merge(listOf(
            hit("a", 0, "a", mapOf("Tmdb" to "1")),
            hit("b", 1, "b", mapOf("Tmdb" to "2")),
            hit("c", 2, "c", emptyMap()),
            hit("d", 3, "d", emptyMap(), year = 2023),
        ))
        assertEquals(2, result.works.size)
        assertEquals(setOf("c", "d"), result.unmerged.map { it.item.id }.toSet())
    }
}
