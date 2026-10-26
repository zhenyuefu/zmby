package app.zmby.aggregate

import app.zmby.catalog.CatalogId
import app.zmby.catalog.CatalogWork
import app.zmby.emby.BaseItemDto
import app.zmby.emby.MediaSourceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationMergeTest {
    private fun movie(id: String, tmdb: String? = null, rating: Double? = null, sources: Int = 1) = BaseItemDto(
        id, name = id, type = "Movie", communityRating = rating,
        providerIds = tmdb?.let { mapOf("Tmdb" to it) }.orEmpty(), mediaSources = List(sources) { MediaSourceInfo("$id-$it") },
    )
    private fun work(id: String, rating: Double? = null) = CatalogWork(CatalogId("tmdb", "movie", id), "t$id", rating = rating, ids = mapOf("Tmdb" to id))

    @Test fun `server copies merge on external IDs and titles on more servers come first`() {
        val recs = RecommendationMerge.merge(
            listOf(
                "a" to listOf(movie("a1", "1", rating = 9.0), movie("a2", "2", rating = 7.0), movie("a3")),
                "b" to listOf(movie("b2", "2", rating = 7.0, sources = 2), movie("b3")),
            ),
            tmdb = emptyList(),
            exclude = emptySet(),
        )
        assertEquals(listOf("movie:tmdb:2", "movie:tmdb:1"), recs.take(2).map { it.key })
        assertEquals(3, recs.first().versions)
        // Without an external ID a title is never merged across servers.
        assertEquals(4, recs.size)
    }

    @Test fun `TMDB joins on the same key and TMDB-only titles follow in TMDB order`() {
        val recs = RecommendationMerge.merge(
            listOf("a" to listOf(movie("a1", "1", rating = 6.0), movie("a5", "5", rating = 8.0))),
            tmdb = listOf(work("9"), work("1", rating = 9.5), work("8")),
            exclude = emptySet(),
        )
        assertEquals(listOf("movie:tmdb:1", "movie:tmdb:5", "movie:tmdb:9", "movie:tmdb:8"), recs.map { it.key })
        assertTrue(recs[0].hasResource && recs[0].catalog != null)
        assertFalse(recs[2].hasResource)
    }

    @Test fun `the work on the page and anything past twenty are left out`() {
        val many = (1..30).map { movie("m$it", "$it") }
        val recs = RecommendationMerge.merge(listOf("a" to many), tmdb = listOf(work("40")), exclude = setOf("movie:tmdb:3"))
        assertEquals(20, recs.size)
        assertTrue(recs.none { it.key == "movie:tmdb:3" })
    }

    @Test fun `episodes and folders are not recommendations`() {
        val recs = RecommendationMerge.merge(listOf("a" to listOf(BaseItemDto("e", type = "Episode"), BaseItemDto("f", type = "Folder"))), emptyList(), emptySet())
        assertTrue(recs.isEmpty())
    }
}
