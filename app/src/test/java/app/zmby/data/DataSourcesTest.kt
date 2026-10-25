package app.zmby.data

import app.zmby.store.DataSourceRow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataSourcesTest {
    private fun config(id: String, aggregate: Boolean = true, enabled: Boolean = true) =
        ServerConfig(id, id.uppercase(), listOf(ServerLine("l", "主线路", "http://$id")), "l", "u", "t", enabled = enabled, aggregate = aggregate)

    @Test fun `row values say 全部, n 台, n 个 or 关闭 and never use a slash`() {
        assertEquals("全部", SourceRules.label(11, 11))
        assertEquals("10 台", SourceRules.label(10, 11))
        assertEquals("关闭", SourceRules.label(0, 11))
        assertEquals("3 个", SourceRules.label(3, 5, providers = true))
        for (s in listOf(SourceRules.label(10, 11), SourceRules.label(3, 5, true))) assertFalse(s.contains('/'))
    }

    @Test fun `fan-out scope is the source and the enabled servers, in priority order`() {
        val registry = ServerRegistry(MemoryRegistryStore(listOf(config("a"), config("b"), config("c", enabled = false))))
        val rows = listOf(DataSourceRow(SourceFeature.Search.key, "a", enabled = false)).associateBy { SourceRules.rowKey(it.feature, it.source) }
        assertEquals(listOf("b"), SourceRules.scope(rows, SourceFeature.Search, registry.all))
        assertEquals(listOf("a", "b"), SourceRules.scope(rows, SourceFeature.Metadata, registry.all))
    }

    @Test fun `the old 允许聚合 switch carries over to aggregation, search and watch history`() {
        val rows = SourceRules.migrate(listOf(config("a"), config("b", aggregate = false)))
        assertEquals(setOf("aggregate", "search", "watch"), rows.map { it.feature }.toSet())
        assertTrue(rows.all { it.source == "b" && !it.enabled })
    }

    @Test fun `providers default to local, Emby, TheIntroDB, IntroDB and PublicMetaDB needs a key`() {
        val none = SourceRules.providers(emptyMap()) { false }
        assertEquals(listOf("本地手动", "Emby", "TheIntroDB", "IntroDB", "PublicMetaDB"), none.map { it.provider.label })
        assertEquals(listOf(true, true, true, true, false), none.map { it.enabled })
        val stored = mapOf(SourceRules.rowKey("segments", "publicmetadb") to DataSourceRow("segments", "publicmetadb", true, 0))
        assertFalse("no key, still off", SourceRules.providers(stored) { false }.first { it.provider == SegmentProvider.PublicMetaDB }.enabled)
        val withKey = SourceRules.providers(stored) { true }
        assertEquals(SegmentProvider.PublicMetaDB, withKey.first().provider)
        assertTrue(withKey.first().enabled)
    }

    @Test fun `moving a provider renumbers the list and stops at the ends`() {
        val list = SourceRules.providers(emptyMap()) { false }
        val moved = SourceRules.move(list, SegmentProvider.IntroDB, -1)
        assertEquals(listOf("local", "emby", "introdb", "theintrodb", "publicmetadb"), moved.map { it.provider.key })
        assertEquals((0..4).toList(), moved.map { it.order })
        assertEquals(list.map { it.provider }, SourceRules.move(list, SegmentProvider.Local, -1).map { it.provider })
    }

    @Test fun `DataSources keeps choices in memory, persists them, and migrates once`() = runBlocking {
        val registry = ServerRegistry(MemoryRegistryStore(listOf(config("a"), config("b", aggregate = false))))
        val saved = mutableListOf<DataSourceRow>()
        val sources = DataSources(registry, persist = { saved += it }, providerKey = { null })
        var marked = false
        sources.load(emptyList(), migrated = false) { marked = true }
        assertTrue(marked)
        assertEquals(listOf("a"), sources.servers(SourceFeature.Aggregate))
        assertEquals(listOf("a", "b"), sources.servers(SourceFeature.Favorites))
        assertEquals("1 台", sources.label(SourceFeature.Search))
        sources.set(SourceFeature.Search, "b", true)
        assertEquals("全部", sources.label(SourceFeature.Search))
        assertTrue(saved.any { it.feature == "search" && it.source == "b" && it.enabled })
        assertEquals("4 个", sources.label(SourceFeature.Segments))
    }
}
