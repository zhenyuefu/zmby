package app.zmby.catalog

import app.zmby.aggregate.*
import app.zmby.emby.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test

class CatalogResourcesTest {
    private val health = object : HealthBook {
        override fun isCooling(serverId: String) = false
        override fun onSuccess(serverId: String) = Unit
        override fun onFailure(serverId: String, error: Throwable, timedOut: Boolean) = Unit
    }
    @Test fun `directory scans all seasons and pages preserving conflicting series as separate resources`() = runBlocking {
        val scanned = mutableListOf<String>()
        val source = object : VersionSource {
            override suspend fun byProviderIds(server: String, type: String, ids: List<String>) = listOf(
                BaseItemDto("good", type = "Series", providerIds = mapOf("Tmdb" to "10", "Imdb" to "tt1")),
                BaseItemDto("conflict", type = "Series", providerIds = mapOf("Tmdb" to "10", "Imdb" to "tt2")),
                BaseItemDto("unrelated", type = "Series", providerIds = mapOf("Tmdb" to "99")))
            override suspend fun searchType(server: String, term: String, type: String, limit: Int) = error("No name search required")
            override suspend fun item(server: String, id: String): BaseItemDto = error("No metadata enrichment")
            override suspend fun sources(server: String, itemId: String): List<MediaSourceInfo> = error("No PlaybackInfo for directory scans")
            override suspend fun seasons(server: String, seriesId: String): List<BaseItemDto> {
                scanned += seriesId
                return listOf(BaseItemDto("$seriesId:1", type = "Season", indexNumber = 1), BaseItemDto("$seriesId:99", type = "Season", indexNumber = 99))
            }
            override suspend fun episodePage(server: String, seriesId: String, seasonId: String, start: Int, limit: Int) =
                ItemsResult(items = listOf(BaseItemDto("$seasonId:$start", type = "Episode", indexNumber = start + 1)), totalRecordCount = 2)
        }
        val work = CatalogWork(CatalogId("tmdb", "tv", "10"), "Series", ids = mapOf("Tmdb" to "10", "Imdb" to "tt1"))
        val result = CatalogResources(source, FanOut(health)).series(listOf("a"), work).toList().single()
        assertTrue(result is Outcome.Ok)
        val batches = (result as Outcome.Ok).value
        assertEquals(setOf("good", "conflict"), scanned.toSet())
        assertEquals(4, batches.single { it.verified }.episodes.size)
        assertEquals(4, batches.single { !it.verified }.episodes.size)
        assertTrue(batches.all { it.episodes.all { ref -> ref.server == "a" && ref.item.parentIndexNumber != null && ref.item.seriesId != null } })
        val mapped = ResourceMapping.episodes(work, listOf(CatalogEpisode("101", 1, 1, "One"), CatalogEpisode("102", 1, 2, "Two")), batches)
        assertEquals(2, mapped.matched.size); assertEquals(6, mapped.unmatched.size)
    }
}
