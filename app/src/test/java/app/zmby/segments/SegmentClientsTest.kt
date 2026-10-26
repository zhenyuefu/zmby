package app.zmby.segments

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Response shapes as the public read APIs answered on 2026-10-25 (TheIntroDB v4, IntroDB /segments, PublicMetaDB per Polyfin). */
class SegmentClientsTest {
    private lateinit var server: MockWebServer
    private val seen = mutableListOf<RecordedRequest>()
    private var answer: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(404) }

    @Before fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = answer(request).also { seen += request }
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    private val http by lazy { SegmentHttp(OkHttpClient(), "zmby/test") }
    private val base get() = server.url("/").toString().trimEnd('/')
    private val episode = SegmentQuery(false, tvdb = "81189", tmdb = "1396", imdb = "tt0903747", season = 1, episode = 2, durationMs = 2_900_000)

    @Test fun `TheIntroDB answer picks the best-rated span of each kind`() {
        val p = TheIntroDbClient.parse("""{"tvdb_id":81189,"type":"tv","season":1,"episode":2,
            "intro":[{"start_ms":314568,"end_ms":330969,"confidence":0.4},{"start_ms":314000,"end_ms":331000,"confidence":0.9,"submission_count":3}],
            "recap":[{"start_ms":null,"end_ms":40000}],"credits":[{"start_ms":2843000,"end_ms":null}],"preview":[{"start_ms":null,"end_ms":10}]}""")!!
        assertEquals(listOf(
            Segment(SegmentKind.Intro, 314_000, 331_000),
            Segment(SegmentKind.Recap, 0, 40_000),
            Segment(SegmentKind.Credits, 2_843_000, null),
        ), p.segments)
        assertTrue(TheIntroDbClient.parse("""{"error":"no submissions for this media","code":"no_submissions"}""")!!.segments.isEmpty())
        assertNull(TheIntroDbClient.parse("<html>"))
    }

    @Test fun `TheIntroDB is asked by TVDB first and by the next ID after a 404`() = runBlocking {
        answer = { r ->
            if (r.requestUrl!!.queryParameter("tmdb_id") == "1396") MockResponse().setBody("""{"intro":[{"start_ms":1000,"end_ms":61000}]}""")
            else MockResponse().setResponseCode(404).setBody("""{"code":"no_submissions"}""")
        }
        val a = TheIntroDbClient(http, base).fetch(episode, null)
        assertEquals(ProviderAnswer.Found(ProviderSegments(listOf(Segment(SegmentKind.Intro, 1_000, 61_000)))), a)
        assertEquals(listOf("81189", null), seen.map { it.requestUrl!!.queryParameter("tvdb_id") })
        val first = seen.first().requestUrl!!
        assertEquals(listOf("1", "2", "2900000"), listOf("season", "episode", "duration_ms").map { first.queryParameter(it) })
        assertNull(seen.first().getHeader("Authorization"))
    }

    @Test fun `rate limits and refused keys are failures with a pause`() = runBlocking {
        answer = { MockResponse().setResponseCode(429).setHeader("Retry-After", "120") }
        assertEquals(ProviderAnswer.Failed("rate limited", 120_000), TheIntroDbClient(http, base).fetch(episode, null))
        answer = { MockResponse().setResponseCode(401) }
        assertEquals(ProviderAnswer.Failed("key refused (401)", 3_600_000), PublicMetaDbClient(http, base).fetch(episode, "pmdb-secret-key"))
        assertEquals("Bearer pmdb-secret-key", seen.last().getHeader("Authorization"))
    }

    @Test fun `nothing is sent without the IDs a provider needs`() = runBlocking {
        val noImdb = episode.copy(imdb = null)
        assertEquals(ProviderAnswer.NotAsked, IntroDbClient(http, base).fetch(noImdb, null))
        assertEquals(ProviderAnswer.NotAsked, PublicMetaDbClient(http, base).fetch(episode.copy(tmdb = null), "k"))
        assertEquals(ProviderAnswer.NotAsked, PublicMetaDbClient(http, base).fetch(episode, null))
        assertEquals(ProviderAnswer.NotAsked, TheIntroDbClient(http, base).fetch(episode.copy(episode = null), null))
        assertTrue(seen.isEmpty())
    }

    @Test fun `IntroDB maps outro to credits and asks films with is_movie`() = runBlocking {
        val p = IntroDbClient.parse("""{"imdb_id":"tt0903747","intro":{"start_sec":314.5,"end_sec":331,"start_ms":314500,"end_ms":331000},
            "recap":null,"outro":{"start_ms":2843000,"end_ms":2901000},"post_credits":null}""")!!
        assertEquals(listOf(Segment(SegmentKind.Intro, 314_500, 331_000), Segment(SegmentKind.Credits, 2_843_000, 2_901_000)), p.segments)
        answer = { MockResponse().setBody("""{"intro":null,"recap":null,"outro":null}""") }
        assertEquals(ProviderAnswer.Empty, IntroDbClient(http, base).fetch(SegmentQuery(true, imdb = "tt0133093"), null))
        assertEquals("true", seen.single().requestUrl!!.queryParameter("is_movie"))
    }

    @Test fun `PublicMetaDB takes one record and never mixes them`() {
        val body = """{"items":[
            {"id":"b","source":"physical","created":"2026-09-01T00:00:00Z","intro_start_ms":0,"intro_end_ms":50000,"credits_start_ms":2000000,"credits_end_ms":2100000,"recap_start_ms":0,"recap_end_ms":20000},
            {"id":"c","source":"streaming","created":"2026-08-01T00:00:00Z","intro_start_ms":1000,"intro_end_ms":61000},
            {"id":"a","source":"streaming","created":"2026-09-01T00:00:00Z","intro_start_ms":2000,"intro_end_ms":62000,"credits_start_ms":null,"credits_end_ms":null}]}"""
        assertEquals(listOf(Segment(SegmentKind.Intro, 2_000, 62_000)), PublicMetaDbClient.parse(body)!!.segments)
        assertTrue(PublicMetaDbClient.parse("""{"items":[]}""")!!.segments.isEmpty())
    }
}
