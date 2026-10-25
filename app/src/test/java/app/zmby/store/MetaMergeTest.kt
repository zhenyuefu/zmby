package app.zmby.store

import app.zmby.emby.BaseItemDto
import app.zmby.emby.ImageInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetaMergeTest {
    private fun movie(id: String, name: String, overview: String? = null, tmdb: String? = "27205", poster: String? = "p", backdrop: String? = "b", logo: String? = null, year: Int? = 2010, genres: List<String> = emptyList()) =
        BaseItemDto(
            id = id, name = name, type = "Movie", overview = overview, productionYear = year, genres = genres,
            providerIds = tmdb?.let { mapOf("Tmdb" to it) }.orEmpty(),
            imageTags = listOfNotNull(poster?.let { "Primary" to it }, logo?.let { "Logo" to it }).toMap(),
            backdropImageTags = listOfNotNull(backdrop),
        )

    @Test fun `same TMDB on several servers is one key`() {
        assertEquals("movie:tmdb:27205", StoreKeys.work("a", movie("1", "Inception")))
        assertEquals("movie:tmdb:27205", StoreKeys.work("b", movie("99", "盗梦空间")))
    }

    @Test fun `key order is TMDB then IMDb then TVDB and never a title`() {
        val imdbOnly = movie("1", "X", tmdb = null).copy(providerIds = mapOf("IMDB" to "TT1375666", "Tvdb" to "5"))
        assertEquals("movie:imdb:tt1375666", StoreKeys.work("a", imdbOnly))
        val tvdbOnly = movie("1", "X", tmdb = null).copy(providerIds = mapOf("tvdb" to "5"))
        assertEquals("movie:tvdb:5", StoreKeys.work("a", tvdbOnly))
        // Title and year would match across servers, but the store keeps it to its own item.
        assertEquals("item:a:1", StoreKeys.work("a", movie("1", "Inception", tmdb = null)))
        assertFalse(StoreKeys.isExternal("item:a:1"))
        assertEquals("a" to "1", StoreKeys.localRef("item:a:1"))
    }

    @Test fun `an episode takes its series key when the series is known`() {
        val ep = BaseItemDto(id = "e", type = "Episode", seriesId = "s", parentIndexNumber = 1, indexNumber = 7)
        assertEquals("series:tmdb:1:s01:e07", StoreKeys.work("a", ep, "series:tmdb:1"))
        assertEquals("item:a:e", StoreKeys.work("a", ep, null))
        assertEquals("item:a:e", StoreKeys.work("a", ep, "item:a:s"))
    }

    @Test fun `person keys prefer TMDB then IMDb then the server's own id`() {
        assertEquals("person:tmdb:6193", StoreKeys.person("a", "p1", mapOf("Tmdb" to " 6193 ")))
        assertEquals("person:imdb:nm1", StoreKeys.person("a", "p1", mapOf("IMDb" to "nm1")))
        assertEquals("person:a:p1", StoreKeys.person("a", "p1", mapOf("Tmdb" to "")))
    }

    @Test fun `text prefers Chinese, then server priority`() {
        val merged = MetaMerge.merge(
            "movie:tmdb:27205",
            listOf(
                MetaCandidate("en", 0, movie("1", "Inception", "A thief who steals secrets", genres = listOf("Sci-Fi"))),
                MetaCandidate("cn2", 2, movie("3", "盗梦空间（二号服）", "第二台的简介")),
                MetaCandidate("cn", 1, movie("2", "盗梦空间", "道姆·柯布是一名盗贼", genres = listOf("科幻", "动作"))),
            ),
            now = 5,
        )!!
        assertEquals("盗梦空间", merged.title)
        assertEquals("cn", merged.titleServer)
        assertEquals("道姆·柯布是一名盗贼", merged.overview)
        assertEquals(listOf("科幻", "动作"), merged.genres)
        assertEquals(2010, merged.year)
    }

    @Test fun `without any Chinese the first non-empty value by priority wins`() {
        val merged = MetaMerge.merge("k", listOf(MetaCandidate("b", 1, movie("2", "Second")), MetaCandidate("a", 0, movie("1", " "))), 0)!!
        assertEquals("Second", merged.title)
        assertEquals("b", merged.titleServer)
    }

    @Test fun `Chinese means Han characters are most of the letters`() {
        assertTrue(MetaMerge.isChinese("沙丘2"))
        assertTrue(MetaMerge.isChinese("X战警：逆转未来"))
        assertFalse(MetaMerge.isChinese("Dune: Part Two 沙"))
        assertFalse(MetaMerge.isChinese("2024"))
    }

    @Test fun `images take the most pixels, unknown sizes fall back to priority`() {
        val low = MetaCandidate("a", 0, movie("1", "x"), listOf(ImageInfo("Primary", null, "p", 400, 600), ImageInfo("Backdrop", 0, "b", 1280, 720)))
        val high = MetaCandidate("b", 1, movie("2", "x"), listOf(ImageInfo("Primary", null, "p", 2000, 3000), ImageInfo("Backdrop", 0, "b", 3840, 2160)))
        val unknown = MetaCandidate("c", 2, movie("3", "x"))
        val merged = MetaMerge.merge("k", listOf(unknown, low, high), 0)!!
        assertEquals(ImageRef("b", "2", "p", 2000, 3000), merged.primary)
        assertEquals("b", merged.backdrop!!.server)
        val noSizes = MetaMerge.merge("k", listOf(MetaCandidate("c", 2, movie("3", "x")), MetaCandidate("a", 0, movie("1", "x"))), 0)!!
        assertEquals("a", noSizes.primary!!.server)
    }

    @Test fun `a logo prefers a transparent PNG`() {
        val jpg = MetaCandidate("a", 0, movie("1", "x", logo = "l"), listOf(ImageInfo("Logo", null, "l", 2000, 600, "/m/logo.jpg")))
        val png = MetaCandidate("b", 1, movie("2", "x", logo = "l"), listOf(ImageInfo("Logo", null, "l", 800, 300, "/m/logo.png")))
        assertEquals("b", MetaMerge.merge("k", listOf(jpg, png), 0)!!.logo!!.server)
    }

    @Test fun `no candidates no row`() {
        assertNull(MetaMerge.merge("k", emptyList(), 0))
    }
}

class FreshnessTest {
    @Test fun `resume and next up are always refreshed, latest after ten minutes, browse after an hour`() {
        val now = 10_000_000L
        assertTrue(Freshness.due(Freshness.Kind.Resume, now - 1, now))
        assertTrue(Freshness.due(Freshness.Kind.NextUp, now - 1, now))
        assertFalse(Freshness.due(Freshness.Kind.Latest, now - 9 * 60_000, now))
        assertTrue(Freshness.due(Freshness.Kind.Latest, now - 10 * 60_000, now))
        assertFalse(Freshness.due(Freshness.Kind.Browse, now - 59 * 60_000, now))
        assertTrue(Freshness.due(Freshness.Kind.Browse, now - 60 * 60_000, now))
        assertTrue(Freshness.due(Freshness.Kind.Latest, null, now))
        assertTrue(Freshness.due(Freshness.Kind.Browse, 0, now))
    }

    @Test fun `excess rows over the cap`() {
        assertEquals(0, Freshness.excess(49_999))
        assertEquals(0, Freshness.excess(50_000))
        assertEquals(7, Freshness.excess(50_007))
    }
}
