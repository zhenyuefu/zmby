package app.zmby.stats

import app.zmby.aggregate.Outcome
import app.zmby.catalog.CatalogEpisode
import app.zmby.catalog.CatalogId
import app.zmby.catalog.CatalogRepository
import app.zmby.catalog.CatalogResources
import app.zmby.catalog.CatalogSeason
import app.zmby.catalog.ResourceBatch
import app.zmby.catalog.ResourceMapping
import app.zmby.data.ServerItem
import app.zmby.emby.BaseItemDto
import app.zmby.log.ZLog
import app.zmby.store.EpisodeMappingRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate

/** A series 追剧日历 follows: from 收藏, 待看 or 在看, with a server copy when one is known. */
data class CalendarSeries(val key: String, val title: String, val instance: ServerItem? = null)

/** One episode's TMDB air date; [date] is ISO (`2026-11-02`). */
@Serializable
data class AiringEpisode(
    val seriesKey: String,
    val tmdbId: String,
    val seriesTitle: String,
    val season: Int,
    val episode: Int?,
    val date: String,
    val catalogEpisodeId: String,
    val image: String? = null,
    /** A server copy of the series, for the lookup's fallback and to open its page. */
    val server: String? = null,
    val seriesId: String? = null,
)

/** The calendar as last built, kept in `catalog_cache` (local first). */
@Serializable
data class StoredAiring(val episodes: List<AiringEpisode>, val fetchedAt: Long, val failed: Int = 0)

/** The calendar's rules, without I/O (tested). */
object AiringRules {
    /** How far around today episodes are kept. */
    const val WINDOW_DAYS = 60L
    const val MAX_SERIES = 40

    /** TMDB id of a store or merge key: `series:tmdb:<id>`; IMDb and TVDB keys need a lookup. */
    fun tmdbId(key: String): String? = key.removePrefix("series:tmdb:").takeIf { key.startsWith("series:tmdb:") && it.toLongOrNull()?.let { n -> n > 0 } == true }

    /** The provider and id of an IMDb or TVDB series key, for TMDB's `find`. */
    fun externalId(key: String): Pair<String, String>? = when {
        key.startsWith("series:imdb:") -> "Imdb" to key.removePrefix("series:imdb:")
        key.startsWith("series:tvdb:") -> "Tvdb" to key.removePrefix("series:tvdb:")
        else -> null
    }?.takeIf { it.second.isNotBlank() }

    /** Each series once (the first source wins its title), at most [MAX_SERIES]. */
    fun distinct(series: List<CalendarSeries>): List<CalendarSeries> =
        series.groupBy { it.key }.values.map { same -> same.first().copy(instance = same.firstNotNullOfOrNull { it.instance }) }.take(MAX_SERIES)

    /** The last two regular seasons: what airs now and what just ended (specials are skipped). */
    fun seasonsToFetch(seasons: List<CatalogSeason>): List<Int> = seasons.map { it.number }.filter { it > 0 }.distinct().sorted().takeLast(2)

    fun keep(ep: CatalogEpisode, today: LocalDate): LocalDate? {
        val date = ep.date?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() } ?: return null
        return date.takeIf { !it.isBefore(today.minusDays(WINDOW_DAYS)) && !it.isAfter(today.plusDays(WINDOW_DAYS)) }
    }

    /** The week (Monday first) that holds [date]. */
    fun week(date: LocalDate): List<LocalDate> = date.with(DayOfWeek.MONDAY).let { monday -> (0L until 7L).map { monday.plusDays(it) } }

    fun on(episodes: List<AiringEpisode>, date: LocalDate): List<AiringEpisode> {
        val iso = date.toString()
        return episodes.filter { it.date == iso }.sortedWith(compareBy<AiringEpisode> { it.seriesTitle }.thenBy { it.season }.thenBy { it.episode ?: 0 })
    }

    /** 「第 3 集 · 今天」, 「第 8 季第 6 集 · 11 月 2 日」 (TMDB has no air time). */
    fun line(ep: AiringEpisode, today: LocalDate): String {
        val number = ep.episode?.let { if (ep.season > 1) "第 ${ep.season} 季第 $it 集" else "第 $it 集" }
        val day = runCatching { LocalDate.parse(ep.date) }.getOrNull()?.let { MeText.relativeDate(it, today) }
        return listOfNotNull(number, day).joinToString(" · ")
    }

    fun aired(ep: AiringEpisode, today: LocalDate): Boolean = runCatching { !LocalDate.parse(ep.date).isAfter(today) }.getOrDefault(false)
}

/** How the calendar stands: [episodes] may be the stored copy while [failed] series could not be refreshed. */
data class CalendarState(
    val episodes: List<AiringEpisode> = emptyList(),
    val loading: Boolean = true,
    /** No TMDB token: the calendar cannot be built. */
    val noToken: Boolean = false,
    /** Series whose air dates could not be refreshed; -1 when none could and nothing was stored. */
    val failed: Int = 0,
    val fetched: Boolean = false,
)

/**
 * 追剧日历 (ui-spec §2.17 ①, local-data spec 追剧日历的数据): TMDB air dates of the series in 收藏, 待看
 * and 在看, the last two seasons each, cached; and which servers already have an aired episode, from
 * the series page's resource lookup.
 */
class AiringCalendar(
    private val catalog: CatalogRepository,
    private val resources: CatalogResources,
    private val rules: suspend (String) -> List<EpisodeMappingRule>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun stored(): StoredAiring? = catalog.storedAiring()

    /** Rebuilds from TMDB; series that fail keep their stored episodes. */
    suspend fun refresh(series: List<CalendarSeries>, today: LocalDate): StoredAiring = coroutineScope {
        val previous = catalog.storedAiring()?.episodes.orEmpty().groupBy { it.seriesKey }
        val slots = Semaphore(4)
        val failed = java.util.concurrent.atomic.AtomicInteger()
        val results = AiringRules.distinct(series).map { s ->
            async {
                slots.withPermit {
                    try {
                        episodes(s, today)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ZLog.w(TAG, "air dates of ${s.key}: ${e.message}")
                        failed.incrementAndGet()
                        previous[s.key].orEmpty()
                    }
                }
            }
        }.awaitAll()
        StoredAiring(results.flatten(), clock(), failed.get()).also { catalog.storeAiring(it) }
    }

    private suspend fun episodes(s: CalendarSeries, today: LocalDate): List<AiringEpisode> {
        val tmdb = AiringRules.tmdbId(s.key) ?: AiringRules.externalId(s.key)?.let { (provider, id) ->
            catalog.resolve(BaseItemDto(id = s.key, type = "Series", providerIds = mapOf(provider to id)))?.takeIf { it.source == "tmdb" }?.id
        } ?: return emptyList()
        val work = catalog.work(CatalogId("tmdb", "tv", tmdb))
        return AiringRules.seasonsToFetch(work.seasons).flatMap { season ->
            catalog.episodes(work, season).mapNotNull { ep ->
                val date = AiringRules.keep(ep, today) ?: return@mapNotNull null
                AiringEpisode(s.key, tmdb, work.title.ifBlank { s.title }, ep.season, ep.number, date.toString(), ep.id, ep.image ?: work.backdrop,
                    s.instance?.server, s.instance?.item?.id)
            }
        }
    }

    /**
     * The servers holding [ep] among [servers] (aggregate sources), by the series page's lookup and
     * matching, including local season mappings; empty when none.
     */
    suspend fun copies(ep: AiringEpisode, servers: List<String>): List<ServerItem> {
        val work = catalog.cached(CatalogId("tmdb", "tv", ep.tmdbId)) ?: catalog.work(CatalogId("tmdb", "tv", ep.tmdbId))
        val catalogEpisodes = AiringRules.seasonsToFetch(work.seasons).plus(ep.season).distinct().flatMap { catalog.episodes(work, it) }
        val fallback = if (ep.server != null && ep.seriesId != null) ServerItem(ep.server, BaseItemDto(id = ep.seriesId, type = "Series", name = ep.seriesTitle)) else null
        val targets = (servers + listOfNotNull(ep.server)).distinct()
        val batches = mutableListOf<ResourceBatch>()
        resources.series(targets, work, fallback).collect { result -> if (result is Outcome.Ok) batches += result.value }
        val mapped = ResourceMapping.episodes(work, catalogEpisodes, batches, rules = rules(work.ref.key))
        return mapped.matched[ep.catalogEpisodeId].orEmpty()
    }

    private companion object { const val TAG = "Airing" }
}

/** For tests and callers that need only the count: distinct servers. */
fun List<ServerItem>.serverCount(): Int = map { it.server }.distinct().size
