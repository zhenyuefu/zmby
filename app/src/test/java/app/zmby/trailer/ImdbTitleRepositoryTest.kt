package app.zmby.trailer

import app.zmby.aggregate.ResultStore
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Local first, shared and never storing failures (统一数据层; trailer and ratings design). */
class ImdbTitleRepositoryTest {
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
    private val calls = AtomicInteger()
    private var fail = false
    private var hasTrailer = true
    private val complete = HashSet<String>()
    private var gate: CompletableDeferred<Unit>? = null
    /** The link expires a day after "now" at the time of the request. */
    private val query: suspend (String) -> String = { id ->
        calls.incrementAndGet()
        gate?.await()
        if (fail) throw IOException("IMDb HTTP 503")
        val expires = (now + 24 * 60 * 60_000L) / 1000
        if (!hasTrailer) """{"data":{"title":{"ratingsSummary":{"aggregateRating":null,"voteCount":0},"latestTrailer":null,"primaryVideos":{"edges":[]}}}}"""
        else """{"data":{"title":{"ratingsSummary":{"aggregateRating":8.4,"voteCount":804343},"latestTrailer":{"id":"vi$id","contentType":{"id":"amzn1.imdb.video.contenttype.trailer"},"runtime":{"value":30},
            "playbackURLs":[{"url":"https://video.example.com/a.mp4?Expires=$expires&Signature=x","videoMimeType":"MP4","videoDefinition":"DEF_1080p"}]},"primaryVideos":{"edges":[]}}}}"""
    }

    private fun repo() = ImdbTitleRepository(query, store, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        complete = { it in complete }, clock = { now })

    @Test
    fun askedOnceThenFromMemoryAndAfterARestartFromTheStore() = runBlocking {
        val first = repo()
        val info = first.trailer("tt0111161")!!
        assertEquals("vitt0111161", info.videoId)
        assertEquals(info, first.trailer("TT0111161"))
        assertEquals(1, calls.get())
        // A new process reads the stored row.
        assertEquals(info, repo().trailer("tt0111161"))
        assertEquals(1, calls.get())
    }

    @Test
    fun anExpiredLinkIsAskedAgainUnlessTheVideoIsFullyCached() = runBlocking {
        val r = repo()
        val info = r.trailer("tt0111161")!!
        now += 24 * 60 * 60_000L - ImdbTitleRepository.LINK_MARGIN_MS + 1
        complete += info.cacheKey
        assertEquals(info, r.trailer("tt0111161"))
        assertEquals(1, calls.get())
        complete.clear()
        val renewed = r.trailer("tt0111161")!!
        assertEquals(2, calls.get())
        assertTrue(renewed.expiresAt > info.expiresAt)
        assertEquals(info.cacheKey, renewed.cacheKey)
    }

    @Test
    fun noTrailerIsKeptForThreeDays() = runBlocking {
        hasTrailer = false
        val r = repo()
        assertNull(r.trailer("tt0000001"))
        now += ImdbTitleRepository.TITLE_FRESH_MS - 1
        assertNull(r.trailer("tt0000001"))
        assertEquals(1, calls.get())
        now += 2
        hasTrailer = true
        assertNotNull(r.trailer("tt0000001"))
        assertEquals(2, calls.get())
    }

    @Test
    fun failuresAreNotStoredAndNotRetriedForTenMinutes() = runBlocking {
        fail = true
        val r = repo()
        assertNull(r.trailer("tt0111161"))
        assertNull(r.trailer("tt0111161"))
        assertEquals(1, calls.get())
        assertTrue(store.rows.isEmpty())
        // Another process tries at once: nothing was stored.
        fail = false
        assertNotNull(repo().trailer("tt0111161"))
        assertEquals(2, calls.get())
        now += ImdbTitleRepository.RETRY_MS
        assertNotNull(r.trailer("tt0111161"))
    }

    @Test
    fun concurrentAsksShareOneRequest() = runBlocking {
        gate = CompletableDeferred()
        val r = repo()
        val asks = (1..4).map { async(Dispatchers.Default) { r.trailer("tt0111161") } }
        while (calls.get() == 0) Thread.sleep(5)
        gate!!.complete(Unit)
        val answers = asks.awaitAll()
        assertEquals(1, calls.get())
        assertTrue(answers.all { it != null && it == answers.first() })
    }

    @Test
    fun ratingComesWithTheTrailerAndIsAskedAgainAfterThreeDays() = runBlocking {
        val r = repo()
        val title = r.title("tt0111161")!!
        assertEquals(8.4, title.rating!!, 0.0)
        assertEquals(804343, title.votes)
        complete += title.trailer!!.cacheKey
        now += ImdbTitleRepository.TITLE_FRESH_MS - 1
        r.title("tt0111161")
        assertEquals(1, calls.get())
        // A fully cached trailer does not keep a rating older than three days.
        now += 2
        r.title("tt0111161")
        assertEquals(2, calls.get())
    }

    @Test
    fun aTitleWithoutRatingHasNone() = runBlocking {
        hasTrailer = false
        val title = repo().title("tt0000001")!!
        assertNull(title.rating)
        assertNull(title.trailer)
    }

    @Test
    fun notAnImdbIdAsksNothing() = runBlocking {
        assertNull(repo().trailer("12345"))
        assertEquals(0, calls.get())
    }
}

