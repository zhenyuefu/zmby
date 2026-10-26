package app.zmby.ui.screens

import app.zmby.ui.userMessage

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zmby.ZmbyApp
import app.zmby.data.BrowseQuery
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
)

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

    init {
        val home = app.settings.homeSource.value.takeIf { id -> app.registry.get(id)?.enabled == true }
        // 默认跟随首页所选服务器；首页为「全部」时取优先级最高的服务器.
        select(home ?: app.registry.enabled.firstOrNull()?.id, null)
    }

    /** Opens [server] (and [libraryId]) with the sort and filters remembered for it. */
    fun select(server: String?, libraryId: String?) {
        server ?: return
        // Focus passing over the selected segment re-selects it: nothing changes then.
        if (server == _state.value.server && libraryId == _state.value.libraryId) return
        val serverChanged = server != _state.value.server
        _state.update { it.copy(server = server, libraryId = libraryId, libraries = if (serverChanged) emptyList() else it.libraries, query = remembered(server, libraryId)) }
        restart()
        if (serverChanged) viewModelScope.launch {
            val wanted = collectionsFor(types)
            val libs = runCatching { app.library.libraries(server).filter { it.collectionType in wanted } }.getOrDefault(emptyList())
            if (_state.value.server == server) _state.update { it.copy(libraries = libs) }
        }
    }

    fun setQuery(q: BrowseQuery) {
        val s = _state.value
        remember(s.server ?: return, s.libraryId, q)
        _state.update { it.copy(query = q) }
        restart()
    }

    private fun restart() {
        loading.values.forEach { it.cancel() }
        loading.clear()
        _pages.value = emptyMap()
        _state.update { it.copy(total = 0, loaded = false, sortUnsupported = false, generation = it.generation + 1) }
        ensure(0, 0)
        loadGenres()
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
        val key = "browse:$server:${s.libraryId ?: "all"}:$types:${s.query.key}:p$page"
        val cached = if (cacheFirst) app.store.list(key) else null
        if (cached != null && cached.items.isNotEmpty() && gen == _state.value.generation) {
            _pages.update { it + (page to cached.items) }
            val first = !_state.value.loaded
            _state.update { if (it.loaded) it else it.copy(total = cached.total, loaded = true) }
            if (first) ensure(lastRange.first, lastRange.second)
        }
        if (cached != null && !Freshness.due(Freshness.Kind.Browse, cached.fetchedAt, System.currentTimeMillis())) return
        try {
            val result = app.library.browse(server, types, false, s.libraryId, page * PAGE, PAGE, s.query.params())
            app.store.putList(key, server, result.items, result.totalRecordCount)
            if (gen != _state.value.generation) return
            _pages.update { it + (page to result.items.map { ServerItem(server, it) }) }
            val sortKey = "$server:${s.query.sort}:${s.query.descending}"
            val unsupported = page == 0 && checkedSorts.add(sortKey) &&
                !withContext(Dispatchers.Default) { SortCheck.consistent(result.items, s.query.sort, s.query.descending) }
            if (unsupported) ZLog.w("Browse", "${app.registry.get(server)?.name} did not keep the ${s.query.sort} order")
            val first = !_state.value.loaded
            _state.update { it.copy(total = result.totalRecordCount, loaded = true, sortUnsupported = it.sortUnsupported || unsupported, error = null) }
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
        return runCatching { app.library.indexOfLetter(server, types, s.libraryId, s.query.params(), letter) }
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

    private fun all(): Map<String, BrowseQuery> = runCatching { json.decodeFromString(mapSerializer, app.settings.browseQueries.value) }.getOrDefault(emptyMap())

    private fun remembered(server: String, libraryId: String?): BrowseQuery = all()["$server|${libraryId ?: "all"}|$types"] ?: BrowseQuery()

    private fun remember(server: String, libraryId: String?, q: BrowseQuery) {
        app.settings.browseQueries.set(json.encodeToString(mapSerializer, all() + ("$server|${libraryId ?: "all"}|$types" to q)))
    }

    companion object {
        const val PAGE = 60
        // In the companion: init {} above reads them before instance properties below it exist.
        private val json = Json { ignoreUnknownKeys = true }
        private val mapSerializer = MapSerializer(String.serializer(), BrowseQuery.serializer())

        fun collectionsFor(types: String): Set<String> = when (types) {
            "Movie" -> setOf("movies", "mixed", "homevideos")
            "Series" -> setOf("tvshows", "mixed")
            else -> emptySet()
        }
    }
}
