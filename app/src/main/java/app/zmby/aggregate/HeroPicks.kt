package app.zmby.aggregate

import app.zmby.data.ServerItem
import app.zmby.emby.BaseItemDto

/** Where a hero title came from; its label sits above the title (ui-spec §2.1 主推区). */
enum class HeroTier(val label: String) {
    /** `GET /Users/{uid}/Suggestions`. */
    Suggested("为你推荐"),
    /** `GET /Movies/Recommendations` (films like ones watched). */
    Recommended("相似推荐"),
    /** Each server's Latest, the fallback. */
    Latest("最近更新"),
}

data class HeroPick(val ref: ServerItem, val tier: HeroTier)

/**
 * The home hero when its source is the servers (主推区 · 媒体库推荐, ui-spec §2.1): Emby's
 * suggestions first, then its film recommendations, then the newest additions fill up to [LIMIT].
 * Each tier takes the servers in priority order, one title from each in turn, so one large server
 * does not crowd out the rest; a work already picked (same external ID, or the same item) is
 * skipped. A server without these endpoints, or one that failed, simply contributes nothing to that
 * tier — the latest additions are always there to fall back on.
 */
object HeroMerge {
    const val LIMIT = 8

    fun pick(
        servers: List<String>,
        suggestions: Map<String, List<BaseItemDto>>,
        recommendations: Map<String, List<BaseItemDto>>,
        latest: List<ServerItem>,
        limit: Int = LIMIT,
    ): List<HeroPick> {
        val out = mutableListOf<HeroPick>()
        val seen = HashSet<String>()
        fun add(ref: ServerItem, tier: HeroTier) {
            if (out.size >= limit) return
            if (ref.item.type !in setOf("Movie", "Series", "Episode", "Video")) return
            val keys = listOfNotNull(RecommendationMerge.key(ref.item), Keys.local(ref.server, ref.item))
            if (keys.any { it in seen }) return
            seen += keys
            out += HeroPick(ref, tier)
        }
        fun roundRobin(lists: Map<String, List<BaseItemDto>>, tier: HeroTier) {
            val queues = servers.mapNotNull { s -> lists[s]?.takeIf { it.isNotEmpty() }?.let { s to it } }
            val longest = queues.maxOfOrNull { it.second.size } ?: 0
            for (i in 0 until longest) for ((s, items) in queues) items.getOrNull(i)?.let { add(ServerItem(s, it), tier) }
        }
        roundRobin(suggestions, HeroTier.Suggested)
        roundRobin(recommendations, HeroTier.Recommended)
        latest.forEach { add(it, HeroTier.Latest) }
        return out
    }

    /** Recommendation categories flattened: the first of every category, then the second, and so on. */
    fun flatten(categories: List<List<BaseItemDto>>): List<BaseItemDto> {
        val longest = categories.maxOfOrNull { it.size } ?: 0
        return (0 until longest).flatMap { i -> categories.mapNotNull { it.getOrNull(i) } }.distinctBy { it.id }
    }
}
