package app.zmby.emby

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EmbyClientTest {
    @Test fun pingRetriesAnInitiallyUnavailableServer() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("""{"Version":"4.9"}"""))
        val (latency, version) = client.ping(session.baseUrl)
        assertTrue(latency != null)
        assertEquals("4.9", version)
        assertEquals(2, server.requestCount)
    }
    private val server = MockWebServer()
    private val client = EmbyClient(OkHttpClient(), ClientInfo(device = "Z9X", deviceId = "dev-1", version = "0.1.0"))
    private lateinit var session: ServerSession

    @Before
    fun setUp() {
        server.start()
        session = ServerSession(server.url("/emby").toString().trimEnd('/'), userId = "u1", accessToken = "tok")
    }

    @After
    fun tearDown() = server.shutdown()

    @Test fun listIDsAndResourceFactsDoNotNeedFullMetadata() = runTest {
        server.enqueue(MockResponse().setBody("""{"Items":[{"Id":"i1","Type":"Movie","ProviderIds":{"Tmdb":"10"}}],"TotalRecordCount":1}"""))
        assertEquals("10",client.search(session,"Mock").items.single().providerIds["Tmdb"])
        assertTrue(server.takeRequest().requestUrl!!.queryParameter("Fields")!!.contains("ProviderIds"))
        server.enqueue(MockResponse().setBody("""{"Id":"i1","Type":"Movie","ProviderIds":{"Tmdb":"10"}}"""))
        client.resourceItem(session,"i1")
        val fields=server.takeRequest().requestUrl!!.queryParameter("Fields")!!.split(',')
        assertTrue("MediaSources" in fields && "ProviderIds" in fields)
        assertTrue("People" !in fields && "Overview" !in fields && "Genres" !in fields)
    }

    @Test
    fun authenticateSendsClientIdentityAndReadsToken() = runTest {
        server.enqueue(MockResponse().setBody("""{"User":{"Id":"u1","Name":"me"},"AccessToken":"tok","ServerId":"s1"}"""))
        server.enqueue(MockResponse().setBody("""{"Id":"s1","ServerName":"家里 NAS"}"""))
        val s = client.authenticate(server.url("/emby/").toString(), "me", "pw")
        assertEquals("tok", s.accessToken)
        assertEquals("家里 NAS", s.serverName)

        val auth = server.takeRequest()
        assertEquals("/emby/Users/AuthenticateByName", auth.path)
        val header = auth.getHeader("X-Emby-Authorization")!!
        assertTrue(header, header.contains("DeviceId=\"dev-1\"") && header.contains("Client=\"zmby\""))
        assertTrue(auth.body.readUtf8().contains("\"Pw\":\"pw\""))
    }

    @Test
    fun progressAlwaysCarriesPositionTicks() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        client.reportProgress(session, PlaybackReport("i1", "ms1", "ps1", positionTicks = 0))
        val req = server.takeRequest()
        assertEquals("/emby/Sessions/Playing/Progress", req.path)
        assertEquals("tok", req.getHeader("X-Emby-Token"))
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals(0L, body["PositionTicks"]!!.jsonPrimitive.long)
        assertEquals("DirectPlay", body["PlayMethod"]!!.jsonPrimitive.content)
    }

    @Test
    fun playbackInfoAsksForTheChosenVersion() = runTest {
        server.enqueue(MockResponse().setBody("""{"MediaSources":[{"Id":"ms2","Container":"mkv"}],"PlaySessionId":"ps"}"""))
        val info = client.playbackInfo(session, "i1", startPositionMs = 60_000, mediaSourceId = "ms2")
        assertEquals("ps", info.playSessionId)
        val req = server.takeRequest()
        val url = req.requestUrl!!
        assertEquals("ms2", url.queryParameter("MediaSourceId"))
        assertEquals("600000000", url.queryParameter("StartTimeTicks"))
        assertTrue(req.body.readUtf8().contains("DirectPlayProfiles"))
    }

    @Test
    fun streamUrlCarriesTheTokenInTheQuery() {
        val url = client.streamUrl(session, "i1", MediaSourceInfo(id = "ms1", container = "mkv"), "ps1")
        assertTrue(url, url.contains("/emby/Videos/i1/stream.mkv?Static=true&MediaSourceId=ms1&PlaySessionId=ps1&DeviceId=dev-1&api_key=tok"))
    }

    @Test
    fun normalizesServerAddresses() {
        assertEquals("http://nas:8096", EmbyClient.normalizeBaseUrl(" nas:8096/ "))
        assertEquals("https://a.b/emby", EmbyClient.normalizeBaseUrl("https://a.b/emby/"))
    }

    @Test
    fun fullDetailsExplicitlyRequestBiographyAndCast() = runTest {
        server.enqueue(MockResponse().setBody("""{"Id":"p1","Type":"Person","Overview":"Biography"}"""))
        assertEquals("Biography", client.item(session, "p1").overview)
        val fields = server.takeRequest().requestUrl!!.queryParameter("Fields")!!.split(",")
        assertTrue(fields.containsAll(listOf("ProviderIds", "Overview", "People", "ProductionLocations")))
    }

    @Test
    fun episodesPageAndStartAtAnEpisode() = runTest {
        server.enqueue(MockResponse().setBody("""{"Items":[{"Id":"e7"},{"Id":"e8"}],"TotalRecordCount":12}"""))
        val page = client.episodes(session, "s1", startItemId = "e7", limit = 2)
        assertEquals(listOf("e7", "e8"), page.items.map { it.id })
        val url = server.takeRequest().requestUrl!!
        assertEquals("/emby/Shows/s1/Episodes", url.encodedPath)
        assertEquals("e7", url.queryParameter("StartItemId"))
        assertEquals("2", url.queryParameter("Limit"))
        assertEquals("u1", url.queryParameter("UserId"))
    }

    @Test
    fun browseCanBeNarrowedToOneLibraryAndPaged() = runTest {
        server.enqueue(MockResponse().setBody("""{"Items":[],"TotalRecordCount":130}"""))
        client.browse(session, "Movie", start = 60, limit = 60, parentId = "lib-docs")
        val url = server.takeRequest().requestUrl!!
        assertEquals("lib-docs", url.queryParameter("ParentId"))
        assertEquals("60", url.queryParameter("StartIndex"))
    }

    @Test
    fun latestAcrossLibrariesIsOneBoundedVideoRequest() = runTest {
        server.enqueue(MockResponse().setBody("[]"))
        client.latest(session, null, 24)
        val url = server.takeRequest().requestUrl!!
        assertEquals("/emby/Users/u1/Items/Latest", url.encodedPath)
        assertEquals(null, url.queryParameter("ParentId"))
        assertEquals("24", url.queryParameter("Limit"))
        assertEquals("Movie,Series,Episode,Video", url.queryParameter("IncludeItemTypes"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun latestEnforcesTheRequestedLimitWhenAServerIgnoresIt() = runTest {
        server.enqueue(MockResponse().setBody((1..100).joinToString(prefix = "[", postfix = "]") { """{"Id":"$it","Type":"Movie"}""" }))
        assertEquals(24, client.latest(session, null, 24).size)
    }
}
