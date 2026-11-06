package app.zmby.aggregate

import app.zmby.data.Library
import app.zmby.emby.BaseItemDto
import app.zmby.log.ZLog
import app.zmby.store.CachedList
import app.zmby.store.LocalStore
import app.zmby.store.WatchingHiddenRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** What 移除 did: the servers whose resume point could not be cleared, and whether the card was hidden here. */
data class RemoveOutcome(val failed: List<String>, val hidden: Boolean)

/**
 * 在看 (ui-spec §2.18), local first: [cards] merges what the store holds for each 观看记录来源
 * server — Resume, NextUp, recently played, the series behind those episodes and their episode
 * lists — with [WatchingMerge] on Dispatchers.Default, and changes as the store does. [refresh]
 * asks the servers and writes their answers; a failed server keeps its last lists.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WatchingFeed(private val library: Library, private val fanOut: FanOut, private val store: LocalStore) {
    fun cards(servers: List<String>, order: Map<String, Int>): Flow<List<WatchCard>> {
        val base = servers.flatMap { listOf(resumeKey(it), nextUpKey(it), playedKey(it), seriesKey(it)) }
        return store.listsFlow(base).flatMapLatest { lists ->
            val watched = servers.associateWith { s -> seriesIn(s, lists) }
            val dirKeys = watched.flatMap { (s, ids) -> ids.map { episodesKey(s, it) } }
            combine(store.listsFlow(dirKeys), store.watchingHidden.observe(), store.episodeMappings.observeAll()) { dirs, hidden, rules ->
                val sources = servers.map { s ->
                    WatchSource(
                        server = s,
                        order = order[s] ?: Int.MAX_VALUE,
                        resume = items(lists[resumeKey(s)]),
                        nextUp = items(lists[nextUpKey(s)]),
                        played = items(lists[playedKey(s)]),
                        series = items(lists[seriesKey(s)]).associateBy { it.id },
                        episodes = watched[s].orEmpty().associateWith { items(dirs[episodesKey(s, it)]) }.filterValues { it.isNotEmpty() },
                    )
                }
                WatchingMerge.merge(sources, rules, hidden.associate { it.workKey to it.hiddenAt })
            }
        }.flowOn(Dispatchers.Default)
    }

    /** When [refresh] last asked the servers; 0 = not yet in this process, or invalidated. */
    private val refreshedAt = java.util.concurrent.atomic.AtomicLong(0L)

    /** A watch operation (played, marked, removed) changed progress: the next [refresh] asks the servers. */
    fun invalidate() = refreshedAt.set(0L)

    /**
     * Resume, NextUp and the recently played list from each server, then the series behind them and
     * the episode lists of the series being watched, which tell 下一集 across servers and the 剧集整体进度.
     * Asks the servers on the first refresh after the app starts, when [force]d (back from the player),
     * after a watch operation ([invalidate]) and otherwise only when the last one is older than
     * [REFRESH_MS] (performance plan RN ①): coming back to home from a detail page asks nothing.
     */
    suspend fun refresh(servers: List<String>, force: Boolean = false, now: Long = System.currentTimeMillis()) {
        if (servers.isEmpty()) return
        val last = refreshedAt.get()
        if (!force && last != 0L && now - last in 0 until REFRESH_MS) {
            ZLog.d(TAG, "在看 asked ${(now - last) / 1000} s ago; not again")
            return
        }
        refreshedAt.set(now)
        coroutineScope {
            launch { collect(servers, "resume") { s -> library.resume(s).map { it.item } }.forEach { (s, items) -> store.putList(resumeKey(s), s, items) } }
            launch { collect(servers, "next up") { s -> library.nextUp(s).map { it.item } }.forEach { (s, items) -> store.putList(nextUpKey(s), s, items) } }
            launch { collect(servers, "played") { s -> library.recentlyPlayed(s) }.forEach { (s, items) -> store.putList(playedKey(s), s, items) } }
        }
        refreshSeries(servers)
    }

    private suspend fun refreshSeries(servers: List<String>) {
        val lists = store.listsFlow(servers.flatMap { listOf(resumeKey(it), nextUpKey(it), playedKey(it)) }).first()
        collect(servers.filter { seriesIn(it, lists).isNotEmpty() }, "series") { s -> library.itemsByIds(s, seriesIn(s, lists)) }
            .forEach { (s, items) -> store.putList(seriesKey(s), s, items) }
        val all = store.listsFlow(servers.map(::seriesKey)).first() + lists
        // The series being watched (part-way or next up somewhere), by external ID across servers.
        fun ids(s: String, seriesId: String) = all[seriesKey(s)]?.items?.firstOrNull { it.item.id == seriesId }?.item?.let(::externalIds).orEmpty()
        val current = servers.flatMap { s -> (items(all[resumeKey(s)]) + items(all[nextUpKey(s)])).mapNotNull { it.seriesId }.map { s to it } }.toSet()
        val currentIds = current.flatMap { (s, id) -> ids(s, id).entries }.map { it.key to it.value }.toSet()
        val targets = servers.associateWith { s ->
            seriesIn(s, all).filter { id -> (s to id) in current || ids(s, id).entries.any { (it.key to it.value) in currentIds } }.take(MAX_SERIES)
        }.filterValues { it.isNotEmpty() }
        // A series' whole episode list is asked again only when it is older than EPISODES_MS or what
        // the servers just said about its episodes (progress, played, next up) is not in it (RN ①).
        val stored = store.listsFlow(targets.flatMap { (s, ids) -> ids.map { episodesKey(s, it) } }).first()
        val due = targets.mapValues { (s, ids) ->
            ids.filter { id -> episodesDue(stored[episodesKey(s, id)], watchedEpisodes(s, id, lists), System.currentTimeMillis()) }
        }.filterValues { it.isNotEmpty() }
        if (due.size < targets.size || due.values.sumOf { it.size } < targets.values.sumOf { it.size })
            ZLog.d(TAG, "episode lists: ${due.values.sumOf { it.size }} of ${targets.values.sumOf { it.size }} series due")
        fanOut.run(due.keys.toList(), FanOut.SEARCH_TIMEOUT_MS) { s ->
            val slots = Semaphore(3)
            coroutineScope {
                due.getValue(s).map { id ->
                    async {
                        slots.withPermit {
                            try {
                                store.putList(episodesKey(s, id), s, library.seriesEpisodes(s, id))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                ZLog.d(TAG, "episodes of a series on ${library.status(s)?.name}: ${e.message}")
                            }
                        }
                    }
                }.awaitAll()
            }
        }.collect { o -> if (o is Outcome.Failed) ZLog.w(TAG, "episode lists of ${library.status(o.server)?.name}: ${if (o.timedOut) "timed out" else o.error.message}") }
    }

    /**
     * 从在看中移除: clears the resume point of every server taking part, each on its own
     * `serverId + itemId`, and hides the card here until the work is played again. When no
     * server could be cleared the card stays, so nothing looks removed that was not.
     */
    suspend fun remove(card: WatchCard): RemoveOutcome = coroutineScope {
        invalidate()
        val failed = card.resumePoints.map { ref ->
            async {
                try {
                    library.clearResume(ref.server, ref.item.id)
                    store.clearProgress(ref.server, ref.item.id)
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ZLog.w(TAG, "resume point not cleared on ${library.status(ref.server)?.name}: ${e.message}")
                    ref.server
                }
            }
        }.awaitAll().filterNotNull().distinct()
        val everyServerFailed = card.resumePoints.isNotEmpty() && failed.size == card.resumePoints.map { it.server }.distinct().size
        if (!everyServerFailed) store.watchingHidden.put(WatchingHiddenRow(card.key, card.lastActivity ?: 0))
        ZLog.i(TAG, "removed ${card.key} from 在看: ${card.resumePoints.size} resume points, ${failed.size} servers failed")
        RemoveOutcome(failed, hidden = !everyServerFailed)
    }

    private suspend fun <T> collect(servers: List<String>, what: String, call: suspend (String) -> T): List<Pair<String, T>> {
        val out = mutableListOf<Pair<String, T>>()
        fanOut.run(servers, FanOut.LIST_TIMEOUT_MS) { call(it) }.collect { o ->
            when (o) {
                is Outcome.Ok -> synchronized(out) { out += o.server to o.value }
                is Outcome.Failed -> ZLog.w(TAG, "$what without ${library.status(o.server)?.name ?: o.server}: ${if (o.timedOut) "timed out" else o.error.message}")
                is Outcome.Skipped -> ZLog.d(TAG, "$what skips ${library.status(o.server)?.name} (cooling down)")
            }
        }
        return out
    }

    private fun items(list: CachedList?): List<BaseItemDto> = list?.items.orEmpty().map { it.item }

    /** The episodes of [seriesId] that the servers' resume, next up and played lists of [server] mention. */
    private fun watchedEpisodes(server: String, seriesId: String, lists: Map<String, CachedList>): List<BaseItemDto> =
        listOf(resumeKey(server), nextUpKey(server), playedKey(server)).flatMap { items(lists[it]) }.filter { it.seriesId == seriesId }

    /** Series ids of the episodes in [server]'s Resume, NextUp and played lists. */
    private fun seriesIn(server: String, lists: Map<String, CachedList>): List<String> =
        listOf(resumeKey(server), nextUpKey(server), playedKey(server)).flatMap { items(lists[it]) }
            .filter { it.type == "Episode" }.mapNotNull { it.seriesId }.distinct()

    private fun externalIds(item: BaseItemDto): Map<String, String> =
        listOf("tmdb", "imdb", "tvdb").mapNotNull { p -> app.zmby.catalog.episodeProviderId(item, p)?.lowercase()?.let { p to it } }.toMap()

    companion object {
        private const val TAG = "Watching"
        /** Episode lists fetched per server and refresh. */
        private const val MAX_SERIES = 24
        fun resumeKey(server: String) = "resume:$server"
        fun nextUpKey(server: String) = "nextup:$server"
        fun playedKey(server: String) = "played:$server"
        fun seriesKey(server: String) = "watchseries:$server"
        fun episodesKey(server: String, seriesId: String) = "watcheps:$server:$seriesId"

        /** Resume, NextUp and recently played are asked again only after this long (user-confirmed 2026-11-04). */
        const val REFRESH_MS = 2 * 60_000L
        /** A watched series' whole episode list is kept this long when nothing about it changed. */
        const val EPISODES_MS = 6 * 60 * 60_000L

        /**
         * Whether a series' stored episode list must be asked again: never fetched, older than
         * [EPISODES_MS], or an episode the servers just listed (resume, next up, recently played)
         * is missing from it or has other progress or played state there.
         */
        fun episodesDue(stored: CachedList?, seen: List<BaseItemDto>, now: Long): Boolean {
            if (stored == null || stored.fetchedAt <= 0 || now - stored.fetchedAt !in 0 until EPISODES_MS) return true
            val byId = stored.items.associateBy { it.item.id }
            return seen.any { ep ->
                val have = byId[ep.id]?.item ?: return@any true
                (have.userData?.played == true) != (ep.userData?.played == true) ||
                    (have.userData?.playbackPositionTicks ?: 0) != (ep.userData?.playbackPositionTicks ?: 0)
            }
        }
    }
}
