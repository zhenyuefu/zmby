package app.zmby.catalog

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class CatalogRepositoryTest {
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
            assertEquals(2, work.seasons.size); assertEquals(1, work.cast.size); assertEquals("A / B", work.cast.single().role)
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
