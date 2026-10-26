package app.zmby.aggregate

import app.zmby.data.Library
import app.zmby.data.ServerItem
import app.zmby.emby.BaseItemDto
import app.zmby.log.ZLog
import app.zmby.store.Freshness
import app.zmby.store.LocalStore
import app.zmby.store.StoreKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class HomeRows(
    val resume: List<ServerItem>,
    val nextUp: List<ServerItem>,
    val latest: List<ServerItem>,
    /** How many servers have the work behind a 最新添加 card, by [ServerItem.key]. */
    val latestCopies: Map<String, Int> = emptyMap(),
    /** Some row has been fetched before (cached or now), so empty rows mean empty. */
    val loaded: Boolean = false,
) {
    companion object { val EMPTY = HomeRows(emptyList(), emptyList(), emptyList()) }
}

/** Pure merging of the home rows, separate from fetching so it can be tested. */
object HomeMerge {
    /** A film's identity across servers: external IDs only (a wrong merge would hide a resume point). */
    fun filmKey(i: BaseItemDto): String? = i.tmdb()?.let { "tmdb:$it" } ?: i.imdb()?.let { "imdb:$it" }

    /**
     * 继续观看 across servers (aggregation spec): newest LastPlayedDate first, one entry per work.
     * Films dedupe on external IDs; episodes on their series' identity ([seriesKey], which may look
     * the series up) plus season and episode — asked only when two servers could collide.
     */
    suspend fun resume(hits: List<Hit>, seriesKey: suspend (Hit) -> String?): List<ServerItem> {
        val sorted = hits.sortedWith(compareByDescending<Hit> { it.item.userData?.lastPlayedDate.orEmpty() }.thenBy { it.order })
        // Episodes from different servers that might be the same: same normalized series name and S/E.
        val collide = sorted.filter { it.item.type == "Episode" }
            .groupBy { Triple(Titles.normalize(it.item.seriesName), it.item.parentIndexNumber, it.item.indexNumber) }
            .filterValues { g -> g.map { it.server }.distinct().size > 1 }
            .values.flatten().toSet()
        val seen = HashSet<String>()
        val out = mutableListOf<ServerItem>()
        for (h in sorted) {
            val key = when {
                h.item.type == "Episode" && h in collide -> Keys.episode(seriesKey(h), h.item.parentIndexNumber, h.item.indexNumber)
                h.item.type == "Movie" -> filmKey(h.item)
                else -> null
            } ?: Keys.local(h.server, h.item)
            if (seen.add(key)) out += h.ref
        }
        return out
    }

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

    /** 下一集: per server, minus series already in 继续观看 on that server. */
    fun nextUp(hits: List<Hit>, resume: List<ServerItem>): List<ServerItem> {
        val inResume = resume.mapNotNull { r -> r.item.seriesId?.let { r.server to it } }.toSet()
        return hits.sortedWith(compareBy<Hit> { it.order }.thenBy { it.rank })
            .filter { h -> (h.server to h.item.seriesId) !in inResume && resume.none { it.key == h.ref.key } }
            .map { it.ref }
            .distinctBy { it.key }
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

/** Which servers each home row asks, in priority order. */
data class HomeServers(val watch: List<String>, val latest: List<String>, val order: Map<String, Int>)

/**
 * The home rows (ui-spec §2.1), local first (local-data spec): [rows] merges what the store holds
 * for each server's 继续观看, 下一集 and 最新添加 and changes as the store does; [refresh] asks the
 * servers through [fanOut] (8-second list timeout) and writes their answers to the store. A slow or
 * failed server keeps its last rows; nothing is cleared.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HomeFeed(private val library: Library, private val fanOut: FanOut, private val store: LocalStore) {
    /** Series identities looked up this run ("server:seriesId" → key). */
    private val seriesKeys = ConcurrentHashMap<String, String>()

    fun rows(servers: HomeServers): Flow<HomeRows> {
        val keys = servers.watch.flatMap { listOf(resumeKey(it), nextUpKey(it)) } + servers.latest.map(::latestKey)
        return store.listsFlow(keys).map { lists ->
            fun hits(key: (String) -> String, ids: List<String>) = ids.flatMap { s ->
                lists[key(s)]?.items.orEmpty().mapIndexed { i, r -> Hit(r, servers.order[s] ?: Int.MAX_VALUE, i) }
            }
            val resumeRows = HomeMerge.resume(hits(::resumeKey, servers.watch), ::localSeriesKey)
            val (latestRows, counts) = HomeMerge.latest(hits(::latestKey, servers.latest))
            HomeRows(
                resume = resumeRows,
                nextUp = HomeMerge.nextUp(hits(::nextUpKey, servers.watch), resumeRows),
                latest = latestRows,
                latestCopies = counts,
                loaded = lists.values.any { it.fetchedAt > 0 },
            )
        }.flowOn(Dispatchers.Default)
    }

    /**
     * Asks the servers again: 继续观看 and 下一集 every time (local-data spec: entering home, back from
     * the player), 最新添加 only when older than ten minutes unless [force]. Returns each server's
     * fresh 最新添加 (for its pinyin probe).
     */
    suspend fun refresh(servers: HomeServers, force: Boolean = false, now: Long = System.currentTimeMillis()): Map<String, List<BaseItemDto>> = coroutineScope {
        val latestDue = servers.latest.filter { force || Freshness.due(Freshness.Kind.Latest, store.list(latestKey(it))?.fetchedAt, now) }
        val fresh = ConcurrentHashMap<String, List<BaseItemDto>>()
        val resume = launch { collect(servers.watch, "resume") { s -> library.resume(s) }.forEach { (s, items) -> store.putList(resumeKey(s), s, items) } }
        launch { collect(servers.watch, "next up") { s -> library.nextUp(s) }.forEach { (s, items) -> store.putList(nextUpKey(s), s, items) } }
        launch {
            collect(latestDue, "latest") { s -> library.latest(s) }.forEach { (s, items) ->
                store.putList(latestKey(s), s, items)
                fresh[s] = items
            }
        }
        resume.join()
        identifyCollidingEpisodes(servers)
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

    /**
     * Episodes in 继续观看 that might be the same on two servers (same series name and S/E) need their
     * series' identity; it is looked up once per server and series and stored, and the episodes are
     * stored again under the series' key.
     */
    private suspend fun identifyCollidingEpisodes(servers: HomeServers) {
        val lists = store.listsFlow(servers.watch.map(::resumeKey)).first()
        val episodes = servers.watch.flatMap { s -> lists[resumeKey(s)]?.items.orEmpty() }.filter { it.item.type == "Episode" && it.item.seriesId != null }
        val colliding = episodes.groupBy { Triple(Titles.normalize(it.item.seriesName), it.item.parentIndexNumber, it.item.indexNumber) }
            .filterValues { g -> g.map { it.server }.distinct().size > 1 }.values.flatten()
        for ((server, refs) in colliding.groupBy { it.server }) {
            var changed = false
            for (seriesId in refs.mapNotNull { it.item.seriesId }.distinct()) {
                if (localSeriesKey(server, seriesId) != null) continue
                val series = try { library.item(server, seriesId) } catch (e: CancellationException) { throw e } catch (_: Exception) { continue }
                store.putItems(server, listOf(series))
                StoreKeys.work(server, series).takeIf(StoreKeys::isExternal)?.let { seriesKeys["$server:$seriesId"] = it; changed = true }
            }
            if (changed) store.putItems(server, refs.map { it.item })
        }
    }

    private suspend fun localSeriesKey(h: Hit): String? = h.item.seriesId?.let { localSeriesKey(h.server, it) }

    private suspend fun localSeriesKey(server: String, seriesId: String): String? =
        seriesKeys["$server:$seriesId"] ?: store.workKeyOf(server, seriesId)?.takeIf(StoreKeys::isExternal)

    companion object {
        private const val TAG = "Home"
        fun resumeKey(server: String) = "resume:$server"
        fun nextUpKey(server: String) = "nextup:$server"
        fun latestKey(server: String) = "latest:$server"
        fun latestKey(server: String, libraryId: String) = "latest:$server:$libraryId"
        fun viewsKey(server: String) = "views:$server"
        fun suggestionsKey(server: String) = "hero:suggestions:$server"
        fun recommendationsKey(server: String) = "hero:recommendations:$server"
    }
}
