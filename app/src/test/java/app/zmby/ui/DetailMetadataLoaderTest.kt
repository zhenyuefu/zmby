package app.zmby.ui

import app.zmby.catalog.*
import app.zmby.emby.BaseItemDto
import app.zmby.ui.screens.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DetailMetadataLoaderTest {
    @Test fun `resource detail never treats an episode ID or missing TMDB value as a movie ID`() {
        for (id in listOf("None", "null", "unknown", "0", "-1", "12345")) {
            val episode = BaseItemDto("file", type = "Episode", providerIds = mapOf("Tmdb" to id))
            assertNull(detailRecommendationId(episode))
            assertNull(detailRecommendationId(episode, CatalogId("tmdb", "tv", "10")))
            if (id != "12345") assertNull(detailRecommendationId(episode.copy(type = "Movie")))
        }
        assertEquals(CatalogId("tmdb", "movie", "10"), detailRecommendationId(BaseItemDto("movie", type = "Movie", providerIds = mapOf("Tmdb" to "10"))))
    }
    private val item = BaseItemDto("server-item",type="Series",name="Server name",providerIds=mapOf("Tmdb" to "10"))
    private val ref = CatalogId("tmdb","tv","10")
    private val work = CatalogWork(ref,"Provider title")
    @Test fun `list identity starts provider and resource lookup without full server metadata or flash`() = runTest {
        var full=0; var identity=0; var fetching=false
        val events=mutableListOf<DetailMetadata>()
        val loader=DetailMetadataLoader({item},{identity++;item},{full++;item},{ref},{null},{ fetching=true; delay(100);work })
        val task=launch { loader.load(null,true) { events+=it } }
        runCurrent()
        assertTrue(fetching);assertEquals(ref,events.first().identity?.ref)
        advanceUntilIdle();task.join()
        assertEquals(0,full);assertEquals(0,identity);assertFalse(events.any { it.fallbackReady });assertEquals(work,events.last().work)
    }
    @Test fun `slow provider may show list fallback but never requests full server descriptions on success`() = runTest {
        var full=0
        val events=mutableListOf<DetailMetadata>()
        val loader=DetailMetadataLoader({item},{item},{full++;item},{ref},{null},{ delay(1000);work })
        loader.load(null,true) { events+=it }
        assertTrue(events.any { it.fallbackReady });assertEquals(work,events.last().work);assertEquals(0,full)
    }
    @Test fun `missing IDs use lightweight identity before full metadata and failed providers fall back`() = runTest {
        val calls=mutableListOf<String>()
        val loader=DetailMetadataLoader({item.copy(providerIds=emptyMap())},{calls+="identity";item},{calls+="full";item},{calls+="resolve";ref},{null},{calls+="provider";error("offline")})
        val events=mutableListOf<DetailMetadata>();loader.load(null,true) { events+=it }
        assertEquals(listOf("identity","resolve","provider","full"),calls);assertTrue(events.last().fallbackReady)
    }
    @Test fun `cached metadata is retained when refresh fails and cancellation never starts server fallback`() = runTest {
        var full=0
        val loader=DetailMetadataLoader({item},{item},{full++;item},{ref},{work},{error("offline")})
        val events=mutableListOf<DetailMetadata>();loader.load(null,true) { events+=it }
        assertEquals(work,events.last().work);assertEquals(0,full)
        val cancelled=DetailMetadataLoader({item},{item},{full++;item},{ref},{null},{awaitCancellation()})
        val job=launch { cancelled.load(null,true) {} };runCurrent();job.cancelAndJoin();assertEquals(0,full)
    }
    @Test fun `failed identity and provider still retain newly fetched server fallback`() = runTest {
        val events=mutableListOf<DetailMetadata>()
        val loader=DetailMetadataLoader({null},{error("unsupported identity endpoint")},{item},{ref},{null},{error("offline")})
        loader.load(null,true) { events+=it }
        assertTrue(events.last().fallbackReady)
    }
    @Test fun `episode focus coordinates survive server and provider item ID differences`() {
        assertEquals(episodeSlot(1,7,"emby-77"),episodeSlot(1,7,"tmdb-999"))
        assertNotEquals(episodeSlot(1,7,"same"),episodeSlot(2,7,"same"))
        assertNotEquals(episodeSlot(null,null,"a"),episodeSlot(null,null,"b"))
    }
}
