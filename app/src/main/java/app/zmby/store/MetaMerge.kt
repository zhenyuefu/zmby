package app.zmby.store

import app.zmby.aggregate.imdb
import app.zmby.aggregate.tmdb
import app.zmby.aggregate.tvdb
import app.zmby.emby.BaseItemDto
import app.zmby.emby.ImageInfo

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

/** One server's copy of a work, for [MetaMerge]: its server priority, the item and image sizes if known. */
data class MetaCandidate(val server: String, val order: Int, val item: BaseItemDto, val images: List<ImageInfo>? = null)

/**
 * Merges a work's copies into one row (local-data spec, 元数据合并): each field chosen on its own and
 * its source remembered. Text prefers Chinese (Han characters are most of the letters), then server
 * priority; images take the most pixels; numbers take the first copy that has one.
 */
object MetaMerge {
    fun merge(key: String, candidates: List<MetaCandidate>, now: Long): WorkMeta? {
        val sorted = candidates.sortedBy { it.order }
        val lead = sorted.firstOrNull() ?: return null
        val title = pickText(sorted) { it.item.name }
        val overview = pickText(sorted) { it.item.overview }
        val genres = pickText(sorted) { it.item.genres.joinToString(GENRE_SEP).ifEmpty { null } }
        val rated = sorted.firstOrNull { it.item.communityRating != null }
        return WorkMeta(
            workKey = key,
            type = lead.item.type ?: "Movie",
            title = title?.second,
            titleServer = title?.first,
            originalTitle = sorted.firstNotNullOfOrNull { it.item.originalTitle?.takeIf(String::isNotBlank) },
            overview = overview?.second,
            overviewServer = overview?.first,
            year = sorted.firstNotNullOfOrNull { it.item.productionYear },
            genres = genres?.second?.split(GENRE_SEP).orEmpty(),
            genresServer = genres?.first,
            rating = rated?.item?.communityRating,
            ratingTmdb = rated?.item?.tmdb() != null,
            runtimeTicks = sorted.firstNotNullOfOrNull { it.item.runTimeTicks },
            premiereDate = sorted.firstNotNullOfOrNull { it.item.premiereDate },
            primary = pickImage(sorted, "Primary"),
            backdrop = pickImage(sorted, "Backdrop"),
            logo = pickImage(sorted, "Logo"),
            fetchedAt = now,
            accessedAt = now,
        )
    }

    /** (server, value): the first Chinese value in priority order, else the first non-empty one. */
    fun pickText(sorted: List<MetaCandidate>, field: (MetaCandidate) -> String?): Pair<String, String>? {
        val values = sorted.mapNotNull { c -> field(c)?.trim()?.takeIf { it.isNotEmpty() }?.let { c.server to it } }
        return values.firstOrNull { isChinese(it.second) } ?: values.firstOrNull()
    }

    /** Han characters are more than half of the letters (spaces, digits and punctuation not counted). */
    fun isChinese(text: String): Boolean {
        var han = 0
        var letters = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) { han++; letters++ } else if (Character.isLetter(cp)) letters++
            i += Character.charCount(cp)
        }
        return letters > 0 && han * 2 > letters
    }

    /**
     * The sharpest image of [type] among the copies: by pixel count from `/Items/{id}/Images`, a Logo
     * preferring PNG (transparent); copies whose sizes are unknown count as 0 and fall back to
     * priority order.
     */
    fun pickImage(sorted: List<MetaCandidate>, type: String): ImageRef? {
        data class Option(val ref: ImageRef, val pixels: Long, val png: Boolean, val order: Int)
        val options = sorted.mapNotNull { c ->
            val tag = when (type) {
                "Backdrop" -> c.item.backdropImageTags.firstOrNull()
                else -> c.item.imageTags[type]
            } ?: return@mapNotNull null
            val info = c.images?.firstOrNull { it.imageType == type && (it.imageTag == null || it.imageTag == tag) && (type != "Backdrop" || (it.imageIndex ?: 0) == 0) }
                ?: c.images?.firstOrNull { it.imageType == type }
            val w = info?.width; val h = info?.height
            Option(ImageRef(c.server, c.item.id, tag, w, h), (w ?: 0).toLong() * (h ?: 0), info?.path?.endsWith(".png", ignoreCase = true) == true, c.order)
        }
        val best = options.sortedWith(
            compareByDescending<Option> { if (type == "Logo") it.png else false }
                .thenByDescending { it.pixels }
                .thenBy { it.order },
        ).firstOrNull()
        return best?.ref
    }

    private const val GENRE_SEP = "\u001f"
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
