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
    private fun repo(now: () -> Long = System::currentTimeMillis, client: OkHttpClient = OkHttpClient(), aliasContext: () -> String = { "" }) =
        DanmuRepository(tmp.root.resolve("cache"), DandanplayApi(client), now, tmp.root.resolve("choices"), aliasContext)

    @Before fun start() { server.start() }
    @After fun stop() { server.shutdown() }
    private fun dispatch(block: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = block(request) }
    }

    @Test fun `the picker reopens the successful alias and all returned candidates without another request`() = runBlocking {
        val first = matched(name = "兰香如故")
        val second = matched(88, "兰香如故", "https://video.example/other").substringAfter("\"matches\":[").substringBeforeLast("]}")
        dispatch { response(first.removeSuffix("]}") + "," + second + "]}") }
        val q = query.copy(names = listOf("兰香如故"))
        val repository = repo()
        val selected = requireNotNull(repository.current(endpoint, "s:ep3", q))
        val restarted = repo()
        assertEquals(listOf(77L, 88L), restarted.candidates(endpoint, "s:ep3", q, selected).map { it.target.episodeId })
        restarted.preferTitle(endpoint, "s:ep3", q, "另一个译名")
        assertEquals("另一个译名", repo().preferredTitle(endpoint, "copy:ep4", q.copy(episode = 4)))
        assertEquals(1, server.requestCount)
    }

    @Test fun `empty filename match loads comments using the full search source identity`() = runBlocking {
        dispatch { req -> when {
            req.path!!.contains("/match") -> response(matched())
            req.path!!.contains("comment/77") -> response("""{"comments":[]}""")
            req.path!!.contains("search/episodes") -> response(searched(99))
            req.path!!.contains("comment/99") -> response(comments("recovered"))
            else -> MockResponse().setResponseCode(404)
        } }
        val repository = repo()
        val key = "s:ep3"
        assertEquals(77L, repository.current(endpoint, key, query)?.target?.episodeId)
        assertEquals("recovered", repository.load(endpoint, key, episode, 3, query)?.single()?.text)
        assertEquals(99L, repository.current(endpoint, key, query)?.target?.episodeId)
        assertEquals("recovered", repository.load(endpoint, key, episode, 3, query)?.single()?.text)
        assertEquals(4, server.requestCount)
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
        assertEquals("菜鸟伙房兵 S02E03", fileName)
        assertFalse(fileName.contains(episode.path!!))
        val comment = server.takeRequest()
        assertEquals("/token/api/v2/comment/77", comment.requestUrl!!.encodedPath)
        assertEquals("json", comment.requestUrl!!.queryParameter("format"))
        assertEquals(77L, repo.current(endpoint, "s:ep3", query)?.target?.episodeId)
        assertEquals(1, repo.load(endpoint, "s:ep3", episode, 5, query)?.size)
        assertEquals(2, server.requestCount)
    }

    @Test fun `known aliases are queried together without requiring identical returned titles`() = runBlocking {
        dispatch { req -> if (req.path!!.contains("/match")) {
            if (req.body.clone().readUtf8().contains("菜鸟炊事兵")) response(matched())
            else response("""{"success":true,"isMatched":false,"matches":[]}""")
        } else response(comments()) }
        val result = repo().load(endpoint, "s:ep3", episode, 3, query) { it.copy(names = it.names + "菜鸟炊事兵") }
        assertEquals(1, result?.size)
        val requests = (1..server.requestCount).map { server.takeRequest() }
        assertTrue(requests.any { it.method == "POST" && it.body.readUtf8().contains("菜鸟炊事兵") })
        assertTrue(server.requestCount in 2..3)
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
        dispatch { response("""{"success":true,"isMatched":false,"matches":[],"animes":[]}""") }
        val repo = repo({ now })
        assertNull(repo.load(endpoint, "s:ep3", episode, 3, query))
        assertNull(repo.load(endpoint, "s:ep3", episode, 3, query))
        assertEquals(2, server.requestCount)
        now += DanmuRepository.NO_MATCH_TTL_MS + 1
        assertNull(repo.load(endpoint, "s:ep3", episode, 3, query))
        assertEquals(4, server.requestCount)
        repo.reset(endpoint, "s:ep3", query)
        assertNull(repo.load(endpoint, "s:ep3", episode, 3, query))
        assertEquals(6, server.requestCount)
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
        assertEquals(5, server.requestCount) // one full-search recovery after both comment IDs disappeared
    }

    @Test fun `empty or invalid comments are not retained as a twelve hour body cache`() = runBlocking {
        for (body in listOf("""{"comments":[]}""", "{}", """{"success":false,"comments":[]}""")) {
            dispatch { when {
                it.path!!.contains("/match") -> response(matched())
                it.path!!.contains("search/episodes") -> response("""{"animes":[]}""")
                else -> response(body)
            } }
            val repo = repo()
            val before = server.requestCount
            repo.load(endpoint, "s:ep3", episode, 3, query)
            repo.load(endpoint, "s:ep3", episode, 3, query)
            // A just-completed match is reused briefly; the empty/invalid body is fetched again.
            assertEquals(before + 5, server.requestCount)
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

    @Test fun `search results are shared across panel opens but explicit search expiry and endpoint changes refetch`() = runBlocking {
        var now = 1_000L
        dispatch { response(searched()) }
        val repo = repo({ now })
        repo.search(endpoint, "name")
        repo.search(endpoint, " name ")
        assertEquals(1, server.requestCount)
        repo.search(endpoint, "name", refresh = true)
        assertEquals(2, server.requestCount)
        now += DanmuRepository.SEARCH_TTL_MS
        repo.search(endpoint, "name")
        repo.search(endpoint.copy(base = server.url("/other").toString()), "name")
        assertEquals(4, server.requestCount)
    }

    @Test fun `concurrent detail panel and playback share one match and one comment download`() = runBlocking {
        dispatch { req ->
            if (req.path!!.contains("/match")) response(matched()).setBodyDelay(150, TimeUnit.MILLISECONDS)
            else response(comments()).setBodyDelay(150, TimeUnit.MILLISECONDS)
        }
        val repo = repo()
        val current = async(Dispatchers.IO) { repo.current(endpoint, "s:ep3", query) }
        val first = async(Dispatchers.IO) { repo.load(endpoint, "s:ep3", episode, 3, query) }
        val second = async(Dispatchers.IO) { repo.load(endpoint, "s:ep3", episode, 5, query) }
        assertEquals(77L, current.await()?.target?.episodeId)
        assertEquals(first.await(), second.await())
        assertEquals(2, server.requestCount)
    }

    @Test fun `negative status in the detail button and panel uses the existing five minute cache`() = runBlocking {
        dispatch { response("""{"success":true,"isMatched":false,"matches":[],"animes":[]}""") }
        val repo = repo()
        assertNull(repo.current(endpoint, "s:ep3", query))
        assertNull(repo.current(endpoint, "s:ep3", query))
        assertNull(repo.load(endpoint, "s:ep3", episode, 3, query))
        assertEquals(2, server.requestCount)
    }

    @Test fun `a match miss uses the pickers complete search and Korean drama episode numbers`() = runBlocking {
        val q = query.copy(names = listOf("21世纪大君夫人"), season = 1, episode = 6)
        dispatch { req -> if (req.path!!.contains("/match")) response("""{"isMatched":false,"matches":[]}""") else
            response("""{"animes":[{"animeId":7,"animeTitle":"21世纪大君夫人(2026)【韩剧】from source","type":"韩剧","episodes":[{"episodeId":101,"episodeTitle":"【source】 第1集：标题"},{"episodeId":106,"episodeTitle":"【source】 第6集：标题"}]}]}""") }
        val repo = repo()
        assertEquals(2, repo.search(endpoint, q.names.first()).size)
        assertEquals(106L, repo.current(endpoint, "ep6", q)?.target?.episodeId)
        assertEquals(3, server.requestCount) // cached full search + match with/without year
    }

    @Test fun `later seasons do not send the works first year and a first season can retry without it`() = runBlocking {
        val names = mutableListOf<String>()
        dispatch { req ->
            val file = DanmuProtocol.json.parseToJsonElement(req.body.readUtf8()).jsonObject["fileName"]!!.jsonPrimitive.content
            names += file
            if ("(2026)" in file) response("""{"isMatched":false,"matches":[]}""") else response(matched())
        }
        val repo = repo()
        assertNotNull(repo.current(endpoint, "ep3", query))
        assertEquals(listOf("菜鸟伙房兵 S02E03"), names)
        assertNotNull(repo.current(endpoint, "ep1", query.copy(season = 1)))
        assertEquals(listOf("菜鸟伙房兵 S02E03", "菜鸟伙房兵 (2026) S01E03", "菜鸟伙房兵 S01E03"), names)
    }

    @Test fun `initial foreign aliases cannot consume every slot before an identity confirmed Chinese alias`() = runBlocking {
        dispatch { req -> if (req.body.readUtf8().contains("正确译名")) response(matched(name = "正确译名 第2季")) else response("""{"isMatched":false,"matches":[]}""") }
        val q = query.copy(names = listOf("English", "Original", "Foreign3", "Foreign4"))
        val selected = repo().current(endpoint, "ep3", q) { it.copy(names = it.names + "正确译名") }
        assertEquals("正确译名", selected?.searchTitle)
        assertTrue(server.requestCount in 1..5)
    }

    @Test fun `configuring metadata credentials bypasses a previous negative match`() = runBlocking {
        var configured = false
        dispatch { req -> if (req.path!!.contains("/match") && req.body.readUtf8().contains("新译名")) response(matched(name = "新译名 第2季"))
            else response("""{"isMatched":false,"matches":[],"animes":[]}""") }
        val repo = repo(aliasContext = { if (configured) "configured" else "empty" })
        assertNull(repo.current(endpoint, "ep3", query))
        configured = true
        val selection = repo.current(endpoint, "ep3", query) { it.copy(names = it.names + "新译名") }
        assertEquals("新译名", selection?.searchTitle)
    }

    @Test fun `supplemental alias fuzzy matches cannot select unrelated works`() = runBlocking {
        dispatch { req -> if (req.path!!.contains("/match")) {
            if (req.body.readUtf8().contains("补充译名")) response(matched(name = "无关的童话", url = "https://video.example/wrong"))
            else response("""{"isMatched":false,"matches":[]}""")
        } else response("""{"animes":[{"animeId":10,"animeTitle":"正确作品 第2季","type":"电视剧","episodes":[{"episodeId":88,"episodeTitle":"第3集"}]}]}""") }
        val q = query.copy(names = listOf("正确作品"))
        val result = repo().current(endpoint, "ep3", q) { it.copy(names = it.names + "补充译名") }
        assertEquals("正确作品 第2季", result?.target?.animeTitle)
        assertEquals(88L, result?.target?.episodeId)
    }

    @Test fun `all eight name requests reach HTTP together without the default per host queue`() = runBlocking {
        val started = java.util.concurrent.CountDownLatch(8)
        dispatch { req ->
            started.countDown()
            if (!started.await(5, TimeUnit.SECONDS)) MockResponse().setResponseCode(503)
            else if (req.body.readUtf8().contains("Name0")) response(matched())
            else response("""{"isMatched":false,"matches":[]}""")
        }
        val q = query.copy(names = (0..7).map { "Name$it" })
        assertNotNull(repo().current(endpoint, "ep3", q))
        assertEquals(0L, started.count)
    }

    private fun seasonSearch(anchor: Int = 58, next: Boolean = true, id: Int = 77): String =
        """{"animes":[{"animeId":8,"animeTitle":"所选剧 第1季 from source","type":"电视剧","episodes":[{"episodeId":$id,"episodeTitle":"【source】 第${anchor}集","url":"https://video.example/$anchor"}${if (next) ",{\"episodeId\":${id+1},\"episodeTitle\":\"【source】 第${anchor+1}集\",\"url\":\"https://video.example/${anchor+1}\"}" else ""}]}]}"""

    @Test fun `season choice survives restart follows offset and refreshed IDs but isolates season work and endpoint`() = runBlocking {
        var id = 77
        dispatch { req -> when {
            req.path!!.contains("search/episodes") -> response(seasonSearch(id = id))
            req.path!!.contains("comment/101") -> response(comments("next"))
            else -> response(matched(91))
        } }
        val original = repo()
        val selected = original.search(endpoint, "所选剧").first()
        // An older per-episode choice is replaced by the subsequent season decision.
        original.saveChoice(endpoint, "ep4", query.copy(episode = 4), selected)
        assertTrue(original.saveChoice(endpoint, "ep3", query, selected, followSeason = true).season)
        val restarted = repo()
        val next = query.copy(episode = 4)
        assertEquals(78L, restarted.current(endpoint, "other-server:ep4", next)?.target?.episodeId)
        id = 100
        assertEquals("next", restarted.load(endpoint, "ep4", episode, 3, next)?.single()?.text)
        assertTrue(restarted.current(endpoint, "ep3", query)!!.season)
        assertFalse(restarted.current(endpoint, "other-season", next.copy(season = 3))!!.manual)
        assertFalse(restarted.current(endpoint, "other-work", next.copy(workId = "tmdb:tv:20"))!!.manual)
        assertFalse(restarted.current(endpoint.copy(base = server.url("/other").toString()), "ep4", next)!!.manual)
    }

    @Test fun `season missing episode never auto switches and reset remains possible`() = runBlocking {
        dispatch { req -> if (req.path!!.contains("search/episodes")) response(seasonSearch(next = false)) else response(matched(91)) }
        val repo = repo()
        val selected = repo.search(endpoint, "所选剧").first()
        repo.saveChoice(endpoint, "ep3", query, selected, followSeason = true)
        val next = query.copy(episode = 4)
        assertNull(repo.load(endpoint, "ep4", episode, 3, next))
        assertEquals(1, server.requestCount)
        repo.reset(endpoint, "ep4", next)
        assertFalse(repo.current(endpoint, "ep4", next)!!.manual)
        assertFalse(repo.current(endpoint, "ep3", query)!!.manual)
    }

    @Test fun `movies and unnumbered sources keep a single item choice`() = runBlocking {
        val repo = repo()
        val selected = DanmuSelection(DanmuTarget(77, url = "https://video.example/1"), "title")
        assertFalse(repo.saveChoice(endpoint, "ep3", query, selected, followSeason = true).season)
        val film = query.copy(movie = true, season = null, episode = null)
        assertFalse(repo.saveChoice(endpoint, "film", film, selected, followSeason = true).season)
        assertNotNull(repo.current(endpoint, "film", film))
        assertEquals(0, server.requestCount)
    }

    @Test fun `synthetic names preserve English letters and remove actual control whitespace`() {
        assertEquals("Star Trek Strange New Worlds S04E01", DanmuRepository.fileName(query.copy(season = 4, episode = 1), "Star Trek\nStrange\tNew\rWorlds"))
        assertEquals("Lupin (2021) S01E01", DanmuRepository.fileName(query.copy(season = 1, episode = 1, year = 2021), "Lupin"))
        assertEquals("Movie (2026)", DanmuRepository.fileName(query.copy(movie = true), "Movie"))
    }

    @Test fun `full search is not confused with the automatic fallback filtered to one episode`() = runBlocking {
        dispatch { req -> when {
            req.path!!.contains("/match") -> MockResponse().setResponseCode(405)
            req.path!!.contains("search/episodes") && req.requestUrl!!.queryParameter("episode") != null -> response("""{"animes":[{"animeId":10,"animeTitle":"菜鸟伙房兵 第2季(2026)【电视剧】","type":"电视剧","episodes":[{"episodeId":77,"episodeTitle":"第3集"}]}]}""")
            req.path!!.contains("search/episodes") -> response("""{"animes":[{"animeId":10,"animeTitle":"菜鸟伙房兵 第2季","type":"电视剧","episodes":[{"episodeId":71,"episodeTitle":"第1集"},{"episodeId":72,"episodeTitle":"第2集"},{"episodeId":77,"episodeTitle":"第3集"}]}]}""")
            else -> response(comments())
        } }
        val repo = repo()
        assertNotNull(repo.load(endpoint, "s:ep3", episode, 3, query))
        assertEquals(3, repo.search(endpoint, query.names.first()).size)
        assertEquals(3, repo.search(endpoint, query.names.first()).size)
        assertEquals(4, server.requestCount)
    }

    @Test fun `cached manual search cannot reuse a recycled ID when a new comment body is needed`() = runBlocking {
        var newId = false
        dispatch { req ->
            when {
                req.path!!.contains("search/episodes") -> response(searched(if (newId) 99 else 77))
                req.path!!.contains("comment/99") -> response(comments())
                else -> MockResponse().setResponseCode(404)
            }
        }
        val repo = repo()
        val selected = repo.search(endpoint, "selected").single()
        repo.saveChoice(endpoint, "s:ep3", query, selected)
        newId = true
        assertNotNull(repo.load(endpoint, "s:ep3", episode, 3, query))
        assertEquals(99L, repo.current(endpoint, "s:ep3", query)?.target?.episodeId)
        assertEquals(3, server.requestCount)
    }

    @Test fun `fallback refreshes filtered search IDs when a body is needed after a prior match`() = runBlocking {
        var now = 1_000L
        var id = 77
        dispatch { req -> when {
            req.path!!.contains("/match") -> MockResponse().setResponseCode(405)
            req.path!!.contains("search/episodes") -> response("""{"animes":[{"animeId":10,"animeTitle":"菜鸟伙房兵 第2季(2026)【电视剧】","type":"电视剧","episodes":[{"episodeId":$id,"episodeTitle":"第3集"}]}]}""")
            req.path!!.contains("comment/99") -> response(comments())
            else -> error("stale comment ID must not be requested")
        } }
        val repo = repo({ now })
        assertEquals(77L, repo.current(endpoint, "s:ep3", query)?.target?.episodeId)
        now += 6_000
        id = 99
        assertNotNull(repo.load(endpoint, "s:ep3", episode, 3, query))
        assertEquals(4, server.requestCount)
    }

    @Test fun `cancelling matching and comment download cancels the actual HTTP call`() = runBlocking {
        for (duringComments in listOf(false, true)) {
            val calls = java.util.concurrent.ConcurrentLinkedQueue<okhttp3.Call>()
            val client = OkHttpClient.Builder().eventListener(object : okhttp3.EventListener() {
                override fun callStart(call: okhttp3.Call) { calls.add(call) }
            }).build()
            val repository = repo(client = client)
            dispatch { req ->
                if (duringComments && req.path!!.contains("/match")) response(matched())
                else MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
            }
            val loading = launch(Dispatchers.Default) { repository.load(endpoint, "s:ep3", episode, 3, query) }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            if (duringComments) assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            val call = calls.last()
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
