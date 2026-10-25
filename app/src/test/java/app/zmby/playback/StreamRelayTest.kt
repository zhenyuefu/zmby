package app.zmby.playback

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class StreamRelayTest {
    private val data = ByteArray(200_000) { (it * 31 % 251).toByte() }
    private val emby = MockWebServer()
    private val cdn = MockWebServer()
    private val clock = AtomicLong(1_000_000)
    private val resolves = AtomicInteger()

    /** The signature version the CDN currently accepts; older links get 403. */
    @Volatile private var version = 1
    @Volatile private var maxAge: Int? = 60
    @Volatile private var redirect = true
    @Volatile private var truncateNext = false
    private val uas = mutableListOf<String?>()

    private lateinit var relay: StreamRelay
    private val player = OkHttpClient.Builder().retryOnConnectionFailure(false).build()

    private fun slice(request: RecordedRequest): MockResponse {
        val range = StreamRelay.parseRange(request.getHeader("Range"))
        val from = range?.first ?: 0
        val to = range?.second ?: (data.size - 1L)
        val body = Buffer().write(data, from.toInt(), (to - from + 1).toInt())
        return MockResponse()
            .setResponseCode(if (range != null) 206 else 200)
            .setHeader("Accept-Ranges", "bytes")
            .setHeader("Content-Type", "video/x-matroska")
            .apply { if (range != null) setHeader("Content-Range", "bytes $from-$to/${data.size}") }
            .setBody(body)
    }

    @Before
    fun setUp() {
        app.zmby.log.ZLog.sink = { l, t, m -> println("$l $t: $m") }
        emby.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (!redirect) return slice(request)
                resolves.incrementAndGet()
                return MockResponse().setResponseCode(302)
                    .setHeader("Location", cdn.url("/file.mkv?v=$version&sign=s$version").toString())
                    .apply { maxAge?.let { setHeader("Cache-Control", "private, max-age=$it") } }
            }
        }
        cdn.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(uas) { uas += request.getHeader("User-Agent") }
                if (request.requestUrl!!.queryParameter("v") != version.toString()) return MockResponse().setResponseCode(403)
                val response = slice(request)
                if (truncateNext) {
                    truncateNext = false
                    response.setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                }
                return response
            }
        }
        emby.start()
        cdn.start()
        relay = StreamRelay(OkHttpClient(), "zmby/test", now = clock::get)
    }

    @After
    fun tearDown() {
        relay.stop()
        emby.shutdown()
        cdn.shutdown()
    }

    private fun local() = relay.register(emby.url("/Videos/1/stream.mkv?api_key=t").toString(), "film.mkv")

    private fun get(url: String, range: String?) = player.newCall(
        Request.Builder().url(url).apply { if (range != null) header("Range", range) }.build(),
    ).execute()

    @Test
    fun relaysRangesThroughTheSignedLink() {
        val url = local()
        get(url, "bytes=100-199").use { r ->
            assertEquals(206, r.code)
            assertEquals("bytes 100-199/200000", r.header("Content-Range"))
            assertEquals("100", r.header("Content-Length"))
            assertEquals("close", r.header("Connection"))
            assertArrayEquals(data.copyOfRange(100, 200), r.body!!.bytes())
        }
        get(url, null).use { r ->
            assertEquals(200, r.code)
            assertArrayEquals(data, r.body!!.bytes())
        }
        assertEquals(1, resolves.get())
        assertEquals(listOf("zmby/test"), synchronized(uas) { uas.distinct() })
    }

    @Test
    fun aServerSpokenToAsTheOfficialClientIsRelayedWithThatAgent() {
        val agent = "Dalvik/2.1.0 (Linux; U; Android 11; Z9X 8K Build/RP1A)"
        val url = relay.register(emby.url("/Videos/1/stream.mkv?api_key=t").toString(), "film.mkv", agent)
        get(url, "bytes=0-99").use { r -> assertEquals(206, r.code) }
        // The signed link sees the same client as Emby did, never okhttp or zmby.
        assertEquals(listOf(agent), synchronized(uas) { uas.distinct() })
    }

    @Test
    fun staggeredConnectionsReadIndependently() {
        val url = local()
        val a = get(url, "bytes=0-")
        val b = get(url, "bytes=150000-")
        val sa = a.body!!.source()
        val sb = b.body!!.source()
        val outA = Buffer()
        val outB = Buffer()
        while (!sa.exhausted() || !sb.exhausted()) {
            if (!sa.exhausted()) sa.read(outA, 4096)
            if (!sb.exhausted()) sb.read(outB, 4096)
        }
        a.close()
        b.close()
        assertArrayEquals(data, outA.readByteArray())
        assertArrayEquals(data.copyOfRange(150000, data.size), outB.readByteArray())
    }

    @Test
    fun linkIsReplacedThirtySecondsBeforeItExpires() {
        val url = local()
        get(url, "bytes=0-9").use { it.body!!.bytes() }
        clock.addAndGet(29_000)
        get(url, "bytes=0-9").use { it.body!!.bytes() }
        assertEquals(1, resolves.get())
        clock.addAndGet(2_000) // 31 s: inside the last 30 s of a 60 s link
        version = 2
        get(url, "bytes=10-19").use { r ->
            assertEquals(206, r.code)
            assertArrayEquals(data.copyOfRange(10, 20), r.body!!.bytes())
        }
        assertEquals(2, resolves.get())
    }

    @Test
    fun rejectedLinkIsResolvedAgainAndTheRangeRetried() {
        maxAge = null
        val url = local()
        get(url, "bytes=0-9").use { it.body!!.bytes() }
        version = 2 // the old signature now answers 403
        get(url, "bytes=500-599").use { r ->
            assertEquals(206, r.code)
            assertArrayEquals(data.copyOfRange(500, 600), r.body!!.bytes())
        }
        assertEquals(2, resolves.get())
    }

    @Test
    fun withoutMaxAgeTheLinkIsKeptUntilItFails() {
        maxAge = null
        val url = local()
        repeat(3) {
            clock.addAndGet(3_600_000)
            get(url, "bytes=0-9").use { it.body!!.bytes() }
        }
        assertEquals(1, resolves.get())
    }

    @Test
    fun shortUpstreamBodyIsResumedNotPassedOff() {
        val url = local()
        truncateNext = true
        get(url, "bytes=1000-120999").use { r ->
            assertEquals(206, r.code)
            assertArrayEquals(data.copyOfRange(1000, 121000), r.body!!.bytes())
        }
        // The resume asked for the rest of the range only.
        val ranges = (0 until cdn.requestCount).map { cdn.takeRequest().getHeader("Range") }
        assertEquals("bytes=1000-120999", ranges.first())
        assertEquals(true, ranges.last()!!.endsWith("-120999") && ranges.last() != ranges.first())
    }

    @Test
    fun serverWithoutRedirectIsStreamedAsIs() {
        redirect = false
        val url = local()
        get(url, "bytes=5-14").use { r ->
            assertEquals(206, r.code)
            assertArrayEquals(data.copyOfRange(5, 15), r.body!!.bytes())
        }
        assertEquals(0, cdn.requestCount)
    }

    @Test
    fun unknownStreamIsNotFound() {
        local()
        val base = local().substringBefore("/r/")
        get("$base/r/nope/x.mkv", null).use { assertEquals(404, it.code) }
    }
}
