package app.zmby.ui.screens

import app.zmby.TraceRecomposition
import app.zmby.media.workTitleOf
import app.zmby.media.titleOf
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.currentStateAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.zmby.catalog.*
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import app.zmby.Diagnostics
import app.zmby.aggregate.WatchCard
import app.zmby.data.Library
import app.zmby.data.InputField
import app.zmby.data.LibraryView
import app.zmby.data.ServerItem
import app.zmby.data.ServerStatus
import app.zmby.emby.BaseItemDto
import app.zmby.emby.ItemsResult
import app.zmby.log.ZLog
import app.zmby.media.episodeCode
import app.zmby.media.progressFraction
import app.zmby.media.remainingLabel
import app.zmby.ui.Nav
import app.zmby.ui.Route
import app.zmby.ui.components.BackdropScreen
import app.zmby.ui.components.TrailerPlayer
import app.zmby.ui.components.rememberTrailerPlayer
import app.zmby.ui.components.ActionFeedbackHost
import app.zmby.ui.components.GlassButton
import app.zmby.ui.components.GlassSurface
import app.zmby.ui.components.Glyph
import app.zmby.ui.components.Health
import app.zmby.ui.components.HealthDot
import app.zmby.ui.components.Icon
import app.zmby.ui.components.MediaCard16x9
import app.zmby.ui.components.PosterCard2x3
import app.zmby.ui.components.RowTitle
import app.zmby.ui.components.Scrims
import app.zmby.ui.components.Segment
import app.zmby.ui.components.SegmentedWell
import app.zmby.ui.components.ZmbyLogo
import app.zmby.ui.components.horizontalRow
import app.zmby.ui.components.onMenuKey
import app.zmby.ui.components.SelectableRow
import app.zmby.ui.components.groupCard
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.focus.focusProperties
import app.zmby.ui.components.scrollToTopFirmly
import app.zmby.ui.open
import app.zmby.ui.rememberInitialFocus
import app.zmby.ui.rememberResumeTick
import app.zmby.ui.rememberRetained
import app.zmby.ui.rememberShownAt
import app.zmby.ui.screenModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zmby.ui.theme.Z
import app.zmby.ui.userMessage
import app.zmby.zmby
import kotlinx.coroutines.launch
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import app.zmby.ui.LocalNavEntry

/** How many 在看 cards the home row shows before 「全部 ›」. */
private const val HOME_WATCHING = 12

/** Top of the focusable content, just under the top bar (which ends at 110 on the design grid). */
private val CONTENT_TOP = 120.dp

/** Main.dc.html: left→right 0.96 → 0.78 (34 %) → 0.1 (70 %); bottom 560 of 1080, solid for its lower 30 %. */
private val HOME_SCRIMS = Scrims(listOf(0f to 0.96f, 0.34f to 0.78f, 0.70f to 0.1f), bottomFrom = 520f / 1080f, bottomSolid = 0.3f)

/** A library picked on a server's own home: the browse tab opens on it. */
data class BrowseTarget(val server: String, val libraryId: String, val types: String)

/**
 * The home tabs (首页, 电影, 剧集, 收藏, 待看) under one screen, switched from the side bar (ui-spec
 * §2.1, 2026-10-26): Left at a row's start opens the bar; Menu opens it on 首页 except on 在看 cards.
 * Back closes the bar, and two consecutive Back presses on 首页 confirm exit.
 */
@Composable
fun HomeScreen(nav: Nav, tab: Int, onExit: () -> Unit, onTab: (Int) -> Unit) {
    TraceRecomposition("Home.root")
    val context = LocalContext.current
    val app = context.zmby
    val library = app.library
    // Coming back to home does not measure again (performance plan RH ①): once, then every ten minutes.
    LaunchedEffect(Unit) { library.measureIfDue() }
    // Initial focus belongs to entering Home, not to switching back to its first tab.
    val shownAt = rememberShownAt()
    // The hero carousel lives here: its pages are the home backdrop, drawn under the top bar.
    val carousel = rememberRetained("heroCarousel") { HeroCarousel() }
    // The resting title's IMDb trailer plays behind the hero (trailer design); released on pause.
    val trailer = rememberTrailerPlayer()
    val backdropVisible = remember(carousel) { { carousel.backdropVisible } }
    val backdropShift = remember(carousel) { { -carousel.scrolled } }
    val vm = screenModel("home") { HomeViewModel(app) }
    // The root reads neither 在看 nor the source (performance plan RH ⑤): the top bar, the source panel
    // and the side bar's count subscribe themselves; key handling reads the current value when pressed.
    var watchingMenu by remember { mutableStateOf<WatchCard?>(null) }
    var removedWatching by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var picking by remember { mutableStateOf(false) }
    var tokenInput by remember { mutableStateOf(false) }
    var browseTarget by rememberRetained("browseTarget") { mutableStateOf<BrowseTarget?>(null) }
    var sideOpen by remember { mutableStateOf(false) }
    val sideBlock = remember { mutableIntStateOf(0) }
    val search = remember { FocusRequester() }
    // The element a tab takes focus on when the side bar switches to it.
    val entry = remember { FocusRequester() }
    var enterPage by remember { mutableIntStateOf(0) }
    LaunchedEffect(enterPage) { if (enterPage > 0) entry.requestWhenReady() }
    val memory = LocalNavEntry.current?.focus
    val focusManager = LocalFocusManager.current

    fun closeWatchingMenu() {
        watchingMenu = null
        memory?.beginRestore()
    }
    LaunchedEffect(vm) {
        vm.watching.collect { watching ->
            val (key, index) = removedWatching ?: return@collect
            if (watching.any { it.key == key }) return@collect
            removedWatching = null
            val neighbor = watching.take(HOME_WATCHING).getOrNull(index.coerceAtMost(HOME_WATCHING - 1).coerceAtMost(watching.lastIndex))
            if (neighbor != null && memory != null) {
                memory.lastKey = "watching:${neighbor.key}"
                memory.beginRestore()
            } else entry.requestWhenReady()
        }
    }

    fun closeSide() {
        sideOpen = false
        // Focus goes back to what had it before; the side bar's rows carry no focus key.
        if (memory?.lastKey != null) memory.beginRestore() else enterPage++
    }
    // 首页来源 and its token input are removed at once; focus goes back to the source button.
    fun closePanel() {
        picking = false
        tokenInput = false
        memory?.beginRestore()
    }
    fun pick(item: SideNavItem) {
        val target = item.tab
        if (target == null) {
            sideOpen = false
            nav.go(
                when (item) {
                    SideNavItem.Search -> Route.Search
                    SideNavItem.Me -> Route.Me
                    SideNavItem.Servers -> Route.Servers
                    else -> Route.Settings
                },
            )
        } else if (target == tab) {
            closeSide()
        } else {
            sideOpen = false
            memory?.lastKey = null
            onTab(target)
            enterPage++
        }
    }
    val exitConfirmation = remember(tab, sideOpen, picking, tokenInput) { HomeExitConfirmation() }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_PAUSE) {
        exitConfirmation.reset()
    }
    // Child panels register later BackHandlers and dismiss before this handler can confirm exit.
    androidx.activity.compose.BackHandler {
        when {
            sideOpen -> closeSide()
            tab != 0 -> sideOpen = true
            exitConfirmation.onBack(android.os.SystemClock.elapsedRealtime()) -> onExit()
            else -> android.widget.Toast.makeText(context, "再按一次返回键退出", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    CompositionLocalProvider(LocalSideNavBlock provides sideBlock) {
    ActionFeedbackHost(Modifier.fillMaxSize()) {
    BackdropScreen(
        null,
        HOME_SCRIMS,
        modifier = Modifier.onPreviewKeyEvent { e ->
            if (e.type == KeyEventType.KeyDown && e.key != Key.Back) exitConfirmation.reset()
            // 在看 has its own actions. Other home cards keep the sidebar Menu shortcut.
            if (tab == 0 && sideBlock.intValue == 0 && e.key == Key.Menu) {
                if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) {
                    val card = vm.watching.value.firstOrNull { "watching:${it.key}" == memory?.lastKey }
                    if (card != null && !sideOpen) watchingMenu = card else sideOpen = true
                }
                true
            } else false
        }.onKeyEvent { e ->
            // Left that finds nothing further left (the start of a row) opens the side bar.
            if (sideOpen || sideBlock.intValue > 0 || e.key != Key.DirectionLeft || e.type != KeyEventType.KeyDown) return@onKeyEvent false
            if (!focusManager.moveFocus(FocusDirection.Left) && e.nativeKeyEvent.repeatCount == 0) sideOpen = true
            true
        },
        visible = backdropVisible,
        shift = backdropShift,
        layer = if (tab == 0) ({ HeroBackdrop(carousel, HOME_SCRIMS, trailer) }) else null,
        slices = if (tab == 0) heroGlassSlices(carousel) else null,
    ) {
        val openSearch = { nav.go(Route.Search) }
        when (tab) {
            0 -> HomeFeed(nav, shownAt, carousel, trailer, search, entry, onLibrary = { lib ->
                val series = lib.item.collectionType == "tvshows"
                browseTarget = BrowseTarget(lib.server, lib.item.id, if (series) "Series" else "Movie")
                onTab(if (series) 2 else 1)
            }, onWatching = { memory?.lastKey = null; onTab(WATCHING_TAB); enterPage++ }, onWatchingMenu = { watchingMenu = it })
            1 -> BrowseScreen(nav, "Movie", browseTarget?.takeIf { it.types == "Movie" }, search, entry) { browseTarget = null }
            2 -> BrowseScreen(nav, "Series", browseTarget?.takeIf { it.types == "Series" }, search, entry) { browseTarget = null }
            3 -> FavoritesGrid(nav, search, entry)
            4 -> WatchlistGrid(nav, search, entry)
            WATCHING_TAB -> WatchingPage(nav, search, entry, onHome = { memory?.lastKey = null; onTab(0); enterPage++ })
        }
        if (tab == 0) {
            HomeTopBar(
                vm = vm,
                search = search,
                onSearch = openSearch,
                onSource = { picking = true },
            )
        }
        if (picking) {
            val servers by app.registry.servers.collectAsStateWithLifecycle()
            val source by vm.source.collectAsStateWithLifecycle()
            SourcePanel(
                servers,
                source,
                onSource = { id -> closePanel(); vm.setSource(id) },
                onShown = vm::setShown,
                onServers = { picking = false; nav.go(Route.Servers) },
                onToken = { picking = false; tokenInput = true },
                onDismiss = ::closePanel,
            )
        }
        if (tokenInput) PhoneInputDialog(InputField.TmdbToken, onDismiss = ::closePanel, onRemote = { tokenInput = false; nav.go(Route.Settings) })
        WatchingMenuHost(watchingMenu, nav, vm, onClose = ::closeWatchingMenu, onRemove = { card ->
            removedWatching = card.key to vm.watching.value.indexOfFirst { it.key == card.key }.coerceAtLeast(0)
        })
        if (!sideOpen) SideNavHint()
        HomeSideNav(vm, sideOpen, tab, onPick = ::pick, onDismiss = ::closeSide)
    }
    }
    }
}

/** The home page with nothing to show: a title, a line of advice, 重试 (focused) and 服务器状态. */
@Composable
private fun HomeEmpty(title: String, advice: String, focus: FocusRequester, onRetry: () -> Unit, onServers: () -> Unit, modifier: Modifier = Modifier, entry: FocusRequester? = null) {
    Column(Modifier.padding(top = 80.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(title, fontSize = 48.sp, fontWeight = FontWeight.ExtraBold)
        Text(advice, fontSize = 23.sp, color = Z.textSecondary, modifier = Modifier.width(820.dp))
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            GlassButton("重试", onRetry, Modifier.focusRequester(focus).then(if (entry != null) Modifier.focusRequester(entry) else Modifier), glyph = Glyph.Refresh)
            GlassButton("服务器状态", onServers)
        }
    }
}

/** The side bar with 在看's count, read here rather than at the home root. */
@Composable
private fun HomeSideNav(vm: HomeViewModel, open: Boolean, tab: Int, onPick: (SideNavItem) -> Unit, onDismiss: () -> Unit) {
    val count by vm.watchingCount.collectAsStateWithLifecycle()
    SideNav(open, tab, onPick = onPick, onDismiss = onDismiss, watching = count)
}

/** Main.dc.html top bar: the zmby Logo, the search pill beside it, and the source button on the right. */
@Composable
private fun HomeTopBar(vm: HomeViewModel, search: FocusRequester, onSearch: () -> Unit, onSource: () -> Unit) {
    // Health and latency change on every speed test and failure: only this bar reads them, not home.
    val servers by LocalContext.current.zmby.registry.servers.collectAsStateWithLifecycle()
    val source by vm.source.collectAsStateWithLifecycle()
    Row(
        // Cached like the rows; the bottom padding keeps the buttons' shadows inside the layer.
        Modifier.fillMaxWidth().cachedLayer().padding(start = 80.dp, end = 80.dp, top = 44.dp, bottom = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        ZmbyLogo()
        SearchPill(onSearch, Modifier.focusRequester(search))
        Spacer(Modifier.weight(1f))
        SourceButton(servers, source, onSource)
    }
}

@Composable
private fun HomeFeed(nav: Nav, shownAt: Long, carousel: HeroCarousel, trailer: TrailerPlayer, search: FocusRequester, entry: FocusRequester, onLibrary: (ServerItem) -> Unit, onWatching: () -> Unit, onWatchingMenu: (WatchCard) -> Unit) {
    TraceRecomposition("Home.feed")
    val context = LocalContext.current
    val app = context.zmby
    val tick = rememberResumeTick()
    val vm = screenModel("home") { HomeViewModel(app) }
    // Which rows exist; each row below subscribes to its own cards (performance plan RH ②).
    val layout by vm.layout.collectAsStateWithLifecycle()
    val rotates by app.settings.heroRotates.flow.collectAsStateWithLifecycle()
    // Shown from the store at once. Entering home refreshes what is due; back from the player (tick > 0)
    // 在看 asks the servers whatever its age (performance plan RN ①).
    LaunchedEffect(tick) { vm.refresh(fromPlayer = tick > 0) }
    // 主推区 (ui-spec §2.1): the chart or the servers' picks, worked out in the ViewModel (RH ④).
    val featured by vm.featured.collectAsStateWithLifecycle()
    val chart by vm.chart.collectAsStateWithLifecycle()
    val heroLabels by vm.heroLabels.collectAsStateWithLifecycle()
    SideEffect {
        carousel.labels = heroLabels
        carousel.defaultLabel = if (chart.use) "TMDB · ${CatalogChart.of(chart.chart).label}" else null
        carousel.items = featured
    }
    // A slide cut off by leaving home rests on its page when home shows again.
    LaunchedEffect(carousel) { carousel.settle() }
    // Only back from the player: coming back from a detail page keeps the hero's full items (RN ④).
    LaunchedEffect(tick) { if (tick > 0) vm.forgetHeroFetches() }
    HeroPrefetch(carousel, tick) { vm.prefetchHero(it) }
    HeroTrailerHost(vm, carousel, trailer)

    val activity = context as? android.app.Activity
    val shown = featured.isNotEmpty() || layout.watching
    LaunchedEffect(shown) { if (shown && activity != null) Diagnostics.homeShown(activity) }

    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
        // The scroll area starts below the overlaid top bar: scroll containers are focus groups, and a
        // group reaching up behind the tabs is not "below" them, so D-pad down would find nothing.
        // A lazy column (P4 perf): a server's own home has a row per library, and a plain scrolling
        // column composed and measured every one of them at once — 2.5–3 s on the Z9X 8K at cold start.
        // Rows just outside the screen stay composed (ahead one and a half rows, behind one), and
        // are prepared in idle time: moving down onto a row not yet composed built ~9 posters
        // inside one frame (150 ms on the box).
        @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
        val list = rememberLazyListState(cacheWindow = androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow(ahead = 600.dp, behind = 400.dp))
        SideEffect { carousel.list = list }
        val running = rememberHeroCountdown(carousel, rotates, scope, trailer)
        LazyColumn(Modifier.fillMaxSize().padding(top = CONTENT_TOP), state = list) {
            item(key = "hero", contentType = "hero") {
                HomeHeroItem(nav, vm, carousel, featured.isNotEmpty(), chart, shownAt, search, entry, running, list, scope)
            }
            // 在看 (ui-spec §2.18): the first cards of the 在看 page, then 「全部 ›」 to it.
            if (layout.watching) item(key = "watching", contentType = "row") { HomeWatchingRow(nav, vm, onWatching, onWatchingMenu) }
            if (layout.watchlist) item(key = "watchlist", contentType = "row") {
                TraceRecomposition("Home.row.watchlist")
                val cards by vm.watchlistRow.collectAsStateWithLifecycle()
                PosterRow("待看", cards, bottom = 48.dp) { card ->
                    card.watchlist?.let { e -> watchlistRef(e, app.registry.all)?.let { nav.open(it) } }
                }
            }
            if (layout.libraries) item(key = "libraries", contentType = "row") { HomeLibrariesRow(vm, onLibrary) }
            layout.libraryRows.forEach { rowKey ->
                item(key = rowKey, contentType = "row") {
                    TraceRecomposition("Home.row.libLatest")
                    val rows by vm.libraryRows.collectAsStateWithLifecycle()
                    val row = rows.firstOrNull { it.key == rowKey }
                    if (row != null) PosterRow(row.title, row.cards, bottom = 48.dp) { card -> card.ref?.let { nav.open(it) } }
                }
            }
            if (layout.latest) item(key = "latest", contentType = "row") {
                TraceRecomposition("Home.row.latest")
                val cards by vm.latestRow.collectAsStateWithLifecycle()
                PosterRow("最新添加", cards, bottom = 80.dp) { card -> card.ref?.let { nav.open(it) } }
            }
        }
    }
}

/**
 * Tells the ViewModel which title the hero rests on and plays the trailer it finds (trailer design).
 * Collected here, not read in composition: a trailer arriving or ending recomposes nothing but the
 * backdrop page it plays on. A slide, scrolling the hero away or leaving home stops it at once.
 */
@Composable
private fun HeroTrailerHost(vm: HomeViewModel, carousel: HeroCarousel, trailer: TrailerPlayer) {
    val app = LocalContext.current.zmby
    val lifecycle by androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    LaunchedEffect(carousel, trailer) {
        snapshotFlow { carousel.resting(lifecycle.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) }.collect { ref ->
            if (ref == null || ref.key != trailer.key) trailer.stop()
            vm.heroResting(ref)
        }
    }
    LaunchedEffect(vm, trailer) {
        kotlinx.coroutines.flow.combine(vm.heroTrailer, app.settings.trailerSound.flow) { t, sound -> t to sound }.collect { (t, sound) ->
            // Null also when 自动播放预告片 is switched off: whatever plays stops.
            if (t == null) trailer.stop() else if (t.key == carousel.current?.key) trailer.load(t.key, t.info, sound)
        }
    }
    DisposableEffect(trailer) {
        onDispose {
            trailer.stop()
            vm.heroResting(null)
        }
    }
}

/** The hero block: the sliding pages and controls, or the empty / chart-error state. */
@Composable
private fun HomeHeroItem(nav: Nav, vm: HomeViewModel, carousel: HeroCarousel, hasTitles: Boolean, chart: ChartState, shownAt: Long, search: FocusRequester, entry: FocusRequester,
    running: Boolean, list: androidx.compose.foundation.lazy.LazyListState, scope: kotlinx.coroutines.CoroutineScope) {
    TraceRecomposition("Home.row.hero")
    val app = LocalContext.current.zmby
    // The text pages slide over the backdrop pages; 详情 and the dots stay put. Both fade and
    // move up with the list as focus goes down to the rows (2026-10-25 user request).
    Box(Modifier.fillMaxWidth().height(598.dp)) {
        if (hasTitles) {
            // Resume/latest rows carry no media streams; the full items give the badges.
            val heroDetails by vm.heroDetails.collectAsStateWithLifecycle()
            Column(Modifier.padding(top = 170.dp - CONTENT_TOP)) {
                HeroPages(carousel, heroDetails)
                HeroControls(
                    carousel = carousel,
                    focus = rememberInitialFocus(carousel.items.firstOrNull()?.key, shownAt),
                    running = running,
                    scope = scope,
                    onOpen = { nav.open(it) },
                    modifier = Modifier.focusRequester(entry).upToSearch(search),
                    onFocusChange = { focused ->
                        // Back on the hero: show the whole top of the page again, not just the button.
                        if (focused && list.canScrollBackward) scope.launch { list.scrollToTopFirmly() }
                    },
                )
            }
        } else {
            Box(Modifier.padding(start = 80.dp, top = 170.dp - CONTENT_TOP).width(880.dp)) {
                if (chart.use && chart.error != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                        Text("TMDB · ${CatalogChart.of(chart.chart).label}", fontSize = 38.sp, fontWeight = FontWeight.Bold)
                        Text(chart.error, fontSize = 23.sp, color = Z.textSecondary)
                        GlassButton("元数据设置", { nav.go(Route.Settings) }, Modifier.focusRequester(rememberInitialFocus("catalogSetup", shownAt)).focusRequester(entry).upToSearch(search))
                    }
                } else {
                    // Nothing cached and nothing from the servers (P4 离线与错误处理): say why and offer a retry.
                    val health by app.registry.servers.collectAsStateWithLifecycle()
                    val rows by vm.rows.collectAsStateWithLifecycle()
                    val watching by vm.watching.collectAsStateWithLifecycle()
                    val enabled = health.filter { it.enabled }
                    val allOff = enabled.isNotEmpty() && enabled.all { it.health == Health.Off }
                    val nothing = watching.isEmpty() && rows.latest.isEmpty()
                    if (nothing && (allOff || rows.loaded)) {
                        HomeEmpty(
                            if (allOff) "暂时连不上任何服务器" else "这些服务器上还没有可显示的内容",
                            if (allOff) "检查网络或服务器状态后重试。打开过的影片和收藏在本地仍可浏览。" else "可以在服务器页打开媒体库的首页开关，或换一个首页来源。",
                            focus = rememberInitialFocus(if (allOff) "offline" else "empty", shownAt),
                            onRetry = {
                                enabled.forEach { app.registry.retry(it.id) }
                                scope.launch { app.library.measure() }
                                vm.refresh(force = true)
                            },
                            onServers = { nav.go(Route.Servers) },
                            modifier = Modifier.upToSearch(search),
                            entry = entry,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeWatchingRow(nav: Nav, vm: HomeViewModel, onWatching: () -> Unit, onWatchingMenu: (WatchCard) -> Unit) {
    TraceRecomposition("Home.row.watching")
    val watching by vm.watching.collectAsStateWithLifecycle()
    val faces = LocalContext.current.zmby.cardFaces.version.collectAsStateWithLifecycle()
    val openWatching = rememberWatchingPlayback(nav)
    Column(Modifier.cachedLayer(), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Box(Modifier.padding(start = 80.dp)) { RowTitle("在看") }
        LazyRow(
            modifier = Modifier.horizontalRow(),
            contentPadding = PaddingValues(start = 80.dp, end = 120.dp, bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(36.dp),
        ) {
            items(watching.take(HOME_WATCHING), key = { it.key }, contentType = { "watching" }) { card ->
                WatchingRowCard(card, rememberWatchTitle(card, faces.value), onClick = { openWatching(card) }, onMenu = { onWatchingMenu(card) }, focusKey = "watching:${card.key}")
            }
            item(key = "watching:all", contentType = "more") {
                Box(Modifier.height(225.dp), contentAlignment = Alignment.Center) {
                    GlassButton("全部 ›", onWatching, label = "在看 全部")
                }
            }
        }
    }
}

@Composable
private fun HomeLibrariesRow(vm: HomeViewModel, onLibrary: (ServerItem) -> Unit) {
    TraceRecomposition("Home.row.libraries")
    val libs by vm.librariesRow.collectAsStateWithLifecycle()
    Column(Modifier.cachedLayer(), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Box(Modifier.padding(start = 80.dp)) { RowTitle("媒体库") }
        LazyRow(
            modifier = Modifier.horizontalRow(),
            contentPadding = PaddingValues(start = 80.dp, end = 120.dp, bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            items(libs, key = { it.key }, contentType = { "library" }) { lib ->
                MediaCard16x9(imageUrl = lib.image, onClick = { onLibrary(lib.lib) }, width = 360.dp, title = lib.title, focusKey = "library:${lib.key}")
            }
        }
    }
}

/** A titled row of 2:3 posters from prepared [PosterModel]s; Menu adds or removes the work from 待看. */
@Composable
private fun PosterRow(title: String, cards: List<PosterModel>, bottom: androidx.compose.ui.unit.Dp, onOpen: (PosterModel) -> Unit) {
    val app = LocalContext.current.zmby
    val scope = rememberCoroutineScope()
    Column(Modifier.cachedLayer(), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Box(Modifier.padding(start = 80.dp)) { RowTitle(title) }
        LazyRow(
            modifier = Modifier.horizontalRow(),
            contentPadding = PaddingValues(start = 80.dp, end = 120.dp, bottom = bottom),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            items(cards, key = { it.key }, contentType = { "poster" }) { card ->
                PosterCard2x3(
                    imageUrl = card.image,
                    title = card.title,
                    onClick = { onOpen(card) },
                    topEnd = card.badge,
                    subtitle = card.subtitle,
                    focusKey = card.focusKey,
                    modifier = Modifier.onMenuKey { card.ref?.let { app.toggleWatchlist(scope, it) } },
                )
            }
        }
    }
}

/** "科幻 · 冒险": genres joined by a middle dot (never a slash). */
internal fun genresLabel(item: BaseItemDto): String? = item.genres.take(2).joinToString(" · ").ifEmpty { null }

/**
 * A home row (or the top bar) drawn into its own offscreen layer (P4 perf). The Z9X 8K redraws the
 * whole screen every frame, and re-issuing every card's drawing kept its RenderThread at 10–14 ms a
 * frame; a cached layer is redrawn only when something in it changes (the row being scrolled, the
 * focused card), the others are one textured quad each.
 */
private fun Modifier.cachedLayer(): Modifier = if ("rowlayer" in Diagnostics.drawOff) this else
    graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
