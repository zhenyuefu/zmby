package app.zmby.playback

import app.zmby.data.MemoryCapsStore
import app.zmby.data.Override
import app.zmby.emby.BaseItemDto
import app.zmby.emby.ClientInfo
import app.zmby.emby.EmbyClient
import app.zmby.emby.ServerSession
import app.zmby.emby.UaMode
import app.zmby.emby.UaSetting
import app.zmby.zidoo.ZidooPlayer
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList

class DirectRelayFallbackTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun `all login identities and redirects start direct and retry only once with the same source`() = runTest {
        val server = MockWebServer()
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when {
                    request.path!!.contains("PlaybackInfo") -> MockResponse().setBody("""
                        {"PlaySessionId":"play-session","MediaSources":[
                          {"Id":"selected","Container":"mkv","DirectStreamUrl":"/video.mkv","RunTimeTicks":10000000000,
                           "MediaStreams":[{"Index":1,"Type":"Audio","Language":"eng"},{"Index":2,"Type":"Audio","Language":"chi"},{"Index":3,"Type":"Subtitle","Codec":"srt","IsExternal":true}],
                           "DefaultAudioStreamIndex":1},
                          {"Id":"other","Container":"mp4","DirectStreamUrl":"/wrong.mp4"}]}
                    """.trimIndent())
                    request.path!!.startsWith("/video.mkv") -> MockResponse().setResponseCode(206).setBody("video")
                    else -> MockResponse().setBody("{}")
                }
            }
        }
        server.start()
        val http = OkHttpClient()
        val relay = StreamRelay(http, "zmby/test")
        try {
            val client = EmbyClient(http, ClientInfo(device = "test", deviceId = "test", version = "test", officialAgent = "Dalvik/test"))
            val caps = MemoryCapsStore()
            val files = DirectoryPlaybackStore(temporary.newFolder())
            val subtitles = LocalStrmPreparer(files, { "1\n00:00:01,000 --> 00:00:02,000\nhello\n".toByteArray() })
            val preparer = PlaybackPreparer(client, StreamProbe(http, "zmby/test"), caps, relay, subtitles)
            val item = BaseItemDto(id = "item", runTimeTicks = 10000000000)
            for (ua in listOf(UaSetting(), UaSetting(detected = UaMode.Official), UaSetting(mode = UaMode.Official), UaSetting(mode = UaMode.Custom, custom = "Custom/test"))) {
                for (redirects in listOf(false, true)) for (delivery in ZidooPlayer.SubtitleDelivery.entries) {
                    val session = ServerSession(server.url("/").toString(), "user", "token", serverId = "owner", ua = ua)
                    caps.update(session.capsKey) { it.copy(rangePrefix = "", streamCheckedAt = System.currentTimeMillis(), redirects = redirects) }
                    val direct = preparer.prepare(session, item, 123_000, "selected", picked = TrackChoice(audio = 2, subtitle = 3), subtitles = delivery)
                    assertEquals(server.url("/video.mkv?api_key=token").toString(), direct.local?.strm?.readText()?.trim() ?: direct.launch.uri)
                    val subtitleBytes = direct.local?.subtitle?.readBytes()
                    direct.local?.let { subtitles.release(it) }
                    val retry = requireNotNull(direct.retryWithRelay)
                    val playbackInfoCount = requests.count { it.path!!.contains("PlaybackInfo") }
                    val forwarded = retry(125_000)
                    val forwardedUrl = forwarded.local?.strm?.readText()?.trim() ?: forwarded.launch.uri
                    try {
                        assertTrue(forwardedUrl.startsWith("http://127.0.0.1:"))
                        if (subtitleBytes != null) assertArrayEquals(subtitleBytes, forwarded.local!!.subtitle.readBytes())
                        assertNull(forwarded.retryWithRelay)
                        assertEquals(125_000, forwarded.startPositionMs)
                        assertEquals(direct.launch.extras.filterKeys { it != ZidooPlayer.EXTRA_POSITION }, forwarded.launch.extras.filterKeys { it != ZidooPlayer.EXTRA_POSITION })
                        assertEquals(playbackInfoCount, requests.count { it.path!!.contains("PlaybackInfo") })
                        http.newCall(Request.Builder().url(forwardedUrl).build()).execute().use { assertEquals("video", it.body!!.string()) }
                        assertEquals(client.identity(session).userAgent, requests.last { it.path!!.startsWith("/video.mkv") }.getHeader("User-Agent"))
                        // Reports still identify the owning item/source and original Emby session.
                        forwarded.reporter.start(125_000)
                        val report = requests.last().body.readUtf8()
                        assertTrue(report.contains("selected")); assertTrue(report.contains("play-session")); assertTrue(report.contains("item"))
                    } finally { forwarded.releaseRelay(); forwarded.local?.let { subtitles.release(it) } }
                    http.newCall(Request.Builder().url(forwardedUrl).build()).execute().use { assertEquals(404, it.code) }
                    try { retry(123_000); fail("second relay attempt allowed") } catch (_: IllegalStateException) { }
                }
            }
            val session = ServerSession(server.url("/").toString(), "user", "token")
            for (mode in listOf(Override.Off, Override.On)) {
                caps.update(session.capsKey) { it.copy(relay = mode, rangePrefix = "", streamCheckedAt = System.currentTimeMillis()) }
                val fixed = preparer.prepare(session, item, 0, "selected")
                assertNull(fixed.retryWithRelay)
                assertEquals(mode == Override.On, fixed.launch.uri.startsWith("http://127.0.0.1:") && !fixed.launch.uri.startsWith(server.url("/").toString()))
                fixed.releaseRelay()
            }
        } finally {
            relay.stop()
            http.connectionPool.evictAll()
            server.shutdown()
        }
    }
}
