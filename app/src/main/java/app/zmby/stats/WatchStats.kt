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
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant

/** Play-history helpers, without I/O (tested). */
object StatsAggregate {
    /** Emby's ISO dates ("2026-10-24T13:05:12.0000000Z"); null when unreadable. */
    fun parseDate(text: String?): Long? = text?.let { runCatching { Instant.parse(it.trim()).toEpochMilli() }.getOrNull() }
}

/**
 * The play history of the servers in 设置 › 数据来源 › 观影统计, kept in `play_history`: 我的 fills
 * 最近看过 with what other clients played (先本地后刷新; the time charts use this box's sessions).
 * A refresh pages through each server's played items, most recent first, and stops at what it
 * already has; a full pass once a week also drops what the server no longer counts as played.
 * Capped at [MAX_ITEMS] per server.
 */
class WatchStats(
    private val store: LocalStore,
    private val library: Library,
    private val servers: () -> List<String>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = store.db.playHistory()

    /** Per server: refreshing, failed, or done. */
    val refreshing = MutableStateFlow<Set<String>>(emptySet())
    val failed = MutableStateFlow<Set<String>>(emptySet())

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
