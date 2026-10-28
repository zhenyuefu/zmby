package app.zmby.playback

import app.zmby.catalog.CatalogId
import app.zmby.catalog.CatalogWork
import app.zmby.emby.BaseItemDto
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaybackArtworkTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `canonical cached artwork is preferred and unavailable images fall back independently`() = runTest {
        val ref = CatalogId("tmdb", "movie", "10")
        val movie = BaseItemDto("movie", type = "Movie", providerIds = mapOf("Tmdb" to "10"))
        val work = CatalogWork(ref, "Film", backdrop = "tmdb-backdrop", logo = "uncached-tmdb-logo")
        val seen = mutableListOf<CatalogId>()
        val resolver = PlaybackArtworkResolver(
            catalog = { seen += it; work }, item = { _, _ -> null },
            backdrop = { "server-backdrop" }, logo = { "server-logo" },
            serve = { urls -> urls.firstOrNull { it != "uncached-tmdb-logo" }?.let { "cached:$it" } },
        )
        assertEquals(PlaybackImages("cached:tmdb-backdrop", "cached:server-logo"), resolver.resolve("a", movie))
        assertEquals(listOf(ref), seen)
    }

    @Test fun `episodes use their series identity across next episode and isolate server hints`() = runTest {
        val ref = CatalogId("tmdb", "tv", "10")
        val seen = mutableListOf<CatalogId>()
        val resolver = PlaybackArtworkResolver(
            catalog = { seen += it; CatalogWork(it, "Series", backdrop = "tmdb") },
            item = { _, _ -> null }, backdrop = { null }, logo = { null }, serve = { it.firstOrNull() },
        )
        resolver.remember("a", "episode1", ref, "series")
        val episode = BaseItemDto("episode2", type = "Episode", seriesId = "series", providerIds = mapOf("Tmdb" to "999"))
        assertEquals("tmdb", resolver.resolve("a", episode).backdrop)
        assertEquals(PlaybackImages(), resolver.resolve("b", episode))
        assertEquals(listOf(ref), seen)
    }

    @Test fun `server fallback reads cached parent metadata and missing images send no logo`() = runTest {
        val series = BaseItemDto("series", type = "Series")
        val resolver = PlaybackArtworkResolver(
            catalog = { error("No catalog request without identity") },
            item = { server, id -> assertEquals("a", server); series.takeIf { id == "series" } },
            backdrop = { if (it.item.id == "series") "parent-backdrop" else null }, logo = { null },
            serve = { it.firstOrNull() },
        )
        assertEquals(PlaybackImages("parent-backdrop", null), resolver.resolve("a", BaseItemDto("episode", type = "Episode", seriesId = "series")))
    }

    @Test fun `player receives only loopback addresses and repeated reads use existing cache bytes`() {
        val file = temporary.newFile().apply { writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47) + "cached-picture".toByteArray()) }
        var opens = 0
        var closes = 0
        val art = CachedArtworkServer { key ->
            if (key != "https://image.example/backdrop") null
            else { opens++; CachedArtwork(file) { closes++ } }
        }
        val http = OkHttpClient()
        try {
            val url = art.cached(listOf("https://image.example/missing", "https://image.example/backdrop"))!!
            assertTrue(url.startsWith("http://127.0.0.1:"))
            assertFalse(url.contains("image.example"))
            repeat(2) { http.newCall(Request.Builder().url(url).build()).execute().use {
                assertEquals(200, it.code); assertEquals("image/png", it.header("Content-Type"))
                assertArrayEquals(file.readBytes(), it.body!!.bytes())
            } }
            http.newCall(Request.Builder().url(url).head().build()).execute().use { assertEquals(200, it.code); assertEquals(0, it.body!!.bytes().size) }
            http.newCall(Request.Builder().url(url.substringBefore("/art/") + "/art/unknown").build()).execute().use { assertEquals(404, it.code) }
            assertNull(art.cached(listOf("https://image.example/uncached")))
            assertEquals(opens, closes)
        } finally { art.stop() }
    }
}
