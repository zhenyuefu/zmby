package app.zmby.ui.screens

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
import app.zmby.data.Library
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

/** The search glyph opens the search screen instead of switching tabs. */
private const val SEARCH_TAB = 5

/** Top of the focusable content, just under the top bar (which ends at 110 on the design grid). */
private val CONTENT_TOP = 120.dp

private val TABS = listOf(
    Segment("首页"),
    Segment("电影"),
    Segment("剧集"),
    Segment("收藏"),
    Segment("待看"),
    Segment("", glyph = Glyph.Search, selectOnFocus = false),
)

/** Main.dc.html: left→right 0.96 → 0.78 (34 %) → 0.1 (70 %); bottom 560 of 1080, solid for its lower 30 %. */
private val HOME_SCRIMS = Scrims(listOf(0f to 0.96f, 0.34f to 0.78f, 0.70f to 0.1f), bottomFrom = 520f / 1080f, bottomSolid = 0.3f)

/** A library picked on a server's own home: the browse tab opens on it. */
data class BrowseTarget(val server: String, val libraryId: String, val types: String)

@Composable
fun HomeScreen(nav: Nav, tab: Int, onTab: (Int) -> Unit) {
    val app = LocalContext.current.zmby
    val library = app.library
    val servers by library.servers.collectAsState()
    LaunchedEffect(Unit) { library.measure() }
    // Initial focus belongs to entering Home, not to switching back to its first tab.
    val shownAt = rememberShownAt()
    // The hero carousel lives here: its pages are the home backdrop, drawn under the top bar.
    val carousel = rememberRetained("heroCarousel") { HeroCarousel() }
    val heroBackdrop = if (tab == 0) carousel.current?.let { library.backdrop(it) } else null
    val backdropVisible = remember(carousel) { { carousel.backdropVisible } }
    val backdropShift = remember(carousel) { { -carousel.scrolled } }
    val vm = screenModel("home") { HomeViewModel(app) }
    val source by vm.source.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    var browseTarget by rememberRetained("browseTarget") { mutableStateOf<BrowseTarget?>(null) }

    BackdropScreen(
        heroBackdrop,
        HOME_SCRIMS,
        visible = backdropVisible,
        shift = backdropShift,
        layer = if (tab == 0) ({ HeroBackdrop(carousel, HOME_SCRIMS) }) else null,
    ) {
        when (tab) {
            0 -> HomeFeed(nav, shownAt, carousel, onLibrary = { lib ->
                val series = lib.item.collectionType == "tvshows"
                browseTarget = BrowseTarget(lib.server, lib.item.id, if (series) "Series" else "Movie")
                onTab(if (series) 2 else 1)
            })
            1 -> BrowseScreen(nav, "Movie", browseTarget?.takeIf { it.types == "Movie" }) { browseTarget = null }
            2 -> BrowseScreen(nav, "Series", browseTarget?.takeIf { it.types == "Series" }) { browseTarget = null }
            3 -> FavoritesGrid(nav)
            4 -> WatchlistGrid(nav)
        }
        TopBar(
            tab = tab,
            servers = servers,
            sourceLabel = servers.firstOrNull { it.id == source }?.name ?: "全部",
            onSource = { picking = true },
            onTab = { i -> if (i == SEARCH_TAB) nav.go(Route.Search) else onTab(i) },
            onServers = { nav.go(Route.Servers) },
            onSettings = { nav.go(Route.Settings) },
        )
        if (picking) {
            SourcePicker(servers.filter { it.enabled }, source, onPick = { id -> picking = false; vm.setSource(id) }, onDismiss = { picking = false })
        }
    }
}

/**
 * 首页来源 (ui-spec §2.1): 「全部」 and the servers in priority order with their health; picking one
 * shows that server's own home. Back closes it.
 */
@Composable
private fun SourcePicker(servers: List<ServerStatus>, current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Z.bg.copy(alpha = 0.72f))) {
        Column(
            Modifier.padding(start = 560.dp, top = 120.dp).width(520.dp).groupCard(androidx.compose.foundation.shape.RoundedCornerShape(28.dp)).padding(12.dp)
                .focusProperties { onExit = { cancelFocusChange() } }.focusGroup(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val options = listOf<Pair<String, ServerStatus?>>("" to null) + servers.map { it.id to it }
            options.forEachIndexed { i, (id, s) ->
                SelectableRow(
                    onClick = { onPick(id) },
                    modifier = if (id == current || (i == 0 && options.none { it.first == current })) Modifier.focusRequester(first) else Modifier,
                    selected = id == current,
                    radius = 20.dp,
                    label = "首页来源 ${s?.name ?: "全部"}",
                ) {
                    if (s != null) HealthDot(s.health)
                    Text(s?.name ?: "全部", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** The home page with nothing to show: a title, a line of advice, 重试 (focused) and 服务器状态. */
@Composable
private fun HomeEmpty(title: String, advice: String, focus: FocusRequester, onRetry: () -> Unit, onServers: () -> Unit) {
    Column(Modifier.padding(top = 80.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(title, fontSize = 48.sp, fontWeight = FontWeight.ExtraBold)
        Text(advice, fontSize = 23.sp, color = Z.textSecondary, modifier = Modifier.width(820.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            GlassButton("重试", onRetry, Modifier.focusRequester(focus), glyph = Glyph.Refresh)
            GlassButton("服务器状态", onServers)
        }
    }
}

@Composable
private fun TopBar(tab: Int, servers: List<ServerStatus>, sourceLabel: String, onSource: () -> Unit, onTab: (Int) -> Unit, onServers: () -> Unit, onSettings: () -> Unit) {
    Row(
        // Cached like the rows; the bottom padding keeps the buttons' shadows inside the layer.
        Modifier.fillMaxWidth().cachedLayer().padding(start = 80.dp, end = 80.dp, top = 44.dp, bottom = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) { ZmbyLogo() }
        // 首页来源: 「全部」 or one server's own home, left of the navigation capsule.
        GlassSurface(onSource, label = "首页来源", modifier = Modifier.padding(end = 14.dp)) {
            Row(Modifier.height(64.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(sourceLabel, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
                Icon(Glyph.Down, size = 20.dp, strokeWidth = 2.4f)
            }
        }
        SegmentedWell(TABS, tab, onTab, glassTrack = true, segHeight = 52.dp, fontSize = 22, horizontalPadding = 28.dp, labelPrefix = "tab")
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            GlassSurface(onServers, label = "服务器状态") {
                Row(
                    Modifier.height(64.dp).padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val enabled = servers.filter { it.enabled }
                    val online = enabled.count { it.health != Health.Off }
                    HealthDot(
                        when {
                            online == 0 -> Health.Off
                            online < enabled.size || enabled.any { it.health == Health.Warn } -> Health.Warn
                            else -> Health.Ok
                        },
                    )
                    Text("$online 台在线", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            GlassSurface(onSettings, shape = CircleShape, label = "设置") {
                Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) { Icon(Glyph.Gear, size = 26.dp, strokeWidth = 2f) }
            }
        }
    }
}

@Composable
private fun HomeFeed(nav: Nav, shownAt: Long, carousel: HeroCarousel, onLibrary: (ServerItem) -> Unit) {
    val context = LocalContext.current
    val app = context.zmby
    val library = app.library
    val tick = rememberResumeTick()
    val vm = screenModel("home") { HomeViewModel(app) }
    val rows by vm.rows.collectAsStateWithLifecycle()
    val resume = rows.resume
    val latest = rows.latest
    val nextUp = rows.nextUp
    val settings = app.settings
    val showNextUp by settings.showNextUpRow.flow.collectAsStateWithLifecycle()
    val showLatest by settings.showLatestRow.flow.collectAsStateWithLifecycle()
    val rotates by settings.heroRotates.flow.collectAsStateWithLifecycle()
    // Shown from the store at once; entering home and coming back from the player ask the servers again.
    LaunchedEffect(tick) { vm.refresh() }
    val serverHome by vm.serverHome.collectAsStateWithLifecycle()
    // 主推区: recent additions (ui-spec §2.1) — the chosen server's own, or every server's merged.
    val chart by settings.tmdbChart.flow.collectAsStateWithLifecycle()
    val language by settings.metadataLanguage.flow.collectAsStateWithLifecycle()
    val posterLanguage by settings.posterLanguage.flow.collectAsStateWithLifecycle()
    val logoLanguage by settings.logoLanguage.flow.collectAsStateWithLifecycle()
    val token by settings.tmdbToken.flow.collectAsStateWithLifecycle()
    var chartError by remember(chart, token) { mutableStateOf<String?>(null) }
    val featured by produceState<List<ServerItem>>(emptyList(), chart, listOf(language, posterLanguage, logoLanguage, token), tick) {
        withContext(Dispatchers.IO) {
            try {
                val titles = app.catalog.chart(CatalogChart.of(chart)).take(8)
                value = titles.map { (app.catalog.cached(it.ref) ?: it).cardRef() }
                chartError = null
                for (work in titles) {
                    try {
                        val full = app.catalog.work(work.ref).cardRef()
                        value = value.map { if (it.item.id == full.item.id) full else it }
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { /* A title remains a usable catalogue entry. */ }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { chartError = e.message }
        }
    }
    SideEffect { carousel.items = featured }
    // A slide cut off by leaving home rests on its page when home shows again.
    LaunchedEffect(carousel) { carousel.settle() }
    LaunchedEffect(tick) { vm.forgetHeroFetches() }
    HeroPrefetch(carousel, tick) { vm.prefetchHero(it) }
    // Resume/latest rows carry no media streams; the full items give the badges.
    val heroDetails by vm.heroDetails.collectAsStateWithLifecycle()
    val serverList by app.registry.servers.collectAsStateWithLifecycle()
    val watchEntries by app.watchlist.entries.collectAsStateWithLifecycle()
    val showWatchlist by settings.showWatchlistRow.flow.collectAsStateWithLifecycle()
    val servers = serverList

    val activity = context as? android.app.Activity
    val shown = featured.isNotEmpty() || resume.isNotEmpty()
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
        val running = HeroCountdown(carousel, rotates, scope)
        LazyColumn(Modifier.fillMaxSize().padding(top = CONTENT_TOP), state = list) {
          item(key = "hero", contentType = "hero") {
           // The text pages slide over the backdrop pages; 详情 and the dots stay put. Both fade and
           // move up with the list as focus goes down to the rows (2026-10-25 user request).
           Box(Modifier.fillMaxWidth().height(550.dp)) {
            if (featured.isNotEmpty()) {
                Column(Modifier.padding(top = 170.dp - CONTENT_TOP)) {
                    HeroPages(carousel, heroDetails)
                    HeroControls(
                        carousel = carousel,
                        focus = rememberInitialFocus(featured.firstOrNull()?.key, shownAt),
                        running = running,
                        scope = scope,
                        onOpen = { nav.open(it) },
                        onFocusChange = { focused ->
                            // Back on the hero: show the whole top of the page again, not just the button.
                            if (focused && list.canScrollBackward) scope.launch { list.scrollToTopFirmly() }
                        },
                    )
                }
            } else {
                Box(Modifier.padding(start = 80.dp, top = 170.dp - CONTENT_TOP).width(880.dp)) {
                    if (!app.catalog.configured || chartError != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                            Text("TMDB · ${CatalogChart.of(chart).label}", fontSize = 38.sp, fontWeight = FontWeight.Bold)
                            Text(chartError ?: "在设置中填写 TMDB Read Access Token，启用影视榜单轮播", fontSize = 23.sp, color = Z.textSecondary)
                            GlassButton("元数据设置", { nav.go(Route.Settings) }, Modifier.focusRequester(rememberInitialFocus("catalogSetup", shownAt)))
                        }
                    } else {
                    // Nothing cached and nothing from the servers (P4 离线与错误处理): say why and offer a retry.
                    val enabled = serverList.filter { it.enabled }
                    val allOff = enabled.isNotEmpty() && enabled.all { it.health == Health.Off }
                    val nothing = resume.isEmpty() && nextUp.isEmpty() && latest.isEmpty()
                    if (nothing && (allOff || rows.loaded)) {
                        HomeEmpty(
                            if (allOff) "暂时连不上任何服务器" else "这些服务器上还没有可显示的内容",
                            if (allOff) "检查网络或服务器状态后重试。打开过的影片和收藏在本地仍可浏览。" else "可以在服务器页打开媒体库的首页开关，或换一个首页来源。",
                            focus = rememberInitialFocus(if (allOff) "offline" else "empty", shownAt),
                            onRetry = {
                                enabled.forEach { app.registry.retry(it.id) }
                                scope.launch { library.measure() }
                                vm.refresh(force = true)
                            },
                            onServers = { nav.go(Route.Servers) },
                        )
                    }
                    }
                }
            }
           }
          }
            if (resume.isNotEmpty()) item(key = "resume", contentType = "row") {
                Column(Modifier.cachedLayer(), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    Box(Modifier.padding(start = 80.dp)) { RowTitle("继续观看") }
                    LazyRow(
                        modifier = Modifier.horizontalRow(),
                        contentPadding = PaddingValues(start = 80.dp, end = 120.dp, bottom = 48.dp),
                        horizontalArrangement = Arrangement.spacedBy(36.dp),
                    ) {
                        items(resume, key = { it.key }, contentType = { "resume" }) { ref ->
                            ResumeCard(ref, onClick = { nav.open(ref) }, focusKey = "resume:${ref.key}")
                        }
                    }
                }
            }
            if (showNextUp && nextUp.isNotEmpty()) item(key = "nextup", contentType = "row") {
                Column(Modifier.cachedLayer(), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    Box(Modifier.padding(start = 80.dp)) { RowTitle("下一集") }
                    LazyRow(
                        modifier = Modifier.horizontalRow(),
                        contentPadding = PaddingValues(start = 80.dp, end = 120.dp, bottom = 48.dp),
                        horizontalArrangement = Arrangement.spacedBy(36.dp),
                    ) {
                        items(nextUp, key = { it.key }, contentType = { "nextup" }) { ref ->
                            val item = ref.item
                            MediaCard16x9(
                                imageUrl = library.landscapeImage(ref),
                                onClick = { nav.open(ref) },
                                title = workTitleOf(item),
                                topStart = library.status(ref.server)?.name,
                                focusKey = "nextup:${ref.key}",
                            ) {
                                Text(listOfNotNull(episodeCode(item), item.name).joinToString(" · "), fontSize = 19.sp, color = Z.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            if (showWatchlist && watchEntries.isNotEmpty()) item(key = "watchlist", contentType = "row") {
                Column(Modifier.cachedLayer(), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    Box(Modifier.padding(start = 80.dp)) { RowTitle("待看") }
                    LazyRow(
                        modifier = Modifier.horizontalRow(),
                        contentPadding = PaddingValues(start = 80.dp, end = 120.dp, bottom = 48.dp),
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                    ) {
                        items(watchEntries, key = { it.workKey }, contentType = { "poster" }) { e ->
                            val ref = watchlistRef(e, servers)
                            PosterCard2x3(
                                imageUrl = ref?.let { library.primaryImage(it) },
                                title = e.title,
                                onClick = { if (ref != null) nav.open(ref) },
                                subtitle = listOfNotNull(if (e.type == "Series") "剧集" else "电影", e.year?.toString()).joinToString(" · "),
                                focusKey = "watchrow:${e.workKey}",
                                modifier = Modifier.onMenuKey { if (ref != null) app.toggleWatchlist(scope, ref) },
                            )
                        }
                    }
                }
            }
            serverHome?.let { own ->
                if (own.libraries.isNotEmpty()) item(key = "libraries", contentType = "row") {
                    Column(Modifier.cachedLayer(), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                        Box(Modifier.padding(start = 80.dp)) { RowTitle("媒体库") }
                        LazyRow(
                            modifier = Modifier.horizontalRow(),
                            contentPadding = PaddingValues(start = 80.dp, end = 120.dp, bottom = 48.dp),
                            horizontalArrangement = Arrangement.spacedBy(28.dp),
                        ) {
                            items(own.libraries, key = { it.key }, contentType = { "library" }) { lib ->
                                MediaCard16x9(
                                    imageUrl = library.primaryImage(lib, maxHeight = 340),
                                    onClick = { onLibrary(lib) },
                                    width = 360.dp,
                                    title = lib.item.name,
                                    focusKey = "library:${lib.key}",
                                )
                            }
                        }
                    }
                }
                own.libraries.forEach { lib ->
                    val items = own.latest[lib.item.id].orEmpty()
                    if (items.isNotEmpty()) item(key = "liblatest:${lib.key}", contentType = "row") {
                        Column(Modifier.cachedLayer(), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                            Box(Modifier.padding(start = 80.dp)) { RowTitle("最新 · ${lib.item.name.orEmpty()}") }
                            LazyRow(
                                modifier = Modifier.horizontalRow(),
                                contentPadding = PaddingValues(start = 80.dp, end = 120.dp, bottom = 48.dp),
                                horizontalArrangement = Arrangement.spacedBy(28.dp),
                            ) {
                                items(items, key = { it.key }, contentType = { "poster" }) { ref ->
                                    PosterCard2x3(
                                        imageUrl = library.primaryImage(ref),
                                        title = titleOf(ref.item),
                                        onClick = { nav.open(ref) },
                                        subtitle = listOfNotNull(if (ref.item.type == "Series") "剧集" else "电影", ref.item.productionYear?.toString()).joinToString(" · "),
                                        focusKey = "liblatest:${lib.item.id}:${ref.key}",
                                        modifier = Modifier.onMenuKey { app.toggleWatchlist(scope, ref) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (serverHome == null && showLatest && latest.isNotEmpty()) item(key = "latest", contentType = "row") {
                Column(Modifier.cachedLayer(), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    Box(Modifier.padding(start = 80.dp)) { RowTitle("最新添加") }
                    LazyRow(
                        modifier = Modifier.horizontalRow(),
                        contentPadding = PaddingValues(start = 80.dp, end = 120.dp, bottom = 80.dp),
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                    ) {
                        items(latest, key = { it.key }, contentType = { "poster" }) { ref ->
                            val item = ref.item
                            val count = rows.latestCopies[ref.key] ?: 1
                            PosterCard2x3(
                                imageUrl = library.primaryImage(ref),
                                title = titleOf(item),
                                onClick = { nav.open(ref) },
                                topEnd = if (count > 1) "$count 个版本" else null,
                                subtitle = listOfNotNull(if (item.type == "Series") "剧集" else "电影", item.productionYear?.toString()).joinToString(" · "),
                                focusKey = "latest:${ref.key}",
                                modifier = Modifier.onMenuKey { app.toggleWatchlist(scope, ref) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** "科幻 · 冒险": genres joined by a middle dot (never a slash). */
internal fun genresLabel(item: BaseItemDto): String? = item.genres.take(2).joinToString(" · ").ifEmpty { null }

@Composable
private fun ResumeCard(ref: ServerItem, onClick: () -> Unit, focusKey: String) {
    val library = LocalContext.current.zmby.library
    val item = ref.item
    val isEpisode = item.type == "Episode"
    MediaCard16x9(
        imageUrl = library.landscapeImage(ref),
        onClick = onClick,
        title = workTitleOf(item),
        topStart = library.status(ref.server)?.name,
        progress = progressFraction(item),
        focusKey = focusKey,
    ) {
        val caption = listOfNotNull(if (isEpisode) episodeCode(item) else null, remainingLabel(item)).joinToString(" · ")
        Text(caption, fontSize = 19.sp, color = Z.textSecondary, maxLines = 1)
    }
}

/**
 * A home row (or the top bar) drawn into its own offscreen layer (P4 perf). The Z9X 8K redraws the
 * whole screen every frame, and re-issuing every card's drawing kept its RenderThread at 10–14 ms a
 * frame; a cached layer is redrawn only when something in it changes (the row being scrolled, the
 * focused card), the others are one textured quad each.
 */
private fun Modifier.cachedLayer(): Modifier =
    graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
