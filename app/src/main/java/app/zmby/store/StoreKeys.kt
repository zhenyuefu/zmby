package app.zmby.store

import app.zmby.aggregate.imdb
import app.zmby.aggregate.tmdb
import app.zmby.aggregate.tvdb
import app.zmby.emby.BaseItemDto

/**
 * Keys of the local store (local-data spec, 作品键从哪来). Unlike the merge keys used for matching
 * ([app.zmby.aggregate.Keys]) these never fall back to titles: a work is keyed by an external ID
 * (TMDB > IMDb > TVDB) or else by the one item it is, so cached metadata is never shared by guess.
 */
object StoreKeys {
    fun work(server: String, item: BaseItemDto, seriesKey: String? = null): String = when (item.type) {
        "Movie" -> external("movie", item)
        "Series" -> external("series", item)
        "Episode" -> seriesKey?.takeIf { isExternal(it) }?.let { sk ->
            val s = item.parentIndexNumber; val e = item.indexNumber
            if (s != null && e != null) "%s:s%02d:e%02d".format(sk, s, e) else null
        }
        else -> null
    } ?: local(server, item.id)

    private fun external(kind: String, item: BaseItemDto): String? =
        item.tmdb()?.let { "$kind:tmdb:$it" } ?: item.imdb()?.let { "$kind:imdb:$it" } ?: item.tvdb()?.let { "$kind:tvdb:$it" }

    fun local(server: String, itemId: String) = "item:$server:$itemId"

    /** Keyed by an external ID, so the same work on other servers shares it. */
    fun isExternal(key: String) = !key.startsWith("item:")

    /** For an `item:` key: the one (server, item) it stands for. */
    fun localRef(key: String): Pair<String, String>? =
        key.takeIf { it.startsWith("item:") }?.removePrefix("item:")?.split(':', limit = 2)?.takeIf { it.size == 2 }?.let { it[0] to it[1] }

    fun person(server: String, personId: String, providerIds: Map<String, String> = emptyMap()): String {
        val ids = providerIds.entries.associate { it.key.lowercase() to it.value.trim() }.filterValues { it.isNotEmpty() }
        ids["tmdb"]?.let { return "person:tmdb:$it" }
        ids["imdb"]?.let { return "person:imdb:$it" }
        return "person:$server:$personId"
    }
}

/** When cached data is refreshed in the background (local-data spec, 先本地、后刷新). */
object Freshness {
    const val LATEST_MS = 10 * 60_000L
    const val BROWSE_MS = 60 * 60_000L
    const val VIEWS_MS = 24 * 60 * 60_000L
    const val PERSON_PRUNE_MS = 7L * 24 * 60 * 60_000L
    const val MAX_ROWS = 50_000

    enum class Kind { Resume, NextUp, Latest, Browse, Views, Detail, Person }

    /** Whether a list or record fetched at [fetchedAt] (null = never) should be fetched again now. */
    fun due(kind: Kind, fetchedAt: Long?, now: Long): Boolean {
        if (fetchedAt == null || fetchedAt <= 0) return true
        return when (kind) {
            // Progress changes with every playback, on this box or elsewhere: always.
            Kind.Resume, Kind.NextUp, Kind.Detail, Kind.Person -> true
            Kind.Latest -> now - fetchedAt >= LATEST_MS
            Kind.Browse -> now - fetchedAt >= BROWSE_MS
            Kind.Views -> now - fetchedAt >= VIEWS_MS
        }
    }

    /** How many rows to drop to get back to [max]. */
    fun excess(count: Int, max: Int = MAX_ROWS): Int = (count - max).coerceAtLeast(0)
}
