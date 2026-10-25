package app.zmby.emby

import app.zmby.data.MemoryRegistryStore
import app.zmby.data.Override
import app.zmby.data.ServerCaps
import app.zmby.data.ServerConfig
import app.zmby.data.ServerLine
import app.zmby.data.ServerRegistry
import app.zmby.playback.PlaybackPreparer
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/** Falling back to the official Emby identity for servers that check the client (UA fallback). */
class ClientIdentityTest {
    private val server = MockWebServer()
    private val clock = AtomicLong(1_000_000)
    private val dalvik = "Dalvik/2.1.0 (Linux; U; Android 11; Z9X 8K Build/RP1A)"
    private val info = ClientInfo(device = "Z9X 8K", deviceId = "dev-1", version = "0.1.0", officialAgent = dalvik)
    private val client = EmbyClient(OkHttpClient(), info, clock = clock::get)
    private val seen = mutableListOf<RecordedRequest>()
    private val changes = mutableListOf<Triple<String, String, UaMode>>()

    /** How the fake server treats a request that is not the official client. */
    private var refuse: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(403).setBody("403 Forbidden") }
    private var wrongPassword = false

    private fun official(r: RecordedRequest) =
        r.getHeader("User-Agent").orEmpty().startsWith("Dalvik/") && r.getHeader("X-Emby-Authorization").orEmpty().contains("Client=\"AndroidTv\"")

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(seen) { seen += request }
                if (!official(request)) return refuse(request)
                val path = request.path!!
                return when {
                    path.endsWith("AuthenticateByName") && wrongPassword -> MockResponse().setResponseCode(401)
                    path.endsWith("AuthenticateByName") -> MockResponse().setBody("""{"User":{"Id":"u1"},"AccessToken":"tok","ServerId":"s1"}""")
                    path.endsWith("System/Info/Public") -> MockResponse().setBody("""{"Id":"s1","ServerName":"严格服","Version":"4.8.0"}""")
                    else -> MockResponse().setBody("""{"Items":[],"TotalRecordCount":0}""")
                }
            }
        }
        server.start()
        client.onIdentityChange = { base, id, mode -> changes += Triple(base, id, mode) }
    }

    @After
    fun tearDown() = server.shutdown()

    private val base get() = server.url("/emby").toString().trimEnd('/')

    @Test
    fun loginTurnedAwayAsZmbyRetriesAsTheOfficialClient() = runTest {
        val s = client.authenticate(base, "me", "pw")
        assertEquals("tok", s.accessToken)
        assertEquals("严格服", s.serverName)
        assertEquals(UaMode.Official, s.ua.detected)
        assertEquals(UaMode.Official, s.ua.effective)

        val (first, second) = seen.take(2)
        assertEquals("zmby/0.1.0", first.getHeader("User-Agent"))
        assertTrue(first.getHeader("X-Emby-Authorization")!!.contains("Client=\"zmby\""))
        assertEquals(dalvik, second.getHeader("User-Agent"))
        val auth = second.getHeader("X-Emby-Authorization")!!
        assertTrue(auth, auth.contains("Client=\"AndroidTv\"") && auth.contains("Version=\"2.1.54g\"") && auth.contains("DeviceId=\"dev-1\""))
        // System/Info/Public after the login goes out as the official client straight away.
        assertTrue(official(seen[2]))
        assertEquals(3, seen.size)
    }

    @Test
    fun aBlockPageInsteadOfJsonCountsAsTurnedAway() = runTest {
        refuse = { MockResponse().setHeader("Content-Type", "text/html").setBody("<html><body>Access denied</body></html>") }
        assertEquals(UaMode.Official, client.authenticate(base, "me", "pw").ua.detected)
    }

    @Test
    fun aDroppedConnectionCountsAsTurnedAway() = runTest {
        refuse = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START) }
        assertEquals(UaMode.Official, client.authenticate(base, "me", "pw").ua.detected)
    }

    @Test
    fun aServerThatTakesZmbyStaysOnZmby() = runTest {
        refuse = { r ->
            if (r.path!!.endsWith("AuthenticateByName")) MockResponse().setBody("""{"User":{"Id":"u1"},"AccessToken":"tok"}""")
            else MockResponse().setBody("""{"Id":"s1","ServerName":"家里 NAS"}""")
        }
        val s = client.authenticate(base, "me", "pw")
        assertEquals(UaMode.Zmby, s.ua.detected)
        assertEquals(2, seen.size)
        assertTrue(seen.all { it.getHeader("User-Agent") == "zmby/0.1.0" })
        assertTrue(changes.isEmpty())
    }

    @Test
    fun aWrongPasswordFailsWithTheFirstErrorAndIsNotRetriedForAWhile() = runTest {
        wrongPassword = true
        refuse = { MockResponse().setResponseCode(401) }
        repeat(2) {
            try {
                client.authenticate(base, "me", "wrong")
                fail()
            } catch (e: EmbyException) {
                assertEquals(401, e.code)
            }
        }
        // Twice as zmby, the official identity only on the first try.
        assertEquals(listOf("zmby/0.1.0", dalvik, "zmby/0.1.0"), seen.map { it.getHeader("User-Agent") })
        clock.addAndGet(EmbyClient.FALLBACK_PAUSE_MS + 1)
        runCatching { client.authenticate(base, "me", "wrong") }
        assertEquals(5, seen.size)
    }

    @Test
    fun aFixedChoiceNeverFallsBack() = runTest {
        try {
            client.authenticate(base, "me", "pw", UaSetting(mode = UaMode.Zmby))
            fail()
        } catch (e: EmbyException) {
            assertEquals(403, e.code)
        }
        assertEquals(1, seen.size)
    }

    @Test
    fun aRegisteredServerThatStartsCheckingIsSwitchedAndReported() = runTest {
        val s = ServerSession(base, "u1", "tok", id = "srv-1")
        client.views(s)
        assertEquals(listOf(Triple(base, "srv-1", UaMode.Official)), changes)
        assertEquals("tok", seen.last().getHeader("X-Emby-Token"))
    }

    @Test
    fun aServerKnownToNeedTheOfficialClientGetsItFirst() = runTest {
        val s = ServerSession(base, "u1", "tok", id = "srv-1", ua = UaSetting(detected = UaMode.Official))
        client.views(s)
        assertEquals(1, seen.size)
        assertTrue(official(seen.single()))
        assertTrue(changes.isEmpty())
    }

    @Test
    fun pingFindsTheIdentityToo() = runTest {
        val (ms, version) = client.ping(base, UaSetting(), serverId = "srv-1")
        assertTrue(ms != null)
        assertEquals("4.8.0", version)
        assertEquals(UaMode.Official, changes.single().third)
    }

    @Test
    fun customAgentKeepsTheOfficialClientName() = runTest {
        val custom = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/91"
        refuse = { r ->
            if (r.getHeader("User-Agent") == custom) MockResponse().setBody("""{"Items":[],"TotalRecordCount":0}""") else MockResponse().setResponseCode(403)
        }
        client.views(ServerSession(base, "u1", "tok", ua = UaSetting(mode = UaMode.Custom, custom = custom)))
        assertEquals(1, seen.size)
        assertTrue(seen.single().getHeader("X-Emby-Authorization")!!.contains("Client=\"AndroidTv\""))
    }

    @Test
    fun effectiveIdentityAndRelay() {
        assertEquals(UaMode.Zmby, UaSetting().effective)
        assertFalse(UaSetting().needsRelay)
        assertEquals(UaMode.Official, UaSetting(detected = UaMode.Official).effective)
        assertTrue(UaSetting(detected = UaMode.Official).needsRelay)
        assertEquals(UaMode.Zmby, UaSetting(mode = UaMode.Custom, custom = " ").effective)
        assertEquals(UaMode.Zmby, UaSetting(mode = UaMode.Zmby, detected = UaMode.Official).effective)
        assertFalse(UaSetting(mode = UaMode.Official).canFallBack)

        val official = UaSetting(detected = UaMode.Official)
        assertTrue(PlaybackPreparer.usesRelay(ServerCaps(), official))
        assertFalse(PlaybackPreparer.usesRelay(ServerCaps(relay = Override.Off), official))
        assertFalse(PlaybackPreparer.usesRelay(ServerCaps(redirects = true), UaSetting()))
        assertTrue(PlaybackPreparer.usesRelay(ServerCaps(relay = Override.On), UaSetting()))
    }

    @Test
    fun theOfficialAgentIsDalvik() {
        assertEquals("Dalvik/2.1.0 (Linux; U; Android 11; Z9X 8K Build/RP1A.200720.011)", OfficialEmby.dalvikAgent("11", "Z9X 8K", "RP1A.200720.011"))
    }

    @Test
    fun interceptorFillsTheServersAgentOnly() {
        val registry = ServerRegistry(MemoryRegistryStore())
        val line = ServerLine("l1", "主线路", base)
        registry.add(ServerConfig("srv-1", "严格服", listOf(line), "l1", "u1", "tok", ua = UaSetting(detected = UaMode.Official)))
        val http = OkHttpClient.Builder()
            .addInterceptor(UserAgentInterceptor(info.userAgent) { url -> registry.ownerOf(url)?.let { info.identity(it.config.ua).userAgent } })
            .build()
        http.newCall(Request.Builder().url("$base/Items/1/Images/Logo").build()).execute().close()
        http.newCall(Request.Builder().url(server.url("/elsewhere")).build()).execute().close()
        http.newCall(Request.Builder().url("$base/Items/2/Images/Logo").header("User-Agent", "mine").build()).execute().close()
        assertEquals(listOf(dalvik, "zmby/0.1.0", "mine"), seen.map { it.getHeader("User-Agent") })
    }

    @Test
    fun registryKeepsWhatAutoFoundButNotOverAFixedChoice() {
        val registry = ServerRegistry(MemoryRegistryStore())
        registry.add(ServerConfig("a", "A", listOf(ServerLine("l", "主线路", "http://a:8096")), "l", "u", "t"))
        registry.add(ServerConfig("b", "B", listOf(ServerLine("l", "主线路", "http://b:8096/emby")), "l", "u", "t", ua = UaSetting(mode = UaMode.Zmby)))
        registry.recordIdentity("a", UaMode.Official)
        registry.recordIdentity("b", UaMode.Official)
        assertEquals(UaMode.Official, registry.get("a")!!.session.ua.effective)
        assertEquals(UaMode.Zmby, registry.get("b")!!.session.ua.effective)
        assertEquals("b", registry.ownerOf("http://b:8096/emby/Items/1")?.id)
        assertNull(registry.ownerOf("http://b:8096/embyx/Items/1"))
        assertNull(registry.ownerOf("http://a:80961/x"))
    }
}
