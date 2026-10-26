package app.zmby.ui.screens

import app.zmby.data.SourceFeature
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zmby.ZmbyApp
import app.zmby.aggregate.HomeRows
import app.zmby.aggregate.HomeServers
import app.zmby.data.ServerItem
import app.zmby.emby.BaseItemDto
import app.zmby.log.ZLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The home feed's state holder (architecture: 线程与性能约定): rows come from the local store at
 * once and change when a refresh writes the servers' answers; merging runs on Dispatchers.Default.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(private val app: ZmbyApp) : ViewModel() {
    /**
     * 继续观看 and 下一集 from 观看记录来源; 最新添加 from every enabled server (its media library
     * switches decide which libraries).
     */
    private val servers = kotlinx.coroutines.flow.combine(app.registry.servers, app.sources.rows) { list, _ ->
        val enabled = list.filter { it.enabled }
        HomeServers(
            watch = app.sources.servers(SourceFeature.WatchHistory),
            latest = enabled.map { it.id },
            order = enabled.associate { it.id to it.order },
        )
    }.distinctUntilChanged()

    val rows: StateFlow<HomeRows> = servers
        .flatMapLatest { app.home.rows(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, HomeRows.EMPTY)

    private var refreshing: Job? = null

    /** 首页来源: "" for 全部, else a server id (a deleted server falls back to 全部). */
    val source: StateFlow<String> = kotlinx.coroutines.flow.combine(app.settings.homeSource.flow, app.registry.servers) { id, list ->
        id.takeIf { s -> list.any { it.id == s && it.enabled } }.orEmpty()
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** The chosen server's own home; null for 全部. */
    val serverHome: StateFlow<app.zmby.aggregate.ServerHome?> = source
        .flatMapLatest { id -> if (id.isEmpty()) flowOf(null) else app.home.serverHome(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setSource(id: String) {
        app.settings.homeSource.set(id)
        ZLog.i("Home", "home source ${app.registry.get(id)?.name ?: "全部"}")
        refresh()
    }

    init {
        // A resume point from any client means watching started: off 待看 (favorites spec).
        viewModelScope.launch {
            rows.map { it.resume }.distinctUntilChanged().collect { if (it.isNotEmpty()) app.watchlist.removeStarted(it) }
        }
    }

    /** Entering home and back from the player; a refresh still running is not started again. */
    fun refresh(force: Boolean = false) {
        if (refreshing?.isActive == true) {
            ZLog.d("Home", "refresh still running; not started again")
            return
        }
        refreshing = viewModelScope.launch {
            val s = servers.first()
            ZLog.d("Home", "refresh: watch ${s.watch.size}, latest ${s.latest.size} servers${if (force) ", forced" else ""}")
            val own = source.value
            if (own.isNotEmpty()) launch { runCatching { app.home.refreshServer(own, force) } }
            val fresh = runCatching { app.home.refresh(s, force) }
                .onFailure { if (it !is CancellationException) ZLog.w("Home", "refresh failed", it) }
                .getOrNull().orEmpty()
            // One cheap probe per server (a search with Limit=5) when due; uses that server's 最新添加.
            fresh.forEach { (server, items) -> app.library.maybeProbePinyin(server, items) }
        }
    }

    private val _heroDetails = MutableStateFlow<Map<String, BaseItemDto>>(emptyMap())
    /** Full items of the hero's titles (media sources for the badges), by [ServerItem.key]; cached first. */
    val heroDetails: StateFlow<Map<String, BaseItemDto>> = _heroDetails.asStateFlow()
    /** Hero titles asked from their servers since the last [forgetHeroFetches]. */
    private val heroFetched = HashSet<String>()
    private var heroJob: Job? = null

    /**
     * The hero's current title and its neighbours, nearest first (home hero prefetch): the local copy
     * at once, then each server once; a newer window cancels what is left of the older one.
     */
    fun prefetchHero(refs: List<ServerItem>) {
        heroJob?.cancel()
        heroJob = viewModelScope.launch {
            for (ref in refs.filter { it.server != "catalog" }) {
                if (ref.key in _heroDetails.value) continue
                app.store.detail(ref.server, ref.item.id)?.item?.takeIf { it.mediaSources.isNotEmpty() }
                    ?.let { full -> _heroDetails.value = _heroDetails.value + (ref.key to full) }
            }
            for (ref in refs.filter { it.server != "catalog" }) {
                if (!heroFetched.add(ref.key)) continue
                try {
                    val full = app.library.item(ref.server, ref.item.id)
                    _heroDetails.value = _heroDetails.value + (ref.key to full)
                    app.store.putDetail(ref.server, full)
                } catch (e: CancellationException) {
                    heroFetched.remove(ref.key)
                    throw e
                } catch (e: Exception) {
                    ZLog.d("Home", "hero item ${ref.key} not fetched: ${e.message}")
                }
            }
        }
    }

    /** Back from the player: ask the servers again (versions and progress may have changed). */
    fun forgetHeroFetches() {
        heroFetched.clear()
    }
}
