package app.zmby.catalog

import app.zmby.aggregate.*
import app.zmby.data.ServerItem
import app.zmby.emby.BaseItemDto
import app.zmby.store.EpisodeMappingRule

/** Stable resource addresses remain server + item; catalogue addresses can never be played. */
data class ResourceBatch(val server: String, val episodes: List<ServerItem>, val verified: Boolean)
data class EpisodeResources(
    val matched: Map<String, List<ServerItem>>,
    val unmatched: List<ServerItem>,
    /** Why each unmatched entry (by [ServerItem.key]) is not under an episode: 「没有季集编号」 and the like. */
    val reasons: Map<String, String> = emptyMap(),
    /** Identified resources waiting for a season directory, rather than proven unmatched entries. */
    val awaitingCatalog: List<ServerItem> = emptyList(),
    val awaitingIdentity: List<ServerItem> = emptyList(),
)
object ResourceMapping {
    private val providers = listOf("tmdb", "imdb", "tvdb", "bangumi")
    fun conflicts(a: BaseItemDto, b: BaseItemDto): Boolean = providers.any { k ->
        a.providerId(k)?.let { x -> b.providerId(k)?.let { y -> !x.equals(y, true) } } == true
    }
    fun sharesId(a: BaseItemDto, b: BaseItemDto): Boolean = a.type == b.type && listOf("tmdb", "imdb", "tvdb", "bangumi").any { key ->
        a.providerId(key)?.let { id -> b.providerId(key)?.equals(id, true) } == true
    }
    fun same(a: BaseItemDto, b: BaseItemDto): Boolean {
        if (a.type != b.type) return false
        return !conflicts(a, b) && sharesId(a, b)
    }
    /** Exact title aliases and compatible years are usable when there is one candidate, without conflicting IDs. */
    fun named(work: CatalogWork, candidate: BaseItemDto): Boolean {
        val names = listOf(work.title, work.originalTitle).map(Titles::normalize).filter { it.isNotEmpty() }
        return candidate.type == "Series" && !conflicts(work.item(), candidate) &&
            Titles.normalize(candidate.name) in names && (candidate.productionYear == null || work.year == null || candidate.productionYear == work.year)
    }
    fun episodes(work: CatalogWork, catalog: List<CatalogEpisode>, batches: List<ResourceBatch>, pendingSeasons: Set<Int> = emptySet(),
        identities: Map<String, EpisodeIdentity> = emptyMap(), rules: List<EpisodeMappingRule> = emptyList()): EpisodeResources {
        val map = mutableMapOf<String, MutableList<ServerItem>>()
        val extra = mutableListOf<ServerItem>()
        val reasons = mutableMapOf<String, String>()
        val awaiting = mutableListOf<ServerItem>()
        val identifying = mutableListOf<ServerItem>()
        val byId = catalog.groupBy { it.id }
        val byNumber = catalog.filter { it.number != null }.groupBy { it.season to it.number }
        val localRules = rules.filter { it.workKey == work.ref.key }.groupBy { it.serverId to it.seriesId }
        for (batch in batches) for (ref in batch.episodes) {
            val ep = ref.item
            val applicable = localRules[ref.server to ep.seriesId].orEmpty().filter { it.applies(work.ref.key, ref) }
            val manual = applicable.singleOrNull { it.itemId != null }
            val seasonRules = applicable.filter { it.itemId == null }
            val seasonRule = seasonRules.singleOrNull()
            fun ruleTarget(rule: EpisodeMappingRule): CatalogEpisode? = if (rule.itemId != null) byId[rule.targetEpisodeId]?.singleOrNull()
                else ep.indexNumber?.toLong()?.plus(rule.offset)?.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.let {
                    byNumber[rule.targetSeason to it.toInt()]?.singleOrNull()
                }
            fun extra(reason: String) { extra += ref; reasons[ref.key] = reason }
            fun add(target: CatalogEpisode) { map.getOrPut(target.id) { mutableListOf() }.add(ref) }
            if (manual != null) {
                val target = ruleTarget(manual)
                when {
                    target != null -> add(target)
                    manual.targetSeason in pendingSeasons -> awaiting += ref
                    else -> extra("本机映射目标不在目录")
                }
                continue
            }
            if (!batch.verified && seasonRule == null) { extra("剧集身份未确认"); continue }
            // Some cloud servers copy the series TMDB ID to every episode; that is not an episode ID.
            val external = episodeProviderId(ep, work.ref.source)?.takeUnless { it == work.ref.id && it !in byId }
            val exact = if (batch.verified && external != null) byId[external]?.singleOrNull() else null
            if (exact != null) { add(exact); continue }
            if (external != null && batch.verified) {
                if (pendingSeasons.isNotEmpty()) awaiting += ref else extra("外部 ID 对不上目录")
                continue
            }
            val needsIdentity = batch.verified && work.ref.source == "tmdb" && secondaryEpisodeIds(work, ep).isNotEmpty()
            val identity = identities[ref.key]
            if (needsIdentity) {
                when (identity?.state ?: EpisodeIdentityState.Pending) {
                    EpisodeIdentityState.Pending -> { identifying += ref; continue }
                    EpisodeIdentityState.Matched -> {
                        val target = byId[identity?.episodeId]?.singleOrNull()
                        when {
                            target != null -> add(target)
                            pendingSeasons.isNotEmpty() -> awaiting += ref
                            else -> extra("单集 ID 对应条目不在目录")
                        }
                        continue
                    }
                    EpisodeIdentityState.Conflict -> { extra("单集外部 ID 冲突"); continue }
                    else -> Unit
                }
            }
            if (seasonRules.size > 1) { extra("本机季映射范围重叠"); continue }
            if (seasonRule != null) {
                val target = ruleTarget(seasonRule)
                when {
                    target != null -> add(target)
                    seasonRule.targetSeason in pendingSeasons -> awaiting += ref
                    else -> extra("本机映射目标不在目录")
                }
                continue
            }
            if (needsIdentity) {
                extra(if (identity?.state == EpisodeIdentityState.Unavailable) "单集身份暂时无法核验" else "单集外部 ID 未找到对应目录")
                continue
            }
            // Numeric matching is valid only within an identified TMDB series. Bangumi
            // subject numbering does not establish TMDB season offsets.
            val numbered = if (external == null && batch.verified && work.ref.source == "tmdb")
                byNumber[ep.parentIndexNumber to ep.indexNumber]?.singleOrNull() else null
            // Several files at one slot are versions of that episode, each retaining its address.
            val target = numbered
            if (target == null && batch.verified && pendingSeasons.isNotEmpty() &&
                (ep.parentIndexNumber in pendingSeasons || ep.parentIndexNumber == null && external != null)) {
                awaiting += ref
                continue
            }
            if (target == null) {
                extra += ref
                reasons[ref.key] = when {
                    !batch.verified -> "剧集身份未确认"
                    external != null -> "外部 ID 对不上目录"
                    ep.parentIndexNumber == null || ep.indexNumber == null -> "没有季集编号"
                    work.ref.source != "tmdb" -> "编号与番剧目录不对应"
                    else -> "目录里没有这一集"
                }
            } else map.getOrPut(target.id) { mutableListOf() }.add(ref)
        }
        return EpisodeResources(map.mapValues { it.value.distinctBy { ref -> ref.key } }, extra.distinctBy { it.key }, reasons,
            awaiting.distinctBy { it.key }, identifying.distinctBy { it.key })
    }
}

class CatalogResources(private val source: VersionSource, private val fanOut: FanOut) {
    private fun key(work: CatalogWork, fallback: ServerItem?): Any = listOf("series-resources", work.ref, work.ids,
        work.title, work.originalTitle, work.year, fallback?.server, fallback?.item?.id, fallback?.item?.providerIds)

    fun invalidate(servers: List<String>, work: CatalogWork, fallback: ServerItem? = null) = fanOut.invalidate(key(work, fallback), servers)

    /** All seasons are inspected, including server-only seasons, with pagination and a loop guard. */
    fun series(servers: List<String>, work: CatalogWork, fallback: ServerItem? = null) = fanOut.run(servers, FanOut.SEARCH_TIMEOUT_MS,
        share = key(work, fallback), cacheMs = FanOut.RESOURCE_CACHE_MS) { server ->
        val ids = work.ids.map { (key, value) -> "${key.lowercase()}.$value" }
        val lookedUp = try { source.byProviderIds(server, "Series", ids) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { fallback?.takeIf { it.server == server }?.let { listOf(it.item) } ?: throw e }
        // A provider lookup may return another edition and omit the opened series, or omit its IDs.
        // The opened item's explicit identity is equally valid evidence; conflicting copies stay unverified.
        val candidates = (lookedUp + listOfNotNull(fallback?.takeIf { it.server == server }?.item))
            .filter { it.type == "Series" }.groupBy { it.id }.values.map { copies ->
                copies.firstOrNull { ResourceMapping.conflicts(work.item(), it) }
                    ?: copies.firstOrNull { ResourceMapping.same(work.item(), it) } ?: copies.first()
            }
        val matches = candidates.filter { ResourceMapping.same(work.item(), it) }
        val uncertain = candidates.filter { !ResourceMapping.same(work.item(), it) && ResourceMapping.sharesId(work.item(), it) }
        val nameCandidates = if (matches.isEmpty() && uncertain.isEmpty()) {
            (source.searchType(server, work.title, "Series", 5) + listOfNotNull(fallback?.takeIf { it.server == server }?.item))
                .distinctBy { it.id }.filter { ResourceMapping.named(work, it) }
        } else emptyList()
        val originals = (uncertain + nameCandidates + listOfNotNull(fallback?.takeIf { it.server == server }?.item))
            .filter { candidate -> matches.none { it.id == candidate.id } }.distinctBy { it.id }
        val output = mutableListOf<ServerItem>()
        // Multiple editions of the same series are resources; keep their independent addresses.
        for (series in matches + originals) {
            for (season in source.seasons(server, series.id)) {
                var start = 0
                val seen = mutableSetOf<String>()
                while (true) {
                    val page = source.episodePage(server, series.id, season.id, start, 100)
                    val fresh = page.items.filter { seen.add(it.id) }
                    if (page.items.isNotEmpty() && fresh.isEmpty()) throw CatalogException("服务器剧集分页未前进")
                    output += fresh.map { ep -> ServerItem(server, ep.copy(parentIndexNumber = ep.parentIndexNumber ?: season.indexNumber,
                        seriesId = ep.seriesId ?: series.id, seriesName = ep.seriesName ?: series.name)) }
                    start += page.items.size
                    if (page.items.isEmpty() || start >= page.totalRecordCount) break
                    if (start > 20_000) throw CatalogException("服务器剧集分页结果异常")
                }
            }
        }
        // A unique exact-name candidate can use explicit season/episode numbers. Conflicts stay separate.
        // Some servers return episodes whose SeriesId names another edition of the same show.
        // Verify those parents explicitly instead of treating an unqueried ID as an identity conflict.
        val known = (candidates + originals).map { it.id }.toSet()
        val parentIds = output.mapNotNull { it.item.seriesId }.distinct().filter { it !in known }
        val parents = if (parentIds.isEmpty()) emptyList() else source.identities(server, parentIds)
        val verifiedIds = matches.map { it.id }.toSet() + listOfNotNull(nameCandidates.singleOrNull()?.id) +
            parents.filter { it.id in parentIds && ResourceMapping.same(work.item(), it) }.map { it.id }
        output.groupBy { it.item.seriesId in verifiedIds }.map { (verified, rows) -> ResourceBatch(server, rows, verified) }
    }
}
