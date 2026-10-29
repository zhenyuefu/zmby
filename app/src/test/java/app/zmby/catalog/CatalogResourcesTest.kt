package app.zmby.catalog

import app.zmby.aggregate.*
import app.zmby.emby.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test

class CatalogResourcesTest {
    private class NamedSource(private val candidates: List<BaseItemDto>, private val identified: List<BaseItemDto> = emptyList(),
        private val episodeParent: String? = null, private val parentIdentities: List<BaseItemDto> = emptyList()) : VersionSource {
        var queries = 0
        var identityQueries = 0
        override suspend fun byProviderIds(server: String, type: String, ids: List<String>): List<BaseItemDto> { queries++; return identified }
        override suspend fun searchType(server: String, term: String, type: String, limit: Int) = candidates
        override suspend fun item(server: String, id: String): BaseItemDto = error("not needed")
        override suspend fun identities(server: String, ids: List<String>): List<BaseItemDto> { identityQueries++; return parentIdentities }
        override suspend fun sources(server: String, itemId: String): List<MediaSourceInfo> = error("not needed")
        override suspend fun seasons(server: String, seriesId: String) = listOf(BaseItemDto("season:$seriesId", type = "Season", indexNumber = 1))
        override suspend fun episodePage(server: String, seriesId: String, seasonId: String, start: Int, limit: Int) =
            ItemsResult(listOf(BaseItemDto("episode:$seriesId", type = "Episode", indexNumber = 1, seriesId = episodeParent)), 1)
    }

    @Test fun `one exact name candidate without IDs maps by its season and episode and directory is cached`() = runBlocking {
        val work = CatalogWork(CatalogId("tmdb", "tv", "10"), "某部剧", year = 2024, ids = mapOf("Tmdb" to "10"))
        val source = NamedSource(listOf(BaseItemDto("series", "[网盘] 某部剧", "Series", productionYear = 2024)))
        val fan = FanOut(health)
        val loader = CatalogResources(source, fan)
        val fallback = app.zmby.data.ServerItem("a", source.searchType("a", "", "", 5).single())
        val batch = (loader.series(listOf("a"), work, fallback).toList().single() as Outcome.Ok).value
        val mapped = ResourceMapping.episodes(work, listOf(CatalogEpisode("101", 1, 1, "One")), batch)
        assertEquals("episode:series", mapped.matched["101"]!!.single().item.id)
        assertTrue(mapped.unmatched.isEmpty())
        CatalogResources(source, fan).series(listOf("a"), work, fallback).toList()
        assertEquals(1, source.queries)
        loader.invalidate(listOf("a"), work, fallback)
        loader.series(listOf("a"), work, fallback).toList()
        assertEquals(2, source.queries)
    }

    @Test fun `ambiguous exact names and explicit conflicts do not borrow episode numbers`() = runBlocking {
        val work = CatalogWork(CatalogId("tmdb", "tv", "10"), "某部剧", year = 2024, ids = mapOf("Tmdb" to "10"))
        val a = BaseItemDto("a", "某部剧", "Series", productionYear = 2024)
        val source = NamedSource(listOf(a, a.copy(id = "b")))
        val batches = (CatalogResources(source, FanOut(health)).series(listOf("a"), work).toList().single() as Outcome.Ok).value
        val mapped = ResourceMapping.episodes(work, listOf(CatalogEpisode("101", 1, 1, "One")), batches)
        assertTrue(mapped.matched.isEmpty())
        assertEquals(2, mapped.unmatched.size)
        assertFalse(ResourceMapping.named(work, a.copy(providerIds = mapOf("Tmdb" to "99"))))
        assertFalse(ResourceMapping.named(work, a.copy(productionYear = 2023)))
        assertFalse(ResourceMapping.named(work, a.copy(name = "某部剧 第二季")))
    }

    @Test fun `the opened series with explicit IDs is verified even when provider search only returns another edition`() = runBlocking {
        val work = CatalogWork(CatalogId("tmdb", "tv", "10"), "某部剧", ids = mapOf("Tmdb" to "10"))
        val opened = BaseItemDto("opened", type = "Series", providerIds = mapOf("Tmdb" to "10"))
        val source = NamedSource(emptyList(), listOf(opened.copy(id = "other-edition")))
        val batches = (CatalogResources(source, FanOut(health)).series(listOf("a"), work,
            app.zmby.data.ServerItem("a", opened)).toList().single() as Outcome.Ok).value
        assertTrue(batches.all { it.verified })
        val mapped = ResourceMapping.episodes(work, listOf(CatalogEpisode("101", 1, 1, "One")), batches)
        assertEquals(setOf("episode:opened", "episode:other-edition"), mapped.matched["101"]!!.map { it.item.id }.toSet())
        assertTrue(mapped.unmatched.isEmpty())
    }

    @Test fun `provider search omitting IDs does not erase the opened series identity or override explicit conflicts`() = runBlocking {
        val work = CatalogWork(CatalogId("tmdb", "tv", "10"), "某部剧", ids = mapOf("Tmdb" to "10", "Imdb" to "tt1"))
        val opened = BaseItemDto("opened", type = "Series", providerIds = work.ids)
        suspend fun batches(found: BaseItemDto) = (CatalogResources(NamedSource(emptyList(), listOf(found)), FanOut(health))
            .series(listOf("a"), work, app.zmby.data.ServerItem("a", opened)).toList().single() as Outcome.Ok).value
        assertTrue(batches(opened.copy(providerIds = emptyMap())).single().verified)
        assertFalse(batches(opened.copy(providerIds = work.ids + ("Imdb" to "tt2"))).single().verified)
    }

    @Test fun `episodes from another parent edition use verified external identity instead of being marked unconfirmed`() = runBlocking {
        val work = CatalogWork(CatalogId("tmdb", "tv", "10"), "Series", ids = mapOf("Tmdb" to "10"))
        val series = BaseItemDto("queried", type = "Series", providerIds = work.ids)
        val source = NamedSource(emptyList(), listOf(series), "alias", listOf(series.copy(id = "alias")))
        val batches = (CatalogResources(source, FanOut(health)).series(listOf("a"), work).toList().single() as Outcome.Ok).value
        val mapped = ResourceMapping.episodes(work, listOf(CatalogEpisode("101", 1, 1, "One")), batches)
        assertEquals(1, source.identityQueries)
        assertTrue(batches.single().verified)
        assertEquals("alias", mapped.matched["101"]!!.single().item.seriesId)
        assertTrue(mapped.unmatched.isEmpty())
    }

    @Test fun `conflicting or absent parent identities do not bypass series verification`() = runBlocking {
        val work = CatalogWork(CatalogId("tmdb", "tv", "10"), "Series", ids = mapOf("Tmdb" to "10"))
        val series = BaseItemDto("queried", type = "Series", providerIds = work.ids)
        for (parents in listOf(emptyList(), listOf(series.copy(id = "alias", providerIds = mapOf("Tmdb" to "99"))))) {
            val source = NamedSource(emptyList(), listOf(series), "alias", parents)
            val batches = (CatalogResources(source, FanOut(health)).series(listOf("a"), work).toList().single() as Outcome.Ok).value
            assertFalse(batches.single().verified)
            val mapped = ResourceMapping.episodes(work, listOf(CatalogEpisode("101", 1, 1, "One")), batches)
            assertTrue(mapped.matched.isEmpty())
            assertEquals("剧集身份未确认", mapped.reasons["a:episode:queried"])
        }
    }

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
