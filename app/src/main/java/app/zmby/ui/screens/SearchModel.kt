package app.zmby.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zmby.ZmbyApp
import app.zmby.aggregate.Hit
import app.zmby.aggregate.MergeResult
import app.zmby.aggregate.Merger
import app.zmby.aggregate.SearchState
import app.zmby.catalog.CatalogWork
import app.zmby.data.ServerItem
import app.zmby.data.SourceFeature
import app.zmby.log.ZLog
import app.zmby.media.titleOf
import app.zmby.search.Candidate
import app.zmby.search.LiveSearch
import app.zmby.search.LiveSearchState
import app.zmby.search.Pinyin
import app.zmby.search.PinyinCandidates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Both modes search as typed; pinyin also offers candidates below the keyboard. */
internal enum class KeyMode { Pinyin, Abc }

/** TMDB's answer for the query, or why there is none. */
/**
 * One merged result card, built off the main thread: [key] is the work's own key (made unique when
 * two works share one), so another server's answer that changes the representative copy updates the
 * card in place instead of replacing it (and keeps focus on it).
 */
@androidx.compose.runtime.Immutable
internal data class SearchCard(val key: String, val ref: ServerItem, val image: String?, val title: String, val subtitle: String, val copies: Int)

internal fun searchCardKeys(keys: List<String>): List<String> {
    val seen = HashMap<String, Int>()
    return keys.map { k -> val n = seen.merge(k, 1, Int::plus)!!; if (n == 1) k else "$k#$n" }
}

internal data class CatalogSearch(val works: List<CatalogWork> = emptyList(), val error: String? = null)

/**
 * The search page's state holder (performance plan R5): the query, pinyin candidates, the live
 * search across servers (debounced, superseded work cancelled), TMDB results and the merged hits,
 * worked out off the main thread. The keyboard side and the results side of the page each read only
 * their part. Kept with the page's back-stack entry, so coming back from a result does not search again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class SearchViewModel(private val app: ZmbyApp) : ViewModel() {
    val query = MutableStateFlow("")
    val mode = MutableStateFlow(KeyMode.Pinyin)

    private val library = app.library
    private val candidates = PinyinCandidates(viewModelScope, library::candidateServers, library::localTitles)
    private val live = LiveSearch(viewModelScope, { term, reuse ->
        app.search.search(app.sources.order(SourceFeature.Search).let { order -> app.sources.servers(SourceFeature.Search).map { id -> id to (order[id] ?: Int.MAX_VALUE) } }, term, reuse)
    }, library::rememberSearch)
    val liveState: StateFlow<LiveSearchState> = live.state

    /** Hits found while typing pinyin, offered as candidates too (only for the query they answer). */
    private val rawHits = MutableStateFlow<List<Hit>>(emptyList())

    /** Candidates ranked by pinyin, off the main thread. */
    val shownCandidates: StateFlow<List<Candidate>> = combine(query, mode, candidates.candidates, rawHits) { q, m, local, hits ->
        if (m == KeyMode.Pinyin && q.isNotBlank() && !Pinyin.hasHan(q)) {
            PinyinCandidates.rank(q, local + hits.filter { Pinyin.hasHan(it.item.name.orEmpty()) }.map { h ->
                Candidate(h.item.name.orEmpty(), h.item.productionYear, h.item, h.server, h.order)
            })
        } else emptyList()
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The live hits merged into works, with the hits they came from (to tell a stale merge). */
    val merged: StateFlow<Pair<List<Hit>?, MergeResult?>> = live.state.map { it.snapshot?.hits }.distinctUntilChanged()
        .map { hits -> hits to hits?.let { Merger.merge(it) } }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null to null)

    /** The merged works as cards: image URL, title and caption worked out here, not while composing. */
    val cards: StateFlow<List<SearchCard>> = combine(merged, app.cardFaces.version) { (_, result), _ ->
        val works = result?.works.orEmpty()
        works.zip(searchCardKeys(works.map { it.key })) { work, key ->
            val lead = work.lead
            val item = lead.item
            val face = app.cardFaces.face(lead.ref)
            SearchCard(
                key = key,
                ref = lead.ref,
                image = face?.poster ?: app.library.primaryImage(lead.ref),
                title = face?.title ?: titleOf(item),
                subtitle = listOfNotNull(if (item.type == "Series") "剧集" else "电影", item.productionYear?.toString()).joinToString(" · "),
                copies = work.entries.size,
            )
        }
    }.distinctUntilChanged().flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** TMDB results for the query when TMDB search is on, 350 ms after typing stops. */
    val catalog: StateFlow<CatalogSearch> = combine(query, app.settings.tmdbSearch.flow, app.settings.metadataLanguage.flow, app.settings.tmdbToken.flow) { q, on, _, _ -> q to on }
        .transformLatest { (q, on) ->
            emit(CatalogSearch())
            if (q.isBlank() || !on || !app.catalog.configured) return@transformLatest
            delay(350)
            emit(withContext(Dispatchers.IO) {
                try { CatalogSearch(app.catalog.search(q)) }
                catch (e: CancellationException) { throw e } catch (e: Exception) { CatalogSearch(error = e.message) }
            })
        }.stateIn(viewModelScope, SharingStarted.Eagerly, CatalogSearch())

    init {
        viewModelScope.launch {
            // Matching local titles converts up to 2000 of them to pinyin: off the main thread. Full live
            // results provide remote candidates, avoiding a second set of candidate requests.
            combine(query, mode) { q, m -> q to m }.collectLatest { (q, m) ->
                withContext(Dispatchers.Default) { candidates.onInput(if (m == KeyMode.Pinyin) q else "", fetchRemote = false) }
            }
        }
        viewModelScope.launch { combine(query, mode) { q, _ -> q }.collect { live.submit(it) } }
        viewModelScope.launch {
            live.state.collect { st ->
                val q = query.value
                if (st.term == q.trim() && mode.value == KeyMode.Pinyin && !Pinyin.hasHan(q)) rawHits.value = st.snapshot?.hits.orEmpty()
            }
        }
        // A paused result is retryable after its deadline (or the server page's 立即重试).
        viewModelScope.launch {
            live.state.collectLatest { st ->
                val snap = st.snapshot ?: return@collectLatest
                if (!snap.done) return@collectLatest
                val deadlines = snap.states.filterValues { it == SearchState.Skipped }.keys.mapNotNull { id -> app.registry.get(id)?.coolingUntil }
                if (deadlines.isNotEmpty()) {
                    delay((deadlines.min() - System.currentTimeMillis()).coerceAtLeast(0) + 50)
                    live.refresh()
                }
            }
        }
    }

    fun changeQuery(text: String) {
        // Cancel the previous HTTP work immediately, before the debounce. First: setting the query
        // resumes the collector that submits the new term at once (Main.immediate).
        live.submit("")
        rawHits.value = emptyList()
        query.value = text
    }

    fun toggleMode() { mode.value = if (mode.value == KeyMode.Pinyin) KeyMode.Abc else KeyMode.Pinyin }

    fun search() { query.value.takeIf { it.isNotBlank() }?.let { live.submit(it, immediate = true, saveHistory = true) } }

    /** A candidate was chosen: search its title at once, reusing the hit it came from. */
    fun pick(c: Candidate) {
        ZLog.i("Search", "picked candidate '${c.title}' from ${c.serverKey ?: "local titles"}")
        changeQuery(c.title)
        val reuse = c.item?.let { item -> c.serverKey?.let { id -> Hit(ServerItem(id, item), app.registry.get(id)?.order ?: 0) } }
        live.submit(c.title, reuse, immediate = true, saveHistory = true)
    }

    override fun onCleared() {
        candidates.clear()
        live.close()
    }
}
