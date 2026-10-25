package app.zmby.playback

import app.zmby.data.MemoryCapsStore
import app.zmby.emby.ClientInfo
import app.zmby.emby.EmbyClient
import app.zmby.emby.MediaSourceInfo
import app.zmby.emby.MediaStream
import app.zmby.emby.BaseItemDto
import app.zmby.emby.ServerSession
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamUrlsTest {
    @Test
    fun directStreamUrlIsResolvedAndGetsTheToken() {
        val src = MediaSourceInfo(id = "ms", directStreamUrl = "/videos/1/original.mkv?MediaSourceId=ms&PlaySessionId=p")
        assertEquals(
            "http://nas:8096/videos/1/original.mkv?MediaSourceId=ms&PlaySessionId=p&api_key=tok",
            StreamUrls.direct("http://nas:8096", src, "tok"),
        )
        // A base under /emby and a path that repeats it do not double up.
        val withPrefix = MediaSourceInfo(id = "ms", directStreamUrl = "/emby/videos/1/original.mkv?api_key=x")
        assertEquals("https://h/emby/videos/1/original.mkv?api_key=x", StreamUrls.direct("https://h/emby", withPrefix, "tok"))
        // Absolute URLs (some 302 setups) are kept.
        val absolute = MediaSourceInfo(id = "ms", directStreamUrl = "https://cdn.example/f.mkv?sign=s")
        assertEquals("https://cdn.example/f.mkv?sign=s&api_key=tok", StreamUrls.direct("http://nas", absolute, "tok"))
        assertNull(StreamUrls.direct("http://nas", MediaSourceInfo(id = "ms"), "tok"))
    }

    @Test
    fun prefixCanBeMovedEitherWay() {
        val root = "http://h:8096/Videos/1/stream.mkv?Static=true&api_key=t"
        assertEquals("", StreamUrls.prefixOf(root))
        val emby = StreamUrls.withPrefix(root, "/emby")
        assertEquals("http://h:8096/emby/Videos/1/stream.mkv?Static=true&api_key=t", emby)
        assertEquals("/emby", StreamUrls.prefixOf(emby))
        assertEquals(root, StreamUrls.withPrefix(emby, ""))
    }

    @Test
    fun subtitleUrlsPreferDeliveryUrl() {
        val src = MediaSourceInfo(id = "ms")
        val delivered = MediaStream(8, type = "Subtitle", codec = "ass", isExternal = true, deliveryUrl = "/Videos/1/ms/Subtitles/8/0/Stream.ass?api_key=t")
        assertEquals("http://h/Videos/1/ms/Subtitles/8/0/Stream.ass?api_key=t", StreamUrls.subtitle("http://h", "1", src, delivered, "t"))
        val plain = MediaStream(9, type = "Subtitle", codec = "subrip", isExternal = true)
        assertEquals("http://h/Videos/1/ms/Subtitles/9/Stream.srt?api_key=t", StreamUrls.subtitle("http://h", "1", src, plain, "t"))
    }
}

class StreamProbeTest {
    private val server = MockWebServer()

    @After
    fun tearDown() = server.shutdown()

    /** A proxy that ignores Range at the root and honours it under /emby. */
    private fun rangeOnlyUnderEmby() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.requestUrl!!.encodedPath
            return when {
                path.startsWith("/emby/Videos/") && request.getHeader("Range") != null ->
                    MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-0/1000").setBody("x")
                path.startsWith("/Videos/") -> MockResponse().setResponseCode(200).setBody("whole file")
                path.endsWith("/PlaybackInfo") -> MockResponse().setBody(
                    """{"PlaySessionId":"ps","MediaSources":[{"Id":"ms","Container":"mkv","DirectStreamUrl":"/Videos/i1/original.mkv?MediaSourceId=ms",
                       "MediaStreams":[{"Index":0,"Type":"Video"},{"Index":1,"Type":"Audio"},{"Index":2,"Type":"Audio"},{"Index":3,"Type":"Subtitle","Codec":"subrip","IsExternal":true}],
                       "DefaultAudioStreamIndex":2}]}""",
                )
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    @Test
    fun picksThePrefixThatHonoursRange() = runTest {
        server.dispatcher = rangeOnlyUnderEmby()
        server.start()
        val probe = StreamProbe(OkHttpClient(), "zmby/test")
        val check = probe.check(server.url("/Videos/1/stream.mkv?api_key=t").toString())!!
        assertEquals("/emby", check.rangePrefix)
        assertNull(check.redirect)
    }

    @Test
    fun detectsRedirectsWithTheirMaxAge() = runTest {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://cdn.example/f.mkv?sign=abc").setHeader("Cache-Control", "max-age=60"))
        server.start()
        val check = StreamProbe(OkHttpClient(), "zmby/test").check(server.url("/Videos/1/stream.mkv").toString())!!
        assertEquals(60L, check.redirect?.maxAgeSeconds)
        assertEquals("http://cdn.example/f.mkv?sign=abc", check.redirect?.location)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun preparerUsesDirectStreamUrlProbedPrefixAndTracks() = runTest {
        server.dispatcher = rangeOnlyUnderEmby()
        server.start()
        val client = EmbyClient(OkHttpClient(), ClientInfo(device = "Z9X", deviceId = "d", version = "1"))
        val caps = MemoryCapsStore()
        val preparer = PlaybackPreparer(client, StreamProbe(OkHttpClient(), "zmby/test"), caps)
        val base = server.url("/").toString().trimEnd('/')
        val session = ServerSession(base, "u1", "tok")
        val prepared = preparer.prepare(session, BaseItemDto(id = "i1", name = "Film"), 0)
        val uri = prepared.launch.uri
        assertTrue(uri, uri.contains("/emby/Videos/i1/original.mkv?MediaSourceId=ms&api_key=tok"))
        assertEquals("/emby", caps.get(base).rangePrefix)
        // Server default audio is Emby index 2 = the second audio track.
        assertEquals(1, prepared.launch.extras["audio_idx"])
        assertTrue(prepared.launch.extras["subs"].toString(), prepared.launch.extras["subs"].toString().contains("/Subtitles/3/Stream.srt"))

        // The second playback reuses the cached answer: no new probes.
        val before = server.requestCount
        preparer.prepare(session, BaseItemDto(id = "i1", name = "Film"), 0)
        assertEquals(before + 1, server.requestCount) // just PlaybackInfo
    }
}
