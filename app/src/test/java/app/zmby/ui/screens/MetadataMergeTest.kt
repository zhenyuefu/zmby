package app.zmby.ui.screens

import app.zmby.catalog.CatalogId
import app.zmby.catalog.CatalogWork
import app.zmby.data.MemoryRegistryStore
import app.zmby.data.ServerConfig
import app.zmby.data.ServerItem
import app.zmby.data.ServerLine
import app.zmby.data.ServerRegistry
import app.zmby.data.SourceFeature
import app.zmby.data.SourceRules
import app.zmby.emby.BaseItemDto
import app.zmby.store.DataSourceRow
import org.junit.Assert.assertEquals
import org.junit.Test

/** 影片信息来源 field by field (统一数据层 ④). */
class MetadataMergeTest {
    private fun config(id: String) = ServerConfig(id, id.uppercase(), listOf(ServerLine("l", "主线路", "http://$id")), "l", "u", "t")
    private val registry = ServerRegistry(MemoryRegistryStore(listOf(config("a"), config("b"))))
    private val tmdb = CatalogWork(CatalogId("tmdb", "movie", "1"), "沙丘2", overview = "", year = 2024, rating = 8.1, backdrop = "tmdb-backdrop")
    private val a = ServerItem("a", BaseItemDto(id = "1", name = "Dune 2", overview = "A 的简介", productionYear = 2024, genres = listOf("科幻")))
    private val b = ServerItem("b", BaseItemDto(id = "9", name = "沙丘 第二部", overview = "B 的简介"))
    private fun display(ref: ServerItem) = DetailDisplay(ref.item.name.orEmpty(), ref.item.overview, ref.item.genres, ref.item.productionYear, null, ref.item.communityRating, false, "${ref.server}-backdrop", null)
    private fun rows(vararg r: DataSourceRow) = r.associateBy { SourceRules.rowKey(it.feature, it.source) }

    @Test fun `by default TMDB comes first and empty fields fall through to the servers in order`() {
        val order = SourceRules.metadata(emptyMap(), registry.all)
        assertEquals(listOf("tmdb", "bangumi", "a", "b"), order.map { it.key })
        val d = MetadataMerge.display(order, tmdb, listOf(a, b), ::display)
        assertEquals("沙丘2", d.title)
        assertEquals("A 的简介", d.overview)
        assertEquals(listOf("科幻"), d.genres)
        assertEquals(8.1, d.rating)
        assertEquals("tmdb-backdrop", d.backdrop)
    }

    @Test fun `a server put first or TMDB switched off changes which source each field comes from`() {
        var r = rows()
        r = r + SourceRules.moveMetadata(r, registry.all, "b", -1).associateBy { SourceRules.rowKey(it.feature, it.source) }
        r = r + SourceRules.moveMetadata(r, registry.all, "b", -1).associateBy { SourceRules.rowKey(it.feature, it.source) }
        r = r + SourceRules.moveMetadata(r, registry.all, "b", -1).associateBy { SourceRules.rowKey(it.feature, it.source) }
        assertEquals(listOf("b", "tmdb", "bangumi", "a"), SourceRules.metadata(r, registry.all).map { it.key })
        val d = MetadataMerge.display(SourceRules.metadata(r, registry.all), tmdb, listOf(a, b), ::display)
        assertEquals("沙丘 第二部", d.title)
        assertEquals("B 的简介", d.overview)
        assertEquals(2024, d.year)
        val off = rows(DataSourceRow(SourceFeature.Metadata.key, "tmdb", false))
        assertEquals("Dune 2", MetadataMerge.display(SourceRules.metadata(off, registry.all), tmdb, listOf(a, b), ::display).title)
    }

    @Test fun `with every source off the page's own copy still shows`() {
        val all = rows(*listOf("tmdb", "bangumi", "a", "b").map { DataSourceRow(SourceFeature.Metadata.key, it, false) }.toTypedArray())
        assertEquals("Dune 2", MetadataMerge.display(SourceRules.metadata(all, registry.all), tmdb, listOf(a, b), ::display).title)
    }

    private val bangumi = CatalogWork(CatalogId("bangumi", "movie", "5"), "沙丘：第二部", overview = "Bangumi 的简介", rating = 7.9,
        cast = listOf(app.zmby.catalog.CatalogPerson("bangumi", "7", "声优")))
    private val withPeople = ServerItem("a", a.item.copy(people = listOf(app.zmby.emby.PersonDto(id = "p", name = "演员", type = "Actor"))))

    @Test fun `the other catalogue's entry fills the fields the page's own work leaves empty`() {
        val order = SourceRules.metadata(emptyMap(), registry.all)
        val d = MetadataMerge.display(order, listOf(tmdb, bangumi), listOf(a, b), ::display)
        assertEquals("沙丘2", d.title)
        assertEquals("Bangumi 的简介", d.overview)
        assertEquals("tmdb-backdrop", d.backdrop)
        // A Bangumi page gets TMDB's backdrop when TMDB's entry is loaded.
        val bgmPage = MetadataMerge.display(order, listOf(bangumi, tmdb), listOf(a), ::display)
        assertEquals("沙丘2", bgmPage.title)
        assertEquals("tmdb-backdrop", bgmPage.backdrop)
    }

    @Test fun `cast comes whole from the first source in order that has people`() {
        val order = SourceRules.metadata(emptyMap(), registry.all)
        // TMDB has no cast here, Bangumi does.
        assertEquals(MetadataMerge.Cast.Catalog(bangumi.cast), MetadataMerge.cast(order, listOf(tmdb, bangumi), listOf(withPeople)))
        // Bangumi switched off: the server copy's People.
        val noBgm = SourceRules.metadata(rows(DataSourceRow(SourceFeature.Metadata.key, "bangumi", false)), registry.all)
        assertEquals(MetadataMerge.Cast.Server(withPeople), MetadataMerge.cast(noBgm, listOf(tmdb, bangumi), listOf(withPeople)))
        // Nothing anywhere.
        assertEquals(MetadataMerge.Cast.None, MetadataMerge.cast(order, listOf(tmdb), listOf(a)))
    }

    @Test fun `the other catalogue is loaded only when it is on and may add something`() {
        val order = SourceRules.metadata(emptyMap(), registry.all)
        val complete = tmdb.copy(overview = "简介", genres = listOf("科幻"), cast = bangumi.cast)
        assertEquals(false, MetadataMerge.wantsCompanion(order, complete))
        assertEquals(true, MetadataMerge.wantsCompanion(order, tmdb))
        // A Bangumi work never has TMDB's backdrop and logo.
        assertEquals(true, MetadataMerge.wantsCompanion(order, bangumi))
        val off = SourceRules.metadata(rows(DataSourceRow(SourceFeature.Metadata.key, "bangumi", false)), registry.all)
        assertEquals(false, MetadataMerge.wantsCompanion(off, tmdb))
        // Bangumi put first: worth loading even for a complete TMDB work.
        var r = rows()
        r = r + SourceRules.moveMetadata(r, registry.all, "bangumi", -1).associateBy { SourceRules.rowKey(it.feature, it.source) }
        assertEquals(true, MetadataMerge.wantsCompanion(SourceRules.metadata(r, registry.all), complete))
    }

    @Test fun `a person opens TMDB's page only when TMDB comes before the person's server`() {
        val order = SourceRules.metadata(emptyMap(), registry.all)
        assertEquals(true, MetadataMerge.personFromTmdb(order, "a"))
        var r = rows()
        repeat(2) { r = r + SourceRules.moveMetadata(r, registry.all, "a", -1).associateBy { SourceRules.rowKey(it.feature, it.source) } }
        assertEquals(false, MetadataMerge.personFromTmdb(SourceRules.metadata(r, registry.all), "a"))
        assertEquals(true, MetadataMerge.personFromTmdb(SourceRules.metadata(r, registry.all), "b"))
        val off = SourceRules.metadata(rows(DataSourceRow(SourceFeature.Metadata.key, "tmdb", false)), registry.all)
        assertEquals(false, MetadataMerge.personFromTmdb(off, "b"))
    }

    @Test fun `episode rows take each field from the first source in order, ignoring number-only names`() {
        val catalog = EpisodeFields.Fields("TMDB 标题", "TMDB 简介", "tmdb-still")
        val servers = mapOf("a" to EpisodeFields.Fields(null, "A 的简介", "a-still"), "b" to EpisodeFields.Fields("B 标题", null, null))
        val order = SourceRules.metadata(emptyMap(), registry.all)
        assertEquals(catalog, EpisodeFields.pick(order, "tmdb", catalog) { servers[it] })
        var r = rows()
        repeat(2) { r = r + SourceRules.moveMetadata(r, registry.all, "a", -1).associateBy { SourceRules.rowKey(it.feature, it.source) } }
        // a first: its overview and still; a has no name of its own (only the number), so TMDB's.
        assertEquals(EpisodeFields.Fields("TMDB 标题", "A 的简介", "a-still"), EpisodeFields.pick(SourceRules.metadata(r, registry.all), "tmdb", catalog) { servers[it] })
        // Nothing loaded yet: the catalogue's fields.
        assertEquals(catalog, EpisodeFields.pick(emptyList(), "tmdb", catalog) { servers[it] })
    }
}
