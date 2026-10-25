package app.zmby.ui.screens

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.zmby.data.Library
import app.zmby.data.LibraryView
import app.zmby.data.ServerItem
import app.zmby.data.ServerStatus
import app.zmby.emby.BaseItemDto
import app.zmby.emby.ItemsResult
import app.zmby.log.ZLog
import app.zmby.media.episodeCode
import app.zmby.media.formatClock
import app.zmby.media.formatDuration
import app.zmby.media.heroBadges
import app.zmby.media.progressFraction
import app.zmby.media.remainingLabel
import app.zmby.ui.Nav
import app.zmby.ui.Route
import app.zmby.ui.components.BackdropScreen
import app.zmby.ui.components.Badge
import app.zmby.ui.components.GlassButton
import app.zmby.ui.components.GlassIconButton
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
import app.zmby.ui.components.TitleLogo
import app.zmby.ui.components.ZmbyLogo
import app.zmby.ui.components.horizontalRow
import app.zmby.ui.components.onMenuKey
import app.zmby.ui.components.SelectableRow
import app.zmby.ui.components.groupCard
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.focus.focusProperties
import app.zmby.ui.components.scrollToTopFirmly
import app.zmby.ui.open
import app.zmby.ui.play
import app.zmby.ui.rememberInitialFocus
import app.zmby.ui.rememberResumeTick
import app.zmby.ui.rememberRetained
import app.zmby.ui.rememberShownAt
import app.zmby.ui.screenModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zmby.ui.resumeMsOf
import app.zmby.ui.theme.Z
import app.zmby.ui.userMessage
import app.zmby.zmby
import kotlinx.coroutines.delay
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
    var backdrop by remember { mutableStateOf<String?>(null) }
    val vm = screenModel("home") { HomeViewModel(app) }
    val source by vm.source.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    var browseTarget by rememberRetained("browseTarget") { mutableStateOf<BrowseTarget?>(null) }

    BackdropScreen(if (tab == 0) backdrop else null, HOME_SCRIMS) {
        when (tab) {
            0 -> HomeFeed(nav, shownAt, onBackdrop = { backdrop = it }, onLibrary = { lib ->
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

@Composable
private fun TopBar(tab: Int, servers: List<ServerStatus>, sourceLabel: String, onSource: () -> Unit, onTab: (Int) -> Unit, onServers: () -> Unit, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 80.dp, end = 80.dp, top = 44.dp),
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
private fun HomeFeed(nav: Nav, shownAt: Long, onBackdrop: (String?) -> Unit, onLibrary: (ServerItem) -> Unit) {
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
    val featured = remember(serverHome, latest, resume) { (serverHome?.recent ?: latest).ifEmpty { resume }.take(5) }
    var page by rememberRetained("page") { mutableIntStateOf(0) }
    var heroFocused by remember { mutableStateOf(false) }
    LaunchedEffect(featured, heroFocused, rotates) {
        while (rotates && !heroFocused && featured.size > 1) {
            delay(9_000)
            page = (page + 1) % featured.size
        }
    }
    val hero = featured.getOrNull(page.coerceAtMost((featured.size - 1).coerceAtLeast(0)))
    // Resume/latest rows carry no media streams; the full item gives badges and the version count.
    val heroFull by vm.heroFull.collectAsStateWithLifecycle()
    LaunchedEffect(hero?.key, tick) { vm.showHero(hero) }
    val error: String? = null

    val backdropUrl = hero?.let { library.backdrop(it) }
    LaunchedEffect(backdropUrl) { onBackdrop(backdropUrl) }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
        // The scroll area starts below the overlaid top bar: scroll containers are focus groups, and a
        // group reaching up behind the tabs is not "below" them, so D-pad down would find nothing.
        val scroll = rememberScrollState()
        Column(Modifier.fillMaxSize().padding(top = CONTENT_TOP).verticalScroll(scroll)) {
            Spacer(Modifier.height(170.dp - CONTENT_TOP))
            Box(
                Modifier
                    .height(500.dp)
                    .padding(start = 80.dp)
                    .width(880.dp)
                    .graphicsLayer { alpha = (1f - scroll.value / 300f).coerceIn(0f, 1f) },
            ) {
                if (hero != null) {
                    Hero(
                        focus = rememberInitialFocus(featured.firstOrNull()?.key, shownAt),
                        ref = hero,
                        full = heroFull?.takeIf { it.id == hero.item.id },
                        page = page,
                        pages = featured.size,
                        onPlay = {
                            if (hero.item.type == "Series") nav.open(hero) else play(context, hero, hero.item.resumeMs)
                        },
                        onDetails = { nav.open(hero) },
                        // Left on the first button, right on the last one: the previous / next title,
                        // wrapping around; focus stays on that button.
                        onStep = { delta -> if (featured.size > 1) page = Math.floorMod(page + delta, featured.size) },
                        onFocusChange = { focused ->
                            heroFocused = focused
                            // Back on the hero: show the whole top of the page again, not just the button.
                            if (focused && scroll.value > 0) scope.launch { scroll.scrollToTopFirmly() }
                        },
                    )
                } else if (error != null) {
                    Text("加载失败：$error", fontSize = 24.sp, color = Z.textSecondary)
                }
            }
            if (resume.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
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
            if (showNextUp && nextUp.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
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
                                title = item.seriesName ?: item.name,
                                topStart = library.status(ref.server)?.name,
                                focusKey = "nextup:${ref.key}",
                            ) {
                                Text(listOfNotNull(episodeCode(item), item.name).joinToString(" · "), fontSize = 19.sp, color = Z.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            val watchEntries by app.watchlist.entries.collectAsStateWithLifecycle()
            val showWatchlist by settings.showWatchlistRow.flow.collectAsStateWithLifecycle()
            val servers by app.registry.servers.collectAsStateWithLifecycle()
            if (showWatchlist && watchEntries.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
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
                if (own.libraries.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
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
                    if (items.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                            Box(Modifier.padding(start = 80.dp)) { RowTitle("最新 · ${lib.item.name.orEmpty()}") }
                            LazyRow(
                                modifier = Modifier.horizontalRow(),
                                contentPadding = PaddingValues(start = 80.dp, end = 120.dp, bottom = 48.dp),
                                horizontalArrangement = Arrangement.spacedBy(28.dp),
                            ) {
                                items(items, key = { it.key }, contentType = { "poster" }) { ref ->
                                    PosterCard2x3(
                                        imageUrl = library.primaryImage(ref),
                                        title = ref.item.name.orEmpty(),
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
            if (serverHome == null && showLatest && latest.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
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
                                title = item.name.orEmpty(),
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

@Composable
private fun Hero(
    focus: FocusRequester,
    ref: ServerItem,
    full: BaseItemDto?,
    page: Int,
    pages: Int,
    onPlay: () -> Unit,
    onDetails: () -> Unit,
    onStep: (Int) -> Unit,
    onFocusChange: (Boolean) -> Unit,
) {
    val app = LocalContext.current.zmby
    val library = app.library
    val scope = rememberCoroutineScope()
    val item = ref.item
    val isEpisode = item.type == "Episode"
    val title = if (isEpisode) item.seriesName ?: item.name.orEmpty() else item.name.orEmpty()
    val favorites by app.favorites.items.collectAsState()
    val favorite = remember(favorites, ref.key) { app.favorites.isFavorite(ref) }
    // focusGroup makes hasFocus cover every hero button, not just the first one; otherwise the
    // carousel keeps rotating under 详情/收藏 and they act on a different title.
    Column(Modifier.onFocusChanged { onFocusChange(it.hasFocus) }.focusGroup(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        TitleLogo(library.logo(ref), title, maxWidth = 560.dp, maxHeight = 170.dp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val meta = listOfNotNull(
                item.productionYear?.toString(),
                if (isEpisode) listOfNotNull(episodeCode(item), item.name).joinToString(" ") else genresLabel(item),
                formatDuration(item.runtimeMs),
            )
            Text(meta.joinToString("  ·  "), fontSize = 22.sp, color = Z.textPrimary.copy(alpha = 0.78f), maxLines = 1)
            heroBadges(full?.mediaSources?.firstOrNull()).forEach { Badge(it, fontSize = 16) }
        }
        val overview = (full ?: item).overview
        if (!overview.isNullOrBlank()) {
            Text(overview, fontSize = 24.sp, lineHeight = 37.sp, color = Z.textPrimary.copy(alpha = 0.75f), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(760.dp))
        }
        Row(
            Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val resumeMs = resumeMsOf(item)
            GlassButton(
                if (resumeMs > 0) "继续播放 · ${formatClock(resumeMs)}" else "播放",
                onPlay,
                Modifier.focusRequester(focus).stepOn(Key.DirectionLeft, pages) { onStep(-1) },
                glyph = Glyph.Play,
            )
            val versions = full?.mediaSources?.size ?: 0
            GlassButton(if (versions > 1) "$versions 个版本" else "详情", onDetails)
            GlassIconButton(
                if (favorite) Glyph.Check else Glyph.Plus,
                label = if (favorite) "已收藏" else "加入收藏",
                onClick = { scope.launch { app.favorites.toggle(ref, listOf(ref)) } },
            )
            val watchEntries by app.watchlist.entries.collectAsState()
            val inWatchlist = remember(watchEntries, ref.key) { app.watchlist.contains(ref) }
            GlassIconButton(
                Glyph.Bookmark,
                label = if (inWatchlist) "已在待看" else "加入待看",
                on = inWatchlist,
                modifier = Modifier.stepOn(Key.DirectionRight, pages) { onStep(1) },
                onClick = { app.toggleWatchlist(scope, ref) },
            )
        }
        if (pages > 1) {
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(pages) { i ->
                    Box(Modifier.height(8.dp).width(if (i == page) 34.dp else 8.dp).clip(CircleShape).background(if (i == page) Z.focusFill else Z.textPrimary.copy(alpha = 0.4f)))
                }
            }
        }
    }
}

/**
 * The hero's carousel keys (focus rules): [key] on an edge button turns the page instead of moving
 * focus, as long as there is more than one page. Held keys repeat, one title per repeat.
 */
private fun Modifier.stepOn(key: Key, pages: Int, step: () -> Unit): Modifier = onPreviewKeyEvent { e ->
    if (pages > 1 && e.type == KeyEventType.KeyDown && e.key == key) { step(); true } else false
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
        title = if (isEpisode) item.seriesName else item.name,
        topStart = library.status(ref.server)?.name,
        progress = progressFraction(item),
        focusKey = focusKey,
    ) {
        val caption = listOfNotNull(if (isEpisode) episodeCode(item) else null, remainingLabel(item)).joinToString(" · ")
        Text(caption, fontSize = 19.sp, color = Z.textSecondary, maxLines = 1)
    }
}
