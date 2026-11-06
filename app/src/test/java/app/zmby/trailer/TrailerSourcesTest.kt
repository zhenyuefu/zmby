package app.zmby.trailer

import app.zmby.aggregate.ResultStore
import app.zmby.data.ServerItem
import app.zmby.emby.BaseItemDto
import app.zmby.ratings.RottenTomatoesApi
import app.zmby.ratings.RottenTomatoesRepository
import app.zmby.ratings.RottenTomatoesTrailers
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fallback trailer sources (trailer design): order, Rotten Tomatoes HLS and server trailers. Fixtures are hand-written. */
class TrailerSourcesTest {
    private val json = Json { ignoreUnknownKeys = true }

    private class MemoryStore(val now: () -> Long) : ResultStore {
        val rows = ConcurrentHashMap<List<String>, Pair<String, Long>>()
        override suspend fun read(kind: String, key: String, server: String, maxAgeMs: Long) =
            rows[listOf(kind, key, server)]?.takeIf { now() - it.second in 0 until maxAgeMs }?.first
        override suspend fun write(kind: String, key: String, server: String, json: String) { rows[listOf(kind, key, server)] = json to now() }
        override suspend fun drop(kind: String, key: String, servers: List<String>) = Unit
        override suspend fun dropKinds(kinds: List<String>, servers: List<String>) = Unit
    }

    private var now = 1_790_000_000_000L
    private val store = MemoryStore { now }

    private fun info(source: String) = TrailerInfo("v-$source", "https://video.example.com/$source", 0, null, now + 1, source = source)

    // ---- order

    @Test
    fun imdbFirstThenRottenTomatoesThenTheServers() = runBlocking {
        val asked = mutableListOf<String>()
        val copy = ServerItem("nas", BaseItemDto(id = "m1", type = "Movie", localTrailerCount = 1))
        suspend fun run(imdb: TrailerInfo?, rt: TrailerInfo?, server: TrailerInfo?, query: TrailerQuery = TrailerQuery("tt1", listOf(copy))): TrailerInfo? {
            asked.clear()
            return firstTrailer(query, { asked += "imdb"; imdb }, { asked += "rt"; rt }, { copy, unknown -> asked += "server:${copy.server}${if (unknown) "?" else ""}"; server })
        }
        assertEquals("imdb", run(info("imdb"), info("rt"), info("nas"))!!.source)
        assertEquals(listOf("imdb"), asked)
        assertEquals("rt", run(null, info("rt"), info("nas"))!!.source)
        assertEquals(listOf("imdb", "rt"), asked)
        assertEquals("nas", run(null, null, info("nas"))!!.source)
        assertEquals(listOf("imdb", "rt", "server:nas?"), asked)
        // No IMDb ID: only the servers.
        assertEquals("nas", run(info("imdb"), info("rt"), info("nas"), TrailerQuery(null, listOf(copy)))!!.source)
        assertEquals(listOf("server:nas?"), asked)
        // At most three copies are asked.
        val many = (1..5).map { ServerItem("s$it", copy.item) }
        assertNull(run(null, null, null, TrailerQuery(null, many)))
        // Only the first may be asked without a known trailer count.
        assertEquals(listOf("server:s1?", "server:s2", "server:s3"), asked)
    }

    // ---- Rotten Tomatoes

    private fun videosPage(vararg entries: String) = """<html><script id="videos" type="application/json">[${entries.joinToString(",")}]</script></html>"""
    private fun video(id: String, type: String, redBand: Boolean = false, host: String = "https://link.theplatform.com/s/X/media") =
        """{"file":"$host/$id?formats=MPEG-DASH+widevine,M3U+none,MPEG4","publicId":"$id","title":"$type $id","videoType":"$type","isRedBand":$redBand,"durationInSeconds":"160.4"}"""
    private val smil = """<smil><body><seq><ref src="http://ads.example.com/" tags="preroll"></ref><par><switch>
        <video src="https://cdn.example.com/out/v1/abc/index.m3u8" system-bitrate="5121000" height="1080" width="1920"/></switch></par></seq></body></smil>"""

    @Test
    fun theFirstTrailerThatIsNotRedBand() {
        val page = videosPage(video("c1", "CLIP"), video("r1", "TRAILER", redBand = true), video("t3", "TRAILER"), video("t2", "TRAILER"))
        val v = RottenTomatoesTrailers.first(json, page)!!
        assertEquals("t3", v.id)
        assertEquals("https://link.theplatform.com/s/X/media/t3", v.file)
        assertEquals(160, v.durationSec)
        assertNull(RottenTomatoesTrailers.first(json, videosPage(video("c1", "FEATURETTE"))))
        // Only thePlatform links, unless the mock server is in use.
        val foreign = videosPage(video("t1", "TRAILER", host = "https://evil.example.com/media"))
        assertNull(RottenTomatoesTrailers.first(json, foreign))
        assertEquals("t1", RottenTomatoesTrailers.first(json, foreign, anyHost = true)!!.id)
        assertEquals("https://cdn.example.com/out/v1/abc/index.m3u8", RottenTomatoesTrailers.hls(smil))
        assertNull(RottenTomatoesTrailers.hls(smil.replace("https://cdn", "http://cdn")))
    }

    private val wikidata = AtomicInteger()
    private val videoPages = AtomicInteger()
    private val smils = AtomicInteger()
    private var videosAnswer: () -> String = { videosPage(video("t3", "TRAILER")) }

    private fun rt() = RottenTomatoesRepository(
        path = { wikidata.incrementAndGet(); """{"results":{"bindings":[{"rt":{"value":"m/dune_part_two"}}]}}""" },
        page = { throw IOException("not used") },
        store = store, clock = { now },
        videos = { videoPages.incrementAndGet(); videosAnswer() },
        smil = { smils.incrementAndGet(); smil },
    )

    @Test
    fun rottenTomatoesTrailerIsHlsAndStored() = runBlocking {
        val t = rt().trailer("tt15239678")!!
        assertEquals(TrailerFormat.Hls, t.format)
        assertEquals("rottentomatoes", t.source)
        assertEquals("https://cdn.example.com/out/v1/abc/index.m3u8", t.url)
        assertTrue(t.cached)
        // Another process: from the store, nothing asked.
        assertEquals(t, rt().trailer("tt15239678"))
        assertEquals(1, wikidata.get())
        assertEquals(1, videoPages.get())
        assertEquals(1, smils.get())
    }

    @Test
    fun noRottenTomatoesTrailerIsRememberedAndFailuresAreNot() = runBlocking {
        videosAnswer = { throw IOException("HTTP 503") }
        val r = rt()
        assertNull(r.trailer("tt15239678"))
        assertTrue(store.rows.keys.none { it[0] == RottenTomatoesRepository.KIND_TRAILER })
        videosAnswer = { throw RottenTomatoesApi.NotFound() }
        now += RottenTomatoesRepository.RETRY_MS
        assertNull(r.trailer("tt15239678"))
        assertNull(rt().trailer("tt15239678"))
        assertEquals(2, videoPages.get())
        assertEquals(0, smils.get())
    }

    // ---- server trailers

    private val lists = AtomicInteger()
    private var listAnswer: () -> List<BaseItemDto> = { listOf(BaseItemDto(id = "tr1", type = "Trailer", runTimeTicks = 1_500_000_000L)) }

    private fun servers() = ServerTrailers(
        list = { _, _ -> lists.incrementAndGet(); listAnswer() },
        stream = { server, id -> "http://$server.example/Videos/$id/stream?api_key=secret" to "UA/$server" },
        login = { "u1@http://$it.example" },
        store = store, clock = { now },
    )

    @Test
    fun aTitleWithoutStoredTrailersCostsNoRequest() = runBlocking {
        assertNull(servers().trailer(ServerItem("nas", BaseItemDto(id = "m1", type = "Movie"))))
        assertNull(servers().trailer(ServerItem("nas", BaseItemDto(id = "m1", type = "Movie", localTrailerCount = 0)), askUnknown = true))
        assertEquals(0, lists.get())
        // An item that does not say is asked only when allowed, and the answer is stored.
        listAnswer = { emptyList() }
        val unknown = ServerItem("nas", BaseItemDto(id = "m2", type = "Movie"))
        assertNull(servers().trailer(unknown, askUnknown = true))
        assertNull(servers().trailer(unknown, askUnknown = true))
        assertEquals(1, lists.get())
    }

    @Test
    fun theServerTrailerIsFoundOnceAndItsUrlBuiltEachTime() = runBlocking {
        val copy = ServerItem("nas", BaseItemDto(id = "m1", type = "Movie", localTrailerCount = 2))
        val t = servers().trailer(copy)!!
        assertEquals(TrailerFormat.Server, t.format)
        assertEquals("tr1", t.videoId)
        assertEquals("nas", t.source)
        assertEquals("UA/nas", t.userAgent)
        assertEquals(150, t.runtimeSec)
        assertFalse(t.cached)
        assertEquals(t.url, servers().trailer(copy)!!.url)
        assertEquals(1, lists.get())
        // The stored row names the trailer, never its URL (the token).
        assertTrue(store.rows.values.none { it.first.contains("api_key") || it.first.contains("secret") })
    }

    @Test
    fun serverFailuresAreNotStored() = runBlocking {
        listAnswer = { throw IOException("timeout") }
        val s = servers()
        val copy = ServerItem("nas", BaseItemDto(id = "m1", type = "Movie", localTrailerCount = 1))
        assertNull(s.trailer(copy))
        assertNull(s.trailer(copy))
        assertEquals(1, lists.get())
        assertTrue(store.rows.isEmpty())
    }
}
