package app.zmby.aggregate

import app.zmby.data.ServerItem
import app.zmby.emby.BaseItemDto

/** A search or home hit: an item from a server, with that server's priority (0 = first). */
data class Hit(val ref: ServerItem, val order: Int, val rank: Int = 0) {
    val item: BaseItemDto get() = ref.item
    val server: String get() = ref.server
}

/** One work card: the same film or series on one or more servers. */
data class MergedWork(
    val key: String,
    val entries: List<Hit>,
    /** Per server, why its entry joined (「TMDB 一致」, 「片名 + 年份一致 · 可能匹配」). */
    val reasons: Map<String, Verdict> = emptyMap(),
) {
    /** The entry the card shows: the one on the highest-priority server. */
    val lead: Hit get() = entries.minWith(compareBy<Hit> { it.order }.thenBy { it.rank })
    val servers: Int get() = entries.map { it.server }.distinct().size
}

data class MergeResult(val works: List<MergedWork>, val unmerged: List<Hit>)

/**
 * Groups search hits into works (aggregation spec, 聚合搜索). Hits with external IDs join a work
 * whose IDs agree and none conflict (TMDB first; series also TVDB); a hit that fits two works joins
 * neither. Hits without IDs join a work only as its single plausible match by title and year
 * (possible); the rest are 「未能合并」, interleaved server by server. Films and series never mix.
 */
object Merger {
    fun merge(hits: List<Hit>, strictExternal: Boolean = false): MergeResult {
        val ordered = hits.sortedWith(compareBy<Hit> { it.order }.thenBy { it.rank })
        val clusters = mutableListOf<MutableList<Hit>>()
        val reasons = mutableListOf<MutableMap<String, Verdict>>()
        val loose = mutableListOf<Hit>()
        val idClusters = HashMap<String, MutableSet<Int>>()
        for (hit in ordered) {
            if (!hasIds(hit.item)) {
                loose += hit
                continue
            }
            // A strong match needs a shared ID. Avoid scanning and normalizing every unrelated work.
            val ids = externalKeys(hit.item)
            val candidates = ids.flatMap { idClusters[it].orEmpty() }.distinct()
            val fits = candidates.filter { i ->
                val pairs = clusters[i].map {
                    if (strictExternal) {
                        if (MetadataIdentity.same(it.item, hit.item)) PairLevel.Strong else PairLevel.None
                    } else same(it.item, hit.item)
                }
                pairs.none { it == PairLevel.None } && pairs.any { it == PairLevel.Strong }
            }
            val cluster: Int
            if (fits.size == 1) {
                val i = fits.single()
                cluster = i
                clusters[i] += hit
                reasons[i].putIfAbsent(hit.server, Verdict(Confidence.Strong, Match.compare(clusters[i].first().item, hit.item).reason))
            } else {
                cluster = clusters.size
                clusters += mutableListOf(hit)
                reasons += mutableMapOf(hit.server to Verdict(Confidence.Strong, "外部 ID"))
            }
            ids.forEach { idClusters.getOrPut(it) { linkedSetOf() }.add(cluster) }
        }
        val titleClusters = clusters.indices.groupBy { i -> clusters[i].first().item.let { it.type to titleYear(it) } }
        val unmerged = mutableListOf<Hit>()
        for (hit in loose) {
            if (strictExternal) { unmerged += hit; continue }
            val title = titleYear(hit.item)
            val fits = if (title == null) emptyList() else titleClusters[hit.item.type to title].orEmpty()
                .filter { i -> clusters[i].none { it.server == hit.server } }
            if (fits.size == 1) {
                val i = fits.single()
                clusters[i] += hit
                reasons[i][hit.server] = Verdict(Confidence.Possible, "片名 + 年份一致")
            } else {
                unmerged += hit
            }
        }
        val works = clusters.mapIndexed { i, c -> MergedWork(keyOf(c), c, reasons[i]) }
            .sortedWith(compareBy<MergedWork> { it.lead.order }.thenBy { it.lead.rank })
        return MergeResult(works, interleave(unmerged))
    }

    private fun hasIds(i: BaseItemDto) = i.tmdb() != null || i.imdb() != null || (i.type == "Series" && i.tvdb() != null)

    private fun externalKeys(i: BaseItemDto): List<String> = listOfNotNull(
        i.tmdb()?.let { "${i.type}:tmdb:$it" },
        i.imdb()?.let { "${i.type}:imdb:$it" },
        i.tvdb()?.let { "${i.type}:tvdb:$it" },
    )

    private fun same(a: BaseItemDto, b: BaseItemDto): PairLevel {
        if (a.type != b.type) return PairLevel.None
        val j = Match.compare(a, b)
        // Between items that have IDs only the IDs count; title rules are for the ID-less ones.
        return if (j.level == PairLevel.Plausible) PairLevel.Plausible else j.level
    }

    private fun titleYear(i: BaseItemDto): Pair<String, Int>? {
        val t = Titles.normalize(i.name)
        val y = i.productionYear ?: return null
        return if (t.isEmpty()) null else t to y
    }

    private fun keyOf(c: List<Hit>): String {
        val lead = c.first()
        return (if (lead.item.type == "Series") Keys.series(lead.item) else Keys.movie(lead.item)) ?: Keys.local(lead.server, lead.item)
    }

    /** Round-robin by server priority, keeping each server's own order (Moonfin's fallback). */
    fun interleave(hits: List<Hit>): List<Hit> {
        val byServer = hits.groupBy { it.order }.toSortedMap().values.map { it.sortedBy { h -> h.rank }.toMutableList() }
        val out = mutableListOf<Hit>()
        while (byServer.any { it.isNotEmpty() }) for (q in byServer) if (q.isNotEmpty()) out += q.removeAt(0)
        return out
    }
}
