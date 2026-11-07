package app.zmby.playback

import app.zmby.data.MemoryCapsStore
import app.zmby.data.Override
import app.zmby.emby.BaseItemDto
import app.zmby.emby.ClientInfo
import app.zmby.emby.EmbyClient
import app.zmby.emby.ServerSession
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class ProxiedPlaybackTest {
    @Test fun `a proxied line plays through the relay even when the relay is off`() = runTest {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.contains("PlaybackInfo") -> MockResponse().setBody(
                    """{"PlaySessionId":"s","MediaSources":[{"Id":"a","Container":"mkv","DirectStreamUrl":"/video.mkv"}]}""")
                else -> MockResponse().setBody("{}")
            }
        }
        server.start()
        val http = OkHttpClient()
        val relay = StreamRelay(http, "zmby/test")
        try {
            val client = EmbyClient(http, ClientInfo(device = "test", deviceId = "test", version = "test", officialAgent = "Dalvik/test"))
            val caps = MemoryCapsStore()
            val session = ServerSession(server.url("/").toString(), "user", "token")
            caps.update(session.capsKey) { it.copy(relay = Override.Off, rangePrefix = "", streamCheckedAt = System.currentTimeMillis()) }
            val item = BaseItemDto(id = "item")
            for (proxied in listOf(false, true)) {
                val preparer = PlaybackPreparer(client, StreamProbe(http, "zmby/test"), caps, relay, proxied = { proxied && it.startsWith(server.url("/").toString()) })
                val p = preparer.prepare(session, item, 0, "a")
                try {
                    assertEquals(proxied, p.launch.uri.startsWith("http://127.0.0.1:") && !p.launch.uri.startsWith(server.url("/").toString()))
                    assertNull(p.retryWithRelay)
                } finally { p.releaseRelay() }
            }
        } finally {
            relay.stop()
            http.connectionPool.evictAll()
            server.shutdown()
        }
    }
}
