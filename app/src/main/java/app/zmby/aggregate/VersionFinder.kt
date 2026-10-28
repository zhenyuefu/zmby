package app.zmby.aggregate

import app.zmby.emby.BaseItemDto
import app.zmby.emby.ItemsResult
import app.zmby.emby.MediaSourceInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The Emby calls version lookup needs, per server ([app.zmby.data.Library] implements it). */
interface VersionSource {
    /** `AnyProviderIdEquals=tmdb.1,imdb.tt2` — some servers ignore it and return everything; callers filter. */
    suspend fun byProviderIds(server: String, type: String, ids: List<String>): List<BaseItemDto>
    suspend fun searchType(server: String, term: String, type: String, limit: Int): List<BaseItemDto>
    suspend fun item(server: String, id: String): BaseItemDto
    suspend fun seasons(server: String, seriesId: String): List<BaseItemDto>
    suspend fun episodePage(server: String, seriesId: String, seasonId: String, start: Int, limit: Int): ItemsResult
    /** The sources the server will actually play (PlaybackInfo, not playback). */
    suspend fun sources(server: String, itemId: String): List<MediaSourceInfo>
}

/** What one other server has of the work on screen. */
sealed interface ServerVersions {
    val server: String

    data class Found(override val server: String, val item: BaseItemDto, val sources: List<MediaSourceInfo>, val verdict: Verdict) : ServerVersions
    /** The series is there but not this episode: 「缺此集」. */
    data class Missing(override val server: String, val reason: String) : ServerVersions
    data class NotFound(override val server: String, val reason: String) : ServerVersions
    data class Failed(override val server: String, val message: String, val timedOut: Boolean, val retryable: Boolean = true) : ServerVersions
    data class Skipped(override val server: String) : ServerVersions
}

/**
 * 详情页查找全部版本 (aggregation spec). The open server is not looked up (it is the first group);
 * the others are asked in parallel through [fanOut] with the 20-second timeout.
 */
class VersionFinder(private val source: VersionSource, private val fanOut: FanOut) {

    private fun key(ref: BaseItemDto, series: BaseItemDto? = null): Any = listOf("versions", identity(ref), series?.let(::identity))
    private fun identity(ref: BaseItemDto): Any = listOf(ref.id, ref.type, ref.name, ref.productionYear, ref.providerIds,
        ref.path, ref.parentIndexNumber, ref.indexNumber)

    fun invalidate(servers: List<String>, ref: BaseItemDto, series: BaseItemDto? = null) = fanOut.invalidate(key(ref, series), servers)

    fun movie(servers: List<String>, ref: BaseItemDto): Flow<ServerVersions> =
        fanOut.run(servers, FanOut.SEARCH_TIMEOUT_MS, share = key(ref), cacheMs = FanOut.RESOURCE_CACHE_MS) { s -> findMovie(s, ref) }.map(::unwrap)

    fun episode(servers: List<String>, ref: BaseItemDto, refSeries: BaseItemDto): Flow<ServerVersions> =
        fanOut.run(servers, FanOut.SEARCH_TIMEOUT_MS, share = key(ref, refSeries), cacheMs = FanOut.RESOURCE_CACHE_MS) { s -> findEpisode(s, ref, refSeries) }.map(::unwrap)

    private fun unwrap(o: Outcome<ServerVersions>): ServerVersions = when (o) {
        is Outcome.Ok -> o.value
        is Outcome.Failed -> ServerVersions.Failed(o.server, o.error.message ?: o.error.javaClass.simpleName, o.timedOut, FanOut.isTransient(o.error))
        is Outcome.Skipped -> ServerVersions.Skipped(o.server)
    }

    suspend fun findMovie(server: String, ref: BaseItemDto): ServerVersions {
        val m = findWork(server, ref, "Movie", NAME_CANDIDATES)
        return if (m.item != null && m.verdict != null) found(server, m.item, m.verdict) else ServerVersions.NotFound(server, m.reason)
    }

    suspend fun findEpisode(server: String, ref: BaseItemDto, refSeries: BaseItemDto): ServerVersions {
        val m = findWork(server, refSeries, "Series", SERIES_CANDIDATES)
        val series = m.item ?: return ServerVersions.NotFound(server, "没有这部剧（${m.reason}）")
        val seriesVerdict = m.verdict ?: return ServerVersions.NotFound(server, m.reason)
        val season = source.seasons(server, series.id).firstOrNull { it.indexNumber == ref.parentIndexNumber }
            ?: return ServerVersions.Missing(server, "缺第 ${ref.parentIndexNumber} 季")
        // Page through the season: episode 200 of a long anime is not on the first page.
        var start = 0
        while (true) {
            val page = source.episodePage(server, series.id, season.id, start, EPISODE_PAGE)
            page.items.firstOrNull { it.indexNumber == ref.indexNumber && (it.parentIndexNumber == null || it.parentIndexNumber == ref.parentIndexNumber) }?.let { ep ->
                val withSeason = if (ep.parentIndexNumber == null) ep.copy(parentIndexNumber = ref.parentIndexNumber) else ep
                val j = Match.compareEpisode(ref, refSeries, withSeason, series)
                if (j.level == PairLevel.None) return ServerVersions.NotFound(server, j.reason)
                val confidence = if (seriesVerdict.confidence == Confidence.Strong && j.level == PairLevel.Strong) Confidence.Strong else Confidence.Possible
                return found(server, ep, Verdict(confidence, j.reason))
            }
            start += page.items.size
            if (page.items.isEmpty() || start >= page.totalRecordCount) return ServerVersions.Missing(server, "缺此集")
        }
    }

    private data class WorkMatch(val item: BaseItemDto?, val verdict: Verdict?, val reason: String)

    /** Finds the same film or series on [server]: external IDs first, then the name. */
    private suspend fun findWork(server: String, ref: BaseItemDto, type: String, nameCandidates: Int): WorkMatch {
        val ids = providerQuery(ref, type)
        if (ids.isNotEmpty()) {
            // Filter again here: a server that does not know AnyProviderIdEquals returns its whole library.
            val hits = source.byProviderIds(server, type, ids).filter { it.type == type && sharesId(ref, it, type) }
            if (hits.isNotEmpty()) {
                Match.judge(hits) { Match.compare(ref, it) }?.let { return WorkMatch(it.first, it.second, it.second.reason) }
                return WorkMatch(null, null, Match.explain(hits) { Match.compare(ref, it) })
            }
        }
        if (ref.id.startsWith("catalog:")) return WorkMatch(null, null, "无已确认外部 ID 的资源")
        val name = ref.name?.takeIf { it.isNotBlank() } ?: return WorkMatch(null, null, "缺少片名")
        // Name hits are re-read with full fields before judging.
        val candidates = source.searchType(server, name, type, nameCandidates)
            .filter { it.type == type && Match.compare(ref, it).level != PairLevel.None }
            .take(nameCandidates)
            .map { optional { source.item(server, it.id) } ?: it }
        Match.judge(candidates) { Match.compare(ref, it) }?.let { return WorkMatch(it.first, it.second, it.second.reason) }
        return WorkMatch(null, null, Match.explain(candidates) { Match.compare(ref, it) })
    }

    private suspend fun found(server: String, item: BaseItemDto, verdict: Verdict): ServerVersions {
        val sources = try { source.sources(server, item.id).ifEmpty { item.mediaSources } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { item.mediaSources.ifEmpty { throw e } }
        return ServerVersions.Found(server, item, sources, verdict)
    }

    private suspend fun <T> optional(call: suspend () -> T): T? = try { call() }
    catch (e: CancellationException) { throw e }
    catch (_: Exception) { null }

    companion object {
        const val NAME_CANDIDATES = 10
        const val SERIES_CANDIDATES = 3
        const val EPISODE_PAGE = 100

        /** `tmdb.<id>`, `imdb.<id>` (and `tvdb.<id>` for series) of [ref]. */
        fun providerQuery(ref: BaseItemDto, type: String): List<String> = listOfNotNull(
            ref.tmdb()?.let { "tmdb.$it" },
            ref.imdb()?.let { "imdb.$it" },
            if (type == "Series") ref.tvdb()?.let { "tvdb.$it" } else null,
        )

        fun sharesId(a: BaseItemDto, b: BaseItemDto, type: String): Boolean =
            (a.tmdb() != null && a.tmdb() == b.tmdb()) || (a.imdb() != null && a.imdb() == b.imdb()) ||
                (type == "Series" && a.tvdb() != null && a.tvdb() == b.tvdb())
    }
}
