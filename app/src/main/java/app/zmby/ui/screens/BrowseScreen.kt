package app.zmby.ui.screens

import app.zmby.media.titleOf
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import app.zmby.data.ServerStatus
import app.zmby.emby.BaseItemDto
import app.zmby.ui.components.GlassSurface
import app.zmby.ui.components.GlassToggle
import app.zmby.ui.components.HealthDot
import app.zmby.ui.components.Icon
import app.zmby.ui.components.liftShadow
import app.zmby.ui.components.onFill
import app.zmby.ui.components.panelSurface
import app.zmby.data.BrowseQuery
import app.zmby.data.BrowseSort
import app.zmby.data.LetterRail
import app.zmby.data.WatchFilter
import app.zmby.ui.Nav
import app.zmby.ui.Route
import app.zmby.ui.components.Focusable
import app.zmby.ui.components.GlassButton
import app.zmby.ui.components.Glyph
import app.zmby.ui.components.Health
import app.zmby.ui.components.PosterCard2x3
import app.zmby.ui.components.Segment
import app.zmby.ui.components.SegmentedWell
import app.zmby.ui.components.SelectableRow
import app.zmby.ui.components.glass
import app.zmby.ui.components.groupCard
import app.zmby.ui.components.onMenuKey
import app.zmby.ui.open
import app.zmby.ui.rememberResumeTick
import app.zmby.ui.screenModel
import app.zmby.ui.theme.Z
import app.zmby.zmby
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val COLUMNS = 6
private val POSTER = 240.dp
/** Browse.dc.html: 6 × 240 posters with 52 between them, from x 80; the scrollbar sits at x 1830. */
private val GRID_WIDTH = 80.dp + POSTER * COLUMNS + 52.dp * (COLUMNS - 1) + 30.dp

/**
 * 电影 / 剧集 (ui-spec §2.8, Browse · BrowseSeries): the page name with the library's count and the
 * search pill; a two-row toolbar (server and library segments, then sort, direction, 筛选 with its
 * count, the condition chips, 清除 and 「符合 n 部」); a six-column poster grid with a slot for every
 * item; a scrollbar with 「第 n 部 · 共 m 部」, replaced by the letter rail when sorted by title; fast
 * jumps (fast-forward / rewind, or holding up / down).
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
internal fun BrowseScreen(nav: Nav, types: String, target: BrowseTarget?, search: FocusRequester, entry: FocusRequester, onTargetUsed: () -> Unit) {
    val app = LocalContext.current.zmby
    val vm = screenModel("browse:$types") { BrowseViewModel(app, types) }
    val st by vm.state.collectAsStateWithLifecycle()
    val pages by vm.pages.collectAsStateWithLifecycle()
    val servers by app.registry.servers.collectAsStateWithLifecycle()
    val enabled = servers.filter { it.enabled }
    val tick = rememberResumeTick()
    LaunchedEffect(tick) { if (tick > 0) vm.refresh() }
    LaunchedEffect(target) {
        if (target != null) {
            vm.select(target.server, target.libraryId)
            onTargetUsed()
        }
    }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var panel by remember { mutableStateOf<String?>(null) }
    // Closing a panel puts focus back on the button that opened it.
    val sortButton = remember { FocusRequester() }
    val filterButton = remember { FocusRequester() }
    val moreButton = remember { FocusRequester() }
    val firstRow = remember { FocusRequester() }
    fun closePanel() {
        val back = when (panel) {
            "filter" -> filterButton
            "servers" -> moreButton
            else -> sortButton
        }
        panel = null
        scope.launch { back.requestWhenReady() }
    }
    // The slot the remote is on, and where a jump wants focus to land.
    var focused by remember { mutableIntStateOf(0) }
    var focusTarget by remember { mutableStateOf<Int?>(null) }
    var lastMove by remember { mutableLongStateOf(0L) }
    var held by remember { mutableStateOf(false) }

    LaunchedEffect(st.generation) {
        if (grid.firstVisibleItemIndex > 0) grid.scrollToItem(0)
        focused = 0
    }
    // Pages for the slots in view (and the next page), not every slot passed while scrolling fast.
    LaunchedEffect(grid, st.generation) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.let { v -> (v.firstOrNull()?.index ?: 0) to (v.lastOrNull()?.index ?: 0) } }
            .distinctUntilChanged()
            .debounce(120)
            .collect { (a, b) -> vm.ensure(a, b) }
    }
    LaunchedEffect(grid) { snapshotFlow { grid.firstVisibleItemIndex }.collect { lastMove = System.currentTimeMillis() } }

    fun jump(delta: Int) {
        if (st.total == 0) return
        val visibleRows = (grid.layoutInfo.visibleItemsInfo.size / COLUMNS).coerceAtLeast(1)
        val to = (focused + delta * visibleRows * COLUMNS).coerceIn(0, st.total - 1)
        scope.launch {
            grid.scrollToItem((to / COLUMNS) * COLUMNS)
            focusTarget = to
        }
    }

    fun jumpTo(index: Int) = scope.launch {
        grid.scrollToItem((index / COLUMNS) * COLUMNS)
        focusTarget = index
    }

    val focusedRef = pages[focused / BrowseViewModel.PAGE]?.getOrNull(focused % BrowseViewModel.PAGE)
    Column(Modifier.fillMaxSize()) {
        PageHeader(if (types == "Series") "剧集" else "电影", st.libraryTotal?.let { "$it 部" }, search, down = if (enabled.size > 1 || st.libraries.size > 1) firstRow else sortButton) { nav.go(Route.Search) }
        Toolbar(
            st = st,
            servers = enabled,
            onServer = { id -> vm.select(id, null) },
            onMore = { panel = "servers" },
            onLibrary = { i -> vm.select(st.server, st.libraries.getOrNull(i - 1)?.id) },
            onQuery = vm::setQuery,
            onPanel = { panel = it },
            sortButton = sortButton,
            filterButton = filterButton,
            moreButton = moreButton,
            filterOpen = panel == "filter",
            search = search,
            firstRow = firstRow,
            entry = entry.takeIf { st.total == 0 },
        )
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(GRID_WIDTH).fillMaxHeight()) {
                if (st.loaded && st.total == 0) {
                    Text(if (st.query.filtered) "没有符合筛选的内容" else "没有内容", fontSize = 26.sp, color = Z.textSecondary, modifier = Modifier.padding(start = 80.dp, top = 40.dp))
                }
                st.error?.let { message ->
                    // Offline and never browsed here: the reason and a retry, not an empty grid.
                    Row(Modifier.padding(start = 80.dp, top = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Text(message, fontSize = 24.sp, color = Z.textSecondary, maxLines = 2, modifier = Modifier.widthIn(max = 1100.dp))
                        GlassButton("重试", vm::retry, glyph = Glyph.Refresh, height = 64.dp)
                    }
                }
                val dim by animateFloatAsState(if (panel == "filter") 0.45f else 1f, tween(200), label = "gridDim")
                LazyVerticalGrid(
                    columns = GridCells.Fixed(COLUMNS),
                    state = grid,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = dim }
                        .onPreviewKeyEvent { e ->
                            when {
                                e.type == KeyEventType.KeyUp && (e.key == Key.DirectionDown || e.key == Key.DirectionUp) -> { held = false; false }
                                e.type != KeyEventType.KeyDown -> false
                                e.key == Key.MediaFastForward -> { jump(1); true }
                                e.key == Key.MediaRewind -> { jump(-1); true }
                                // Holding up / down: one screen per hold, not a fly-through.
                                (e.key == Key.DirectionDown || e.key == Key.DirectionUp) && e.nativeKeyEvent.repeatCount > 0 -> {
                                    if (!held) { held = true; jump(if (e.key == Key.DirectionDown) 1 else -1) }
                                    true
                                }
                                else -> false
                            }
                        },
                    contentPadding = PaddingValues(start = 80.dp, end = 30.dp, top = 40.dp, bottom = 80.dp),
                    horizontalArrangement = Arrangement.spacedBy(52.dp),
                    verticalArrangement = Arrangement.spacedBy(40.dp),
                ) {
                    items(st.total, key = { "${st.generation}:$it" }, contentType = { "poster" }) { i ->
                        val ref = pages[i / BrowseViewModel.PAGE]?.getOrNull(i % BrowseViewModel.PAGE)
                        val requester = remember { FocusRequester() }
                        if (focusTarget == i) {
                            LaunchedEffect(i) {
                                awaitFrame()
                                runCatching { requester.requestFocus() }
                                focusTarget = null
                            }
                        }
                        Box(Modifier.focusRequester(requester).then(if (i == 0) Modifier.focusRequester(entry) else Modifier)) {
                            val item = ref?.item
                            PosterCard2x3(
                                imageUrl = ref?.let { app.library.primaryImage(it) },
                                title = item?.let(::titleOf).orEmpty(),
                                onClick = { if (ref != null) nav.open(ref) },
                                width = POSTER,
                                topEnd = item?.let(::unwatchedBadge),
                                subtitle = item?.let(::browseCaption) ?: " ",
                                focusKey = ref?.let { "grid:${it.key}" } ?: "slot:$i",
                                onFocus = { focused = i; lastMove = System.currentTimeMillis() },
                                modifier = Modifier.onMenuKey { if (ref != null) app.toggleWatchlist(scope, ref) },
                            )
                        }
                    }
                }
            }
            Box(Modifier.fillMaxHeight().weight(1f)) {
                if (st.query.sort == BrowseSort.Title && st.total > 0) {
                    LetterRailColumn(current = focusedRef?.item?.let(::letterOf), onLetter = { letter -> scope.launch { vm.indexOf(letter)?.let { jumpTo(it) } } })
                } else {
                    val viewport by remember(grid) {
                        derivedStateOf { grid.firstVisibleItemIndex to grid.layoutInfo.visibleItemsInfo.size }
                    }
                    Scrollbar(position = focused, first = viewport.first, visible = viewport.second, total = st.total, lastMove = lastMove)
                }
            }
        }
    }
    when (panel) {
        "sort" -> SortPanel(st.query, onPick = { vm.setQuery(st.query.sortedBy(it)); closePanel() }, onDismiss = ::closePanel)
        "filter" -> FilterPanel(st.query, st.genres, st.total, onChange = vm::setQuery, onDismiss = ::closePanel)
        "servers" -> ServerPanel(enabled, st.server, onPick = { id -> vm.select(id, null); closePanel() }, onDismiss = ::closePanel)
    }
}

/** 「2024 · 8.1」 for a film, 「2014 · 6 季」 for a series. */
internal fun browseCaption(item: BaseItemDto): String = listOfNotNull(
    item.productionYear?.toString(),
    if (item.type == "Series") item.childCount?.takeIf { it > 0 }?.let { "$it 季" } else item.communityRating?.takeIf { it > 0 }?.let { "%.1f".format(java.util.Locale.ROOT, it) },
).joinToString(" · ").ifEmpty { " " }

/** A series poster's corner: 「5 集未看」 or 「全部看完」 (BrowseSeries.dc.html); nothing for films. */
internal fun unwatchedBadge(item: BaseItemDto): String? {
    if (item.type != "Series") return null
    val data = item.userData ?: return null
    val unplayed = data.unplayedItemCount
    return when {
        unplayed != null && unplayed > 0 -> "$unplayed 集未看"
        data.played || unplayed == 0 -> "全部看完"
        else -> null
    }
}

/** The rail letter a title files under: its sort name's first letter, a Chinese title's pinyin initial, else #. */
internal fun letterOf(item: BaseItemDto): String {
    val name = (item.sortName ?: item.name).orEmpty().trim()
    val c = name.firstOrNull() ?: return "#"
    if (c.uppercaseChar() in 'A'..'Z') return c.uppercaseChar().toString()
    if (app.zmby.search.Pinyin.isHan(c)) {
        val initial = app.zmby.search.Pinyin.initials(c.toString()).firstOrNull()?.uppercaseChar()
        if (initial != null && initial in 'A'..'Z') return initial.toString()
    }
    return "#"
}

/** Servers shown as segments; the rest go behind 「更多 n 台」 (the chosen one always shows). */
internal fun visibleServers(servers: List<ServerStatus>, chosen: String?, max: Int = 3): List<ServerStatus> {
    if (servers.size <= max + 1) return servers
    val first = servers.take(max)
    val pick = servers.firstOrNull { it.id == chosen }
    return if (pick == null || pick in first) first else first.dropLast(1) + pick
}

private val Pill = RoundedCornerShape(percent = 50)

@Composable
private fun Toolbar(
    st: BrowseState,
    servers: List<ServerStatus>,
    onServer: (String) -> Unit,
    onMore: () -> Unit,
    onLibrary: (Int) -> Unit,
    onQuery: (BrowseQuery) -> Unit,
    onPanel: (String) -> Unit,
    sortButton: FocusRequester,
    filterButton: FocusRequester,
    moreButton: FocusRequester,
    filterOpen: Boolean,
    search: FocusRequester,
    firstRow: FocusRequester,
    entry: FocusRequester?,
) {
    Column(Modifier.fillMaxWidth().padding(start = 80.dp, end = 80.dp, top = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.upToSearch(search).focusRequester(firstRow).focusGroup(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (servers.size > 1) {
                val shown = visibleServers(servers, st.server)
                val hidden = servers.size - shown.size
                // OK confirms: passing over a segment on the way to the sort button must not reload the grid.
                SegmentedWell(
                    shown.map { Segment(it.name, dim = it.health == Health.Off, health = it.health) } + listOfNotNull(if (hidden > 0) Segment("更多 $hidden 台") else null),
                    shown.indexOfFirst { it.id == st.server }.coerceAtLeast(0),
                    { i -> if (i < shown.size) onServer(shown[i].id) else onMore() },
                    fontSize = 19, horizontalPadding = 22.dp, labelPrefix = "服务器", segHeight = 50.dp, selectOnFocus = false,
                    modifier = Modifier.focusRequester(moreButton),
                )
            }
            if (st.libraries.size > 1) {
                SegmentedWell(
                    items = listOf(Segment("全部")) + st.libraries.map { Segment(it.name) },
                    selected = st.libraries.indexOfFirst { it.id == st.libraryId } + 1,
                    onSelect = onLibrary,
                    selectOnFocus = false,
                    fontSize = 19,
                    horizontalPadding = 22.dp,
                    labelPrefix = "库",
                    segHeight = 50.dp,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ToolbarPill(
                "排序 · ${st.query.sort.label}",
                { onPanel("sort") },
                Modifier.focusRequester(sortButton).then(if (entry != null) Modifier.focusRequester(entry) else Modifier),
                trailing = { Icon(Glyph.Down, size = 20.dp, strokeWidth = 2.4f) },
            )
            SegmentedWell(
                items = listOf(Segment("降序"), Segment("升序")),
                selected = if (st.query.descending) 0 else 1,
                onSelect = { onQuery(st.query.copy(descending = it == 0)) },
                selectOnFocus = false,
                fontSize = 19,
                horizontalPadding = 22.dp,
                labelPrefix = "顺序",
                segHeight = 50.dp,
            )
            val count = st.query.chips().size
            ToolbarPill("筛选", { onPanel("filter") }, Modifier.focusRequester(filterButton), on = filterOpen, label = "筛选", trailing = {
                if (count > 0) CountBadge(count, inverted = filterOpen)
                if (filterOpen) Icon(Glyph.Up, size = 20.dp, strokeWidth = 2.4f)
            })
            // Each chip removes its filter; 清除 removes them all.
            st.query.chips().forEach { (label, without) -> ConditionChip(label, onClick = { onQuery(without) }) }
            if (st.query.filtered) ConditionChip("清除", plain = true, onClick = { onQuery(st.query.cleared()) })
            Spacer(Modifier.weight(1f))
            if (st.sortUnsupported) Text("这台服务器不支持该排序", fontSize = 19.sp, color = Z.warn, maxLines = 1)
            if (st.loaded) Text("符合 ${st.total} 部", fontSize = 19.sp, color = Z.textSecondary, maxLines = 1)
        }
    }
}

/** `.lg.pill` in the toolbar: 64 high, 21/600, with an optional trailing glyph or count. */
@Composable
private fun ToolbarPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, on: Boolean = false, label: String = text, trailing: @Composable () -> Unit = {}) {
    GlassSurface(onClick, modifier, label = label, on = on) {
        Row(Modifier.height(64.dp).padding(horizontal = 26.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            trailing()
        }
    }
}

/** `.cnt`: the white count bubble on 筛选 (dark on the lit button). */
@Composable
private fun CountBadge(n: Int, inverted: Boolean) {
    Box(
        Modifier.height(26.dp).widthIn(min = 26.dp).background(if (inverted) Z.onFocus else Color.White, Pill).padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) { Text("$n", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = if (inverted) Color.White else Z.onFocus) }
}

/** `.chip`: a chosen condition with ✕ (OK removes it), or the plain 清除. */
@Composable
private fun ConditionChip(text: String, plain: Boolean = false, onClick: () -> Unit) {
    Focusable(onClick, shape = Pill, focusedScale = 1.06f, outline = 0.dp, shadow = 0.dp, label = if (plain) text else "移除筛选 $text") { f ->
        Row(
            Modifier.height(48.dp)
                .then(if (f) Modifier.liftShadow(Pill).onFill(Pill) else if (!plain) Modifier.background(Color.White.copy(alpha = 0.10f), Pill) else Modifier)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val fg = if (f) Z.onFocus else if (plain) Z.textPrimary.copy(alpha = 0.75f) else Z.textPrimary
            Text(text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = fg)
            if (!plain) Icon(Glyph.Close, size = 16.dp, color = fg, strokeWidth = 2.4f)
        }
    }
}

/**
 * The thin track at x 1830: the thumb's length and place follow the grid against the server's total;
 * 「第 n 部 · 共 m 部」 floats in a small glass pill left of the thumb while moving and fades a second after.
 */
@Composable
private fun Scrollbar(position: Int, first: Int, visible: Int, total: Int, lastMove: Long) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(lastMove) {
        if (lastMove == 0L) return@LaunchedEffect
        shown = true
        delay(1_000)
        shown = false
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(300), label = "position")
    BoxWithConstraints(Modifier.padding(start = 20.dp, top = 40.dp).width(6.dp).height(720.dp)) {
        if (total <= 0) return@BoxWithConstraints
        val trackHeight = maxHeight
        val fraction = (visible.toFloat() / total).coerceIn(0.06f, 1f)
        val offset = (first.toFloat() / total).coerceIn(0f, 1f - fraction)
        Box(Modifier.fillMaxSize().clip(Pill).background(Z.textPrimary.copy(alpha = 0.12f)))
        Box(Modifier.offset(y = trackHeight * offset).fillMaxWidth().height(trackHeight * fraction).clip(Pill).background(Z.textPrimary))
        if (alpha > 0f) {
            // Left of the track, over the grid's last column: measured without the track's narrow width.
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = (-18).dp, y = trackHeight * offset)
                    .wrapContentWidth(Alignment.End, unbounded = true).alpha(alpha),
            ) {
                Text(
                    "第 ${position + 1} 部 · 共 $total 部",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.glass(Pill, shadow = false).padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** 字母轨 (# A–Z, x 1832) in place of the scrollbar when sorted by title; the focused title's letter is a white pill. */
@Composable
private fun LetterRailColumn(current: String?, onLetter: (String) -> Unit) {
    Column(
        Modifier.padding(start = 22.dp, top = 24.dp).width(40.dp).focusGroup(),
        verticalArrangement = Arrangement.spacedBy(1.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LetterRail.LETTERS.forEach { letter ->
            Focusable({ onLetter(letter) }, shape = Pill, focusedScale = 1.3f, outline = 0.dp, shadow = 0.dp, label = "字母 $letter", focusKey = "letter:$letter") { f ->
                val lit = f || letter == current
                Box(Modifier.size(30.dp, 25.dp).then(if (lit) Modifier.background(Z.focusFill, Pill) else Modifier), contentAlignment = Alignment.Center) {
                    Text(letter, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (lit) Z.onFocus else Z.textTertiary)
                }
            }
        }
    }
}

/** 排序: the sort fields as a single-choice list. */
@Composable
private fun SortPanel(query: BrowseQuery, onPick: (BrowseSort) -> Unit, onDismiss: () -> Unit) {
    OverlayPanel(onDismiss, width = 460.dp) { first ->
        BrowseSort.entries.forEach { s ->
            SelectableRow(
                onClick = { onPick(s) },
                modifier = if (s == query.sort) Modifier.focusRequester(first) else Modifier,
                selected = s == query.sort,
                radius = 20.dp,
                label = "排序 ${s.label}",
            ) { Text(s.label, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)) }
        }
    }
}

/** 更多 n 台: every enabled server, in priority order, with its health. */
@Composable
private fun ServerPanel(servers: List<ServerStatus>, current: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    OverlayPanel(onDismiss, width = 600.dp) { first ->
        servers.forEach { s ->
            SelectableRow(
                onClick = { onPick(s.id) },
                modifier = if (s.id == current) Modifier.focusRequester(first) else Modifier,
                selected = s.id == current,
                dimmed = s.health == Health.Off,
                radius = 20.dp,
                label = "服务器 ${s.name}",
            ) {
                HealthDot(s.health)
                Text(s.name, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(latencyLabel(s), fontSize = 18.sp, color = app.zmby.ui.components.secondaryText())
            }
        }
    }
}

/**
 * 筛选 (BrowseSeries.dc.html): a 820-wide panel under the button over the dimmed grid: 观看状态,
 * 只看收藏 and 4K switches, 年代 and 类型 chips (chosen = bright with an outline), then 清除 and
 * 「完成 · 符合 n 部」, which follows every change. Back is 完成.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterPanel(query: BrowseQuery, genres: List<String>, total: Int, onChange: (BrowseQuery) -> Unit, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    BlockSideNav()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestWhenReady() }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .padding(start = 520.dp, top = 290.dp)
                .width(820.dp)
                .heightIn(max = 760.dp)
                .panelSurface(RoundedCornerShape(36.dp))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 34.dp, vertical = 30.dp)
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            @Composable
            fun Title(text: String) = Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Z.textTertiary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Title("观看状态") }
                SegmentedWell(
                    items = WatchFilter.entries.map { Segment(it.label) },
                    selected = query.watched.ordinal,
                    onSelect = { onChange(query.copy(watched = WatchFilter.entries[it])) },
                    selectOnFocus = false,
                    fontSize = 19,
                    labelPrefix = "观看状态",
                    modifier = Modifier.focusRequester(first),
                )
            }
            SwitchRow("只看收藏", query.favorite) { onChange(query.copy(favorite = !query.favorite)) }
            SwitchRow("4K", query.uhd) { onChange(query.copy(uhd = !query.uhd)) }
            Title("年代")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BrowseQuery.DECADES.forEach { d ->
                    FilterChip("$d 年代", d in query.decades) { onChange(query.copy(decades = if (d in query.decades) query.decades - d else query.decades + d)) }
                }
            }
            if (genres.isNotEmpty()) {
                Title("类型")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    genres.forEach { g -> FilterChip(g, g in query.genres) { onChange(query.copy(genres = if (g in query.genres) query.genres - g else query.genres + g)) } }
                }
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ToolbarPill("清除", { onChange(query.cleared()) })
                ToolbarPill("完成 · 符合 $total 部", onDismiss, label = "完成")
            }
        }
    }
}

/** `.trow`: a 60-high row with its label and a switch; OK toggles it. */
@Composable
private fun SwitchRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    SelectableRow(onToggle, showCheck = false, radius = 18.dp, padding = PaddingValues(horizontal = 14.dp, vertical = 7.dp), label = "$label ${if (checked) "开" else "关"}") {
        Text(label, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        GlassToggle(checked)
    }
}

/** `.chip` in the filter panel: chosen = 22 % white with an outline; focus = white and 1.06. */
@Composable
private fun FilterChip(label: String, on: Boolean, onToggle: () -> Unit) {
    Focusable(onToggle, shape = Pill, focusedScale = 1.06f, outline = 0.dp, shadow = 0.dp, label = "$label ${if (on) "已选" else "未选"}") { f ->
        Box(
            Modifier.height(50.dp)
                .then(
                    when {
                        f -> Modifier.liftShadow(Pill).onFill(Pill)
                        on -> Modifier.background(Color.White.copy(alpha = 0.22f), Pill).border(1.5.dp, Color.White.copy(alpha = 0.6f), Pill)
                        else -> Modifier.background(Color.White.copy(alpha = 0.08f), Pill)
                    },
                )
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center,
        ) { Text(label, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = if (f) Z.onFocus else Z.textPrimary) }
    }
}

/** A card over a dimmed screen; focus stays inside, Back closes it. */
@Composable
internal fun OverlayPanel(onDismiss: () -> Unit, width: androidx.compose.ui.unit.Dp, content: @Composable (first: FocusRequester) -> Unit) {
    BackHandler(onBack = onDismiss)
    BlockSideNav()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { awaitFrame(); runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Z.bg.copy(alpha = 0.78f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(width).groupCard(RoundedCornerShape(28.dp)).padding(20.dp)
                .verticalScroll(rememberScrollState())
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) { content(first) }
    }
}
