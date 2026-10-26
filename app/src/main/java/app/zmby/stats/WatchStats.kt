package app.zmby.stats

import app.zmby.data.Library
import app.zmby.emby.BaseItemDto
import app.zmby.emby.ticksToMs
import app.zmby.log.ZLog
import app.zmby.store.LocalStore
import app.zmby.store.PlayHistoryRow
import app.zmby.store.StoreKeys
import app.zmby.store.SyncState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** One month of 按月分布. */
data class MonthStat(val month: YearMonth, val movies: Int, val episodes: Int, val watchedMs: Long)

/** The 观影统计 page's numbers. */
data class StatsSummary(
    val movies: Int,
    val episodes: Int,
    /** Series with at least one watched episode. */
    val series: Int,
    /** Runtime of everything watched, each work once. */
    val totalMs: Long,
    /** The last [StatsAggregate.MONTHS] months, oldest first; months without plays are zeros. */
    val months: List<MonthStat>,
    /** Series by watched episodes, most first. */
    val topSeries: List<Pair<String, Int>>,
    /** Watched items whose server gave no play date (not in any month). */
    val undated: Int,
    val servers: Int,
) {
    val empty: Boolean get() = movies == 0 && episodes == 0

    companion object { val EMPTY = StatsSummary(0, 0, 0, 0, emptyList(), emptyList(), 0, 0) }
}

/** The aggregation, without I/O (tested). */
object StatsAggregate {
    const val MONTHS = 12
    const val TOP_SERIES = 5

    /**
     * A work watched on several servers counts once ([PlayHistoryRow.workKey]; works without
     * external IDs are per server), dated by its latest play.
     */
    fun summarize(rows: List<PlayHistoryRow>, now: Long, zone: ZoneId): StatsSummary {
        if (rows.isEmpty()) return StatsSummary.EMPTY
        val works = rows.groupBy { it.workKey }.values.map { same ->
            val latest = same.maxByOrNull { it.playedAt ?: Long.MIN_VALUE }!!
            latest.copy(runtimeMs = same.firstNotNullOfOrNull { it.runtimeMs })
        }
        val movies = works.filter { it.type != "Episode" }
        val episodes = works.filter { it.type == "Episode" }
        val current = YearMonth.from(Instant.ofEpochMilli(now).atZone(zone))
        val first = current.minusMonths((MONTHS - 1).toLong())
        val byMonth = works.filter { it.playedAt != null }.groupBy { YearMonth.from(Instant.ofEpochMilli(it.playedAt!!).atZone(zone)) }
        val months = (0 until MONTHS).map { i ->
            val m = first.plusMonths(i.toLong())
            val list = byMonth[m].orEmpty()
            MonthStat(m, list.count { it.type != "Episode" }, list.count { it.type == "Episode" }, list.sumOf { it.runtimeMs ?: 0 })
        }
        val top = episodes.filter { it.seriesKey != null }.groupBy { it.seriesKey }.values
            .map { eps -> (eps.firstNotNullOfOrNull { it.seriesName } ?: "未知剧集") to eps.size }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            .take(TOP_SERIES)
        return StatsSummary(
            movies = movies.size,
            episodes = episodes.size,
            series = episodes.mapNotNull { it.seriesKey }.distinct().size,
            totalMs = works.sumOf { it.runtimeMs ?: 0 },
            months = months,
            topSeries = top,
            undated = works.count { it.playedAt == null },
            servers = rows.map { it.serverId }.distinct().size,
        )
    }

    /** Emby's ISO dates ("2026-10-24T13:05:12.0000000Z"); null when unreadable. */
    fun parseDate(text: String?): Long? = text?.let { runCatching { Instant.parse(it.trim()).toEpochMilli() }.getOrNull() }
}

/**
 * 观影统计 (P4): the play history of the servers in 设置 › 数据来源 › 观影统计, kept in
 * `play_history` so the page shows at once (先本地后刷新). A refresh pages through each server's
 * played items, most recent first, and stops at what it already has; a full pass once a week also
 * drops what the server no longer counts as played. Capped at [MAX_ITEMS] per server.
 */
class WatchStats(
    private val store: LocalStore,
    private val library: Library,
    private val servers: () -> List<String>,
    /** Emits when the 观影统计 servers change, so the summary follows the choice. */
    private val sourceChanges: Flow<*>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = store.db.playHistory()

    /** Per server: refreshing, failed, or done. */
    val refreshing = MutableStateFlow<Set<String>>(emptySet())
    val failed = MutableStateFlow<Set<String>>(emptySet())

    fun summary(zone: ZoneId = ZoneId.systemDefault()): Flow<StatsSummary> =
        combine(dao.allFlow(), sourceChanges) { rows, _ ->
            val chosen = servers().toSet()
            StatsAggregate.summarize(rows.filter { it.serverId in chosen }, clock(), zone)
        }.flowOn(Dispatchers.Default)

    /** The last refresh of any chosen server, or null if never. */
    suspend fun lastRefresh(): Long? = servers().mapNotNull { store.sources.sync(FEATURE, it)?.lastPullAt?.takeIf { t -> t > 0 } }.maxOrNull()

    suspend fun refresh(force: Boolean = false) {
        for (server in servers()) {
            val sync = store.sources.sync(FEATURE, server)
            val now = clock()
            if (!force && sync != null && now - sync.lastPullAt < REFRESH_MS) continue
            refreshing.value = refreshing.value + server
            try {
                val full = sync == null || now - (store.sources.sync(FEATURE_FULL, server)?.lastPullAt ?: 0) > FULL_MS
                pull(server, full, now)
                store.sources.upsertSync(SyncState(FEATURE, server, now))
                if (full) store.sources.upsertSync(SyncState(FEATURE_FULL, server, now))
                failed.value = failed.value - server
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ZLog.w(TAG, "play history of ${library.status(server)?.name ?: server}: ${e.message}")
                store.sources.upsertSync(SyncState(FEATURE, server, sync?.lastPullAt ?: 0, e.message))
                failed.value = failed.value + server
            } finally {
                refreshing.value = refreshing.value - server
            }
        }
    }

    private suspend fun pull(server: String, full: Boolean, now: Long) {
        val newest = if (full) null else dao.newest(server)
        var start = 0
        var seen = 0
        while (seen < MAX_ITEMS) {
            val page = library.playedItems(server, start, PAGE)
            val rows = page.items.map { row(server, it, now) }
            if (rows.isNotEmpty()) dao.upsert(rows)
            seen += rows.size
            start += page.items.size
            // Sorted by play date: once a page reaches what was there before, the rest is known.
            val reachedKnown = newest != null && rows.any { (it.playedAt ?: 0) <= newest }
            if (page.items.size < PAGE || start >= page.totalRecordCount || reachedKnown) break
        }
        // A full pass rewrote every row it saw; older rows are no longer played on that server.
        if (full && seen < MAX_ITEMS) dao.deleteStale(server, now)
        ZLog.i(TAG, "play history of ${library.status(server)?.name ?: server}: $seen item(s)${if (full) " (full)" else ""}")
    }

    private suspend fun row(server: String, item: BaseItemDto, now: Long): PlayHistoryRow {
        val seriesKey = item.seriesId?.let { id -> store.workKeyOf(server, id) ?: StoreKeys.local(server, id) }
        return PlayHistoryRow(
            serverId = server,
            itemId = item.id,
            type = item.type ?: "Movie",
            workKey = store.workKeyOf(server, item.id) ?: StoreKeys.work(server, item, seriesKey),
            seriesKey = seriesKey,
            seriesName = item.seriesName,
            title = item.name,
            runtimeMs = item.runTimeTicks?.let(::ticksToMs),
            playedAt = StatsAggregate.parseDate(item.userData?.lastPlayedDate),
            playCount = item.userData?.playCount ?: 1,
            fetchedAt = now,
        )
    }

    companion object {
        private const val TAG = "Stats"
        const val FEATURE = "stats"
        private const val FEATURE_FULL = "stats_full"
        const val PAGE = 200
        const val MAX_ITEMS = 5_000
        /** Entering the page refreshes at most this often (the 刷新 button always does). */
        const val REFRESH_MS = 30 * 60_000L
        const val FULL_MS = 7L * 24 * 3600_000
    }
}
