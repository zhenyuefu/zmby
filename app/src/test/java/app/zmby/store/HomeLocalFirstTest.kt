package app.zmby.store

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.zmby.SessionStore
import app.zmby.aggregate.FanOut
import app.zmby.aggregate.HomeFeed
import app.zmby.aggregate.HomeServers
import app.zmby.data.Library
import app.zmby.data.MemoryCapsStore
import app.zmby.data.MemoryRegistryStore
import app.zmby.data.ServerConfig
import app.zmby.data.ServerLine
import app.zmby.data.ServerRegistry
import app.zmby.emby.ClientInfo
import app.zmby.emby.EmbyClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 先本地、后刷新 for the home rows (local-data spec) against a fake Emby: rows come from the store,
 * 最新添加 is not asked again within ten minutes, and a server that is gone leaves its rows in place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class HomeLocalFirstTest {
    private val server = MockWebServer()
    private val hits = ConcurrentHashMap<String, AtomicInteger>()
    private lateinit var db: ZmbyDatabase
    private var now = 10_000_000L

    private fun count(path: String) = hits[path]?.get() ?: 0

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                val kind = when {
                    path.endsWith("/Views") -> "views"
                    path.endsWith("/Items/Resume") -> "resume"
                    path.endsWith("/Items/Latest") -> "latest"
                    path.endsWith("/Shows/NextUp") -> "nextup"
                    else -> path
                }
                hits.getOrPut(kind) { AtomicInteger() }.incrementAndGet()
                val body = when (kind) {
                    "views" -> """{"Items":[{"Id":"lib","Name":"电影","CollectionType":"movies"}],"TotalRecordCount":1}"""
                    "resume" -> """{"Items":[{"Id":"r1","Name":"漫长的季节","Type":"Movie","ProviderIds":{"Tmdb":"5"},"UserData":{"PlaybackPositionTicks":100,"LastPlayedDate":"2026-10-24T10:00:00Z"}}],"TotalRecordCount":1}"""
                    "latest" -> """[{"Id":"m1","Name":"沙丘2","Type":"Movie","ProviderIds":{"Tmdb":"693134"},"DateCreated":"2026-10-20T00:00:00Z"}]"""
                    "nextup" -> """{"Items":[],"TotalRecordCount":0}"""
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setBody(body).setHeader("Content-Type", "application/json")
            }
        }
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ZmbyDatabase::class.java).build()
    }

    @After fun stop() {
        db.close()
        runCatching { server.shutdown() }
    }

    private fun feed(): Pair<HomeFeed, HomeServers> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val url = server.url("/").toString().trimEnd('/')
        val line = ServerLine("l", "主线路", url)
        val registry = ServerRegistry(MemoryRegistryStore(listOf(ServerConfig("s1", "家里 NAS", listOf(line), "l", "u", "t"))))
        val emby = EmbyClient(OkHttpClient(), ClientInfo(device = "test", deviceId = "d", version = "1"))
        val store = LocalStore(db, emby.json) { now }
        val library = Library(context, emby, registry, SessionStore(context), MemoryCapsStore(), store)
        return HomeFeed(library, FanOut(registry), store) to HomeServers(listOf("s1"), listOf("s1"), mapOf("s1" to 0))
    }

    @Test fun `latest is not asked again within ten minutes, resume always is`() = runBlocking {
        val (home, servers) = feed()
        home.refresh(servers, now = now)
        now += 5 * 60_000
        home.refresh(servers, now = now)
        assertEquals(2, count("resume"))
        assertEquals(2, count("nextup"))
        assertEquals("cached for ten minutes", 1, count("latest"))
        now += 6 * 60_000
        home.refresh(servers, now = now)
        assertEquals(2, count("latest"))
        // The libraries are asked once (memory for ten minutes, the store for offline starts).
        assertEquals(1, count("views"))
    }

    @Test fun `a server outside 观看记录来源 is not asked for resume or next up`() = runBlocking {
        val other = MockWebServer()
        val otherHits = ConcurrentHashMap<String, AtomicInteger>()
        other.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                otherHits.getOrPut(request.requestUrl!!.encodedPath.substringAfterLast('/')) { AtomicInteger() }.incrementAndGet()
                return MockResponse().setBody("""{"Items":[],"TotalRecordCount":0}""")
            }
        }
        other.start()
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val configs = listOf(
                ServerConfig("s1", "家里 NAS", listOf(ServerLine("l", "主线路", server.url("/").toString().trimEnd('/'))), "l", "u", "t"),
                ServerConfig("s2", "朋友的服", listOf(ServerLine("l", "主线路", other.url("/").toString().trimEnd('/'))), "l", "u", "t"),
            )
            val registry = ServerRegistry(MemoryRegistryStore(configs))
            val sources = app.zmby.data.DataSources(registry, persist = {}, providerKey = { null })
            sources.load(emptyList(), migrated = true) {}
            sources.set(app.zmby.data.SourceFeature.WatchHistory, "s2", false)
            val emby = EmbyClient(OkHttpClient(), ClientInfo(device = "test", deviceId = "d", version = "1"))
            val store = LocalStore(db, emby.json) { now }
            val library = Library(context, emby, registry, SessionStore(context), MemoryCapsStore(), store)
            val home = HomeFeed(library, FanOut(registry), store)
            home.refresh(HomeServers(sources.servers(app.zmby.data.SourceFeature.WatchHistory), listOf("s1"), mapOf("s1" to 0, "s2" to 1)), now = now)
            assertEquals(1, count("resume"))
            assertEquals(0, otherHits["Resume"]?.get() ?: 0)
            assertEquals(0, otherHits["NextUp"]?.get() ?: 0)
        } finally {
            other.shutdown()
        }
    }

    @Test fun `rows show from the store before and without the servers`() = runBlocking {
        val (home, servers) = feed()
        home.refresh(servers, now = now)
        server.shutdown()
        val (offline, _) = feed()
        val rows = offline.rows(servers).first()
        assertEquals(listOf("r1"), rows.resume.map { it.item.id })
        assertEquals(listOf("m1"), rows.latest.map { it.item.id })
        // A refresh against the dead server leaves them in place.
        offline.refresh(servers, force = true, now = now)
        assertEquals(listOf("m1"), offline.rows(servers).first().latest.map { it.item.id })
    }
}
