package app.zmby.aggregate

import app.zmby.data.Library
import app.zmby.data.ServerItem
import app.zmby.emby.BaseItemDto
import app.zmby.log.ZLog
import app.zmby.store.Freshness
import app.zmby.store.LocalStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class HomeRows(
    /** 在看 (ui-spec §2.18): one card per work, the same list as the 在看 page. */
    val watching: List<WatchCard>,
    val latest: List<ServerItem>,
    /** How many servers have the work behind a 最新添加 card, by [ServerItem.key]. */
    val latestCopies: Map<String, Int> = emptyMap(),
    /** Some row has been fetched before (cached or now), so empty rows mean empty. */
    val loaded: Boolean = false,
) {
    companion object { val EMPTY = HomeRows(emptyList(), emptyList()) }
}

/** Pure merging of the home rows, separate from fetching so it can be tested. */
object HomeMerge {
    /** 最新添加 across servers: merged into works (strong IDs only), newest first; [copies] counts servers. */
    fun latest(hits: List<Hit>, limit: Int = 40): Pair<List<ServerItem>, Map<String, Int>> {
        val merged = Merger.merge(hits)
        // A work is as new as its newest copy: added on any server just now is "latest".
        data class Row(val lead: Hit, val servers: Int, val added: String)
        val rows = merged.works.map { w -> Row(w.lead, w.servers, w.entries.maxOf { it.item.dateCreated.orEmpty() }) } +
            merged.unmerged.map { Row(it, 1, it.item.dateCreated.orEmpty()) }
        val sorted = rows.sortedWith(compareByDescending<Row> { it.added }.thenBy { it.lead.order }).take(limit)
        return sorted.map { it.lead.ref } to sorted.associate { it.lead.ref.key to it.servers }
    }
}

/** One server's own home (ui-spec §2.1 首页来源切换): its libraries and each library's newest items. */
data class ServerHome(
    val server: String,
    val libraries: List<ServerItem>,
    /** Per library id, newest first. */
    val latest: Map<String, List<ServerItem>>,
    /** The server's newest items across its libraries, for the hero. */
    val recent: List<ServerItem>,
    val loaded: Boolean,
)

/**
 * Which servers each home row asks, in priority order: [order] is 首页与影视来源's (最新添加, 主推区),
 * [watchOrder] 观看记录来源's (在看). Each follows the server page's order until sorted by hand.
 */
data class HomeServers(val watch: List<String>, val latest: List<String>, val order: Map<String, Int>, val watchOrder: Map<String, Int> = order)

/**
 * The home rows (ui-spec §2.1), local first (local-data spec): [rows] merges what the store holds
 * for 在看 ([WatchingFeed]) and each server's 最新添加 and changes as the store does; [refresh] asks the
 * servers through [fanOut] (8-second list timeout) and writes their answers to the store. A slow or
 * failed server keeps its last rows; nothing is cleared.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HomeFeed(private val library: Library, private val fanOut: FanOut, private val store: LocalStore) {
    val watching = WatchingFeed(library, fanOut, store)

    fun rows(servers: HomeServers): Flow<HomeRows> {
        val keys = servers.latest.map(::latestKey)
        val latest = store.listsFlow(keys).map { lists ->
            val hits = servers.latest.flatMap { s -> lists[latestKey(s)]?.items.orEmpty().mapIndexed { i, r -> Hit(r, servers.order[s] ?: Int.MAX_VALUE, i) } }
            HomeMerge.latest(hits) to lists.values.any { it.fetchedAt > 0 }
        }
        val watchKeys = servers.watch.map(WatchingFeed::resumeKey)
        return kotlinx.coroutines.flow.combine(latest, watching.cards(servers.watch, servers.watchOrder), store.listsFlow(watchKeys)) { (l, loaded), cards, watchLists ->
            HomeRows(
                watching = cards,
                latest = l.first,
                latestCopies = l.second,
                loaded = loaded || watchLists.values.any { it.fetchedAt > 0 },
            )
        }.flowOn(Dispatchers.Default)
    }

    /**
     * Asks the servers again: 在看 every time (local-data spec: entering home, back from
     * the player), 最新添加 only when older than ten minutes unless [force]. Returns each server's
     * fresh 最新添加 (for its pinyin probe).
     */
    suspend fun refresh(servers: HomeServers, force: Boolean = false, now: Long = System.currentTimeMillis(), watchingForce: Boolean = force): Map<String, List<BaseItemDto>> = coroutineScope {
        val latestDue = servers.latest.filter { force || Freshness.due(Freshness.Kind.Latest, store.list(latestKey(it))?.fetchedAt, now) }
        val fresh = ConcurrentHashMap<String, List<BaseItemDto>>()
        launch { watching.refresh(servers.watch, watchingForce, now) }
        launch {
            collect(latestDue, "latest") { s -> library.latest(s) }.forEach { (s, items) ->
                store.putList(latestKey(s), s, items)
                fresh[s] = items
            }
        }
        fresh
    }

    /**
     * 主推区 from the servers (ui-spec §2.1): each server's stored suggestions and film
     * recommendations, as (suggestions, recommendations) by server; changes as the store does.
     */
    fun heroLists(servers: List<String>): Flow<Pair<Map<String, List<BaseItemDto>>, Map<String, List<BaseItemDto>>>> =
        store.listsFlow(servers.flatMap { listOf(suggestionsKey(it), recommendationsKey(it)) }).map { lists ->
            servers.associateWith { s -> lists[suggestionsKey(s)]?.items.orEmpty().map { it.item } } to
                servers.associateWith { s -> lists[recommendationsKey(s)]?.items.orEmpty().map { it.item } }
        }.flowOn(Dispatchers.Default)

    /**
     * Asks [servers] for their suggestions and film recommendations when older than ten minutes (or
     * [force]). A server without the endpoints, or one that fails them, stores nothing for that
     * list, so the hero quietly falls back to the latest additions. Read-only requests.
     */
    suspend fun refreshHero(servers: List<String>, force: Boolean = false, now: Long = System.currentTimeMillis()) {
        val due = servers.filter { force || Freshness.due(Freshness.Kind.Latest, store.list(suggestionsKey(it))?.fetchedAt, now) }
        if (due.isEmpty()) return
        suspend fun <T> quietly(what: String, server: String, call: suspend () -> List<T>): List<T> = try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ZLog.d(TAG, "$what of ${library.status(server)?.name}: ${e.message}")
            emptyList()
        }
        fanOut.run(due, FanOut.LIST_TIMEOUT_MS) { s ->
            quietly("suggestions", s) { library.suggestions(s) } to quietly("movie recommendations", s) { library.movieRecommendations(s) }
        }.collect { o ->
            if (o is Outcome.Ok) {
                store.putList(suggestionsKey(o.server), o.server, o.value.first)
                store.putList(recommendationsKey(o.server), o.server, o.value.second)
            }
        }
    }

    /** [server]'s own home from the store: libraries (home switch on) and their newest items. */
    fun serverHome(server: String): Flow<ServerHome> =
        store.listsFlow(listOf(viewsKey(server))).map { lists -> library.homeLibraries(server, lists[viewsKey(server)]?.items.orEmpty().map { it.item }) }
            .distinctUntilChanged()
            .flatMapLatest { libs ->
                store.listsFlow(libs.map { latestKey(server, it.id) }).map { lists ->
                    val latest = libs.associate { it.id to lists[latestKey(server, it.id)]?.items.orEmpty() }
                    val recent = latest.values.flatten().distinctBy { it.item.id }.sortedByDescending { it.item.dateCreated.orEmpty() }
                    ServerHome(server, libs.map { ServerItem(server, it) }, latest, recent, lists.values.any { it.fetchedAt > 0 })
                }
            }.flowOn(Dispatchers.Default)

    /** One server's home: the newest of each library (one Latest request per library), when older than ten minutes. */
    suspend fun refreshServer(server: String, force: Boolean = false, now: Long = System.currentTimeMillis()) = coroutineScope {
        val libs = try { library.libraries(server).filter { it.onHome } } catch (e: CancellationException) { throw e } catch (e: Exception) {
            ZLog.w(TAG, "libraries of ${library.status(server)?.name}: ${e.message}"); return@coroutineScope
        }
        val due = libs.filter { force || Freshness.due(Freshness.Kind.Latest, store.list(latestKey(server, it.id))?.fetchedAt, now) }
        val slots = kotlinx.coroutines.sync.Semaphore(2)
        due.map { lib ->
            launch {
                slots.acquire()
                try {
                    val outcome = fanOut.all(listOf(server), FanOut.LIST_TIMEOUT_MS) { library.latestIn(server, lib.id) }.single()
                    if (outcome is Outcome.Ok) store.putList(latestKey(server, lib.id), server, outcome.value.map { it.item })
                } finally {
                    slots.release()
                }
            }
        }
    }

    private suspend fun collect(servers: List<String>, what: String, call: suspend (String) -> List<ServerItem>): List<Pair<String, List<BaseItemDto>>> {
        val out = mutableListOf<Pair<String, List<BaseItemDto>>>()
        fanOut.run(servers, FanOut.LIST_TIMEOUT_MS) { call(it) }.collect { o ->
            when (o) {
                is Outcome.Ok -> synchronized(out) { out += o.server to o.value.map { it.item } }
                is Outcome.Failed -> ZLog.w(TAG, "$what without ${library.status(o.server)?.name ?: o.server}: ${if (o.timedOut) "timed out" else o.error.message}")
                is Outcome.Skipped -> ZLog.d(TAG, "$what skips ${library.status(o.server)?.name} (cooling down)")
            }
        }
        return out
    }

    companion object {
        private const val TAG = "Home"
        fun latestKey(server: String) = "latest:$server"
        fun latestKey(server: String, libraryId: String) = "latest:$server:$libraryId"
        fun viewsKey(server: String) = "views:$server"
        fun suggestionsKey(server: String) = "hero:suggestions:$server"
        fun recommendationsKey(server: String) = "hero:recommendations:$server"
    }
}
