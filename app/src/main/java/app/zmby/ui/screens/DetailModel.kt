package app.zmby.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zmby.ZmbyApp
import app.zmby.aggregate.Confidence
import app.zmby.aggregate.ServerVersions
import app.zmby.aggregate.Verdict
import app.zmby.data.ServerItem
import app.zmby.store.StoreKeys
import app.zmby.log.ZLog
import app.zmby.store.CachedDetail
import app.zmby.store.CastEntry
import app.zmby.store.WorkMeta
import app.zmby.ui.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One item's details, local first (local-data spec): what the store has (the merged work, the cast,
 * the last version cards) shows at once; [refresh] fetches the item again, the image sizes it still
 * needs and merges the work anew, and the screen follows the store.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailViewModel(private val app: ZmbyApp, val server: String, val itemId: String) : ViewModel() {
    val detail: StateFlow<CachedDetail?> = app.store.detailFlow(server, itemId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val meta: StateFlow<WorkMeta?> = detail.map { it?.workKey }.distinctUntilChanged()
        .flatMapLatest { key -> if (key == null) flowOf(null) else app.store.metaFlow(key) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val cast: StateFlow<List<CastEntry>> = detail.map { it?.workKey }.distinctUntilChanged()
        .flatMapLatest { key -> if (key == null) flowOf(emptyList()) else app.store.castFlow(key) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Version cards from the last visit, by server, until this visit's lookup answers. */
    private val _cachedVersions = MutableStateFlow<Map<String, ServerVersions.Found>>(emptyMap())
    val cachedVersions: StateFlow<Map<String, ServerVersions.Found>> = _cachedVersions.asStateFlow()

    private var refreshing: Job? = null

    init {
        viewModelScope.launch {
            val key = detail.filterNotNull().map { it.workKey }.first()
            _cachedVersions.value = cachedVersions(app, key)
        }
    }

    /** Opening the page and back from the player. */
    fun refresh() {
        if (refreshing?.isActive == true) return
        refreshing = viewModelScope.launch {
            try {
                val item = app.library.item(server, itemId)
                _error.value = null
                val key = app.store.putDetail(server, item)
                app.store.touch(server, itemId, key)
                refreshMeta(app, key, server, itemId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Offline or failing: the cached page stays; only an empty page says why.
                ZLog.w("Detail", "refresh of $server:$itemId failed: ${e.message}")
                if (detail.value == null) _error.value = e.userMessage()
            }
        }
    }

    companion object {
        /**
         * Asks the metadata servers that have this work for the image sizes still unknown (once per
         * instance), then merges the work again.
         */
        suspend fun refreshMeta(app: ZmbyApp, key: String, server: String, itemId: String) {
            val sources = app.metadataServers()
            for ((s, id) in app.store.missingImages(key, sources.toSet())) {
                // A work keyed by one item only takes that item's images.
                if (!StoreKeys.isExternal(key) && (s != server || id != itemId)) continue
                runCatching { app.library.images(s, id) }
                    .onSuccess { app.store.putImages(s, id, it) }
                    .onFailure { if (it is CancellationException) throw it; ZLog.d("Detail", "image sizes of $s:$id: ${it.message}") }
            }
            app.store.remerge(key, sources)
        }

        suspend fun cachedVersions(app: ZmbyApp, forKey: String): Map<String, ServerVersions.Found> =
            app.store.versions(forKey).mapNotNull { v ->
                val item = app.store.decodeItem(v.itemJson) ?: return@mapNotNull null
                val sources = app.store.decodeSources(v.sourcesJson).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                v.serverId to ServerVersions.Found(v.serverId, item, sources, Verdict(if (v.possible) Confidence.Possible else Confidence.Strong, v.reason))
            }.toMap()

        /** Keeps a found version card for next time ([forKey] = the work on screen). */
        suspend fun rememberVersions(app: ZmbyApp, forKey: String, found: ServerVersions) {
            when (found) {
                is ServerVersions.Found -> {
                    app.store.putItems(found.server, listOf(found.item))
                    app.store.putVersion(forKey, found.server, found.item, found.sources, found.verdict.reason, found.verdict.confidence == Confidence.Possible)
                }
                // Gone from that server: its old card must not come back next time.
                is ServerVersions.NotFound, is ServerVersions.Missing -> app.store.dropVersion(forKey, found.server)
                else -> Unit
            }
        }
    }
}

/** What the header shows: the merged work where the store has one, else the item's own fields. */
data class DetailDisplay(
    val title: String,
    val overview: String?,
    val genres: List<String>,
    val year: Int?,
    val runtimeMs: Long?,
    val rating: Double?,
    val ratingTmdb: Boolean,
    val backdrop: String?,
    val logo: String?,
)

fun ZmbyApp.display(ref: ServerItem, meta: WorkMeta?): DetailDisplay {
    val item = ref.item
    return DetailDisplay(
        title = meta?.title ?: item.name.orEmpty(),
        overview = meta?.overview ?: item.overview,
        genres = meta?.genres?.takeIf { it.isNotEmpty() } ?: item.genres,
        year = meta?.year ?: item.productionYear,
        runtimeMs = (meta?.runtimeTicks ?: item.runTimeTicks)?.let { it / 10_000 },
        rating = meta?.rating ?: item.communityRating,
        ratingTmdb = meta?.ratingTmdb ?: item.providerIds.keys.any { it.equals("Tmdb", ignoreCase = true) },
        backdrop = meta?.backdrop?.let { library.imageUrl(it, "Backdrop", 1080) } ?: library.backdrop(ref),
        logo = meta?.logo?.let { library.imageUrl(it, "Logo", 340) } ?: library.logo(ref),
    )
}
