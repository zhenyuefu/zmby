package app.zmby.danmu

import app.zmby.emby.BaseItemDto
import kotlinx.coroutines.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class DanmuRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private val episode = BaseItemDto(id = "ep3", type = "Episode", seriesName = "菜鸟伙房兵", parentIndexNumber = 2, indexNumber = 3,
        productionYear = 2030, path = "/media/private/file.mkv")
    private val query = DanmuQuery(listOf("菜鸟伙房兵"), false, 2, 3, 2026, workId = "tmdb:tv:10")
    private val endpoint get() = DanmuEndpoint(server.url("/token").toString())
    private fun response(body: String) = MockResponse().setBody(body)
    private fun matched(id: Int = 77, name: String = "菜鸟炊事兵 第1季(2025)〖电视剧〗from source", url: String = "https://video.example/episode58") =
        """{"success":true,"isMatched":true,"matches":[{"episodeId":$id,"animeId":10,"animeTitle":"$name","episodeTitle":"第58集","url":"$url","type":"电视剧","shift":0}]}"""
    private fun searched(id: Int = 77, url: String = "https://video.example/episode58") =
        """{"success":true,"animes":[{"animeId":10,"animeTitle":"菜鸟炊事兵","type":"电视剧","episodes":[{"episodeId":$id,"episodeTitle":"第58集","url":"$url"}]}]}"""
    private fun comments(text: String = "内容 x 3 ️♡8") = """{"comments":[{"p":"1,1,16777215","m":"$text"}]}"""
    private fun repo(now: () -> Long = System::currentTimeMillis, client: OkHttpClient = OkHttpClient()) =
        DanmuRepository(tmp.root.resolve("cache"), DandanplayApi(client), now, tmp.root.resolve("choices"))

    @Before fun start() { server.start() }
    @After fun stop() { server.shutdown() }
    private fun dispatch(block: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = block(request) }
    }

    @Test fun `match is primary and aliases and cross-season results are trusted and cached`() = runBlocking {
        server.enqueue(response(matched()))
        server.enqueue(response(comments()))
        val repo = repo()
        assertEquals("内容 x 3 ️♡8", repo.load(endpoint, "s:ep3", episode, 3, query)?.single()?.text)
        val match = server.takeRequest()
        assertEquals("POST", match.method)
        assertEquals("/token/api/v2/match", match.requestUrl!!.encodedPath)
        val fileName = DanmuProtocol.json.parseToJsonElement(match.body.readUtf8()).jsonObject["fileName"]!!.jsonPrimitive.content
        assertEquals("菜鸟伙房兵 (2026) S02E03", fileName)
        assertFalse(fileName.contains(episode.path!!))
        val comment = server.takeRequest()
        assertEquals("/token/api/v2/comment/77", comment.requestUrl!!.encodedPath)
        assertEquals("json", comment.requestUrl!!.queryParameter("format"))
        assertEquals(77L, repo.current(endpoint, "s:ep3", query)?.target?.episodeId)
        assertEquals(1, repo.load(endpoint, "s:ep3", episode, 5, query)?.size)
        assertEquals(2, server.requestCount)
    }

    @Test fun `known aliases are tried after a miss without requiring identical returned titles`() = runBlocking {
        server.enqueue(response("""{"success":true,"isMatched":false,"matches":[]}"""))
        server.enqueue(response(matched()))
        server.enqueue(response(comments()))
        val result = repo().load(endpoint, "s:ep3", episode, 3, query) { it.copy(names = it.names + "菜鸟炊事兵") }
        assertEquals(1, result?.size)
        server.takeRequest()
        assertTrue(server.takeRequest().body.readUtf8().contains("菜鸟炊事兵"))
        assertEquals(3, server.requestCount)
    }

    @Test fun `unsupported filename matching falls back to search and remembers the capability`() = runBlocking {
        var matches = 0
        dispatch { req -> when {
            req.path!!.contains("/match") -> { matches++; MockResponse().setResponseCode(405) }
            req.path!!.contains("search/episodes") -> response("""{"animes":[{"animeId":10,"animeTitle":"菜鸟伙房兵 第2季(2026)【电视剧】from x","type":"电视剧","episodes":[{"episodeId":77,"episodeTitle":"第3集"}]}]}""")
            else -> response(comments())
        } }
        val repo = repo()
        assertEquals(1, repo.load(endpoint, "s:ep3", episode, 3, query)?.size)
        val other = query.copy(episode = 4)
        assertEquals(1, repo.load(endpoint, "s:ep4", episode, 3, other)?.size)
        assertEquals(1, matches)
        assertEquals(5, server.requestCount)
    }

    @Test fun `authentication rate limit and server errors never trigger fallback or a negative cache`() = runBlocking {
        for (code in listOf(401, 429, 500)) {
            dispatch { MockResponse().setResponseCode(code) }
            val repo = repo()
            val before = server.requestCount
            assertNull(repo.load(endpoint, "s:ep3", episode, 3, query))
            assertNull(repo.load(endpoint, "s:ep3", episode, 3, query))
            assertEquals(before + 2, server.requestCount)
        }
    }

    @Test fun `negative matches expire quickly and reset bypasses them immediately`() = runBlocking {
        var now = System.currentTimeMillis()
        dispatch { response("""{"success":true,"isMatched":false,"matches":[]}""") }
        val repo = repo({ now })
        assertNull(repo.load(endpoint, "s:ep3", episode, 3, query))
        assertNull(repo.load(endpoint, "s:ep3", episode, 3, query))
        assertEquals(1, server.requestCount)
        now += DanmuRepository.NO_MATCH_TTL_MS + 1
        assertNull(repo.load(endpoint, "s:ep3", episode, 3, query))
        assertEquals(2, server.requestCount)
        repo.reset(endpoint, "s:ep3", query)
        assertNull(repo.load(endpoint, "s:ep3", episode, 3, query))
        assertEquals(3, server.requestCount)
    }

    @Test fun `404 causes exactly one rematch and stale cache IDs are not used after body expiry`() = runBlocking {
        var now = System.currentTimeMillis()
        var matches = 0
        val ids = mutableListOf<String>()
        dispatch { req -> if (req.path!!.contains("/match")) response(matched(if (++matches == 1) 77 else 88)) else {
            ids += req.requestUrl!!.encodedPath
            if (req.path!!.contains("/77")) MockResponse().setResponseCode(404) else response(comments())
        } }
        val repo = repo({ now })
        assertEquals(1, repo.load(endpoint, "s:ep3", episode, 3, query)?.size)
        assertEquals(listOf("/token/api/v2/comment/77", "/token/api/v2/comment/88"), ids)
        assertEquals(2, matches)
        now += DanmuRepository.COMMENTS_TTL_MS + 1
        assertEquals(1, repo.load(endpoint, "s:ep3", episode, 3, query)?.size)
        assertEquals(3, matches)
        assertEquals("/token/api/v2/comment/88", ids.last())
        assertEquals(1, ids.count { it.endsWith("/77") })
    }

    @Test fun `recovery never loops when all fresh IDs are already gone`() = runBlocking {
        dispatch { if (it.path!!.contains("/match")) response(matched()) else MockResponse().setResponseCode(404) }
        assertNull(repo().load(endpoint, "s:ep3", episode, 3, query))
        assertEquals(4, server.requestCount)
    }

    @Test fun `empty or invalid comments are not retained as a twelve hour body cache`() = runBlocking {
        for (body in listOf("""{"comments":[]}""", "{}", """{"success":false,"comments":[]}""")) {
            dispatch { if (it.path!!.contains("/match")) response(matched()) else response(body) }
            val repo = repo()
            val before = server.requestCount
            repo.load(endpoint, "s:ep3", episode, 3, query)
            repo.load(endpoint, "s:ep3", episode, 3, query)
            assertEquals(before + 4, server.requestCount)
            assertTrue(tmp.root.resolve("cache").listFiles().orEmpty().none { it.name.startsWith("c3-") })
        }
    }

    @Test fun `reused IDs cannot make different works share a body cache`() = runBlocking {
        var count = 0
        dispatch { if (it.path!!.contains("/match")) response(matched(url = "")) else response(comments("body${++count}")) }
        val repo = repo()
        assertEquals("body1", repo.load(endpoint, "s:ep3", episode, 3, query)?.single()?.text)
        assertEquals("body2", repo.load(endpoint, "s:other", episode, 3, query.copy(workId = "tmdb:tv:20"))?.single()?.text)
    }

    @Test fun `manual source survives restart and stale IDs are recovered by source URL without auto switching`() = runBlocking {
        val saved = DanmuSelection(DanmuTarget(77, "10", "菜鸟炊事兵", "第58集", url = "https://video.example/episode58"), "菜鸟炊事兵", true)
        val original = repo()
        original.saveChoice(endpoint, "s:ep3", query, saved)
        val restarted = repo()
        dispatch { req -> when {
            req.path!!.contains("search/episodes") -> response(searched(99))
            req.path!!.contains("comment/99") -> response(comments("manual"))
            else -> MockResponse().setResponseCode(500)
        } }
        val renamed = query.copy(names = listOf("另一个译名"))
        assertEquals("manual", restarted.load(endpoint, "another-server:copy", episode, 3, renamed)?.single()?.text)
        assertEquals(99L, restarted.current(endpoint, "s:ep3", renamed)?.target?.episodeId)
        assertTrue(restarted.current(endpoint, "s:ep3", renamed)!!.manual)
        assertEquals(2, server.requestCount)
        restarted.reset(endpoint, "s:ep3", renamed)
        dispatch { if (it.path!!.contains("/match")) response(matched()) else response(comments("auto")) }
        assertEquals("auto", restarted.load(endpoint, "s:ep3", episode, 3, renamed)?.single()?.text)
    }

    @Test fun `a missing manual source is kept for correction and never replaced by an automatic result`() = runBlocking {
        val repo = repo()
        val saved = DanmuSelection(DanmuTarget(77, "10", "selected", "第58集", url = "https://video.example/missing"), "selected", true)
        repo.saveChoice(endpoint, "s:ep3", query, saved)
        dispatch { req -> assertTrue(req.path!!.contains("search/episodes")); response(searched()) }
        assertNull(repo.load(endpoint, "s:ep3", episode, 3, query))
        assertEquals(saved, repo.current(endpoint, "s:ep3", query))
        assertEquals(1, server.requestCount)
    }

    @Test fun `a manual record without source identity never selects an arbitrary refreshed episode`() = runBlocking {
        val repo = repo()
        val saved = DanmuSelection(DanmuTarget(12), "selected", true)
        repo.saveChoice(endpoint, "s:ep3", query, saved)
        dispatch { req ->
            assertTrue(req.path!!.contains("search/episodes"))
            response("""{"animes":[{"episodes":[{"episodeId":99}]}]}""")
        }
        assertNull(repo.load(endpoint, "s:ep3", episode, 3, query))
        assertEquals(saved, repo.current(endpoint, "s:ep3", query))
        assertEquals(1, server.requestCount)
    }

    @Test fun `both GET and POST signatures cover the API path with a token prefix preserved`() = runBlocking {
        server.enqueue(response("""{"isMatched":false,"matches":[]}"""))
        val api = DandanplayApi(OkHttpClient()) { 1_700_000_000_000 }
        api.match(endpoint.copy(appId = "app", appSecret = "secret"), "title S02E03")
        val request = server.takeRequest()
        assertEquals("app", request.getHeader("X-AppId"))
        assertEquals(DandanplayApi.signature("app", "1700000000", "/api/v2/match", "secret"), request.getHeader("X-Signature"))
    }

    @Test fun `cancelling matching and comment download cancels the actual HTTP call`() = runBlocking {
        for (duringComments in listOf(false, true)) {
            val client = OkHttpClient()
            val repository = repo(client = client)
            dispatch { req ->
                if (duringComments && req.path!!.contains("/match")) response(matched())
                else MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
            }
            val loading = launch(Dispatchers.Default) { repository.load(endpoint, "s:ep3", episode, 3, query) }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            if (duringComments) assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            val call = client.dispatcher.runningCalls().single()
            loading.cancel()
            withTimeout(2_000) { loading.join() }
            assertTrue(call.isCanceled())
        }
    }

    @Test fun `fallback query never substitutes episode year or original episode title for series metadata`() {
        assertEquals(DanmuQuery(listOf("菜鸟伙房兵"), false, 2, 3), DanmuRepository.queryOf(episode.copy(originalTitle = "episode title")))
        assertEquals("Series 第2季 E03", DanmuRepository.fileName(DanmuQuery(listOf("Series 第2季"), false, episode = 3), "Series 第2季"))
        assertNull(DanmuRepository.queryOf(episode.copy(indexNumber = null)))
        assertNull(DanmuRepository.queryOf(BaseItemDto("series", type = "Series")))
    }
}
