package app.zmby.ui.screens

import app.zmby.data.SourceFeature
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zmby.ZmbyApp
import app.zmby.trailer.ImdbTrailers
import app.zmby.trailer.TrailerInfo
import app.zmby.trailer.TrailerQuery
import app.zmby.aggregate.HomeRows
import app.zmby.aggregate.WatchCard
import app.zmby.aggregate.WatchState
import app.zmby.aggregate.HeroMerge
import app.zmby.aggregate.HeroPick
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.zmby.media.titleOf
import app.zmby.catalog.CatalogChart
import kotlinx.coroutines.flow.transformLatest
import app.zmby.catalog.cardRef

/**
 * The home feed's state holder (architecture: 线程与性能约定): rows come from the local store at
 * once and change when a refresh writes the servers' answers; merging runs on Dispatchers.Default.
 */
/**
 * A home poster as the row draws it (performance plan RH ③): image URL, title, caption, badge and
 * focus key worked out off the main thread, so composing a card concatenates and looks up nothing.
 */
@androidx.compose.runtime.Immutable
data class PosterModel(
    val key: String,
    /** What OK opens; null for a 待看 entry whose servers are all gone (it opens a reachable copy at click time). */
    val ref: ServerItem?,
    val image: String?,
    val title: String,
    val subtitle: String,
    val badge: String? = null,
    val focusKey: String,
    /** 待看 rows: the entry, for opening a reachable copy when pressed. */
    val watchlist: app.zmby.store.WatchlistRow? = null,
)

/** A library on a server's own home, as its 16:9 card. */
@androidx.compose.runtime.Immutable
data class LibraryCardModel(val key: String, val lib: ServerItem, val image: String?, val title: String?)

/** 「最新 · 电影」 on a server's own home. */
@androidx.compose.runtime.Immutable
data class LibraryRowModel(val key: String, val title: String, val cards: List<PosterModel>)

/** Which home rows exist (the feed lays out only these; each row subscribes to its own cards). */
@androidx.compose.runtime.Immutable
data class HomeLayout(
    val watching: Boolean = false,
    val watchlist: Boolean = false,
    val libraries: Boolean = false,
    val libraryRows: List<String> = emptyList(),
    val latest: Boolean = false,
)

/** 主推区 from a TMDB chart: [use] when the setting asks for it and a token is set. */
@androidx.compose.runtime.Immutable
data class ChartState(val use: Boolean = false, val chart: String = "", val titles: List<ServerItem> = emptyList(), val error: String? = null)

/** The trailer of the hero title keyed [key] ([ServerItem.key]). */
@androidx.compose.runtime.Immutable
data class HeroTrailer(val key: String, val info: TrailerInfo)

/** A hero title must rest this long before its trailer is looked up. */
internal const val TRAILER_REST_MS = 1_000L

/** How long the trailer lookup waits for a hero title's full item (its provider IDs). */
private const val HERO_DETAIL_WAIT_MS = 5_000L

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(private val app: ZmbyApp) : ViewModel() {
    /**
     * 在看 from 观看记录来源; 最新添加 from every enabled server (its media library
     * switches decide which libraries).
     */
    private val servers = kotlinx.coroutines.flow.combine(app.registry.lineup, app.sources.rows) { list, _ ->
        val enabled = list.filter { it.enabled }
        HomeServers(
            watch = app.sources.servers(SourceFeature.WatchHistory),
            // 首页与影视来源: a server hidden from home is left out of 全部.
            latest = app.sources.servers(SourceFeature.Browse),
            order = app.sources.order(SourceFeature.Browse),
            watchOrder = app.sources.order(SourceFeature.WatchHistory),
        )
    }.distinctUntilChanged()

    val rows: StateFlow<HomeRows> = servers
        .flatMapLatest { app.home.rows(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, HomeRows.EMPTY)

    private var refreshing: Job? = null

    /** 在看 (ui-spec §2.18): the home row and the page; without 下一集 when the setting is off. */
    val watching: StateFlow<List<WatchCard>> = kotlinx.coroutines.flow.combine(rows, app.settings.watchingIncludesNextUp.flow) { r, next ->
        if (next) r.watching else r.watching.filter { it.state == WatchState.Resume }
    }.flowOn(kotlinx.coroutines.Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** How many 在看 cards (the side bar's count). */
    val watchingCount: StateFlow<Int> = watching.map { it.size }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** 移除 and 标记为已观看 in flight, by card key; the menu ignores a second press meanwhile. */
    private val busy = HashSet<String>()

    /**
     * 从在看中移除; [onDone] gets the text for the result notice (null when nothing needs saying):
     * failed servers by name.
     */
    fun remove(card: WatchCard, onDone: (String?) -> Unit) {
        if (!busy.add(card.key)) return
        viewModelScope.launch {
            try {
                val outcome = app.home.watching.remove(card)
                val names = outcome.failed.map { app.registry.get(it)?.name ?: "一台服务器" }
                onDone(when {
                    names.isEmpty() -> null
                    !outcome.hidden -> "未能清除续播进度：${names.joinToString("、")}"
                    else -> "已从在看中移除；${names.joinToString("、")} 的续播进度未能清除"
                })
            } finally {
                busy.remove(card.key)
                refresh()
            }
        }
    }

    /**
     * 标记为已观看, as the detail page's button: the card's episode or film on its server and on
     * every server holding the same one ([WatchCard.alternates]); [onDone] gets the servers not reached.
     */
    fun markWatched(card: WatchCard, onDone: (error: String?, unsynced: Int) -> Unit) {
        val target = card.target ?: return
        if (!busy.add(card.key)) return
        viewModelScope.launch {
            try {
                val unsynced = app.setWatched(target, true, card.alternates)
                ZLog.i("Home", "marked ${card.key} watched on ${1 + card.alternates.size} copies, $unsynced server(s) not reached")
                onDone(null, unsynced)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ZLog.w("Home", "mark watched failed: ${e.message}")
                onDone("未能标记为已观看：${app.registry.get(target.server)?.name ?: "服务器"}", 0)
            } finally {
                busy.remove(card.key)
                refresh()
            }
        }
    }

    /** 首页来源: "" for 全部, else a server id (a deleted or hidden server falls back to 全部). */
    val source: StateFlow<String> = kotlinx.coroutines.flow.combine(app.settings.homeSource.flow, servers) { id, s ->
        id.takeIf { it in s.latest }.orEmpty()
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /**
     * Shows or hides [id] on home and the 电影 / 剧集 pages (首页与影视来源). Choosing a hidden server
     * as the home source shows it again ([setSource]).
     */
    fun setShown(id: String, shown: Boolean) {
        viewModelScope.launch {
            app.sources.set(SourceFeature.Browse, id, shown)
            if (shown) refresh()
        }
    }

    /** The chosen server's own home; null for 全部. */
    val serverHome: StateFlow<app.zmby.aggregate.ServerHome?> = source
        .flatMapLatest { id -> if (id.isEmpty()) flowOf(null) else app.home.serverHome(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The servers the hero asks: every enabled one for 全部 (priority order), else the chosen one. */
    private val heroServers = kotlinx.coroutines.flow.combine(source, servers) { src, s ->
        if (src.isEmpty()) s.latest.sortedBy { s.order[it] ?: Int.MAX_VALUE } else listOf(src)
    }.distinctUntilChanged()

    /**
     * 主推区 when it shows the servers (媒体库推荐): suggestions, then film recommendations, then the
     * latest additions (merged across servers for 全部, the server's own for one server).
     */
    val heroPicks: StateFlow<List<HeroPick>> = kotlinx.coroutines.flow.combine(
        heroServers.flatMapLatest { ids -> app.home.heroLists(ids).map { ids to it } },
        rows,
        serverHome,
    ) { (ids, lists), r, own ->
        HeroMerge.pick(ids, lists.first, lists.second, own?.recent ?: r.latest)
    }.flowOn(kotlinx.coroutines.Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---- Per-row card models (performance plan RH ②③): each row subscribes to its own list.

    private fun typeYear(item: BaseItemDto) = listOfNotNull(if (item.type == "Series") "剧集" else "电影", item.productionYear?.toString()).joinToString(" · ")

    /** 最新添加 (全部 only), with 「n 个版本」 when several servers have the work. */
    val latestRow: StateFlow<List<PosterModel>> = kotlinx.coroutines.flow.combine(rows, serverHome, app.settings.showLatestRow.flow, app.cardFaces.version) { r, own, show, _ ->
        if (own != null || !show) emptyList() else r.latest.map { ref ->
            val count = r.latestCopies[ref.key] ?: 1
            val face = app.cardFaces.face(ref)
            PosterModel(ref.key, ref, face?.poster ?: app.library.primaryImage(ref), face?.title ?: titleOf(ref.item), typeYear(ref.item),
                if (count > 1) "$count 个版本" else null, "latest:${ref.key}")
        }
    }.distinctUntilChanged().flowOn(kotlinx.coroutines.Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 待看: the poster from the configured server order; OK picks a reachable copy at that moment. */
    val watchlistRow: StateFlow<List<PosterModel>> = kotlinx.coroutines.flow.combine(app.watchlist.entries, app.registry.lineup, app.settings.showWatchlistRow.flow, app.cardFaces.version) { entries, lineup, show, _ ->
        if (!show) emptyList() else entries.map { e ->
            val ref = watchlistRef(e, lineup)
            val face = ref?.let { app.cardFaces.faceOfWork(it.server, e.workKey) }
            PosterModel(e.workKey, ref, face?.poster ?: ref?.let { app.library.primaryImage(it) }, face?.title ?: e.title,
                listOfNotNull(if (e.type == "Series") "剧集" else "电影", e.year?.toString()).joinToString(" · "),
                focusKey = "watchrow:${e.workKey}", watchlist = e)
        }
    }.distinctUntilChanged().flowOn(kotlinx.coroutines.Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** A server's own home: its libraries. */
    val librariesRow: StateFlow<List<LibraryCardModel>> = serverHome.map { own ->
        own?.libraries.orEmpty().map { lib -> LibraryCardModel(lib.key, lib, app.library.primaryImage(lib, maxHeight = 340), lib.item.name) }
    }.distinctUntilChanged().flowOn(kotlinx.coroutines.Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** A server's own home: each library's newest titles. */
    val libraryRows: StateFlow<List<LibraryRowModel>> = kotlinx.coroutines.flow.combine(serverHome, app.cardFaces.version) { own, _ ->
        own?.libraries.orEmpty().mapNotNull { lib ->
            val items = own?.latest?.get(lib.item.id).orEmpty()
            if (items.isEmpty()) null else LibraryRowModel("liblatest:${lib.key}", "最新 · ${lib.item.name.orEmpty()}", items.map { ref ->
                val face = app.cardFaces.face(ref)
                PosterModel(ref.key, ref, face?.poster ?: app.library.primaryImage(ref), face?.title ?: titleOf(ref.item), typeYear(ref.item), focusKey = "liblatest:${lib.item.id}:${ref.key}")
            })
        }
    }.distinctUntilChanged().flowOn(kotlinx.coroutines.Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val layout: StateFlow<HomeLayout> = kotlinx.coroutines.flow.combine(watching, watchlistRow, librariesRow, libraryRows, latestRow) { w, wl, libs, libRows, latest ->
        HomeLayout(w.isNotEmpty(), wl.isNotEmpty(), libs.isNotEmpty(), libRows.map { it.key }, latest.isNotEmpty())
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, HomeLayout())

    // ---- 主推区 from a TMDB chart (RH ④): asked here, not in the feed's composition; one publish when complete.

    val chart: StateFlow<ChartState> = kotlinx.coroutines.flow.combine(
        kotlinx.coroutines.flow.combine(app.settings.heroSource.flow, app.settings.tmdbToken.flow, app.settings.tmdbChart.flow) { src, token, chart -> Triple(src, token, chart) },
        kotlinx.coroutines.flow.combine(app.settings.metadataLanguage.flow, app.settings.posterLanguage.flow, app.settings.logoLanguage.flow) { a, b, c -> listOf(a, b, c) },
    ) { (src, token, chart), _ -> Triple(HeroSource.of(src) == HeroSource.Tmdb && token.isNotBlank(), chart, token) }
        .distinctUntilChanged()
        .transformLatest { (use, chart, _) ->
            if (!use) { emit(ChartState()); return@transformLatest }
            try {
                val titles = app.catalog.chart(CatalogChart.of(chart)).take(8)
                val stored = titles.map { (app.catalog.cached(it.ref) ?: it).cardRef() }
                emit(ChartState(true, chart, stored))
                // Each title completed from TMDB, then the whole list published once (not eight times).
                val full = titles.map { work ->
                    try { app.catalog.work(work.ref).cardRef() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                }
                val done = stored.map { s -> full.firstOrNull { it?.item?.id == s.item.id } ?: s }
                if (done != stored) emit(ChartState(true, chart, done))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(ChartState(true, chart, error = e.message))
            }
        }.flowOn(kotlinx.coroutines.Dispatchers.IO).stateIn(viewModelScope, SharingStarted.Eagerly, ChartState())

    /** The hero's titles: the chart's, or the servers' picks. */
    val featured: StateFlow<List<ServerItem>> = kotlinx.coroutines.flow.combine(chart, heroPicks) { c, picks ->
        if (c.use) c.titles else picks.map { it.ref }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The line above each hero title (「家里 NAS · 为你推荐」); empty for the chart, which has one line for all. */
    val heroLabels: StateFlow<Map<String, String>> = kotlinx.coroutines.flow.combine(chart, heroPicks, source) { c, picks, src ->
        if (c.use) emptyMap() else picks.associate { p -> p.ref.key to listOfNotNull(app.registry.get(p.ref.server)?.name?.takeIf { src.isEmpty() }, p.tier.label).joinToString(" · ") }
    }.distinctUntilChanged().flowOn(kotlinx.coroutines.Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun setSource(id: String) {
        if (id.isNotEmpty() && !app.sources.enabled(SourceFeature.Browse, id)) viewModelScope.launch { app.sources.set(SourceFeature.Browse, id, true) }
        app.settings.homeSource.set(id)
        ZLog.i("Home", "home source ${app.registry.get(id)?.name ?: "全部"}")
        refresh()
    }

    init {
        // A resume point from any client means watching started: off 待看 (favorites spec).
        viewModelScope.launch {
            rows.map { r -> r.watching.filter { it.state == WatchState.Resume }.flatMap { it.resumePoints } }
                .distinctUntilChanged().collect { if (it.isNotEmpty()) app.watchlist.removeStarted(it) }
        }
    }

    /** Entering home and back from the player; a refresh still running is not started again. */
    /** [fromPlayer]: back from the external player, so 在看 asks the servers whatever its age (RN ①). */
    fun refresh(force: Boolean = false, fromPlayer: Boolean = false) {
        if (refreshing?.isActive == true) {
            ZLog.d("Home", "refresh still running; not started again")
            return
        }
        refreshing = viewModelScope.launch {
            val s = servers.first()
            ZLog.d("Home", "refresh: watch ${s.watch.size}, latest ${s.latest.size} servers${if (force) ", forced" else ""}")
            val own = source.value
            if (own.isNotEmpty()) launch { runCatching { app.home.refreshServer(own, force) } }
            launch {
                runCatching { app.home.refreshHero(heroServers.first(), force) }
                    .onFailure { if (it !is CancellationException) ZLog.w("Home", "hero refresh failed", it) }
            }
            val fresh = runCatching { app.home.refresh(s, force, watchingForce = force || fromPlayer) }
                .onFailure { if (it !is CancellationException) ZLog.w("Home", "refresh failed", it) }
                .getOrNull().orEmpty()
            // One cheap probe per server (a search with Limit=5) when due; uses that server's 最新添加.
            fresh.forEach { (server, items) -> app.library.maybeProbePinyin(server, items) }
        }
    }

    private val _heroDetails = MutableStateFlow<Map<String, BaseItemDto>>(emptyMap())
    /** Full items of the hero's titles (media sources for the badges), by [ServerItem.key]; cached first. */
    val heroDetails: StateFlow<Map<String, BaseItemDto>> = _heroDetails.asStateFlow()
    /** Stored full items to ask again (back from the player: versions and progress may have changed). */
    private val heroStale = HashSet<String>()
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
                // A stored full item (with media sources) is enough until back from the player (RN ④).
                if (ref.key !in heroStale && _heroDetails.value[ref.key]?.mediaSources?.isNotEmpty() == true) continue
                try {
                    val full = app.library.item(ref.server, ref.item.id)
                    _heroDetails.value = _heroDetails.value + (ref.key to full)
                    heroStale.remove(ref.key)
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

    // ---- IMDb trailer of the title the hero rests on (trailer design; ui-spec §2.1).

    private val heroRest = MutableStateFlow<ServerItem?>(null)

    /**
     * The hero rests on [ref] (no slide, on screen, app in front), or on nothing. Its trailer is asked
     * only after it has rested [TRAILER_REST_MS], so flicking through the titles asks nothing.
     */
    fun heroResting(ref: ServerItem?) {
        if (heroRest.value?.key != ref?.key) heroRest.value = ref
    }

    /** The trailer to play behind the resting title, once found (cache first, then one IMDb request). */
    val heroTrailer: StateFlow<HeroTrailer?> = kotlinx.coroutines.flow.combine(heroRest, app.settings.trailerAutoplay.flow) { ref, on -> ref.takeIf { on } }
        .distinctUntilChanged { a, b -> a?.key == b?.key }
        .transformLatest { ref ->
            emit(null)
            if (ref == null) return@transformLatest
            kotlinx.coroutines.delay(TRAILER_REST_MS)
            app.trailers.find(heroQuery(ref))?.let { emit(HeroTrailer(ref.key, it)) }
        }
        .flowOn(kotlinx.coroutines.Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * What the trailer sources need of a hero title (trailer design): the IMDb ID and the server copy
     * of the film or series, from its full item (list answers may lack provider IDs and the trailer
     * count; the hero fetches full items anyway); for an episode its series as stored locally.
     */
    private suspend fun heroQuery(ref: ServerItem): TrailerQuery {
        if (ref.server == "catalog") return TrailerQuery(ImdbTrailers.idOf(ref.item))
        // The full item: provider IDs and LocalTrailerCount (the server trailers' fallback).
        val full = _heroDetails.value[ref.key] ?: kotlinx.coroutines.withTimeoutOrNull(HERO_DETAIL_WAIT_MS) {
            _heroDetails.map { it[ref.key] }.filterNotNull().first()
        } ?: app.store.detail(ref.server, ref.item.id)?.item
        val item = full ?: ref.item
        // An episode's trailer is its series': IMDb ID and stored trailers of the series as kept locally.
        val title = if (item.type == "Episode") item.seriesId?.let { app.store.detail(ref.server, it)?.item } else item
        return TrailerQuery(ImdbTrailers.idOf(title) ?: ImdbTrailers.idOf(ref.item),
            listOfNotNull(title?.let { ServerItem(ref.server, it) }))
    }

    /** Back from the player: ask the servers again (versions and progress may have changed). */
    fun forgetHeroFetches() {
        heroFetched.clear()
        heroStale.addAll(_heroDetails.value.keys)
    }
}
