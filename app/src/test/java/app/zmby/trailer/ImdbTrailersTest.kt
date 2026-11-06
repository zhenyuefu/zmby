package app.zmby.trailer

import app.zmby.emby.BaseItemDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Reading IMDb's GraphQL answer (trailer design); links are made-up example.com URLs. */
class ImdbTrailersTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val now = 1_790_000_000_000L

    private fun link(name: String, expires: Long? = 1_790_086_400) =
        "https://video.example.com/$name.mp4" + (expires?.let { "?Expires=$it&Signature=abc~def__&Key-Pair-Id=K1" } ?: "")

    private fun video(id: String, type: String = "trailer", vararg urls: Pair<String, String>, runtime: Int? = 31) = """
        {"id":"$id","contentType":{"id":"amzn1.imdb.video.contenttype.$type"},"runtime":${runtime?.let { "{\"value\":$it}" } ?: "null"},
         "playbackURLs":[${urls.joinToString(",") { (def, url) -> "{\"url\":\"$url\",\"videoMimeType\":\"${if (def == "DEF_AUTO") "M3U8" else "MP4"}\",\"videoDefinition\":\"$def\"}" }}]}
    """.trimIndent()

    private fun answer(latest: String?, vararg primary: String) =
        """{"data":{"title":{"latestTrailer":${latest ?: "null"},"primaryVideos":{"edges":[${primary.joinToString(",") { "{\"node\":$it}" }}]}}}}"""

    @Test
    fun picksTheTallestMp4UpToTheScreen() {
        val body = answer(video("vi1", "trailer", "DEF_1080p" to link("1080"), "DEF_AUTO" to "https://video.example.com/hls.m3u8",
            "DEF_SD" to link("sd"), "DEF_720p" to link("720"), "DEF_480p" to link("480")))
        val full = ImdbTrailers.parse(json, body, 1080, now)!!
        assertEquals("vi1", full.videoId)
        assertEquals(1080, full.height)
        assertEquals(link("1080"), full.url)
        assertEquals(31, full.runtimeSec)
        assertEquals(1_790_086_400_000L, full.expiresAt)
        assertEquals("imdb:vi1:1080", full.cacheKey)
        assertEquals(720, ImdbTrailers.parse(json, body, 800, now)!!.height)
        // A screen smaller than every copy gets the smallest one.
        assertEquals(360, ImdbTrailers.parse(json, body, 240, now)!!.height)
    }

    @Test
    fun fallsBackToTheFirstTrailerOfTheTitlesVideos() {
        val hlsOnly = video("vi1", "trailer", "DEF_AUTO" to "https://video.example.com/hls.m3u8")
        val clip = video("vi2", "clip", "DEF_1080p" to link("clip"))
        val trailer = video("vi3", "trailer", "DEF_720p" to link("t3"))
        val picked = ImdbTrailers.parse(json, answer(hlsOnly, clip, trailer), 1080, now)!!
        assertEquals("vi3", picked.videoId)
        assertEquals(720, picked.height)
    }

    @Test
    fun noTrailerGivesNull() {
        assertNull(ImdbTrailers.parse(json, answer(null), 1080, now))
        assertNull(ImdbTrailers.parse(json, answer(null, video("vi2", "clip", "DEF_1080p" to link("clip"))), 1080, now))
        assertNull(ImdbTrailers.parse(json, """{"data":{"title":null}}""", 1080, now))
        assertNull(ImdbTrailers.parse(json, """{"errors":[{"message":"bad"}]}""", 1080, now))
        // Only https links are played.
        assertNull(ImdbTrailers.parse(json, answer(video("vi1", "trailer", "DEF_1080p" to "http://video.example.com/a.mp4")), 1080, now))
    }

    @Test
    fun aLinkWithoutExpiresIsUsedForSixHours() {
        val body = answer(video("vi1", "trailer", "DEF_720p" to link("720", expires = null), runtime = null))
        val info = ImdbTrailers.parse(json, body, 1080, now)!!
        assertEquals(now + 6 * 60 * 60_000L, info.expiresAt)
        assertNull(info.runtimeSec)
    }

    @Test
    fun imdbIdsOfFilmsAndSeriesOnly() {
        assertEquals("tt1234567", ImdbTrailers.normalize("TT1234567"))
        assertEquals("tt1234567", ImdbTrailers.normalize("https://www.imdb.com/title/tt1234567/"))
        assertNull(ImdbTrailers.normalize("1234567"))
        assertNull(ImdbTrailers.normalize(null))
        val movie = BaseItemDto(id = "1", type = "Movie", providerIds = mapOf("Imdb" to "tt0111161"))
        val series = BaseItemDto(id = "2", type = "Series", providerIds = mapOf("IMDB" to "tt0903747"))
        val episode = BaseItemDto(id = "3", type = "Episode", providerIds = mapOf("Imdb" to "tt0959621"))
        assertEquals("tt0111161", ImdbTrailers.idOf(movie))
        assertEquals("tt0903747", ImdbTrailers.idOf(series))
        assertNull(ImdbTrailers.idOf(episode))
        assertNull(ImdbTrailers.idOf(BaseItemDto(id = "4", type = "Movie")))
        assertEquals("tt0903747", ImdbTrailers.firstId(listOf(null, episode, BaseItemDto(id = "4", type = "Movie"), series, movie)))
    }

    @Test
    fun ratingFromTheSameAnswer() {
        assertEquals(8.4 to 804343, ImdbTrailers.rating(json, """{"data":{"title":{"ratingsSummary":{"aggregateRating":8.4,"voteCount":804343}}}}"""))
        assertNull(ImdbTrailers.rating(json, """{"data":{"title":{"ratingsSummary":{"aggregateRating":null,"voteCount":0}}}}"""))
        assertNull(ImdbTrailers.rating(json, """{"data":{"title":null}}"""))
    }

    @Test
    fun requestCarriesOnlyTheImdbId() {
        val body = json.parseToJsonElement(ImdbTrailers.requestBody("tt0111161")).jsonObject
        assertEquals(setOf("query", "variables"), body.keys)
        assertEquals("tt0111161", body["variables"]!!.jsonObject["id"]!!.jsonPrimitive.content)
    }
}
