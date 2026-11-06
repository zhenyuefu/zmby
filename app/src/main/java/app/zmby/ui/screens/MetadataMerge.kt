package app.zmby.ui.screens

import app.zmby.catalog.CatalogPerson
import app.zmby.catalog.CatalogWork
import app.zmby.data.ServerItem
import app.zmby.data.SourceRules

/**
 * The detail page's metadata field by field from 影片信息来源 in its order (统一数据层 ④): each
 * field takes the first source switched on there that has it. [catalogs] are the catalogue entries the
 * page loaded (the page's own TMDB or Bangumi work first, then the other catalogue's entry for the same
 * work when there is one), [copies] the servers' (the page's own copy first). With every source off, or
 * none having a field, the page's own copy and then its own catalogue work are used, so a page never
 * goes blank.
 */
internal object MetadataMerge {
    fun display(order: List<SourceRules.MetaSource>, canonical: CatalogWork?, copies: List<ServerItem>, server: (ServerItem) -> DetailDisplay): DetailDisplay =
        display(order, listOfNotNull(canonical), copies, server)

    fun display(order: List<SourceRules.MetaSource>, catalogs: List<CatalogWork>, copies: List<ServerItem>, server: (ServerItem) -> DetailDisplay): DetailDisplay {
        val byCatalog = catalogs.associateBy { it.ref.source }
        val byServer = copies.groupBy { it.server }.mapValues { (_, list) -> list.first() }
        val candidates = order.filter { it.enabled }.mapNotNull { m ->
            if (m.catalog) byCatalog[m.key]?.display() else byServer[m.key]?.let(server)
        }
        val own = copies.firstOrNull()?.let(server)
        val all = candidates + listOfNotNull(own, catalogs.firstOrNull()?.display())
        if (all.isEmpty()) return DetailDisplay("", null, emptyList(), null, null, null, false, null, null)
        fun <T> first(get: (DetailDisplay) -> T?): T? = all.firstNotNullOfOrNull { d -> get(d)?.takeUnless { it is String && it.isBlank() || it is List<*> && it.isEmpty() } }
        val rated = all.firstOrNull { it.rating != null && it.rating > 0 }
        return DetailDisplay(
            title = first { it.title } ?: "",
            overview = first { it.overview },
            genres = first { it.genres } ?: emptyList(),
            year = first { it.year },
            runtimeMs = first { it.runtimeMs },
            rating = rated?.rating,
            ratingTmdb = rated?.ratingTmdb ?: false,
            backdrop = first { it.backdrop },
            logo = first { it.logo },
        )
    }

    /** Where 演职人员 come from: one source's whole list, people are not merged across sources. */
    sealed interface Cast {
        data class Catalog(val people: List<CatalogPerson>) : Cast
        data class Server(val ref: ServerItem) : Cast
        data object None : Cast
    }

    /**
     * 演职人员 by 影片信息来源: the first source switched on that has people (a catalogue's cast, a server
     * copy's People). Without one, the page's own catalogue work, then its own copy, as before.
     */
    fun cast(order: List<SourceRules.MetaSource>, catalogs: List<CatalogWork>, copies: List<ServerItem>): Cast {
        val byCatalog = catalogs.associateBy { it.ref.source }
        val byServer = copies.groupBy { it.server }.mapValues { (_, list) -> list.first() }
        for (m in order) {
            if (!m.enabled) continue
            if (m.catalog) byCatalog[m.key]?.cast?.takeIf { it.isNotEmpty() }?.let { return Cast.Catalog(it) }
            else byServer[m.key]?.takeIf { hasPeople(it) }?.let { return Cast.Server(it) }
        }
        catalogs.firstOrNull()?.cast?.takeIf { it.isNotEmpty() }?.let { return Cast.Catalog(it) }
        return copies.firstOrNull()?.takeIf { hasPeople(it) }?.let { Cast.Server(it) } ?: Cast.None
    }

    /**
     * 人物页 by 影片信息来源: a server's person with a TMDB ID opens TMDB's biography and credits when
     * TMDB is switched on and comes before that server; otherwise the server's own person page.
     */
    fun personFromTmdb(order: List<SourceRules.MetaSource>, server: String): Boolean {
        if (order.isEmpty()) return true
        val enabled = order.filter { it.enabled }
        val tmdb = enabled.indexOfFirst { it.key == "tmdb" }
        if (tmdb < 0) return false
        val own = enabled.indexOfFirst { it.key == server }
        return own < 0 || tmdb < own
    }

    private fun hasPeople(ref: ServerItem) = ref.item.people.any { it.id != null && !it.name.isNullOrBlank() && it.type in setOf("Actor", "Director", "Writer") }

    /**
     * Whether the other catalogue's entry is worth loading for [primary] (TMDB 与 Bangumi 互补): it is
     * switched on and either comes before [primary] in 影片信息来源 or [primary] leaves a field empty that
     * it may fill (a Bangumi work never has TMDB's backdrop and logo). A TMDB work with its text fields
     * and cast, Bangumi after it, asks nothing more.
     */
    fun wantsCompanion(order: List<SourceRules.MetaSource>, primary: CatalogWork): Boolean {
        val other = if (primary.ref.source == "tmdb") "bangumi" else "tmdb"
        val enabled = order.filter { it.enabled }
        val at = enabled.indexOfFirst { it.key == other }
        if (at < 0) return false
        val own = enabled.indexOfFirst { it.key == primary.ref.source }
        if (own < 0 || at < own) return true
        // Bangumi has no backdrop, logo or verified poster: only the text fields and cast can come from it.
        if (other == "tmdb") return true
        return primary.overview.isBlank() || primary.genres.isEmpty() || (primary.rating ?: 0.0) <= 0 || primary.cast.isEmpty()
    }
}
