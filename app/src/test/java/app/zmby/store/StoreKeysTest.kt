package app.zmby.store

import app.zmby.emby.BaseItemDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StoreKeysTest {
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
}
