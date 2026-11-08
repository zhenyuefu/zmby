package app.zmby.ui.screens

import app.zmby.aggregate.Keys
import app.zmby.media.titleOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zmby.ZmbyApp
import app.zmby.trailer.ImdbTrailers
import app.zmby.trailer.TrailerInfo
import app.zmby.trailer.TrailerQuery
import app.zmby.trailer.TrailerFormat
import app.zmby.trailer.MAX_TRAILER_COPIES
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

/**
 * One item's details, local first (local-data spec): the cached item and the last version cards
 * show at once; [refresh] loads canonical TMDB / Bangumi metadata, falling back to this one server's
 * item. Server copies are never merged across servers.
 */
open class DetailViewModel(private val app: ZmbyApp, val server: String, val itemId: String, private val provider: app.zmby.catalog.CatalogId? = null, private val resourceOnly: Boolean = false) : ViewModel() {
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

    /** The catalogue work, only while it is in the current metadata languages. */
    val canonical: StateFlow<app.zmby.catalog.CatalogWork?> = kotlinx.coroutines.flow.combine(catalog, app.settings.metadataLanguage.flow, app.settings.posterLanguage.flow,
        app.settings.logoLanguage.flow, app.settings.tmdbToken.flow) { work, l, p, g, t ->
        work?.takeIf { catalogLanguageKey == listOf(l, p, g, t) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _companion = MutableStateFlow<CompanionWork?>(null)
    /**
     * The other catalogue's entry for [canonical] (影片信息来源 TMDB 与 Bangumi 逐字段互补), loaded only when
     * [MetadataMerge.wantsCompanion] says it may add something; local cache first, else one fetch.
     */
    val companion: StateFlow<CompanionWork?> = _companion.asStateFlow()

    /** The work's IMDb ID: the catalogue work, the other catalogue's entry, the identity lookup, then the page's own copy. */
    private val imdbId: kotlinx.coroutines.flow.Flow<String?> = kotlinx.coroutines.flow.combine(
        kotlinx.coroutines.flow.combine(_catalog, _lookup, _companion) { work, lookup, companion -> listOf(work?.item(), companion?.work?.item(), lookup?.item()) },
        detail.map { it?.ref?.item },
    ) { catalogs, own -> if (resourceOnly) null else ImdbTrailers.firstId(catalogs + own) }.distinctUntilChanged()

    /** Server copies of the work found by the page (a film's versions on the other servers), after its own copy. */
    protected val trailerCopies = MutableStateFlow<List<ServerItem>>(emptyList())

    private val _trailer = MutableStateFlow<TrailerInfo?>(null)

    /**
     * The work's trailer once found (trailer design; ui-spec §2.2): IMDb, then Rotten Tomatoes by the
     * IMDb ID, then the trailers stored on the page's own server copy and the copies the page found.
     * Asked [DETAIL_TRAILER_DELAY_MS] after what it needs is known, so the page's own requests go
     * first; stored answers need no request. A trailer already found is kept while what it came from
     * is unchanged (copies arriving one server at a time do not restart it). None for resource-only
     * pages; found also with 自动播放预告片 off, for the header's 预告片 button.
     */
    val trailer: StateFlow<TrailerInfo?> = _trailer.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val own = detail.map { d -> d?.ref?.takeIf { !resourceOnly && it.server != "catalog" } }.distinctUntilChanged { a, b -> a?.key == b?.key }
            var askedId: String? = null
            // Looked up also with 自动播放预告片 off: the header's 预告片 button still opens it (ui-spec §2.23).
            kotlinx.coroutines.flow.combine(imdbId, own, trailerCopies) { id, mine, found ->
                if (resourceOnly) null
                else TrailerQuery(id, (listOfNotNull(mine) + found).distinctBy { it.key }.take(MAX_TRAILER_COPIES))
            }.distinctUntilChanged { a, b -> a?.imdbId == b?.imdbId && a?.copies?.map { it.key } == b?.copies?.map { it.key } }
                .collectLatest { query ->
                    if (query == null || query.imdbId == null && query.copies.isEmpty()) { _trailer.value = null; askedId = null; return@collectLatest }
                    val current = _trailer.value
                    // IMDb and Rotten Tomatoes answer by the IMDb ID alone; a server's trailer while its copy stays.
                    if (current != null && current.format != TrailerFormat.Server && query.imdbId == askedId) return@collectLatest
                    if (current?.format == TrailerFormat.Server && query.imdbId == askedId && query.copies.any { it.server == current.source }) return@collectLatest
                    kotlinx.coroutines.delay(DETAIL_TRAILER_DELAY_MS)
                    _trailer.value = app.trailers.find(query)
                    askedId = query.imdbId
                }
        }
    }

    /**
     * IMDb, Tomatometer and Popcornmeter of the work (ratings design; ui-spec §2.2) by the same IMDb
     * ID as the trailer, which they share one IMDb request with; stored answers first. Null with
     * 「IMDb 与烂番茄评分」 off or without an IMDb ID.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val ratings: StateFlow<app.zmby.ratings.ExternalRatings?> = kotlinx.coroutines.flow.combine(imdbId, app.settings.externalRatings.flow) { id, on -> id.takeIf { on } }
        .distinctUntilChanged()
        .transformLatest { id ->
            emit(null)
            if (id != null) emit(app.ratings.of(id).takeUnless { it.isEmpty })
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun languageKey() = listOf(app.settings.metadataLanguage.value, app.settings.posterLanguage.value, app.settings.logoLanguage.value, app.settings.tmdbToken.value)

    init {
        // Opened from a catalogue card (the TMDB hero, 为你推荐): the work it showed is in memory, so the
        // page draws at once instead of flashing 「正在加载…」 until the cache read answers.
        provider?.takeIf { !resourceOnly }?.let(app.catalog::recent)?.let { work ->
            catalogLanguageKey = languageKey()
            _catalog.value = work
        }
        viewModelScope.launch(Dispatchers.IO) {
            val key = detail.filterNotNull().map { it.workKey }.first()
            _cachedVersions.value = cachedVersions(app, key)
        }
        viewModelScope.launch(Dispatchers.IO) {
            val ids = detail.map { it?.ref?.item?.providerIds.orEmpty() }.distinctUntilChanged()
            kotlinx.coroutines.flow.combine(canonical, ids, app.sources.rows, app.registry.lineup) { work, known, _, _ ->
                work?.takeIf { MetadataMerge.wantsCompanion(app.sources.metadata(), it) }?.let { it to known }
            }.distinctUntilChanged { a, b -> a?.first?.ref?.key == b?.first?.ref?.key && a?.first?.title == b?.first?.title && a?.second == b?.second }
                .collectLatest { need ->
                    if (need == null) { _companion.value = null; return@collectLatest }
                    val (work, _) = need
                    if (_companion.value?.primary != work.ref.key) _companion.value = null
                    loadCompanion(work) { _companion.value = CompanionWork(work.ref.key, it) }
                }
        }
    }

    /** The cached entry at once; fetched when missing or older than [COMPANION_FRESH_MS] (then published again). */
    private suspend fun loadCompanion(work: app.zmby.catalog.CatalogWork, publish: (app.zmby.catalog.CatalogWork) -> Unit) {
        try {
            val copies = listOfNotNull(detail.value?.ref?.item)
            val id = app.catalog.companion(work, copies)?.takeIf { it.source != "tmdb" || app.catalog.configured } ?: return
            val hit = app.catalog.cachedAt(id)
            hit?.let { publish(it.first) }
            if (hit != null && System.currentTimeMillis() - hit.second < COMPANION_FRESH_MS) return
            publish(app.catalog.work(id))
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            ZLog.i("Detail", "companion metadata unavailable: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Opening the page and back from the player ([force]). A server item fetched in full within
     * [app.zmby.data.Library.DETAIL_FRESH_MS] is shown from the store without asking again (RN ③).
     */
    fun refresh(replace: Boolean = false, force: Boolean = false) {
        if (refreshing?.isActive == true) { if (!replace) return else refreshing?.cancel() }
        val ticket = generation.incrementAndGet()
        val languageKey = languageKey()
        if(catalogLanguageKey != languageKey) { _catalog.value = null; _fallbackReady.value = false }
        refreshing = viewModelScope.launch(Dispatchers.IO) {
            val loader = DetailMetadataLoader(
                seed = { if (server == "catalog") null else app.store.detail(server, itemId)?.item },
                identity = { app.library.providerIdsOf(server, itemId)?.also { app.store.putItems(server,listOf(it)) } },
                serverDetail = {
                    // Fresh only after a full fetch here (putDetail below), so the stored copy is the full item.
                    val stored = app.store.detail(server, itemId)?.item
                    if (!force && stored != null && app.library.detailFresh(server, itemId)) stored
                    else app.library.item(server, itemId).also { app.store.putDetail(server, it); app.library.markDetailFresh(server, itemId) }
                },
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
        /** A detail page's trailer is looked up this long after its IMDb ID is known. */
        const val DETAIL_TRAILER_DELAY_MS = 800L

        /** How long the other catalogue's cached entry is used before it is fetched again (the catalogue answer cache's default day). */
        const val COMPANION_FRESH_MS = 24 * 60 * 60_000L

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

/** [work] is the other catalogue's entry for the catalogue work keyed [primary]. */
data class CompanionWork(val primary: String, val work: app.zmby.catalog.CatalogWork)

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

/**
 * The film (or single item) detail page's state holder: [DetailViewModel]'s metadata plus its versions
 * and their merged progress through a [VersionPresenter] (performance plan R4), so servers answering
 * recompose only the sections that show versions, not the page.
 */
internal class MovieDetailViewModel(private val app: ZmbyApp, server: String, itemId: String, provider: app.zmby.catalog.CatalogId?, resourceOnly: Boolean) :
    DetailViewModel(app, server, itemId, provider, resourceOnly) {
    val tick = MutableStateFlow(0)

    private val queries = kotlinx.coroutines.flow.combine(canonical, lookup, detail, kotlinx.coroutines.flow.combine(cachedVersions, fallbackReady) { c, f -> c to f }, tick) { canonical, lookup, cached, (cards, serverOnly), t ->
        val item = cached?.ref?.item ?: canonical?.item()
        // Resource lookup starts as soon as the external identity is known, while metadata is loading.
        val work = canonical ?: lookup
        null to when {
            work != null -> VersionQuery.Catalog(CatalogVersionLookup.Input(work, null, emptyList(), t, cached?.ref,
                manual = item?.let { app.library.pickedVersion(Keys.work(server, it)) }, lookUp = true, directoryPending = false))
            // Asked in server terms only once the page falls back to the server item: an item with
            // external IDs is usually identified in the catalogue a moment later, and asking both
            // ways would send the same requests twice. Until then last visit's cards show.
            item != null && server != "catalog" -> VersionQuery.Server(ServerVersionLookup.Input(server, item, null, serverOnly, cards, cached?.workKey))
            else -> VersionQuery.None
        }
    }.distinctUntilChanged()

    private val presenter = VersionPresenter(app, viewModelScope, queries, tick, onServerRefresh = { refresh(replace = true) }, onCatalogRefresh = null)

    /** The item's versions and their merged progress; read only by the sections that show them. */
    val view: StateFlow<EpisodeView> get() = presenter.view
    val hasPlayable: StateFlow<Boolean> get() = presenter.hasPlayable
    val versionsShown: StateFlow<Boolean> get() = presenter.anything
    val instances: StateFlow<List<ServerItem>> get() = presenter.instances

    init {
        // The versions found on the other servers are trailer sources too (after the page's own copy).
        viewModelScope.launch { presenter.instances.collect { trailerCopies.value = it } }
    }
}

