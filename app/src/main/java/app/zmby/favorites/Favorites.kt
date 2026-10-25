package app.zmby.favorites

import android.content.Context
import app.zmby.aggregate.FanOut
import app.zmby.aggregate.Keys
import app.zmby.aggregate.Outcome
import app.zmby.data.FavoriteSync
import app.zmby.data.Library
import app.zmby.data.ServerItem
import app.zmby.emby.BaseItemDto
import app.zmby.emby.EmbyException
import app.zmby.log.ZLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import app.zmby.store.LocalStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where a favourite lives on one server; [synced] = the server is known to have it marked. */
@Serializable
data class FavoriteInstance(val server: String, val itemId: String, val synced: Boolean = false)

/**
 * A favourite work (favorites spec): keyed by the work ([Keys.work]: external IDs, else title and
 * year, else the single item), with what the list shows. Removal leaves a tombstone so a sync
 * cannot bring it back.
 */
@Serializable
data class Favorite(
    val workKey: String,
    val title: String,
    val year: Int? = null,
    val type: String,
    val addedAt: Long,
    val updatedAt: Long,
    val removedAt: Long? = null,
    val instances: List<FavoriteInstance> = emptyList(),
    /** Poster: the Primary image tag of the first instance. */
    val imageTag: String? = null,
) {
    val active: Boolean get() = removedAt == null
}

@Serializable
data class PendingWrite(val server: String, val itemId: String, val favorite: Boolean, val attempts: Int = 0)

@Serializable
data class FavoritesState(
    val favorites: List<Favorite> = emptyList(),
    val queue: List<PendingWrite> = emptyList(),
    /** Last successful pull per server. */
    val lastPull: Map<String, Long> = emptyMap(),
)

/** The outcome of one server write. */
sealed interface WriteResult {
    data object Ok : WriteResult
    /** 401 / 403 / 405: the server does not let this user change favourites. */
    data class Refused(val code: Int) : WriteResult
    data class Error(val message: String) : WriteResult
}

/** Favourite rules without I/O, so they can be tested. */
object FavoriteLogic {
    const val MAX_ATTEMPTS = 10

    fun find(state: FavoritesState, key: String) = state.favorites.firstOrNull { it.workKey == key }

    /**
     * Favourite or un-favourite the work behind [lead] (local first). Writes are queued for every
     * known instance on a server whose policy is not [FavoriteSync.Off].
     */
    fun toggle(state: FavoritesState, lead: ServerItem, instances: List<ServerItem>, now: Long, policy: (String) -> FavoriteSync): FavoritesState {
        val key = Keys.work(lead.server, lead.item)
        val old = find(state, key)
        val adding = old?.active != true
        val known = (old?.instances.orEmpty().map { it.server to it.itemId } + instances.map { it.server to it.item.id }).distinct()
        val fav = Favorite(
            workKey = key,
            title = lead.item.name ?: old?.title.orEmpty(),
            year = lead.item.productionYear ?: old?.year,
            type = lead.item.type ?: old?.type ?: "Movie",
            addedAt = if (adding) now else old!!.addedAt,
            updatedAt = now,
            removedAt = if (adding) null else now,
            instances = known.map { (s, id) -> FavoriteInstance(s, id, old?.instances?.firstOrNull { it.server == s && it.itemId == id }?.synced ?: false) },
            imageTag = lead.item.imageTags["Primary"] ?: old?.imageTag,
        )
        val writes = known.filter { (s, _) -> policy(s) != FavoriteSync.Off }.map { (s, id) -> PendingWrite(s, id, adding) }
        // A newer intent replaces queued writes for the same item.
        val queue = state.queue.filterNot { q -> writes.any { it.server == q.server && it.itemId == q.itemId } } + writes
        return state.copy(favorites = state.favorites.filterNot { it.workKey == key } + fav, queue = queue)
    }

    /**
     * Merges one two-way server's favourites into the local list. Server-side additions join
     * (or revive a tombstone older than the last pull); a local removal newer than the last pull wins
     * and is written back; a synced instance the server no longer has, with no newer local change,
     * was removed in another client — removed here too (servers keep no favourite time, so local
     * operation time against the last pull is all there is).
     */
    fun pull(state: FavoritesState, server: String, items: List<BaseItemDto>, now: Long, policy: (String) -> FavoriteSync): FavoritesState {
        val lastPull = state.lastPull[server] ?: 0L
        var favorites = state.favorites
        var queue = state.queue
        val seen = HashSet<String>()
        for (item in items.filter { it.type == "Movie" || it.type == "Series" }) {
            val key = Keys.work(server, item)
            seen += key
            val old = favorites.firstOrNull { it.workKey == key }
            val instance = FavoriteInstance(server, item.id, synced = true)
            val updated = when {
                old == null -> Favorite(key, item.name.orEmpty(), item.productionYear, item.type!!, now, now, null, listOf(instance), item.imageTags["Primary"])
                old.active -> old.copy(instances = old.instances.filterNot { it.server == server && it.itemId == item.id } + instance)
                old.removedAt!! > lastPull -> {
                    queue = queue.filterNot { it.server == server && it.itemId == item.id } + PendingWrite(server, item.id, false)
                    old
                }
                else -> old.copy(removedAt = null, updatedAt = now, instances = old.instances.filterNot { it.server == server && it.itemId == item.id } + instance)
            }
            favorites = favorites.filterNot { it.workKey == key } + updated
        }
        if (lastPull > 0) {
            favorites = favorites.map { f ->
                val gone = f.active && f.updatedAt <= lastPull && f.workKey !in seen && f.instances.any { it.server == server && it.synced }
                if (!gone) return@map f
                // Removed elsewhere: follow the server, and tell the other servers that have it.
                f.instances.filter { it.server != server && policy(it.server) != FavoriteSync.Off }.forEach { i ->
                    queue = queue.filterNot { it.server == i.server && it.itemId == i.itemId } + PendingWrite(i.server, i.itemId, false)
                }
                f.copy(removedAt = now, updatedAt = now, instances = f.instances.map { if (it.server == server) it.copy(synced = false) else it })
            }
        }
        return FavoritesState(favorites, queue, state.lastPull + (server to now))
    }

    /** Applies a write's result. A refusal drops every queued write for that server (it is turned to 不同步). */
    fun written(state: FavoritesState, write: PendingWrite, result: WriteResult): FavoritesState = when (result) {
        WriteResult.Ok -> state.copy(
            queue = state.queue - write,
            favorites = state.favorites.map { f ->
                f.copy(instances = f.instances.map { if (it.server == write.server && it.itemId == write.itemId) it.copy(synced = write.favorite) else it })
            },
        )
        is WriteResult.Refused -> state.copy(queue = state.queue.filterNot { it.server == write.server })
        is WriteResult.Error -> state.copy(
            queue = state.queue.map { if (it == write) it.copy(attempts = it.attempts + 1) else it }.filter { it.attempts < MAX_ATTEMPTS },
        )
    }

    fun refusal(code: Int?) = code == 401 || code == 403 || code == 405
}

/**
 * The local favourites and their sync (favorites spec), persisted in the local store (P3; before
 * that one JSON blob in app-private preferences, migrated once by [load] and left in place).
 */
class Favorites(
    context: Context,
    private val library: Library,
    private val fanOut: FanOut,
    private val scope: CoroutineScope,
    private val store: LocalStore,
    /** In 设置 › 数据来源 › 收藏数据来源; a server outside it is treated as 不同步. */
    private val inSource: (String) -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val prefs = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val state = MutableStateFlow(FavoritesState())

    /** Active favourites, newest first. */
    private val _items = MutableStateFlow(active(state.value))
    val items: StateFlow<List<Favorite>> = _items.asStateFlow()

    /** The latest state to write; conflated, so a burst of changes is one transaction. */
    private val pending = Channel<FavoritesState>(Channel.CONFLATED)

    init {
        scope.launch(Dispatchers.IO) { for (next in pending) runCatching { store.saveFavorites(next) }.onFailure { ZLog.w(TAG, "saving favourites failed", it) } }
    }

    /** Reads the favourites from the store (startup, background); the first time, imports the old JSON. */
    suspend fun load() {
        val loaded = store.loadFavorites() ?: migrate()
        state.value = loaded
        _items.value = active(loaded)
    }

    private suspend fun migrate(): FavoritesState {
        if (store.marked(MIGRATED)) return FavoritesState()
        val old = prefs.getString(KEY, null)?.let(::decodeJson) ?: FavoritesState()
        if (old.favorites.isNotEmpty() || old.queue.isNotEmpty()) store.saveFavorites(old)
        store.mark(MIGRATED)
        ZLog.i(TAG, "moved ${old.favorites.size} favourites and ${old.queue.size} queued writes from preferences into the local store")
        return old
    }

    private fun active(s: FavoritesState) = s.favorites.filter { it.active }.sortedByDescending { it.addedAt }

    private fun policy(server: String): FavoriteSync =
        if (!inSource(server)) FavoriteSync.Off else library.status(server)?.config?.favoriteSync ?: FavoriteSync.Off

    private fun save(next: FavoritesState) {
        state.value = next
        _items.value = active(next)
        pending.trySend(next)
    }

    fun isFavorite(ref: ServerItem): Boolean = _items.value.any { f ->
        f.workKey == Keys.work(ref.server, ref.item) || f.instances.any { it.server == ref.server && (it.itemId == ref.item.id || it.itemId == ref.item.seriesId) }
    }

    /**
     * Toggles the work behind [ref] (an episode stands for its series) with the [instances] known on
     * screen (the versions found on other servers), then sends the writes.
     */
    suspend fun toggle(ref: ServerItem, instances: List<ServerItem>) {
        val lead = if (ref.item.type == "Episode" && ref.item.seriesId != null) {
            runCatching { ServerItem(ref.server, library.item(ref.server, ref.item.seriesId)) }.getOrNull() ?: return
        } else ref
        val all = if (lead === ref) instances else listOf(lead)
        lock.withLock { save(FavoriteLogic.toggle(state.value, lead, all, clock(), ::policy)) }
        ZLog.i(TAG, "${if (isFavorite(lead)) "added" else "removed"} ${lead.item.name} (${all.size} instances)")
        scope.launch(Dispatchers.IO) { drain() }
    }

    /** Sends queued writes to servers whose policy allows it; a refusing server is switched to 不同步. */
    suspend fun drain() {
        val writes = lock.withLock { state.value.queue.toList() }
        for (w in writes) {
            if (policy(w.server) == FavoriteSync.Off || library.status(w.server)?.enabled != true) continue
            val result = try {
                library.setFavorite(w.server, w.itemId, w.favorite)
                WriteResult.Ok
            } catch (e: EmbyException) {
                if (FavoriteLogic.refusal(e.code)) WriteResult.Refused(e.code!!) else WriteResult.Error(e.message.orEmpty())
            } catch (e: Exception) {
                WriteResult.Error(e.message ?: e.javaClass.simpleName)
            }
            ZLog.i(TAG, "write ${if (w.favorite) "add" else "remove"} ${w.itemId} on ${w.server}: $result")
            lock.withLock { save(FavoriteLogic.written(state.value, w, result)) }
            if (result is WriteResult.Refused) {
                library.registry.update(w.server) {
                    it.copy(favoriteSync = FavoriteSync.Off, favoriteSyncNote = "服务器拒绝写入收藏（${result.code}），已改为不同步")
                }
                ZLog.w(TAG, "${library.status(w.server)?.name} refused a favourite write (${result.code}); sync turned off")
            }
        }
    }

    /** Pulls the two-way servers' favourites (opening the favourites page, at most every [minGapMs]). */
    suspend fun pull(minGapMs: Long = PULL_GAP_MS) {
        val now = clock()
        val due = library.registry.enabled
            .filter { policy(it.id) == FavoriteSync.TwoWay && now - (state.value.lastPull[it.id] ?: 0) >= minGapMs }
            .map { it.id }
        // In parallel with the list timeout: one slow server does not hold up the others.
        fanOut.run(due, FanOut.LIST_TIMEOUT_MS) { s -> library.browse(s, "Movie,Series", favorites = true, limit = PULL_LIMIT).items }.collect { o ->
            when (o) {
                is Outcome.Ok -> lock.withLock { save(FavoriteLogic.pull(state.value, o.server, o.value, clock(), ::policy)) }
                is Outcome.Failed -> ZLog.w(TAG, "pull from ${library.status(o.server)?.name} failed: ${o.error.message}")
                is Outcome.Skipped -> Unit
            }
        }
        drain()
    }

    companion object {
        private const val TAG = "Favorites"
        private const val KEY = "state"
        private const val MIGRATED = "migration:favorites_json"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        /** The P2 preferences blob; unreadable content is no favourites rather than a crash. */
        fun decodeJson(text: String): FavoritesState? = runCatching { json.decodeFromString(FavoritesState.serializer(), text) }.getOrNull()
        private const val PULL_GAP_MS = 10 * 60_000L
        private const val PULL_LIMIT = 300
    }
}
