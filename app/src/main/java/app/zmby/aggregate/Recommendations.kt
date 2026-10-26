package app.zmby.aggregate

import app.zmby.catalog.CatalogWork
import app.zmby.data.ServerItem
import app.zmby.emby.BaseItemDto
import kotlinx.serialization.Serializable

/**
 * One title in 为你推荐 (ui-spec §2.14): the servers' copies of it (empty when only TMDB has it) and
 * the TMDB entry when TMDB recommended it.
 */
@Serializable
data class Recommendation(
    val key: String,
    val copies: List<StoredCopy> = emptyList(),
    val catalog: CatalogWork? = null,
) {
    val hasResource: Boolean get() = copies.isNotEmpty()
    val refs: List<ServerItem> get() = copies.map { ServerItem(it.server, it.item) }
    /** 「n 个版本」: the media sources of every copy (a copy without them counts once). */
    val versions: Int get() = copies.sumOf { it.item.mediaSources.size.coerceAtLeast(1) }
}

/** A server's copy, kept in the local store with the recommendation list. */
@Serializable
data class StoredCopy(val server: String, val item: BaseItemDto)

@Serializable
data class StoredRecommendations(val items: List<Recommendation>, val fetchedAt: Long)

/**
 * Pure merging of the recommendation sources, separate from fetching so it can be tested.
 *
 * Each aggregating server's `/Items/{id}/Similar` answers, in server priority order, merge into
 * works by external ID only (TMDB, then IMDb, TVDB for series); a title without one stays that one
 * server's. TMDB `recommendations` join on the same key. Titles with resources come first — on more
 * servers first, then by rating (TMDB's when known) — and the TMDB-only ones after them in TMDB's
 * order. The work on the page itself never appears.
 */
object RecommendationMerge {
    const val LIMIT = 20

    /** The key a server item merges on; null when it has no external ID (it stays on its own). */
    fun key(item: BaseItemDto): String? {
        val kind = when (item.type) {
            "Movie" -> "movie"
            "Series" -> "series"
            else -> return null
        }
        item.tmdb()?.let { return "$kind:tmdb:$it" }
        item.imdb()?.let { return "$kind:imdb:$it" }
        if (kind == "series") item.tvdb()?.let { return "series:tvdb:$it" }
        return null
    }

    fun key(work: CatalogWork): String = "${if (work.ref.kind == "movie") "movie" else "series"}:${work.ref.source}:${work.ref.id}"

    fun merge(
        servers: List<Pair<String, List<BaseItemDto>>>,
        tmdb: List<CatalogWork>,
        exclude: Set<String>,
        limit: Int = LIMIT,
    ): List<Recommendation> {
        data class Acc(val key: String, val first: Int, val copies: MutableList<StoredCopy> = mutableListOf(), var catalog: CatalogWork? = null, var tmdbRank: Int = Int.MAX_VALUE)
        val byKey = LinkedHashMap<String, Acc>()
        var order = 0
        for ((server, items) in servers) {
            for (item in items) {
                if (item.type !in setOf("Movie", "Series")) continue
                val k = key(item) ?: Keys.local(server, item)
                if (k in exclude || Keys.local(server, item) in exclude) continue
                val acc = byKey.getOrPut(k) { Acc(k, order++) }
                // One copy per server: a server listing the same work twice keeps its first.
                if (acc.copies.none { it.server == server }) acc.copies += StoredCopy(server, lean(item))
            }
        }
        tmdb.forEachIndexed { i, work ->
            val k = key(work)
            if (k in exclude) return@forEachIndexed
            val acc = byKey.getOrPut(k) { Acc(k, order++) }
            if (acc.catalog == null) { acc.catalog = work; acc.tmdbRank = i }
        }
        val all = byKey.values.toList()
        fun rating(a: Acc): Double = a.catalog?.rating ?: a.copies.firstNotNullOfOrNull { it.item.communityRating } ?: 0.0
        val withResource = all.filter { it.copies.isNotEmpty() }
            .sortedWith(compareByDescending<Acc> { it.copies.size }.thenByDescending { rating(it) }.thenBy { it.first })
        val tmdbOnly = all.filter { it.copies.isEmpty() }.sortedBy { it.tmdbRank }
        return (withResource + tmdbOnly).take(limit).map { Recommendation(it.key, it.copies.toList(), it.catalog) }
    }

    /** What a card needs: no streams or chapters in the stored list. */
    private fun lean(item: BaseItemDto) = item.copy(
        mediaSources = item.mediaSources.map { it.copy(mediaStreams = emptyList()) },
        chapters = emptyList(),
        people = emptyList(),
    )
}
