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
    @Test fun `extras and conflicting IDs stay separate while duplicate slots are playable versions`() {
        val resources = listOf(resource("a", "extra", 99), resource("a", "unnumbered", null), resource("a", "wrong", 3, tmdb = "999"), resource("a", "dup1", 2), resource("a", "dup2", 2), resource("a", "good", 1))
        val mapped = ResourceMapping.episodes(work, catalog, listOf(ResourceBatch("a", resources, true)))
        assertEquals(setOf("101", "102"), mapped.matched.keys)
        assertEquals(setOf("dup1", "dup2"), mapped.matched["102"]!!.map { it.item.id }.toSet())
        assertEquals(setOf("extra", "unnumbered", "wrong"), mapped.unmatched.map { it.item.id }.toSet())
    }
    @Test fun `inherited show ID does not block valid episode numbers`() {
        val mapped = ResourceMapping.episodes(work, catalog, listOf(ResourceBatch("a", listOf(resource("a", "episode", 2, tmdb = "10")), true)))
        assertEquals("episode", mapped.matched["102"]!!.single().item.id)
        assertTrue(mapped.unmatched.isEmpty())
    }
    @Test fun `a real catalog episode ID still wins when its number equals the series ID`() {
        val mapped = ResourceMapping.episodes(work, listOf(CatalogEpisode("10", 1, 1, "One")),
            listOf(ResourceBatch("a", listOf(resource("a", "episode", null, season = null, tmdb = "10")), true)))
        assertEquals("episode", mapped.matched["10"]!!.single().item.id)
        assertTrue(mapped.unmatched.isEmpty())
    }
    @Test fun `invalid season episode coordinates are never shifted to fit the catalog`() {
        val refs = listOf(resource("a", "season", 1, season = 99), resource("a", "zero", 0), resource("a", "negative", -1))
        val mapped = ResourceMapping.episodes(work, catalog, listOf(ResourceBatch("a", refs, true)))
        assertTrue(mapped.matched.isEmpty())
        assertEquals(3, mapped.unmatched.size)
    }
    @Test fun `unverified series never borrows a numbered slot`() {
        val mapped = ResourceMapping.episodes(work, catalog, listOf(ResourceBatch("a", listOf(resource("a", "guess", 1)), false)))
        assertTrue(mapped.matched.isEmpty()); assertEquals("guess", mapped.unmatched.single().item.id)
    }

    @Test fun `whole series mapping separates genuine extras from resources awaiting another season directory`() {
        val first = CatalogEpisode("101", 1, 1, "One")
        val second = CatalogEpisode("201", 2, 1, "Two")
        val refs = listOf(resource("a", "first", 1), resource("a", "second", 1, season = 2),
            resource("a", "extra-first", 99), resource("a", "extra-second", 99, season = 2))
        val batches = listOf(ResourceBatch("a", refs, true))
        val partial = ResourceMapping.episodes(work, listOf(first), batches, pendingSeasons = setOf(2))
        assertEquals(listOf("extra-first"), partial.unmatched.map { it.item.id })
        assertEquals(setOf("second", "extra-second"), partial.awaitingCatalog.map { it.item.id }.toSet())
        val complete = ResourceMapping.episodes(work, listOf(first, second), batches)
        assertEquals(setOf("101", "201"), complete.matched.keys)
        assertEquals(setOf("extra-first", "extra-second"), complete.unmatched.map { it.item.id }.toSet())
        assertTrue(complete.awaitingCatalog.isEmpty())
    }

    @Test fun `an episode ID without coordinates waits for all season directories but conflicts are not matched by number`() {
        val ref = resource("a", "id-only", null, season = null, tmdb = "201")
        val batch = listOf(ResourceBatch("a", listOf(ref), true))
        val partial = ResourceMapping.episodes(work, catalog, batch, pendingSeasons = setOf(2))
        assertEquals(listOf(ref), partial.awaitingCatalog)
        assertTrue(partial.unmatched.isEmpty())
        val complete = ResourceMapping.episodes(work, catalog + CatalogEpisode("201", 2, 1, "Two"), batch)
        assertEquals(listOf(ref), complete.matched["201"])
        val conflict = resource("a", "wrong-id", 1, tmdb = "999")
        val pending = ResourceMapping.episodes(work, catalog, listOf(ResourceBatch("a", listOf(conflict), true)), pendingSeasons = setOf(2))
        assertTrue(pending.matched.isEmpty())
        assertEquals(listOf(conflict), pending.awaitingCatalog)
        assertEquals("外部 ID 对不上目录", ResourceMapping.episodes(work, catalog,
            listOf(ResourceBatch("a", listOf(conflict), true))).reasons[conflict.key])
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
    private fun index(text: String) = AnimeIndex.decode(text.byteInputStream())
    @Test fun `published bangumi-data object and plain arrays both index their aliases`() {
        assertEquals("12", index("""{"siteMeta":{"x":1},"items":[{"title":"Anime","sites":[{"site":"bangumi","id":"12"}]}]}""").find(listOf("Anime"), null))
        assertEquals("12", index("""  [{"title":"Anime","sites":[{"site":"bangumi","id":"12"}]}]""").find(listOf("anime!"), null))
    }
    @Test fun `anime aliases match original simplified or traditional without merging ambiguous subjects`() {
        val row = """{"title":"原名","titleTranslate":{"zh-Hans":["简体名"],"zh-Hant":["繁體名"]},"begin":"2024-01-01","sites":[{"site":"bangumi","id":"12"}]}"""
        val one = index("[$row]")
        assertEquals("12", one.find(listOf("简体名"), 2024))
        assertEquals("12", one.find(listOf("繁體名"), null))
        assertNull(one.find(listOf("简体名"), 2023))
        val other = row.replace("\"id\":\"12\"", "\"id\":\"13\"")
        assertNull(index("[$row,$other]").find(listOf("原名"), null))
        // An item with no Bangumi entry still counts, so it cannot be silently skipped over.
        assertNull(index("""[$row,{"title":"原名","sites":[]}]""").find(listOf("原名"), null))
    }
    @Test fun `stored index reads back the same matches and rejects foreign files`() {
        val dir = java.nio.file.Files.createTempDirectory("anime").toFile()
        try {
            val file = java.io.File(dir, "index.txt")
            index("""[{"title":"原名","titleTranslate":{"zh-Hans":["简体名"]},"begin":"2024-01-01","sites":[{"site":"bangumi","id":"12"}]}]""").write(file, 42)
            val (at, read) = AnimeIndex.read(file)!!
            assertEquals(42L, at)
            assertEquals("12", read.find(listOf("简体名"), 2024))
            file.writeText("{\"not\":\"an index\"}")
            assertNull(AnimeIndex.read(file))
        } finally { dir.deleteRecursively() }
    }
    @Test fun `chart region is a country, never a script`() {
        assertEquals("CN", CatalogRegion.of("zh-CN"))
        assertEquals("CN", CatalogRegion.of("zh-Hans"))
        assertEquals("TW", CatalogRegion.of("zh-Hant"))
        assertEquals("HK", CatalogRegion.of("zh-Hant-HK"))
        assertEquals("JP", CatalogRegion.of("ja"))
        assertEquals("US", CatalogRegion.of("en"))
        assertEquals("US", CatalogRegion.of("xx"))
    }
}
