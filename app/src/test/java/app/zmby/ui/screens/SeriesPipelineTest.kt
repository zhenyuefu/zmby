package app.zmby.ui.screens

import app.zmby.aggregate.Outcome
import app.zmby.catalog.CatalogEpisode
import app.zmby.catalog.CatalogId
import app.zmby.catalog.CatalogSeason
import app.zmby.catalog.CatalogWork
import app.zmby.catalog.EpisodeIdentity
import app.zmby.catalog.EpisodeIdentityState
import app.zmby.catalog.ResourceBatch
import app.zmby.data.ServerItem
import app.zmby.emby.BaseItemDto
import app.zmby.emby.UserItemData
import app.zmby.store.EpisodeMappingRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SeriesPipelineTest {
    private fun episode(id: String, season: Int, number: Int) =
        BaseItemDto(id, name = "第 $number 集", type = "Episode", seriesId = "show", parentIndexNumber = season, indexNumber = number)
    private fun season(id: String, number: Int) = BaseItemDto(id, name = "第 $number 季", type = "Season", indexNumber = number)

    private class Fake : SeriesSource {
        var reachable = true
        val answers = mutableListOf<Boolean>()
        var catalogDelayMs = 0L
        val catalogCalls = mutableListOf<Int>()
        override fun directoryReachable() = reachable
        override fun directoryAnswered(ok: Boolean) { answers += ok }
        val stored = mutableMapOf<String, BaseItemDto>()
        var seasons: List<BaseItemDto> = emptyList()
        val episodes = mutableMapOf<String, List<BaseItemDto>>()
        val episodeCalls = mutableListOf<String>()
        var episodeFailures = 0
        var next: BaseItemDto? = null
        val catalogPages = mutableMapOf<Int, List<CatalogEpisode>>()
        var resourceFlow: Flow<Outcome<List<ResourceBatch>>> = flowOf()
        var cooling: Long? = null
        override suspend fun storedItem(server: String, id: String) = stored[id]
        var seasonCalls = 0
        override suspend fun serverSeasons(server: String, seriesId: String) = seasons.also { seasonCalls++ }
        override suspend fun serverEpisodes(server: String, seriesId: String, season: BaseItemDto): List<BaseItemDto> {
            episodeCalls += season.id
            if (episodeFailures > 0) { episodeFailures--; throw IOException("connection reset") }
            return episodes[season.id].orEmpty()
        }
        override suspend fun nextUp(server: String, seriesId: String) = next
        override suspend fun catalogEpisodes(work: CatalogWork, season: Int): List<CatalogEpisode> {
            catalogCalls += season
            if (catalogDelayMs > 0) kotlinx.coroutines.delay(catalogDelayMs)
            return catalogPages[season] ?: throw IOException("no page")
        }
        override fun resources(targets: List<String>, work: CatalogWork, fallback: ServerItem?) = resourceFlow
        override suspend fun episodeIdentity(work: CatalogWork, item: BaseItemDto, refresh: Boolean) = EpisodeIdentity(EpisodeIdentityState.NotFound)
        override fun mappings(workKey: String): Flow<List<EpisodeMappingRule>> = flowOf(emptyList())
        override fun landscapeImage(ref: ServerItem): String? = null
        override val metadataOrder: Flow<List<app.zmby.data.SourceRules.MetaSource>> = flowOf(emptyList())
        override fun coolingUntil(server: String) = cooling
    }

    private class Harness(val scope: TestScope, val source: Fake, episodeId: String? = null, canonical: CatalogWork? = null) {
        val canonical = MutableStateFlow(canonical)
        val lookup = MutableStateFlow<CatalogWork?>(null)
        val fallback = MutableStateFlow<ServerItem?>(null)
        val targets = MutableStateFlow(listOf("a"))
        val progress = MutableStateFlow<Map<String, UserItemData>>(emptyMap())
        // Not backgroundScope: advanceUntilIdle does not wait for background work.
        val work = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler))
        val pipeline = SeriesPipeline(work, "a", "show", episodeId, source, this.canonical, lookup, fallback, targets, progress,
            now = { scope.testScheduler.currentTime }, compute = StandardTestDispatcher(scope.testScheduler))
        fun close() = work.cancel()
    }

    @Test fun `server seasons list the first season and open on the episode the page was opened for`() = runTest {
        val fake = Fake().apply {
            seasons = listOf(season("s0", 0), season("s1", 1), season("s2", 2))
            episodes["s1"] = listOf(episode("e1", 1, 1), episode("e2", 1, 2))
            episodes["s2"] = listOf(episode("e3", 2, 1), episode("e4", 2, 2))
            stored["e4"] = episode("e4", 2, 2)
        }
        val h = Harness(this, fake, episodeId = "e4")
        val picks = mutableListOf<String?>()
        h.work.launch { h.pipeline.picks.collect { picks += it } }
        advanceUntilIdle()
        val data = h.pipeline.state.value
        assertEquals(2, data.season)
        assertEquals(listOf("e3", "e4"), data.episodes.map { it.item.id })
        assertEquals("s2:e2", h.pipeline.currentKey.value)
        assertEquals(listOf<String?>("s2:e2"), picks)
        assertFalse(data.loading)
        h.close()
    }

    @Test fun `without an opened episode the next to watch decides, and choosing a season clears the episode`() = runTest {
        val fake = Fake().apply {
            seasons = listOf(season("s1", 1), season("s2", 2))
            episodes["s1"] = listOf(episode("e1", 1, 1))
            episodes["s2"] = listOf(episode("e3", 2, 1))
            next = episode("e1", 1, 1)
        }
        val h = Harness(this, fake)
        advanceUntilIdle()
        assertEquals("s1:e1", h.pipeline.currentKey.value)
        h.pipeline.chooseSeason(2)
        advanceUntilIdle()
        val data = h.pipeline.state.value
        assertEquals(2, data.season)
        assertNull(h.pipeline.currentKey.value)
        assertEquals(listOf("e3"), data.episodes.map { it.item.id })
        h.close()
    }

    @Test fun `a work known only by its external IDs, catalogue configured or not, lists the server's seasons`() = runTest {
        val fake = Fake().apply {
            seasons = listOf(season("s1", 1))
            episodes["s1"] = listOf(episode("e1", 1, 1))
        }
        val h = Harness(this, fake)
        h.lookup.value = CatalogWork(CatalogId("tmdb", "tv", "10"), "Test", ids = mapOf("Tmdb" to "10"))
        advanceUntilIdle()
        assertEquals(listOf("e1"), h.pipeline.state.value.episodes.map { it.item.id })
        assertFalse(h.pipeline.state.value.loading)
        h.close()
    }

    private val tmdbWork = CatalogWork(CatalogId("tmdb", "tv", "10"), "Test", ids = mapOf("Tmdb" to "10"), seasons = listOf(CatalogSeason(1, "第 1 季", 2)))
    private fun serverShow() = Fake().apply {
        seasons = listOf(season("s1", 1))
        episodes["s1"] = listOf(episode("e1", 1, 1), episode("e2", 1, 2))
    }

    @Test fun `when the catalogue cannot list the season the server's own seasons show, with a note`() = runTest {
        val fake = serverShow()
        val h = Harness(this, fake, canonical = tmdbWork)
        advanceUntilIdle()
        val data = h.pipeline.state.value
        assertNull(data.directoryWork)
        assertEquals(SeriesPipeline.UNREACHABLE, data.directoryNote)
        assertEquals(listOf("e1", "e2"), data.episodes.map { it.item.id })
        assertEquals(listOf(false), fake.answers)
        h.close()
    }

    @Test fun `a catalogue slower than the wait falls back too, and a retry tries it again`() = runTest {
        val fake = serverShow().apply {
            catalogPages[1] = listOf(CatalogEpisode("101", 1, 1, "One"), CatalogEpisode("102", 1, 2, "Two"))
            catalogDelayMs = SeriesPipeline.DIRECTORY_WAIT_MS + 5_000
        }
        val h = Harness(this, fake, canonical = tmdbWork)
        runCurrent(); advanceTimeBy(SeriesPipeline.DIRECTORY_WAIT_MS + 10); runCurrent(); advanceTimeBy(10); runCurrent()
        assertNull(h.pipeline.state.value.directoryWork)
        assertEquals(listOf("e1", "e2"), h.pipeline.state.value.episodes.map { it.item.id })
        fake.catalogDelayMs = 0
        h.pipeline.resourceRetry.value++
        advanceUntilIdle()
        val data = h.pipeline.state.value
        assertEquals(tmdbWork, data.directoryWork)
        assertNull(data.directoryNote)
        assertEquals(listOf("101", "102"), data.episodes.map { it.canonical?.id })
        assertEquals(listOf(false, true), fake.answers)
        h.close()
    }

    @Test fun `after a recent failure elsewhere the page starts on the server's seasons without asking the catalogue`() = runTest {
        val fake = serverShow().apply { reachable = false; catalogPages[1] = listOf(CatalogEpisode("101", 1, 1, "One")) }
        val h = Harness(this, fake, canonical = tmdbWork)
        advanceUntilIdle()
        assertNull(h.pipeline.state.value.directoryWork)
        assertEquals(listOf("e1", "e2"), h.pipeline.state.value.episodes.map { it.item.id })
        assertTrue(fake.catalogCalls.isEmpty())
        h.close()
    }

    @Test fun `a season that failed to load is asked again once the server's cool-down ends`() = runTest {
        val fake = Fake().apply {
            seasons = listOf(season("s1", 1))
            episodes["s1"] = listOf(episode("e1", 1, 1))
            episodeFailures = 1
        }
        val h = Harness(this, fake)
        runCurrent(); advanceTimeBy(10); runCurrent()
        assertEquals("剧集目录暂时无法加载", h.pipeline.state.value.episodeError)
        assertTrue(h.pipeline.state.value.episodes.isEmpty())
        advanceUntilIdle()
        assertNull(h.pipeline.state.value.episodeError)
        assertEquals(listOf("e1"), h.pipeline.state.value.episodes.map { it.item.id })
        assertTrue(fake.episodeCalls.size >= 2)
        h.close()
    }

    @Test fun `with the catalogue work already cached the next to watch still decides the episode`() = runTest {
        val work = CatalogWork(CatalogId("tmdb", "tv", "10"), "Test", ids = mapOf("Tmdb" to "10"),
            seasons = listOf(CatalogSeason(1, "第 1 季", 2), CatalogSeason(2, "第 2 季", 2)))
        val fake = Fake().apply {
            catalogPages[1] = listOf(CatalogEpisode("101", 1, 1, "One"), CatalogEpisode("102", 1, 2, "Two"))
            catalogPages[2] = listOf(CatalogEpisode("201", 2, 1, "Three"), CatalogEpisode("202", 2, 2, "Four"))
            next = episode("a4", 2, 2)
            resourceFlow = flowOf(Outcome.Ok("a", listOf(ResourceBatch("a",
                listOf(ServerItem("a", episode("a1", 1, 1)), ServerItem("a", episode("a4", 2, 2))), true)), 1))
        }
        val h = Harness(this, fake, canonical = work)
        val picks = mutableListOf<String?>()
        h.work.launch { h.pipeline.picks.collect { picks += it } }
        advanceUntilIdle()
        assertEquals(2, h.pipeline.state.value.season)
        assertEquals("s2:e2", h.pipeline.currentKey.value)
        assertEquals(listOf<String?>("s2:e2"), picks)
        assertTrue(fake.seasonCalls == 0)
        h.close()
    }

    @Test fun `catalogue episodes gain their resources as servers answer, one at a time`() = runTest {
        val work = CatalogWork(CatalogId("tmdb", "tv", "10"), "Test", ids = mapOf("Tmdb" to "10"), seasons = listOf(CatalogSeason(1, "第 1 季", 2)))
        val first = CompletableDeferred<Unit>()
        val fake = Fake().apply {
            catalogPages[1] = listOf(CatalogEpisode("101", 1, 1, "One"), CatalogEpisode("102", 1, 2, "Two"))
            resourceFlow = channelFlow {
                send(Outcome.Ok("a", listOf(ResourceBatch("a", listOf(ServerItem("a", episode("a1", 1, 1))), true)), 1))
                first.await()
                send(Outcome.Ok("b", listOf(ResourceBatch("b", listOf(ServerItem("b", episode("b2", 1, 2))), true)), 1))
            }
        }
        val h = Harness(this, fake, canonical = work)
        h.targets.value = listOf("a", "b")
        advanceUntilIdle()
        var data = h.pipeline.state.value
        assertEquals(listOf("s1:e1", "s1:e2"), data.episodes.map { it.key })
        assertEquals(listOf("a"), data.episodes[0].resources.map { it.server })
        assertTrue(data.episodes[1].resources.isEmpty())
        assertEquals(1, data.pending)
        first.complete(Unit)
        advanceUntilIdle()
        data = h.pipeline.state.value
        assertEquals(listOf("b"), data.episodes[1].resources.map { it.server })
        assertEquals(0, data.pending)
        assertFalse(data.loading)
        h.close()
    }

    @Test fun `a server that did not answer the resource search counts as failed and is asked again later`() = runTest {
        val work = CatalogWork(CatalogId("tmdb", "tv", "10"), "Test", ids = mapOf("Tmdb" to "10"), seasons = listOf(CatalogSeason(1, "第 1 季", 1)))
        var calls = 0
        val fake = Fake().apply {
            catalogPages[1] = listOf(CatalogEpisode("101", 1, 1, "One"))
        }
        fake.resourceFlow = channelFlow {
            calls++
            if (calls == 1) send(Outcome.Skipped("a"))
            else send(Outcome.Ok("a", listOf(ResourceBatch("a", listOf(ServerItem("a", episode("a1", 1, 1))), true)), 1))
        }
        val h = Harness(this, fake, canonical = work)
        runCurrent(); advanceTimeBy(10); runCurrent()
        assertEquals(listOf("a"), h.pipeline.state.value.failed)
        advanceUntilIdle()
        assertEquals(2, calls)
        assertTrue(h.pipeline.state.value.failed.isEmpty())
        assertEquals(listOf("a"), h.pipeline.state.value.episodes.single().resources.map { it.server })
        h.close()
    }
}

class CatalogDirectoryTest {
    private val tmdb = app.zmby.catalog.CatalogWork(app.zmby.catalog.CatalogId("tmdb", "tv", "10"), "Test")
    private val bangumi = app.zmby.catalog.CatalogWork(app.zmby.catalog.CatalogId("bangumi", "tv", "20"), "Test")

    @org.junit.Test fun `a cached TMDB work without a token lists the server's own episodes, Bangumi needs no token`() {
        org.junit.Assert.assertNull(catalogDirectory(tmdb, tmdbConfigured = false))
        org.junit.Assert.assertEquals(tmdb, catalogDirectory(tmdb, tmdbConfigured = true))
        org.junit.Assert.assertEquals(bangumi, catalogDirectory(bangumi, tmdbConfigured = false))
        org.junit.Assert.assertNull(catalogDirectory(null, tmdbConfigured = true))
    }
}

class CatalogReachabilityTest {
    @org.junit.Test fun `unreachable for two minutes after a failure, reachable again after an answer`() {
        var now = 1_000L
        val r = app.zmby.catalog.CatalogReachability { now }
        org.junit.Assert.assertTrue(r.reachable())
        r.failed()
        org.junit.Assert.assertFalse(r.reachable())
        now += app.zmby.catalog.CatalogReachability.PAUSE_MS
        org.junit.Assert.assertTrue(r.reachable())
        r.failed(); r.answered()
        org.junit.Assert.assertTrue(r.reachable())
    }
}
