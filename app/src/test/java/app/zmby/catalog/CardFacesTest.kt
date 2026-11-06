package app.zmby.catalog

import app.zmby.data.MemoryRegistryStore
import app.zmby.data.ServerConfig
import app.zmby.data.ServerItem
import app.zmby.data.ServerLine
import app.zmby.data.ServerRegistry
import app.zmby.data.SourceFeature
import app.zmby.data.SourceRules
import app.zmby.emby.BaseItemDto
import app.zmby.store.DataSourceRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 列表卡片按影片信息来源取值 (统一数据层 ④): only works already on the device, by 影片信息来源. */
class CardFacesTest {
    private fun config(id: String) = ServerConfig(id, id.uppercase(), listOf(ServerLine("l", "主线路", "http://$id")), "l", "u", "t")
    private val registry = ServerRegistry(MemoryRegistryStore(listOf(config("a"), config("b"))))
    private fun rows(vararg r: DataSourceRow) = r.associateBy { SourceRules.rowKey(it.feature, it.source) }
    private val movie = ServerItem("a", BaseItemDto(id = "1", name = "Dune 2", type = "Movie", providerIds = mapOf("Tmdb" to "693134")))
    private val anime = ServerItem("a", BaseItemDto(id = "2", name = "Frieren", type = "Series", providerIds = mapOf("Tmdb" to "209867", "Bangumi" to "400602")))

    private class MemoryCache : CatalogStorage {
        val rows = mutableMapOf<String, CatalogCache>()
        override suspend fun get(key: String) = rows[key]
        override suspend fun put(row: CatalogCache) { rows[row.key] = row }
        override suspend fun prune(before: Long) {}
    }

    private fun faces(cache: CatalogStorage, scope: CoroutineScope) = CardFaces(cache, Json { ignoreUnknownKeys = true }, scope, { "zh-CN:zh-CN" })

    @Test fun `a loaded TMDB work gives cards its title and poster, other works keep their own`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val f = faces(MemoryCache(), scope)
        f.order = SourceRules.metadata(emptyMap(), registry.all)
        f.load()
        assertNull(f.face(movie))
        f.remember(CatalogWork(CatalogId("tmdb", "movie", "693134"), "沙丘2", poster = "https://img/dune.jpg"))
        assertEquals(CardFace("沙丘2", "https://img/dune.jpg"), f.face(movie))
        assertNull(f.face(movie.copy(item = movie.item.copy(type = "Episode"))))
        assertEquals(CardFace("沙丘2", "https://img/dune.jpg"), f.faceOfWork("a", "movie:tmdb:693134"))
        assertNull(f.faceOfWork("a", "movie:imdb:tt1"))
        scope.cancel()
    }

    @Test fun `field by field in order, and the card's own server first ends the search`() {
        val tmdb = mapOf("tmdb" to CardFace("芙莉莲 TMDB", "tmdb-poster"), "bangumi" to CardFace("葬送的芙莉莲", null))
        var r = rows()
        // Bangumi before TMDB: its title, TMDB's poster (Bangumi has none).
        r = r + SourceRules.moveMetadata(r, registry.all, "bangumi", -1).associateBy { SourceRules.rowKey(it.feature, it.source) }
        assertEquals(CardFace("葬送的芙莉莲", "tmdb-poster"), CardFaces.pick(SourceRules.metadata(r, registry.all), "a") { tmdb[it] })
        // Server a first: a's own card fields.
        var s = rows()
        repeat(2) { s = s + SourceRules.moveMetadata(s, registry.all, "a", -1).associateBy { SourceRules.rowKey(it.feature, it.source) } }
        assertEquals(listOf("a", "tmdb", "bangumi", "b"), SourceRules.metadata(s, registry.all).map { it.key })
        assertNull(CardFaces.pick(SourceRules.metadata(s, registry.all), "a") { tmdb[it] })
        // …but a card from b still takes TMDB (a's copy is not known to b's card).
        assertEquals(CardFace("芙莉莲 TMDB", "tmdb-poster"), CardFaces.pick(SourceRules.metadata(s, registry.all), "b") { tmdb[it] })
        // Catalogues switched off: own fields.
        val off = rows(DataSourceRow(SourceFeature.Metadata.key, "tmdb", false), DataSourceRow(SourceFeature.Metadata.key, "bangumi", false))
        assertNull(CardFaces.pick(SourceRules.metadata(off, registry.all), "a") { tmdb[it] })
    }

    @Test fun `faces are kept per language and read back after a restart`() = runBlocking {
        val cache = MemoryCache()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val first = faces(cache, scope)
        first.load()
        first.remember(CatalogWork(CatalogId("bangumi", "tv", "400602"), "葬送的芙莉莲"))
        var waited = 0
        while (cache.rows.isEmpty() && waited < 50) { delay(100); waited++ }
        assertTrue(cache.rows.keys.single().startsWith("cardfaces:v1:zh-CN:zh-CN"))
        val second = faces(cache, scope)
        second.order = SourceRules.metadata(emptyMap(), registry.all)
        second.load()
        assertEquals(CardFace("葬送的芙莉莲", null), second.face(anime))
        scope.cancel()
    }
}
