package app.zmby.favorites

import app.zmby.aggregate.Keys
import app.zmby.data.Library
import app.zmby.data.ServerItem
import app.zmby.log.ZLog
import app.zmby.store.LocalStore
import app.zmby.store.WatchlistRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** 待看 sort orders (favorites spec): newest added first by default. */
enum class WatchlistSort(val label: String) { Added("加入时间"), Title("片名"), Released("发行日期") }

/** Watchlist rules without I/O, for tests. */
object WatchlistLogic {
    /** Whether [entry] stands for the work [ref] belongs to (an episode stands for its series). */
    fun matches(entry: WatchlistRow, ref: ServerItem): Boolean {
        val item = ref.item
        if (entry.workKey == Keys.work(ref.server, item)) return true
        return entry.instances.any { pair ->
            val (server, id) = pair.split('|', limit = 2).let { it[0] to it.getOrNull(1) }
            server == ref.server && (id == item.id || (item.seriesId != null && id == item.seriesId))
        }
    }

    /**
     * Works to take off the list because watching started (favorites spec: 开始看了就移到继续观看):
     * anything that has a resume point or was watched — a film, or any episode of a series.
     */
    fun started(entries: List<WatchlistRow>, played: List<ServerItem>): List<String> {
        val started = played.filter { (it.item.userData?.playbackPositionTicks ?: 0) > 0 || it.item.userData?.played == true }
        return entries.filter { e -> started.any { matches(e, it) } }.map { it.workKey }
    }

    fun sorted(entries: List<WatchlistRow>, sort: WatchlistSort): List<WatchlistRow> = when (sort) {
        WatchlistSort.Added -> entries.sortedByDescending { it.addedAt }
        WatchlistSort.Title -> entries.sortedBy { it.title }
        WatchlistSort.Released -> entries.sortedWith(compareByDescending<WatchlistRow> { it.premiereDate ?: it.year?.let { y -> "%04d".format(y) } ?: "" }.thenByDescending { it.addedAt })
    }

    fun entry(lead: ServerItem, instances: List<ServerItem>, now: Long): WatchlistRow {
        val item = lead.item
        return WatchlistRow(
            workKey = Keys.work(lead.server, item),
            title = item.name.orEmpty(),
            year = item.productionYear,
            type = item.type ?: "Movie",
            premiereDate = item.premiereDate,
            addedAt = now,
            instances = (listOf(lead) + instances).map { "${it.server}|${it.item.id}" }.distinct(),
            imageTag = item.imageTags["Primary"],
        )
    }
}

/**
 * 待看 (favorites spec): purely local, never synced to a server and never written as an Emby
 * favourite; it lives in the store's `watchlist` table.
 */
class Watchlist(private val library: Library, private val store: LocalStore, scope: CoroutineScope, private val clock: () -> Long = System::currentTimeMillis) {
    val entries: StateFlow<List<WatchlistRow>> = store.watchlist.allFlow().stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun contains(ref: ServerItem): Boolean = entries.value.any { WatchlistLogic.matches(it, ref) }

    /** Adds or removes the work behind [ref] (an episode stands for its series). */
    suspend fun toggle(ref: ServerItem, instances: List<ServerItem> = emptyList()) {
        val existing = entries.value.filter { WatchlistLogic.matches(it, ref) }
        if (existing.isNotEmpty()) {
            store.watchlist.delete(existing.map { it.workKey })
            ZLog.i(TAG, "removed ${ref.item.name}")
            return
        }
        val lead = if (ref.item.type == "Episode" && ref.item.seriesId != null) {
            runCatching { ServerItem(ref.server, library.item(ref.server, ref.item.seriesId)) }.getOrNull()
                ?: store.detail(ref.server, ref.item.seriesId) ?: return
        } else ref
        store.watchlist.upsert(WatchlistLogic.entry(lead, if (lead === ref) instances else emptyList(), clock()))
        ZLog.i(TAG, "added ${lead.item.name}")
    }

    /** Playback produced progress, or a resume list shows some: those works leave the watchlist. */
    suspend fun removeStarted(played: List<ServerItem>) {
        val keys = WatchlistLogic.started(store.watchlist.all(), played)
        if (keys.isEmpty()) return
        store.watchlist.delete(keys)
        ZLog.i(TAG, "watching started, off the watchlist: ${keys.joinToString()}")
    }

    private companion object { const val TAG = "Watchlist" }
}
