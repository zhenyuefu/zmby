package app.zmby.stats

import app.zmby.store.LocalStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import java.time.ZoneId

/**
 * 我的's numbers, local first: the time charts from this box's sessions ([MeStats.summarize]), 最近看过
 * from the sessions and the servers' play history together. Both follow the 观影统计 source and run
 * on Dispatchers.Default.
 */
class MeFeed(
    private val store: LocalStore,
    /** A session's server counts unless switched off in 观影统计 (deleted servers keep counting). */
    private val inSource: (String) -> Boolean,
    /** Servers whose play history fills in 最近看过. */
    private val historyServers: () -> List<String>,
    private val sourceChanges: Flow<*>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val sessions = store.db.playSessions()

    fun summary(zone: ZoneId = ZoneId.systemDefault()): Flow<MeSummary> =
        combine(sessions.since(MeStats.windowStart(clock(), zone)), sessions.firstFlow(), sourceChanges) { rows, first, _ ->
            MeStats.summarize(rows.filter { inSource(it.serverId) }, clock(), zone, first)
        }.flowOn(Dispatchers.Default)

    fun recent(limit: Int = RECENT): Flow<List<RecentEntry>> =
        combine(sessions.latestPerWork(limit * 2), store.db.playHistory().allFlow(), sourceChanges) { rows, history, _ ->
            val chosen = historyServers().toSet()
            MeStats.recent(rows.filter { inSource(it.serverId) }, history.filter { it.serverId in chosen }, limit)
        }.flowOn(Dispatchers.Default)

    companion object { const val RECENT = 12 }
}
