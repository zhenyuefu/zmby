package app.zmby.catalog

import app.zmby.data.ServerItem
import app.zmby.emby.BaseItemDto
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CatalogRulesTest {
    private fun image(language: String?, path: String, region: String? = null, rating: Double = 9.0) = buildJsonObject {
        put("iso_639_1", language?.let(::JsonPrimitive) ?: JsonNull); put("file_path", path)
        region?.let { put("iso_3166_1", it) }; put("vote_average", rating)
    }
    @Test fun `Chinese region beats traditional and generic Chinese even at a lower rating`() {
        val images = listOf(image("zh", "/traditional", "TW"), image("zh", "/unknown"), image("zh", "/simplified", "CN", 1.0), image("ja", "/original"))
        assertEquals("/simplified", ArtworkLanguage.choose(images, "zh-CN", "ja"))
    }
    @Test fun `missing simplified falls back to original language without accepting TW or generic zh`() {
        assertEquals("/original", ArtworkLanguage.choose(listOf(image("zh-TW", "/tw"), image("zh", "/unknown"), image("ja", "/original")), "zh-CN", "ja"))
    }
    @Test fun `script tags are supported but regions of the same script are not silently substituted`() {
        assertEquals("/hans", ArtworkLanguage.choose(listOf(image("zh-Hans", "/hans"), image("zh-Hant", "/hant")), "zh-CN", "en"))
        assertNull(ArtworkLanguage.choose(listOf(image("zh-HK", "/hk")), "zh-TW", "ja"))
    }
    @Test fun `no fallback language means no arbitrary poster`() {
        assertNull(ArtworkLanguage.choose(listOf(image("en", "/english")), "zh-CN", "ja"))
        assertEquals("/neutral", ArtworkLanguage.choose(listOf(image(null, "/neutral")), "zh-CN", "ja", neutral = true))
        assertNull(ArtworkLanguage.choose(listOf(image(null, "/neutral")), "zh-CN", "ja"))
    }
    @Test fun `original language fallback accepts its regional artwork`() {
        assertEquals("/fr", ArtworkLanguage.choose(listOf(image("fr", "/fr", "FR")), "zh-CN", "fr"))
    }
    private val work = CatalogWork(CatalogId("tmdb", "tv", "10"), "Test", ids = mapOf("Tmdb" to "10"))
    private val catalog = listOf(CatalogEpisode("101", 1, 1, "One"), CatalogEpisode("102", 1, 2, "Two"), CatalogEpisode("103", 1, 3, "Three"))
    private fun resource(server: String, id: String, number: Int?, season: Int? = 1, tmdb: String? = null) = ServerItem(server,
        BaseItemDto(id, type = "Episode", seriesId = "series", parentIndexNumber = season, indexNumber = number, providerIds = tmdb?.let { mapOf("Tmdb" to it) }.orEmpty()))
    @Test fun `incomplete servers fill one directory and missing resources remain in catalogue`() {
        val mapped = ResourceMapping.episodes(work, catalog, listOf(ResourceBatch("a", listOf(resource("a", "a1", 1)), true), ResourceBatch("b", listOf(resource("b", "b2", 2)), true)))
        assertEquals("a", mapped.matched["101"]!!.single().server)
        assertEquals("b", mapped.matched["102"]!!.single().server)
        assertFalse(mapped.matched.containsKey("103"))
        assertEquals(3, catalog.size)
    }
    @Test fun `extras unknown numbers conflicting episode IDs and duplicates are retained separately`() {
        val resources = listOf(resource("a", "extra", 99), resource("a", "unnumbered", null), resource("a", "wrong", 3, tmdb = "999"), resource("a", "dup1", 2), resource("a", "dup2", 2), resource("a", "good", 1))
        val mapped = ResourceMapping.episodes(work, catalog, listOf(ResourceBatch("a", resources, true)))
        assertEquals(setOf("101"), mapped.matched.keys)
        assertEquals(setOf("extra", "unnumbered", "wrong", "dup1", "dup2"), mapped.unmatched.map { it.item.id }.toSet())
    }
    @Test fun `unverified series never borrows a numbered slot`() {
        val mapped = ResourceMapping.episodes(work, catalog, listOf(ResourceBatch("a", listOf(resource("a", "guess", 1)), false)))
        assertTrue(mapped.matched.isEmpty()); assertEquals("guess", mapped.unmatched.single().item.id)
    }
    @Test fun `Bangumi numbering does not imply a TMDB mapping`() {
        val bgm = work.copy(ref = CatalogId("bangumi", "tv", "20"))
        assertTrue(ResourceMapping.episodes(bgm, catalog, listOf(ResourceBatch("a", listOf(resource("a", "guess", 1)), true))).matched.isEmpty())
    }
    @Test fun `external identity requires type shared ID and no conflicts`() {
        assertTrue(ResourceMapping.same(work.item(), BaseItemDto("server", type = "Series", providerIds = mapOf("Tmdb" to "10"))))
        assertFalse(ResourceMapping.same(work.item(), BaseItemDto("server", type = "Movie", providerIds = mapOf("Tmdb" to "10"))))
        assertFalse(ResourceMapping.same(work.item(), BaseItemDto("server", name = "Test", type = "Series")))
        assertFalse(ResourceMapping.same(work.item().copy(providerIds = mapOf("Tmdb" to "10", "Imdb" to "tt1")), BaseItemDto("server", type = "Series", providerIds = mapOf("Tmdb" to "10", "Imdb" to "tt2"))))
    }
    @Test fun `published bangumi-data object exposes its items without losing aliases`() {
        val data = Json.parseToJsonElement("""{"siteMeta":{},"items":[{"title":"Anime","sites":[{"site":"bangumi","id":"12"}]}]}""")
        val rows = AnimeAliases.dataset(data)!!
        assertEquals("12", AnimeAliases.subject(AnimeAliases.find(rows, listOf("Anime"), null)!!))
        assertEquals(rows, AnimeAliases.dataset(rows))
    }
    @Test fun `anime aliases match original simplified or traditional without merging ambiguous subjects`() {
        val row = Json.parseToJsonElement("""{"title":"原名","titleTranslate":{"zh-Hans":["简体名"],"zh-Hant":["繁體名"]},"begin":"2024-01-01","sites":[{"site":"bangumi","id":"12"}]}""").jsonObject
        assertEquals("12", AnimeAliases.subject(AnimeAliases.find(JsonArray(listOf(row)), listOf("简体名"), 2024)!!))
        assertNull(AnimeAliases.find(JsonArray(listOf(row)), listOf("简体名"), 2023))
        val other = row + ("sites" to Json.parseToJsonElement("""[{"site":"bangumi","id":"13"}]"""))
        assertNull(AnimeAliases.find(JsonArray(listOf(row, JsonObject(other))), listOf("原名"), null))
    }
}
