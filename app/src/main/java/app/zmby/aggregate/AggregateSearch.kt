package app.zmby.aggregate

import app.zmby.data.ServerItem
import app.zmby.emby.BaseItemDto
import app.zmby.log.ZLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** The search calls per server ([app.zmby.data.Library] implements it). */
interface SearchSource {
    /** Step 1: the server's own SearchTerm over its searchable libraries (films and series). */
    suspend fun search(server: String, term: String): List<BaseItemDto>
    /** Step 2: `AnyProviderIdEquals`; may return unrelated items (servers that ignore the filter). */
    suspend fun byProviderIds(server: String, type: String, ids: List<String>): List<BaseItemDto>
}

enum class SearchState { Querying, Done, Failed, TimedOut, Skipped }

/** Where a search is: every hit so far and each server's state. [done] once no server is still working. */
data class SearchSnapshot(val hits: List<Hit>, val states: Map<String, SearchState>, val done: Boolean) {
    /** Servers that answered (failed, timed-out and skipped ones did not). */
    val returned: Int get() = states.values.count { it == SearchState.Done }
}

/**
 * 两步聚合搜索 (aggregation spec). Step 1: each server searches the term itself. Step 2: the TMDB
 * IDs of all hits are looked up on every server that did not return them, 20 per request (at most
 * [MAX_IDS] per type and server) and filtered here again — that finds the same film under another
 * translated title and covers servers whose search only matches word prefixes. Step 2 starts when
 * step 1 is complete or after [STEP2_AFTER_MS], with one more round for IDs that arrive later.
 * Snapshots stream out as servers answer; a slow server never holds the others back.
 */
class AggregateSearch(private val source: SearchSource, private val fanOut: FanOut) {

    /**
     * [servers] in priority order (id to order). [reuse]: a pinyin candidate's own result, shown
     * for its server instead of asking that server again.
     */
    fun search(servers: List<Pair<String, Int>>, term: String, reuse: Hit? = null): Flow<SearchSnapshot> = channelFlow {
        val order = servers.toMap()
        val lock = Mutex()
        val hits = mutableListOf<Hit>()
        val states = servers.associate { it.first to SearchState.Querying }.toMutableMap()
        var finished = false
        suspend fun emit() = lock.withLock { send(SearchSnapshot(hits.toList(), states.toMap(), finished && states.values.none { it == SearchState.Querying })) }
        fun add(server: String, items: List<BaseItemDto>) {
            val base = hits.count { it.server == server }
            items.filter { it.type == "Movie" || it.type == "Series" }
                .filter { i -> hits.none { it.server == server && it.item.id == i.id } }
                .forEachIndexed { i, item -> hits += Hit(ServerItem(server, item), order[server] ?: Int.MAX_VALUE, base + i) }
        }
        if (reuse != null && reuse.server in order) {
            hits += reuse
            states[reuse.server] = SearchState.Done
        }
        val ask = servers.map { it.first }.filter { states[it] == SearchState.Querying }
        val stepOne = CompletableDeferred<Unit>()
        if (ask.isEmpty()) stepOne.complete(Unit)
        emit()

        launch {
            var left = ask.size
            fanOut.run(ask, FanOut.SEARCH_TIMEOUT_MS, share = "search:$term") { s -> source.search(s, term) }.collect { o ->
                lock.withLock {
                    states[o.server] = when (o) {
                        is Outcome.Ok -> SearchState.Done.also { add(o.server, o.value) }
                        is Outcome.Failed -> if (o.timedOut) SearchState.TimedOut else SearchState.Failed
                        is Outcome.Skipped -> SearchState.Skipped
                    }
                }
                emit()
                if (--left == 0) stepOne.complete(Unit)
            }
        }

        launch {
            val asked = HashMap<String, MutableSet<String>>() // server -> "type:tmdb" already asked
            suspend fun round() {
                val (wanted, answered) = lock.withLock {
                    val ids = hits.mapNotNull { h -> h.item.tmdb()?.let { h.item.type!! to it } }.distinct()
                    ids to states.filterValues { it == SearchState.Done }.keys.toList()
                }
                if (wanted.isEmpty() || answered.isEmpty()) return
                val plan = lock.withLock {
                    answered.associateWith { server ->
                        val have = hits.filter { it.server == server }.mapNotNull { h -> h.item.tmdb()?.let { h.item.type!! to it } }.toSet()
                        val done = asked.getOrPut(server) { HashSet() }
                        wanted.filter { it !in have && "${it.first}:${it.second}" !in done }
                            .groupBy({ it.first }, { it.second })
                            .mapValues { (_, v) -> v.take(MAX_IDS) }
                            .also { m -> m.forEach { (type, list) -> list.forEach { done += "$type:$it" } } }
                    }.filterValues { it.isNotEmpty() }
                }
                if (plan.isEmpty()) return
                fanOut.run(plan.keys.toList(), FanOut.SEARCH_TIMEOUT_MS) { server ->
                    coroutineScope {
                        plan.getValue(server).flatMap { (type, ids) ->
                            ids.chunked(BATCH).flatMap { batch ->
                                val wantedIds = batch.toSet()
                                source.byProviderIds(server, type, batch.map { "tmdb.$it" })
                                    // A server that ignores the filter answers with its whole library.
                                    .filter { it.type == type && it.tmdb() in wantedIds }
                            }
                        }
                    }
                }.collect { o ->
                    if (o is Outcome.Ok) {
                        lock.withLock { add(o.server, o.value) }
                        if (o.value.isNotEmpty()) ZLog.d(TAG, "step 2 found ${o.value.size} more on ${o.server}")
                        emit()
                    }
                }
            }
            withTimeoutOrNull(STEP2_AFTER_MS) { stepOne.await() }
            round()
            stepOne.await()
            round()
            lock.withLock { finished = true }
            emit()
        }
    }

    companion object {
        private const val TAG = "Search"
        const val BATCH = 20
        const val MAX_IDS = 40
        const val STEP2_AFTER_MS = 4_000L
    }
}
