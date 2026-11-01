package app.zmby.playback

import app.zmby.danmu.DanmuAss
import app.zmby.danmu.Danmaku
import app.zmby.data.MemoryCapsStore
import app.zmby.emby.BaseItemDto
import app.zmby.emby.ClientInfo
import app.zmby.emby.EmbyClient
import app.zmby.emby.ServerSession
import app.zmby.zidoo.ZidooPlayer
import app.zmby.zidoo.ZidooTracks
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList

/** 弹幕 as the second subtitle (danmu design: 播放器字幕) while preparing a com.zidoo.player playback. */
class DanmuSubtitlePrepareTest {
    @get:Rule val temporary = TemporaryFolder()
    private val server = MockWebServer()
    private val http = OkHttpClient()
    private val downloads = CopyOnWriteArrayList<String>()
    private lateinit var preparer: PlaybackPreparer
    private lateinit var files: LocalStrmPreparer
    private lateinit var session: ServerSession

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.contains("PlaybackInfo") -> MockResponse().setBody("""
                    {"PlaySessionId":"ps","MediaSources":[{"Id":"src","Container":"mkv","DirectStreamUrl":"/video.mkv","RunTimeTicks":10000000000,
                     "MediaStreams":[{"Index":1,"Type":"Audio","Language":"chi","IsDefault":true},
                       {"Index":2,"Type":"Subtitle","Codec":"subrip","Language":"chi"},
                       {"Index":3,"Type":"Subtitle","Codec":"PGSSUB","Language":"chi"},
                       {"Index":4,"Type":"Subtitle","Codec":"srt","Language":"eng","IsExternal":true}]}]}
                """.trimIndent())
                request.path!!.startsWith("/video.mkv") -> MockResponse().setResponseCode(206).setBody("video")
                else -> MockResponse().setBody("{}")
            }
        }
        server.start()
        val client = EmbyClient(http, ClientInfo(device = "test", deviceId = "test", version = "test", officialAgent = "Dalvik/test"))
        val caps = MemoryCapsStore()
        files = LocalStrmPreparer(DirectoryPlaybackStore(temporary.newFolder()), { url ->
            downloads += url
            "1\n00:00:01,000 --> 00:00:02,000\n主字幕\n".toByteArray()
        })
        preparer = PlaybackPreparer(client, StreamProbe(http, "zmby/test"), caps, null, files)
        session = ServerSession(server.url("/").toString(), "user", "token", serverId = "owner")
        caps.update(session.capsKey) { it.copy(rangePrefix = "", streamCheckedAt = System.currentTimeMillis()) }
    }

    @After fun stop() {
        http.connectionPool.evictAll()
        server.shutdown()
    }

    private val item = BaseItemDto(id = "item", runTimeTicks = 10000000000)
    private val comments = listOf(Danmaku(1_000, "前方高能"), Danmaku(2_000, "好看", 0x00FFFF))
    private fun danmu(list: List<Danmaku>? = comments, waitMs: Long = 1_000) =
        DanmuSubtitle({ list }, DanmuAss.Config.of(3, 100), waitMs)

    private suspend fun prepare(subtitle: Int?, d: DanmuSubtitle?, delivery: ZidooPlayer.SubtitleDelivery = ZidooPlayer.SubtitleDelivery.LocalStrm) =
        preparer.prepare(session, item, 0, "src", picked = TrackChoice(audio = 1, subtitle = subtitle), subtitles = delivery, danmu = d)

    @Test fun `an embedded subtitle keeps its index and the 弹幕 file lies beside it under the fixed name`() = runTest {
        for (index in listOf(2, 3)) { // text and picture alike: nothing is merged
            val p = prepare(index, danmu())
            val local = requireNotNull(p.local)
            try {
                assertEquals(LocalStrm.DANMU_BASE, local.baseName)
                assertEquals("zmby_danmu.strm", local.strm.name)
                assertNull(local.subtitle)
                assertNull(local.subtitlePid)
                assertEquals(listOf("zmby_danmu.danmu.ass"), local.extras.map { it.name })
                assertTrue(local.extras.single().readText().contains("前方高能"))
                assertTrue(p.launch.extras.containsKey(ZidooPlayer.EXTRA_SUBTITLE_IDX))
                assertFalse(p.launch.extras.containsKey(ZidooPlayer.EXTRA_SUBTITLE_PID))
                assertEquals(local.uri, p.launch.uri)
                assertTrue(downloads.isEmpty())
            } finally { files.release(local) }
        }
    }

    @Test fun `an external subtitle is chosen by PID with the 弹幕 file as a second one`() = runTest {
        val p = prepare(4, danmu())
        val local = requireNotNull(p.local)
        try {
            assertEquals("zmby_danmu.eng.4.srt", local.subtitle?.name)
            assertEquals(local.subtitlePid, p.launch.extras[ZidooPlayer.EXTRA_SUBTITLE_PID])
            assertFalse(p.launch.extras.containsKey(ZidooPlayer.EXTRA_SUBTITLE_IDX))
            assertEquals(listOf("zmby_danmu.danmu.ass"), local.extras.map { it.name })
            assertTrue(downloads.single().contains("/Subtitles/4/"))
        } finally { files.release(local) }
    }

    @Test fun `a new 弹幕 playback replaces the old files and the old release leaves them alone`() = runTest {
        val first = requireNotNull(prepare(4, danmu()).local)
        val second = requireNotNull(prepare(ZidooTracks.SUBTITLE_OFF, danmu()).local)
        val dir = second.strm.parentFile!!
        // The first one's subtitle is gone: the player would list it beside the new video.
        assertEquals(setOf("zmby_danmu.strm", "zmby_danmu.danmu.ass"), dir.list()!!.filter { it.startsWith("zmby_danmu") }.toSet())
        files.release(first)
        assertTrue(second.strm.exists() && second.extras.single().exists())
        files.release(second)
        assertFalse(second.strm.exists() || second.extras.single().exists())
    }

    @Test fun `no comments in time falls back to the chosen subtitle as before`() = runTest {
        val late = CompletableDeferred<List<Danmaku>?>()
        val p = prepare(4, DanmuSubtitle({ late.await() }, DanmuAss.Config.of(3, 100), waitMs = 50))
        val local = requireNotNull(p.local)
        try {
            assertTrue(local.subtitle!!.name.endsWith(".eng.4.srt"))
            assertFalse(local.baseName == LocalStrm.DANMU_BASE)
            assertTrue(local.extras.isEmpty())
        } finally { files.release(local) }
        assertNull(prepare(ZidooTracks.SUBTITLE_OFF, danmu(emptyList())).local)
    }

    @Test fun `players that take subtitles by intent get no generated file`() = runTest {
        val p = prepare(2, danmu(), ZidooPlayer.SubtitleDelivery.IntentExtras)
        assertNull(p.local)
        assertTrue(downloads.isEmpty())
    }
}
