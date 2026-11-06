package app.zmby.ratings

import app.zmby.aggregate.ResultStore
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rotten Tomatoes through Wikidata (ratings design); fixtures are hand-written, not copied pages. */
class RatingsTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun sparql(vararg paths: String) =
        """{"head":{"vars":["rt"]},"results":{"bindings":[${paths.joinToString(",") { """{"rt":{"type":"literal","value":"$it"}}""" }}]}}"""

    private fun page(card: String) = """<html><head></head><body><div>…</div>
        <script id="media-scorecard-json" data-json="mediaScorecard" type="application/json">$card</script></body></html>"""

    private val fresh = page("""{"criticsScore":{"certified":true,"scorePercent":"92%","sentiment":"POSITIVE","title":"Tomatometer"},
        "audienceScore":{"certified":false,"scorePercent":"95%","sentiment":"POSITIVE","title":"Popcornmeter"},"hideAudienceScore":false}""")

    @Test
    fun wikidataPathIsTheFirstWellFormedOne() {
        assertEquals("m/dune_part_two", RottenTomatoesParse.path(json, sparql("m/dune_part_two")))
        assertEquals("tv/breaking_bad", RottenTomatoesParse.path(json, sparql("https://evil.example/x", "TV/Breaking_Bad")))
        assertNull(RottenTomatoesParse.path(json, sparql()))
        assertNull(RottenTomatoesParse.path(json, sparql("m/../../etc")))
        assertTrue(RottenTomatoesParse.sparql("tt15239678").contains("wdt:P345 \"tt15239678\""))
    }

    @Test
    fun scorecardReadsBothMeters() {
        val s = RottenTomatoesParse.scorecard(json, fresh)
        assertEquals(RtScore(92, fresh = true, certified = true), s.tomatometer)
        assertEquals(RtScore(95, fresh = true, certified = false), s.popcornmeter)
    }

    @Test
    fun rottenSpilledHiddenAndMissing() {
        val rotten = RottenTomatoesParse.scorecard(json, page("""{"criticsScore":{"certified":true,"scorePercent":"31%","sentiment":"NEGATIVE"},
            "audienceScore":{"scorePercent":"48%","sentiment":"NEGATIVE"}}"""))
        // Certified only counts for a fresh score.
        assertEquals(RtScore(31, fresh = false, certified = false), rotten.tomatometer)
        assertEquals(RtScore(48, fresh = false), rotten.popcornmeter)
        val hidden = RottenTomatoesParse.scorecard(json, page("""{"criticsScore":{"scorePercent":"70%"},"audienceScore":{"scorePercent":"80%"},"hideAudienceScore":true}"""))
        assertEquals(RtScore(70, fresh = true), hidden.tomatometer)
        assertNull(hidden.popcornmeter)
        val none = RottenTomatoesParse.scorecard(json, page("""{"criticsScore":{"scorePercent":""},"audienceScore":{}}"""))
        assertTrue(none.isEmpty)
        assertTrue(RottenTomatoesParse.scorecard(json, "<html>no card</html>").isEmpty)
    }

    private class MemoryStore(val now: () -> Long) : ResultStore {
        val rows = ConcurrentHashMap<List<String>, Pair<String, Long>>()
        override suspend fun read(kind: String, key: String, server: String, maxAgeMs: Long) =
            rows[listOf(kind, key, server)]?.takeIf { now() - it.second in 0 until maxAgeMs }?.first
        override suspend fun write(kind: String, key: String, server: String, json: String) { rows[listOf(kind, key, server)] = json to now() }
        override suspend fun drop(kind: String, key: String, servers: List<String>) = Unit
        override suspend fun dropKinds(kinds: List<String>, servers: List<String>) = Unit
    }

    private var now = 1_790_000_000_000L
    private val store = MemoryStore { now }
    private val wikidataCalls = AtomicInteger()
    private val pageCalls = AtomicInteger()
    private var mapped: String? = "m/dune_part_two"
    private var pageAnswer: () -> String = { fresh }
    private var wikidataDown = false

    private fun repo() = RottenTomatoesRepository(
        path = { wikidataCalls.incrementAndGet(); if (wikidataDown) throw IOException("HTTP 429"); mapped?.let { sparql(it) } ?: sparql() },
        page = { pageCalls.incrementAndGet(); pageAnswer() },
        store = store, clock = { now },
    )

    @Test
    fun mappingAndScoresAreStoredAndReused() = runBlocking {
        assertEquals(92, repo().scores("tt15239678")!!.tomatometer!!.percent)
        // Another process: both from the store.
        assertEquals(95, repo().scores("tt15239678")!!.popcornmeter!!.percent)
        assertEquals(1, wikidataCalls.get())
        assertEquals(1, pageCalls.get())
        // Scores go stale after three days, the mapping is still known.
        now += RottenTomatoesRepository.SCORES_FRESH_MS
        repo().scores("tt15239678")
        assertEquals(1, wikidataCalls.get())
        assertEquals(2, pageCalls.get())
    }

    @Test
    fun noRottenTomatoesIdAsksNoPageAndIsRemembered() = runBlocking {
        mapped = null
        assertNull(repo().scores("tt0000001"))
        assertNull(repo().scores("tt0000001"))
        assertEquals(1, wikidataCalls.get())
        assertEquals(0, pageCalls.get())
    }

    @Test
    fun aMissingPageIsAnAnswerNotAFailure() = runBlocking {
        pageAnswer = { throw RottenTomatoesApi.NotFound() }
        assertNull(repo().scores("tt15239678"))
        pageAnswer = { fresh }
        assertNull(repo().scores("tt15239678"))
        assertEquals(1, pageCalls.get())
    }

    @Test
    fun failuresAreNotStoredAndWaitTenMinutes() = runBlocking {
        wikidataDown = true
        val r = repo()
        assertNull(r.scores("tt15239678"))
        assertNull(r.scores("tt15239678"))
        assertEquals(1, wikidataCalls.get())
        assertTrue(store.rows.isEmpty())
        wikidataDown = false
        now += RottenTomatoesRepository.RETRY_MS
        assertEquals(92, r.scores("tt15239678")!!.tomatometer!!.percent)
    }
}
