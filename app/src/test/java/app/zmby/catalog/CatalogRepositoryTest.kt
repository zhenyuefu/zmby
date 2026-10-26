package app.zmby.catalog

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Mimics Android's cursor window: a stored row over 2 MB cannot be read back. */
class TooBigRowException : RuntimeException("Row too big to fit into CursorWindow")

class CatalogRepositoryTest {
    private class WindowLimitedCache : CatalogStorage {
        val rows = mutableMapOf<String, CatalogCache>()
        override suspend fun get(key: String) = rows[key]?.also { if (it.json.length > 2 * 1024 * 1024) throw TooBigRowException() }
        override suspend fun put(row: CatalogCache) { rows[row.key] = row }
        override suspend fun prune(before: Long) = Unit
    }
    /** A bangumi-data stand-in over 2 MB: many unrelated items and one real alias. */
    private fun bigDataset(): String = buildString {
        append("""{"siteMeta":{},"items":[""")
        for (i in 1..30_000) append("""{"title":"填充条目$i","titleTranslate":{"zh-Hans":["Filler $i"]},"begin":"2001-01-01","sites":[{"site":"bangumi","id":"${100_000 + i}"}]},""")
        append("""{"title":"原名","titleTranslate":{"zh-Hans":["长篇番剧"]},"begin":"2020-04-01","sites":[{"site":"bangumi","id":"424242"}]}]}""")
    }
    @Test fun `bangumi-data survives a restart from the private index without touching the cache`() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("catalog").toFile()
        try {
            MockWebServer().use { server ->
                val data = bigDataset()
                assertTrue(data.length > 2 * 1024 * 1024)
                server.enqueue(MockResponse().setBody(data))
                val dao = WindowLimitedCache()
                val file = java.io.File(dir, "bangumi-data-index.txt")
                val first = CatalogHttp(OkHttpClient(), dao, { "" }, animeIndexFile = file, animeEndpoints = listOf(server.url("/data.json").toString()))
                assertEquals("424242", first.animeIndex().find(listOf("长篇番剧"), 2020))
                assertTrue("the dataset never goes into the response cache", dao.rows.isEmpty())
                assertTrue(file.length() < data.length / 2)
                assertEquals(listOf("bangumi-data-index.txt"), dir.list()!!.toList())
                // Restart: the CDN is down, the stored index still answers without a request.
                server.enqueue(MockResponse().setResponseCode(503))
                val again = CatalogHttp(OkHttpClient(), dao, { "" }, animeIndexFile = file, animeEndpoints = listOf(server.url("/data.json").toString()))
                assertEquals("424242", again.animeIndex().find(listOf("原名"), null))
                assertEquals(1, server.requestCount)
                val repo = CatalogRepository(again, dao, { "zh-CN" }, { "zh-CN" }, { "zh-CN" }, { true })
                assertEquals(CatalogId("bangumi", "tv", "424242"), repo.resolve(app.zmby.emby.BaseItemDto("x", name = "长篇番剧", type = "Series", productionYear = 2020)))
            }
        } finally { dir.deleteRecursively() }
    }
    @Test fun `an expired index is fetched again and a failed refresh keeps the stale one`() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("catalog").toFile()
        try {
            MockWebServer().use { server ->
                var now = 0L
                val file = java.io.File(dir, "index.txt")
                server.enqueue(MockResponse().setBody("""[{"title":"A","sites":[{"site":"bangumi","id":"1"}]}]"""))
                val api = CatalogHttp(OkHttpClient(), WindowLimitedCache(), { "" }, clock = { now }, animeIndexFile = file, animeEndpoints = listOf(server.url("/d").toString()))
                assertEquals("1", api.animeIndex().find(listOf("A"), null))
                now += CatalogHttp.ANIME_TTL
                server.enqueue(MockResponse().setResponseCode(500))
                assertEquals("1", api.animeIndex().find(listOf("A"), null))
                // No second download attempt within the retry window.
                assertEquals("1", api.animeIndex().find(listOf("A"), null))
                assertEquals(2, server.requestCount)
                now += CatalogHttp.ANIME_RETRY_MS
                server.enqueue(MockResponse().setBody("""[{"title":"A","sites":[{"site":"bangumi","id":"2"}]}]"""))
                assertEquals("2", api.animeIndex().find(listOf("A"), null))
            }
        } finally { dir.deleteRecursively() }
    }
    @Test fun `an unreadable cached row is a miss and the request still succeeds`() = runBlocking {
        MockWebServer().use { server ->
            val dao = WindowLimitedCache()
            val api = CatalogHttp(OkHttpClient(), dao, { "test-token" }, server.url("/").toString())
            val key = server.url("/tv/10").toString()
            dao.rows[key] = CatalogCache(key, "x".repeat(3 * 1024 * 1024), System.currentTimeMillis())
            server.enqueue(MockResponse().setBody("""{"id":10}"""))
            assertEquals("10", api.tmdb("tv/10")["id"].toString())
            assertEquals(1, server.requestCount)
        }
    }

    private class MemoryCache : CatalogStorage {
        val rows = mutableMapOf<String, CatalogCache>()
        override suspend fun get(key: String) = rows[key]
        override suspend fun put(row: CatalogCache) { rows[row.key] = row }
        override suspend fun prune(before: Long) { rows.entries.removeAll { it.value.fetchedAt < before } }
    }
    private val detail = """{"id":10,"name":"Wrong fallback","original_name":"Original","original_language":"ja","first_air_date":"2024-01-01","translations":{"translations":[{"iso_639_1":"zh","iso_3166_1":"CN","data":{"name":"简体片名","overview":"简体简介"}},{"iso_639_1":"zh","iso_3166_1":"TW","data":{"name":"繁體片名","overview":"繁體簡介"}},{"iso_639_1":"ja","iso_3166_1":"JP","data":{"name":"Original","overview":"Original overview"}}]},"seasons":[{"season_number":1,"name":"Season 1","episode_count":3},{"season_number":2,"name":"Season 2","episode_count":5}],"aggregate_credits":{"cast":[{"id":7,"name":"Actor","roles":[{"character":"A"}]},{"id":7,"name":"Actor","roles":[{"character":"B"}]}],"crew":[]},"external_ids":{"imdb_id":"tt1"}}"""
    private val images = """{"posters":[{"iso_639_1":"zh","iso_3166_1":"TW","file_path":"/tw.jpg","vote_average":10},{"iso_639_1":"zh","iso_3166_1":"CN","file_path":"/cn.jpg","vote_average":1},{"iso_639_1":"ja","file_path":"/ja.jpg"}],"logos":[{"iso_639_1":"ja","file_path":"/logo.png"}],"backdrops":[{"iso_639_1":null,"file_path":"/bg.jpg"}]}"""
    @Test fun `work uses regional translations and artwork caches provider only and deduplicates people`() = runBlocking {
        MockWebServer().use { server ->
            val dao = MemoryCache()
            server.enqueue(MockResponse().setBody(detail)); server.enqueue(MockResponse().setBody(images))
            val api = CatalogHttp(OkHttpClient(), dao, { "test-read-token" }, server.url("/3/").toString())
            var language = "zh-CN"
            val repo = CatalogRepository(api, dao, { language }, { "zh-CN" }, { "zh-CN" }, { false })
            val ref = CatalogId("tmdb", "tv", "10")
            val work = repo.work(ref)
            assertEquals("简体片名", work.title); assertEquals("简体简介", work.overview)
            assertTrue(work.poster!!.endsWith("/cn.jpg")); assertTrue(work.logo!!.endsWith("/logo.png"))
            assertEquals(2, work.seasons.size); assertEquals(1, work.cast.size); assertEquals("A · B", work.cast.single().role)
            assertEquals(work, repo.cached(ref)); repo.work(ref); assertEquals(2, server.requestCount)
            val request = server.takeRequest(); assertEquals("Bearer test-read-token", request.getHeader("Authorization")); assertEquals("zh-CN", request.requestUrl!!.queryParameter("language"))
            val imageRequest = server.takeRequest(); assertEquals("zh-CN,ja,null", imageRequest.requestUrl!!.queryParameter("include_image_language"))
            assertFalse(dao.rows.keys.any { "test-read-token" in it }); assertFalse(dao.rows.values.any { "test-read-token" in it.json })
            language = "zh-TW"; assertNull(repo.cached(ref))
        }
    }
    @Test fun `failed refresh preserves cached responses and cancellation is not swallowed`() = runBlocking {
        MockWebServer().use { server ->
            val dao = MemoryCache(); var now = 1L
            server.enqueue(MockResponse().setBody("""{"id":10}""")); server.enqueue(MockResponse().setResponseCode(500))
            val api = CatalogHttp(OkHttpClient(), dao, { "test-token" }, server.url("/").toString(), clock = { now })
            val first = api.tmdb("tv/10"); now += 2 * CatalogHttp.DAY
            assertEquals(first, api.tmdb("tv/10"))
            val before = dao.rows.size
            server.enqueue(MockResponse().setHeadersDelay(5, TimeUnit.SECONDS).setBody("{}"))
            val job = launch { api.tmdb("tv/11") }; delay(30); job.cancelAndJoin()
            assertTrue(job.isCancelled); assertEquals(before, dao.rows.size)
        }
    }
    @Test fun `Bangumi paging retains specials and never forwards TMDB credentials`() = runBlocking {
        MockWebServer().use { server ->
            val dao = MemoryCache()
            server.enqueue(MockResponse().setBody("""{"total":2,"data":[{"id":101,"type":0,"sort":1,"name":"One","name_cn":"第一集"}]}"""))
            server.enqueue(MockResponse().setBody("""{"total":2,"data":[{"id":102,"type":1,"sort":1,"name":"Special"}]}"""))
            val api = CatalogHttp(OkHttpClient(), dao, { "test-token" }, bgmBase = server.url("/v0/").toString())
            val repo = CatalogRepository(api, dao, { "zh-CN" }, { "zh-CN" }, { "zh-CN" }, { true })
            val work = CatalogWork(CatalogId("bangumi", "tv", "20"), "Anime")
            val episodes = repo.episodes(work, 1)
            assertEquals("第一集", episodes.single().title)
            assertEquals(2, server.requestCount)
            val first = server.takeRequest(); val second = server.takeRequest()
            assertNull(first.getHeader("Authorization")); assertEquals("1", second.requestUrl!!.queryParameter("offset"))
            assertEquals("102", repo.episodes(work, 0).single().id)
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun `provider person biography and credits are available offline without Emby aliases`() = runBlocking {
        MockWebServer().use { server ->
            val dao = MemoryCache()
            server.enqueue(MockResponse().setBody("""{"id":7,"name":"Actor","biography":"Biography","combined_credits":{"cast":[{"id":1,"media_type":"movie","title":"Film"},{"id":1,"media_type":"movie","title":"Film"},{"id":1,"media_type":"tv","name":"Series"}]}}"""))
            val api = CatalogHttp(OkHttpClient(), dao, { "test-token" }, server.url("/3/").toString())
            val repo = CatalogRepository(api, dao, { "zh-CN" }, { "zh-CN" }, { "zh-CN" }, { false })
            val person = repo.person("tmdb", "7")
            assertEquals("Biography", person.overview); assertEquals(2, person.works.size)
            assertEquals(person, repo.cachedPerson("tmdb", "7"))
            assertEquals(1, server.requestCount)
            assertTrue(server.takeRequest().path!!.startsWith("/3/person/7?"))
        }
    }
    @Test fun `rate limiting pauses the provider without dropping cached data`() = runBlocking {
        MockWebServer().use { server ->
            val api = CatalogHttp(OkHttpClient(), MemoryCache(), { "test-token" }, server.url("/").toString())
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "60"))
            for (path in listOf("tv/10", "tv/11")) {
                try { api.tmdb(path); fail("rate limited") } catch (_: CatalogException) { }
            }
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun `missing credentials never issues TMDB requests`() = runBlocking {
        MockWebServer().use { server ->
            val api = CatalogHttp(OkHttpClient(), MemoryCache(), { "" }, server.url("/").toString())
            try { api.tmdb("tv/10"); fail("expected configuration error") } catch (e: CatalogException) { assertTrue(e.message!!.contains("Token")) }
            assertEquals(0, server.requestCount)
        }
    }
}
