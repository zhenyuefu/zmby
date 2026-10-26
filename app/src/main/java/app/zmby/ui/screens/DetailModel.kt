package app.zmby.ui.screens

import app.zmby.media.titleOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zmby.ZmbyApp
import app.zmby.aggregate.Confidence
import app.zmby.aggregate.ServerVersions
import app.zmby.aggregate.Verdict
import app.zmby.data.ServerItem
import app.zmby.log.ZLog
import app.zmby.store.CachedDetail
import app.zmby.ui.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One item's details, local first (local-data spec): the cached item and the last version cards
 * show at once; [refresh] loads canonical TMDB / Bangumi metadata, falling back to this one server's
 * item. Server copies are never merged across servers.
 */
class DetailViewModel(private val app: ZmbyApp, val server: String, val itemId: String, private val provider: app.zmby.catalog.CatalogId? = null, private val resourceOnly: Boolean = false) : ViewModel() {
    val detail: StateFlow<CachedDetail?> = app.store.detailFlow(server, itemId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Version cards from the last visit, by server, until this visit's lookup answers. */
    private val _cachedVersions = MutableStateFlow<Map<String, ServerVersions.Found>>(emptyMap())
    val cachedVersions: StateFlow<Map<String, ServerVersions.Found>> = _cachedVersions.asStateFlow()

    private val _lookup = MutableStateFlow<app.zmby.catalog.CatalogWork?>(null)
    val lookup: StateFlow<app.zmby.catalog.CatalogWork?> = _lookup.asStateFlow()
    private val _catalog = MutableStateFlow<app.zmby.catalog.CatalogWork?>(null)
    val catalog: StateFlow<app.zmby.catalog.CatalogWork?> = _catalog.asStateFlow()
    private val _fallbackReady = MutableStateFlow(false)
    val fallbackReady: StateFlow<Boolean> = _fallbackReady.asStateFlow()
    var catalogLanguageKey: List<String> = emptyList()
        private set
    private val generation = java.util.concurrent.atomic.AtomicInteger()
    private var refreshing: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val key = detail.filterNotNull().map { it.workKey }.first()
            _cachedVersions.value = cachedVersions(app, key)
        }
    }

    /** Opening the page and back from the player. */
    fun refresh(replace: Boolean = false) {
        if (refreshing?.isActive == true) { if (!replace) return else refreshing?.cancel() }
        val ticket = generation.incrementAndGet()
        val languageKey = listOf(app.settings.metadataLanguage.value,app.settings.posterLanguage.value,app.settings.logoLanguage.value,app.settings.tmdbToken.value)
        if(catalogLanguageKey != languageKey) { _catalog.value = null; _fallbackReady.value = false }
        refreshing = viewModelScope.launch(Dispatchers.IO) {
            val loader = DetailMetadataLoader(
                seed = { if (server == "catalog") null else app.store.detail(server, itemId)?.item },
                identity = { app.library.providerIdsOf(server, itemId)?.also { app.store.putItems(server,listOf(it)) } },
                serverDetail = { app.library.item(server, itemId).also { app.store.putDetail(server, it) } },
                resolve = { if (resourceOnly || it.type !in setOf("Movie", "Series")) null else app.catalog.resolve(it) },
                cached = app.catalog::cached,
                fetch = app.catalog::work,
            )
            try {
                loader.load(provider, server != "catalog", resourceOnly) { result ->
                    if(generation.get() != ticket) return@load
                    result.identity?.let { _lookup.value = it }
                    result.work?.let { catalogLanguageKey = languageKey; _catalog.value = it }
                    _fallbackReady.value = result.fallbackReady
                    _error.value = result.error
                }
            } catch (e: CancellationException) { throw e }

        }
    }

    companion object {
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

/** What the header shows without canonical metadata: the one server item's own fields. */
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

fun ZmbyApp.display(ref: ServerItem): DetailDisplay {
    val item = ref.item
    return DetailDisplay(
        title = titleOf(item),
        overview = item.overview,
        genres = item.genres,
        year = item.productionYear,
        runtimeMs = item.runTimeTicks?.let { it / 10_000 },
        rating = item.communityRating,
        ratingTmdb = item.providerIds.keys.any { it.equals("Tmdb", ignoreCase = true) },
        backdrop = library.backdrop(ref),
        logo = library.logo(ref),
    )
}

/** Canonical metadata replaces fields inside the existing detail layout. */
fun app.zmby.catalog.CatalogWork.display() = DetailDisplay(title, overview, genres, year, null, rating,
    ref.source == "tmdb", backdrop, logo)
