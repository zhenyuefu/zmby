package app.zmby.danmu

import app.zmby.catalog.*
import app.zmby.emby.BaseItemDto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DanmuQueryResolverTest {
    private val ref = CatalogId("tmdb", "tv", "10")
    private val work = CatalogWork(ref, "菜鸟炊事兵", "취사병", year = 2026, aliases = listOf("菜鸟伙房兵", "另一译名"))
    private val parent = BaseItemDto("series", type = "Series", name = "菜鸟伙房兵", originalTitle = "취사병", productionYear = 2026)
    private val episode = BaseItemDto("ep", type = "Episode", name = "第3集", originalTitle = "Episode name", seriesId = "series",
        seriesName = "菜鸟伙房兵", parentIndexNumber = 5, indexNumber = 3, productionYear = 2030,
        providerIds = mapOf("Tmdb" to "999"))

    @Test fun `next episode without series name resolves parent before deciding there is no query`() = runBlocking {
        val resolver = DanmuQueryResolver({ _,_ -> parent }, { work }, { ref }, { emptyList() })
        val query = resolver.query("s", episode.copy(id = "next", seriesName = null, indexNumber = 4))!!
        assertEquals(4, query.episode)
        assertTrue(parent.name in query.names)
        assertEquals("tmdb:tv:10", query.workId)
    }

    @Test fun `parent names and first year are used and episode IDs never resolve a TV work`() = runBlocking {
        var resolved: BaseItemDto? = null
        val resolver = DanmuQueryResolver({ server,id -> assertEquals("s", server); assertEquals("series", id); parent },
            { work }, { resolved = it; ref }, { emptyList() })
        val query = resolver.query("s", episode)!!
        assertEquals(parent, resolved)
        assertEquals(2026, query.year)
        assertTrue(query.names.containsAll(listOf("菜鸟伙房兵", "菜鸟炊事兵", "취사병", "另一译名")))
        assertFalse("Episode name" in query.names)
        assertEquals("tmdb:tv:10", query.workId)
        assertEquals(5, query.season)
        assertEquals(3, query.episode)
    }

    @Test fun `known catalogue episode mapping supplies numbering without changing the playback item`() = runBlocking {
        val resolver = DanmuQueryResolver({ _,_ -> fail("catalogue hint must not refetch the parent"); null },
            { fail("catalogue hint must not reread the cache"); null }, { fail("known identity"); null }, { emptyList() })
        resolver.remember("s", episode.id, work, CatalogEpisode("canonical", 1, 58, "episode"), episode.seriesId)
        val query = resolver.query("s", episode)!!
        assertEquals(1, query.season)
        assertEquals(58, query.episode)
        assertEquals(5, episode.parentIndexNumber)
        assertEquals(3, episode.indexNumber)
        assertEquals("ep", episode.id)
    }

    @Test fun `a missing parent falls back without confusing episode year with series year`() = runBlocking {
        val resolver = DanmuQueryResolver({ _,_ -> null }, { null }, { fail("must not resolve an episode"); null }, { emptyList() })
        val query = resolver.query("s", episode)!!
        assertNull(query.year)
        assertEquals(listOf("菜鸟伙房兵"), query.names)
        assertEquals("server:s:series:series", query.workId)
    }

    @Test fun `alternate names are enriched by the same external identity only when requested`() = runBlocking {
        var lookups = 0
        val resolver = DanmuQueryResolver({ _,_ -> parent }, { work }, { ref }, { id -> assertEquals(ref, id); lookups++; listOf("平台译名") })
        val basic = resolver.query("s", episode)!!
        assertEquals(0, lookups)
        val enriched = resolver.enrich(basic)
        assertEquals(1, lookups)
        assertTrue("平台译名" in enriched.names)
        assertEquals(basic.workId, enriched.workId)
    }
}
