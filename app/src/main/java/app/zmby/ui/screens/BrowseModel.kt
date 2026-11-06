package app.zmby.ui.screens

import app.zmby.ui.userMessage

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zmby.ZmbyApp
import app.zmby.data.BrowseFilters
import app.zmby.data.BrowseQuery
import app.zmby.data.SourceFeature
import app.zmby.data.LibraryView
import app.zmby.data.ServerItem
import app.zmby.data.SortCheck
import app.zmby.log.ZLog
import app.zmby.store.Freshness
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** What the browse toolbar and grid show. */
@Immutable
data class BrowseState(
    val server: String? = null,
    val libraries: List<LibraryView> = emptyList(),
    /** null = every library of this kind on the server. */
    val libraryId: String? = null,
    val query: BrowseQuery = BrowseQuery(),
    val total: Int = 0,
    val loaded: Boolean = false,
    /** The server did not keep the order of this sort (SortCheck). */
    val sortUnsupported: Boolean = false,
    val genres: List<String> = emptyList(),
    /** Bumps on every server, library or query change: the grid starts again from the top. */
    val generation: Int = 0,
    /** The first page failed and nothing is cached (shown with 重试). */
    val error: String? = null,
    /** Everything in the library without the filters (the count beside the page name). */
    val libraryTotal: Int? = null,
    /** What this server honours here (浏览页筛选); everything until probed. */
    val filters: BrowseFilters = BrowseFilters(),
    /** The server has collections (电影 only): the library segments end with 合集. */
    val hasCollections: Boolean = false,
) {
    /** 合集 is chosen: the grid lists the server's collections (BoxSet). */
    val collections: Boolean get() = libraryId == BrowseViewModel.COLLECTIONS
}

/**
 * 电影 / 剧集 browsing (ui-spec §2.8): one server and library at a time, never merged across servers.
 * The grid has a slot for every item ([BrowseState.total]); pages of [PAGE] are fetched as slots come
 * into view, shown from the store first (local-data spec: older than an hour is fetched again).
 */
class BrowseViewModel(private val app: ZmbyApp, private val types: String) : ViewModel() {
    private val _state = MutableStateFlow(BrowseState())
    val state: StateFlow<BrowseState> = _state.asStateFlow()

    private val _pages = MutableStateFlow<Map<Int, List<ServerItem>>>(emptyMap())
    /** Loaded pages by page number. */
    val pages: StateFlow<Map<Int, List<ServerItem>>> = _pages.asStateFlow()

    private val loading = HashMap<Int, Job>()
    /** The slots last reported on screen, to fetch their pages once the total is known. */
    private var lastRange = 0 to 0
    private val checkedSorts = HashSet<String>()
    /** Library totals by "server|library", known from an unfiltered first page or a count request. */
    private val libraryTotals = HashMap<String, Int>()

    /** Probes in flight, by [filterKey]. */
    private val probing = HashSet<String>()

    init {
        val shown = app.sources.servers(SourceFeature.Browse)
        val home = app.settings.homeSource.value.takeIf { it in shown }
        // 默认跟随首页所选服务器；首页为「全部」时取优先级最高的服务器（首页与影视来源里隐藏的不算）.
        select(home ?: shown.firstOrNull(), null)
    }

    /** The item type the grid asks for: collections under 合集. */
    private fun itemTypes(s: BrowseState) = if (s.collections) "BoxSet" else types

    /** The library the grid narrows to: none under 合集 (collections sit outside the film libraries). */
    private fun parentOf(s: BrowseState) = s.libraryId?.takeUnless { it == COLLECTIONS }

    /** Opens [server] (and [libraryId]) with the sort and filters remembered for it. */
    fun select(server: String?, libraryId: String?) {
        server ?: return
        // Focus passing over the selected segment re-selects it: nothing changes then.
        if (server == _state.value.server && libraryId == _state.value.libraryId) return
        val serverChanged = server != _state.value.server
        val filters = filtersFor(server, libraryId)
        _state.update {
            it.copy(
                server = server, libraryId = libraryId, libraries = if (serverChanged) emptyList() else it.libraries,
                query = filters.apply(remembered(server, libraryId)), filters = filters,
                hasCollections = if (serverChanged) false else it.hasCollections,
            )
        }
        restart()
        probeIfDue(server, libraryId)
        if (serverChanged) viewModelScope.launch {
            val wanted = collectionsFor(types)
            val libs = runCatching { app.library.libraries(server).filter { it.collectionType in wanted } }.getOrDefault(emptyList())
            if (_state.value.server == server) _state.update { it.copy(libraries = libs) }
            if (types == "Movie") {
                // Whether the server has collections: stored with the browse pages (an hour), not asked on every switch.
                val key = "boxsets:$server"
                val stored = app.store.list(key)
                if (stored != null && _state.value.server == server) _state.update { it.copy(hasCollections = stored.total > 0 || it.collections) }
                if (stored == null || Freshness.due(Freshness.Kind.Browse, stored.fetchedAt, System.currentTimeMillis())) {
                    val boxSets = runCatching { app.library.browse(server, "BoxSet", false, null, 0, 0).totalRecordCount }
                        .onFailure { if (it is CancellationException) throw it }.getOrNull()
                    if (boxSets != null) app.store.putList(key, server, emptyList(), boxSets)
                    if (_state.value.server == server) _state.update { it.copy(hasCollections = (boxSets ?: stored?.total ?: 0) > 0 || it.collections) }
                }
            }
        }
    }

    /** The browse servers changed (首页与影视来源): leaves a hidden server for the first one still shown. */
    fun keepWithin(servers: List<String>) {
        val current = _state.value.server
        if (current == null || current !in servers) select(servers.firstOrNull(), null)
    }

    fun setQuery(q: BrowseQuery) {
        val s = _state.value
        remember(s.server ?: return, s.libraryId, q)
        _state.update { it.copy(query = q) }
        restart()
    }

    // ---- 按服务器实际支持的排序与筛选

    private fun filterKey(server: String, libraryId: String?) = "$server|${libraryId ?: "all"}|$types"

    private fun filtersFor(server: String, libraryId: String?): BrowseFilters =
        if (libraryId == COLLECTIONS) BrowseFilters.COLLECTIONS else allFilters()[filterKey(server, libraryId)] ?: BrowseFilters()

    /** Asks the server what it honours here once a day; the grid starts again only if the query loses something. */
    private fun probeIfDue(server: String, libraryId: String?) {
        if (libraryId == COLLECTIONS) return
        val key = filterKey(server, libraryId)
        val now = System.currentTimeMillis()
        if (!filtersFor(server, libraryId).stale(now) || !probing.add(key)) return
        viewModelScope.launch {
            try {
                val genres = runCatching { app.library.genres(server, libraryId, types) }.onFailure { if (it is CancellationException) throw it }.getOrDefault(emptyList())
                val found = app.library.probeFilters(server, types, libraryId, genres, now)
                ZLog.i("Browse", "${app.registry.get(server)?.name} $types filters: sortable=${found.sortable} bad=${found.badSorts} watched=${found.watched} " +
                    "favorite=${found.favorite} 4K=${found.uhd} decades=${found.decades.size} genres=${found.genres}")
                saveFilters(key, found)
                applyFilters(server, libraryId, found)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ZLog.d("Browse", "filter probe on ${app.registry.get(server)?.name} failed: ${e.message}")
            } finally {
                probing.remove(key)
            }
        }
    }

    private fun applyFilters(server: String, libraryId: String?, found: BrowseFilters) {
        val s = _state.value
        if (s.server != server || s.libraryId != libraryId) return
        val q = found.apply(s.query)
        _state.update { it.copy(filters = found, query = q) }
        if (q != s.query) restart()
    }

    /** A sort the server did not keep (SortCheck) is not offered again here until the next probe. */
    private fun dropSort(server: String, libraryId: String?, sort: app.zmby.data.BrowseSort) {
        if (libraryId == COLLECTIONS) return
        val key = filterKey(server, libraryId)
        val now = System.currentTimeMillis()
        val old = allFilters()[key] ?: BrowseFilters(known = true, probedAt = now)
        val next = old.copy(badSorts = old.badSorts + sort)
        saveFilters(key, next)
        val s = _state.value
        if (s.server == server && s.libraryId == libraryId) _state.update { it.copy(filters = next) }
    }

    private fun restart() {
        loading.values.forEach { it.cancel() }
        loading.clear()
        _pages.value = emptyMap()
        val key = libraryKey()
        _state.update { it.copy(total = 0, loaded = false, sortUnsupported = false, generation = it.generation + 1, libraryTotal = libraryTotals[key]) }
        ensure(0, 0)
        if (_state.value.collections) _state.update { it.copy(genres = emptyList()) } else loadGenres()
        if (_state.value.query.filtered && key !in libraryTotals) loadLibraryTotal(key)
    }

    private fun libraryKey(): String = _state.value.let { "${it.server}|${it.libraryId}" }

    /** The library's own count while filters narrow the grid: one Limit=0 request without them. */
    private fun loadLibraryTotal(key: String) {
        val s = _state.value
        val server = s.server ?: return
        viewModelScope.launch {
            val n = runCatching { app.library.browse(server, itemTypes(s), false, parentOf(s), 0, 0, s.query.cleared().params()).totalRecordCount }
                .onFailure { if (it is CancellationException) throw it }.getOrNull() ?: return@launch
            libraryTotals[key] = n
            if (libraryKey() == key) _state.update { it.copy(libraryTotal = n) }
        }
    }

    /** The slots [first]..[last] are (nearly) on screen: fetch their pages and the next one. */
    fun ensure(first: Int, last: Int) {
        lastRange = first to last
        // Until the first page tells the total, only that page: no requests past the end.
        if (!_state.value.loaded) {
            if (0 !in _pages.value && loading[0]?.isActive != true) loading[0] = viewModelScope.launch { load(0) }
            return
        }
        val from = (first - PAGE / 2).coerceAtLeast(0) / PAGE
        val to = (last + PAGE) / PAGE
        val total = _state.value.total
        for (p in from..to) {
            if (_state.value.loaded && p * PAGE >= total) break
            if (p in _pages.value || loading[p]?.isActive == true) continue
            loading[p] = viewModelScope.launch { load(p) }
        }
    }

    /** Back from the player or a detail page: pages older than an hour are fetched again. */
    fun refresh() {
        _pages.value.keys.forEach { p -> viewModelScope.launch { load(p, cacheFirst = false) } }
    }

    /** 重试 after the first page failed: the server's cool-down is lifted and the page asked again. */
    fun retry() {
        val server = _state.value.server ?: return
        app.registry.retry(server)
        _state.update { it.copy(error = null) }
        loading[0]?.cancel()
        loading[0] = viewModelScope.launch { load(0, cacheFirst = false) }
    }

    private suspend fun load(page: Int, cacheFirst: Boolean = true) {
        val s = _state.value
        val server = s.server ?: return
        val gen = s.generation
        val key = "browse:$server:${s.libraryId ?: "all"}:${itemTypes(s)}:${s.query.key}:p$page"
        val cached = if (cacheFirst) app.store.list(key) else null
        if (cached != null && cached.items.isNotEmpty() && gen == _state.value.generation) {
            _pages.update { it + (page to cached.items) }
            val first = !_state.value.loaded
            _state.update { if (it.loaded) it else it.copy(total = cached.total, loaded = true, libraryTotal = if (s.query.filtered) it.libraryTotal else cached.total) }
            if (first) ensure(lastRange.first, lastRange.second)
        }
        if (cached != null && !Freshness.due(Freshness.Kind.Browse, cached.fetchedAt, System.currentTimeMillis())) return
        try {
            val result = app.library.browse(server, itemTypes(s), false, parentOf(s), page * PAGE, PAGE, s.query.params())
            app.store.putList(key, server, result.items, result.totalRecordCount)
            if (gen != _state.value.generation) return
            _pages.update { it + (page to result.items.map { ServerItem(server, it) }) }
            val sortKey = "$server:${s.libraryId}:${s.query.sort}:${s.query.descending}"
            val unsupported = page == 0 && checkedSorts.add(sortKey) &&
                !withContext(Dispatchers.Default) { SortCheck.consistent(result.items, s.query.sort, s.query.descending) }
            if (unsupported) {
                ZLog.w("Browse", "${app.registry.get(server)?.name} did not keep the ${s.query.sort} order")
                dropSort(server, s.libraryId, s.query.sort)
            }
            val first = !_state.value.loaded
            if (!s.query.filtered) libraryTotals["$server|${s.libraryId}"] = result.totalRecordCount
            _state.update { it.copy(total = result.totalRecordCount, loaded = true, sortUnsupported = it.sortUnsupported || unsupported, error = null, libraryTotal = if (s.query.filtered) it.libraryTotal else result.totalRecordCount) }
            if (first) ensure(lastRange.first, lastRange.second)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ZLog.w("Browse", "page $page of ${app.registry.get(server)?.name}: ${e.message}")
            if (gen == _state.value.generation && !_state.value.loaded) {
                _state.update { it.copy(loaded = cached != null, error = if (cached == null && page == 0) "${app.registry.get(server)?.name ?: "这台服务器"}没有返回内容：${e.userMessage()}" else null) }
            }
        }
    }

    /** Where titles starting with [letter] begin (字母轨), or null when the server cannot say. */
    suspend fun indexOf(letter: String): Int? {
        val s = _state.value
        val server = s.server ?: return null
        return runCatching { app.library.indexOfLetter(server, itemTypes(s), parentOf(s), s.query.params(), letter) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()?.coerceIn(0, (s.total - 1).coerceAtLeast(0))
    }

    private fun loadGenres() {
        val s = _state.value
        val server = s.server ?: return
        viewModelScope.launch {
            val genres = runCatching { app.library.genres(server, s.libraryId, types) }.getOrDefault(emptyList())
            if (_state.value.server == server && _state.value.libraryId == s.libraryId) _state.update { it.copy(genres = genres) }
        }
    }

    // ---- 按服务器 + 媒体库记住

    // Decoded once per page model, not on every server or library switch (main thread; performance plan).
    private var queries: Map<String, BrowseQuery>? = null
    private var filterMap: Map<String, BrowseFilters>? = null

    private fun all(): Map<String, BrowseQuery> = queries
        ?: runCatching { json.decodeFromString(mapSerializer, app.settings.browseQueries.value) }.getOrDefault(emptyMap()).also { queries = it }

    private fun remembered(server: String, libraryId: String?): BrowseQuery = all()["$server|${libraryId ?: "all"}|$types"] ?: BrowseQuery()

    private fun remember(server: String, libraryId: String?, q: BrowseQuery) {
        val next = all() + ("$server|${libraryId ?: "all"}|$types" to q)
        queries = next
        app.settings.browseQueries.set(json.encodeToString(mapSerializer, next))
    }

    private fun allFilters(): Map<String, BrowseFilters> = filterMap
        ?: runCatching { json.decodeFromString(filtersSerializer, app.settings.browseFilters.value) }.getOrDefault(emptyMap()).also { filterMap = it }

    private fun saveFilters(key: String, f: BrowseFilters) {
        val next = allFilters() + (key to f)
        filterMap = next
        app.settings.browseFilters.set(json.encodeToString(filtersSerializer, next))
    }

    companion object {
        const val PAGE = 60
        // In the companion: init {} above reads them before instance properties below it exist.
        private val json = Json { ignoreUnknownKeys = true }
        private val mapSerializer = MapSerializer(String.serializer(), BrowseQuery.serializer())
        private val filtersSerializer = MapSerializer(String.serializer(), BrowseFilters.serializer())

        /** The library id of 合集 (the server's collections, 电影 only). */
        const val COLLECTIONS = "@collections"

        fun collectionsFor(types: String): Set<String> = when (types) {
            "Movie" -> setOf("movies", "mixed", "homevideos")
            "Series" -> setOf("tvshows", "mixed")
            else -> emptySet()
        }
    }
}
