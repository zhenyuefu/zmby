package app.zmby.danmu

import app.zmby.catalog.CatalogEpisode
import app.zmby.catalog.CatalogId
import app.zmby.catalog.CatalogWork
import app.zmby.emby.BaseItemDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Resolves the parent work and known catalogue numbering; never treats episode IDs/titles/years
 * as series metadata. All parent/cache reads and alias work run outside the main thread. */
class DanmuQueryResolver(
    private val parent: suspend (String, String) -> BaseItemDto?,
    private val cached: suspend (CatalogId) -> CatalogWork?,
    private val resolve: suspend (BaseItemDto) -> CatalogId?,
    private val aliases: suspend (CatalogId) -> List<String>,
) {
    private data class Hint(val work: CatalogWork, val episode: CatalogEpisode?)
    private val hints = object : LinkedHashMap<String, Hint>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Hint>) = size > 256
    }

    fun remember(server: String, itemId: String, work: CatalogWork, episode: CatalogEpisode?, seriesId: String?) {
        synchronized(hints) {
            hints["$server:$itemId"] = Hint(work, episode)
            if (seriesId != null) hints["$server:$seriesId"] = Hint(work, null)
        }
    }

    suspend fun query(server: String, item: BaseItemDto): DanmuQuery? = withContext(Dispatchers.IO) {
        val hint = synchronized(hints) { hints["$server:${item.id}"] ?: item.seriesId?.let { hints["$server:$it"] } }
        val identity = if (item.type == "Episode") {
            // A version selected from the catalogue already has its parent work and mapping.
            if (hint != null) null else item.seriesId?.let { id ->
                optional { withTimeoutOrNull(3_000) { parent(server, id) } }
            }
        } else item
        val ref = hint?.work?.ref ?: identity?.let { optional { withTimeoutOrNull(3_000) { resolve(it) } } }
        val work = hint?.work ?: ref?.let { optional { cached(it) } }
        // Episode-list responses may omit SeriesName; resolve the parent before rejecting them.
        val basic = DanmuRepository.queryOf(item.copy(
            seriesName = item.seriesName?.takeIf { it.isNotBlank() } ?: work?.title ?: identity?.name,
        )) ?: return@withContext null
        val names = (basic.names + listOfNotNull(identity?.name, work?.title, identity?.originalTitle, work?.originalTitle) +
            work?.aliases.orEmpty()).map { it.trim() }.filter { it.isNotBlank() }.distinct()
        basic.copy(names = names,
            year = work?.year ?: identity?.productionYear,
            season = hint?.episode?.season ?: basic.season,
            episode = hint?.episode?.number ?: basic.episode,
            workId = ref?.key ?: if (item.type == "Episode") item.seriesId?.let { "server:$server:series:$it" } else "server:$server:${item.id}",
            catalogRef = ref)
    }

    /** Collect the known work's aliases before launching all distinct name requests together. */
    suspend fun enrich(query: DanmuQuery): DanmuQuery = withContext(Dispatchers.IO) {
        val ref = query.catalogRef ?: return@withContext query
        val extra = optional { withTimeoutOrNull(5_000) { aliases(ref) } }.orEmpty()
        query.copy(names = (query.names + extra).filter { it.isNotBlank() }.distinct())
    }

    private suspend fun <T> optional(block: suspend () -> T): T? = try { block() }
    catch (e: CancellationException) { throw e } catch (_: Exception) { null }
}
