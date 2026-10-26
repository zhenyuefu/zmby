package app.zmby.catalog

import app.zmby.aggregate.*
import app.zmby.data.ServerItem
import app.zmby.emby.BaseItemDto

/** Stable resource addresses remain server + item; catalogue addresses can never be played. */
data class ResourceBatch(val server: String, val episodes: List<ServerItem>, val verified: Boolean)
data class EpisodeResources(
    val matched: Map<String, List<ServerItem>>,
    val unmatched: List<ServerItem>,
    /** Why each unmatched entry (by [ServerItem.key]) is not under an episode: 「没有季集编号」 and the like. */
    val reasons: Map<String, String> = emptyMap(),
)
object ResourceMapping {
    fun sharesId(a: BaseItemDto, b: BaseItemDto): Boolean = a.type == b.type && listOf("tmdb", "imdb", "tvdb", "bangumi").any { key ->
        a.providerId(key)?.let { id -> b.providerId(key)?.equals(id, true) } == true
    }
    fun same(a: BaseItemDto, b: BaseItemDto): Boolean {
        if (a.type != b.type) return false
        val keys = listOf("tmdb", "imdb", "tvdb", "bangumi")
        if (keys.any { k -> a.providerId(k)?.let { x -> b.providerId(k)?.let { y -> !x.equals(y, true) } } == true }) return false
        return keys.any { k -> a.providerId(k)?.let { x -> b.providerId(k)?.equals(x, true) } == true }
    }
    fun episodes(work: CatalogWork, catalog: List<CatalogEpisode>, batches: List<ResourceBatch>): EpisodeResources {
        val map = mutableMapOf<String, MutableList<ServerItem>>()
        val extra = mutableListOf<ServerItem>()
        val reasons = mutableMapOf<String, String>()
        for (batch in batches) for (ref in batch.episodes) {
            val ep = ref.item
            val external = ep.providerId(work.ref.source)
            val exact = if (batch.verified && external != null) catalog.filter { it.id == external }.singleOrNull() else null
            // Numeric matching is valid only within an externally verified TMDB series. Bangumi
            // subject numbering does not establish TMDB season offsets.
            val numbered = if (external == null && batch.verified && work.ref.source == "tmdb") catalog.filter {
                it.number != null && it.season == ep.parentIndexNumber && it.number == ep.indexNumber
            }.singleOrNull() else null
            val ambiguousNumber = numbered != null && batch.episodes.count {
                it.item.seriesId == ep.seriesId && it.item.parentIndexNumber == ep.parentIndexNumber && it.item.indexNumber == ep.indexNumber
            } > 1
            val target = exact ?: numbered?.takeUnless { ambiguousNumber }
            if (target == null) {
                extra += ref
                reasons[ref.key] = when {
                    !batch.verified -> "剧集身份未确认"
                    external != null -> "外部 ID 对不上目录"
                    ep.parentIndexNumber == null || ep.indexNumber == null -> "没有季集编号"
                    work.ref.source != "tmdb" -> "编号与番剧目录不对应"
                    ambiguousNumber -> "同一集有多条"
                    else -> "目录里没有这一集"
                }
            } else map.getOrPut(target.id) { mutableListOf() }.add(ref)
        }
        return EpisodeResources(map.mapValues { it.value.distinctBy { ref -> ref.key } }, extra.distinctBy { it.key }, reasons)
    }
}

class CatalogResources(private val source: VersionSource, private val fanOut: FanOut) {
    /** All seasons are inspected, including server-only seasons, with pagination and a loop guard. */
    fun series(servers: List<String>, work: CatalogWork, fallback: ServerItem? = null) = fanOut.run(servers, FanOut.SEARCH_TIMEOUT_MS) { server ->
        val ids = work.ids.map { (key, value) -> "${key.lowercase()}.$value" }
        val lookedUp = try { source.byProviderIds(server, "Series", ids) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { fallback?.takeIf { it.server == server }?.let { listOf(it.item) } ?: throw e }
        val candidates = lookedUp.filter { it.type == "Series" }.distinctBy { it.id }
        val matches = candidates.filter { ResourceMapping.same(work.item(), it) }
        val uncertain = candidates.filter { !ResourceMapping.same(work.item(), it) && ResourceMapping.sharesId(work.item(), it) }
        val nameCandidates = if (matches.isEmpty() && uncertain.isEmpty() && fallback?.server != server) {
            val names = setOf(Titles.normalize(work.title), Titles.normalize(work.originalTitle))
            source.searchType(server, work.title, "Series", 5).filter {
                it.type == "Series" && Titles.normalize(it.name) in names && (it.productionYear == null || work.year == null || it.productionYear == work.year)
            }
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
        // A fallback without matching external identity stays wholly in the unmatched section.
        val verifiedIds = matches.map { it.id }.toSet()
        output.groupBy { it.item.seriesId in verifiedIds }.map { (verified, rows) -> ResourceBatch(server, rows, verified) }
    }
}
