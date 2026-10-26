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
import app.zmby.data.BrowseQuery
import app.zmby.data.BrowseSort
import app.zmby.data.LetterRail
import app.zmby.data.WatchFilter
import app.zmby.ui.Nav
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

/**
 * 电影 / 剧集 (ui-spec §2.8): a toolbar (server, library, sort with its direction, filters and their
 * chips), a six-column poster grid with a slot for every item, a scrollbar with 「第 n 部 · 共 m 部」,
 * a letter rail when sorted by title, and fast jumps (fast-forward / rewind, or holding up / down).
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
internal fun BrowseScreen(nav: Nav, types: String, target: BrowseTarget?, onTargetUsed: () -> Unit) {
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
    fun closePanel() {
        val back = if (panel == "filter") filterButton else sortButton
        panel = null
        scope.launch { awaitFrame(); runCatching { back.requestFocus() } }
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

    Column(Modifier.fillMaxSize().padding(top = 120.dp)) {
        Toolbar(
            st = st,
            servers = enabled.map { Segment(it.name, dim = it.health == Health.Off) },
            serverIndex = enabled.indexOfFirst { it.id == st.server }.coerceAtLeast(0),
            onServer = { i -> vm.select(enabled[i].id, null) },
            onLibrary = { i -> vm.select(st.server, st.libraries.getOrNull(i - 1)?.id) },
            onQuery = vm::setQuery,
            onPanel = { panel = it },
            sortButton = sortButton,
            filterButton = filterButton,
        )
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
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
                LazyVerticalGrid(
                    columns = GridCells.Fixed(COLUMNS),
                    state = grid,
                    modifier = Modifier
                        .fillMaxSize()
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
                    contentPadding = PaddingValues(start = 80.dp, end = 24.dp, top = 24.dp, bottom = 80.dp),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalArrangement = Arrangement.spacedBy(36.dp),
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
                        Box(Modifier.focusRequester(requester)) {
                            PosterCard2x3(
                                imageUrl = ref?.let { app.library.primaryImage(it) },
                                title = ref?.item?.let(::titleOf).orEmpty(),
                                onClick = { if (ref != null) nav.open(ref) },
                                width = POSTER,
                                subtitle = ref?.item?.productionYear?.toString() ?: " ",
                                focusKey = ref?.let { "grid:${it.key}" } ?: "slot:$i",
                                onFocus = { focused = i; lastMove = System.currentTimeMillis() },
                                modifier = Modifier.onMenuKey { if (ref != null) app.toggleWatchlist(scope, ref) },
                            )
                        }
                    }
                }
            }
            if (st.query.sort == BrowseSort.Title && st.total > 0) {
                LetterRailColumn(onLetter = { letter -> scope.launch { vm.indexOf(letter)?.let { jumpTo(it) } } })
            }
            Scrollbar(position = focused, first = grid.firstVisibleItemIndex, visible = grid.layoutInfo.visibleItemsInfo.size, total = st.total, lastMove = lastMove)
        }
    }
    when (panel) {
        "sort" -> SortPanel(st.query, onPick = { vm.setQuery(st.query.sortedBy(it)); closePanel() }, onDismiss = ::closePanel)
        "filter" -> FilterPanel(st.query, st.genres, onChange = vm::setQuery, onDismiss = ::closePanel)
    }
}

@Composable
private fun Toolbar(
    st: BrowseState,
    servers: List<Segment>,
    serverIndex: Int,
    onServer: (Int) -> Unit,
    onLibrary: (Int) -> Unit,
    onQuery: (BrowseQuery) -> Unit,
    onPanel: (String) -> Unit,
    sortButton: FocusRequester,
    filterButton: FocusRequester,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 80.dp, end = 80.dp, top = 30.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        if (servers.size > 1) {
            // OK confirms: passing over a segment on the way to the sort button must not reload the grid.
            SegmentedWell(servers, serverIndex, onServer, fontSize = 20, horizontalPadding = 22.dp, labelPrefix = "服务器", segHeight = 50.dp, selectOnFocus = false)
        }
        if (st.libraries.size > 1) {
            SegmentedWell(
                items = listOf(Segment("全部")) + st.libraries.map { Segment(it.name) },
                selected = st.libraries.indexOfFirst { it.id == st.libraryId } + 1,
                onSelect = onLibrary,
                selectOnFocus = false,
                fontSize = 20,
                horizontalPadding = 22.dp,
                labelPrefix = "库",
                segHeight = 50.dp,
            )
        }
        GlassButton("${st.query.sort.label} ›", { onPanel("sort") }, Modifier.focusRequester(sortButton), glyph = Glyph.Sort, height = 56.dp, horizontalPadding = 26.dp, fontSize = 20, label = "排序 ${st.query.sort.label}")
        SegmentedWell(
            items = listOf(Segment("降序"), Segment("升序")),
            selected = if (st.query.descending) 0 else 1,
            onSelect = { onQuery(st.query.copy(descending = it == 0)) },
            selectOnFocus = false,
            fontSize = 20,
            horizontalPadding = 20.dp,
            labelPrefix = "顺序",
            segHeight = 50.dp,
        )
        val count = st.query.chips().size
        GlassButton(if (count > 0) "筛选 · $count ›" else "筛选 ›", { onPanel("filter") }, Modifier.focusRequester(filterButton), glyph = Glyph.Filter, height = 56.dp, horizontalPadding = 26.dp, fontSize = 20, label = "筛选")
        // Each chip removes its filter; 清除 removes them all.
        st.query.chips().forEach { (label, without) ->
            GlassButton(label, { onQuery(without) }, height = 48.dp, horizontalPadding = 20.dp, fontSize = 18, label = "移除筛选 $label")
        }
        if (st.query.filtered) GlassButton("清除", { onQuery(st.query.cleared()) }, height = 48.dp, horizontalPadding = 20.dp, fontSize = 18)
        if (st.total > 0) Text("${st.total} 部", fontSize = 20.sp, color = Z.textTertiary)
        if (st.sortUnsupported) Text("这台服务器不支持该排序", fontSize = 20.sp, color = Z.warn)
    }
}

/**
 * The thin glass track on the right: the thumb's length and place follow the grid against the
 * server's total; 「第 n 部 · 共 m 部」 floats by it while moving and fades a second after.
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
    BoxWithConstraints(Modifier.width(56.dp).fillMaxHeight().padding(top = 24.dp, bottom = 80.dp, end = 32.dp)) {
        if (total <= 0) return@BoxWithConstraints
        val trackHeight = maxHeight
        val fraction = (visible.toFloat() / total).coerceIn(0.06f, 1f)
        val offset = (first.toFloat() / total).coerceIn(0f, 1f - fraction)
        Box(Modifier.align(Alignment.TopEnd).width(8.dp).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(Z.textPrimary.copy(alpha = 0.12f)))
        Box(
            Modifier.align(Alignment.TopEnd).offset(y = trackHeight * offset).width(8.dp).height(trackHeight * fraction)
                .clip(RoundedCornerShape(4.dp)).background(Z.textPrimary.copy(alpha = 0.7f)),
        )
        if (alpha > 0f) {
            // Left of the track, over the grid's last column: measured without the track's narrow width.
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = (-22).dp, y = trackHeight * offset)
                    .wrapContentWidth(Alignment.End, unbounded = true).alpha(alpha),
            ) {
                Text(
                    "第 ${position + 1} 部 · 共 $total 部",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.glass(RoundedCornerShape(percent = 50), shadow = false).padding(horizontal = 18.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** 字母轨 (# A–Z) beside the grid when sorted by title; OK jumps to the first title with that letter. */
@Composable
private fun LetterRailColumn(onLetter: (String) -> Unit) {
    Column(
        Modifier.width(44.dp).fillMaxHeight().padding(top = 24.dp, bottom = 60.dp).verticalScroll(rememberScrollState()).focusGroup(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LetterRail.LETTERS.forEach { letter ->
            Focusable({ onLetter(letter) }, shape = RoundedCornerShape(percent = 50), focusedScale = 1.3f, outline = 0.dp, shadow = 0.dp, label = "字母 $letter", focusKey = "letter:$letter") { f ->
                Box(Modifier.size(30.dp).then(if (f) Modifier.background(Z.focusFill, RoundedCornerShape(percent = 50)) else Modifier), contentAlignment = Alignment.Center) {
                    Text(letter, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (f) Z.onFocus else Z.textSecondary)
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

/** 筛选: genres of this library, decades, watch state, favourites and 4K; every change applies at once. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterPanel(query: BrowseQuery, genres: List<String>, onChange: (BrowseQuery) -> Unit, onDismiss: () -> Unit) {
    OverlayPanel(onDismiss, width = 980.dp) { first ->
        @Composable
        fun Title(text: String) = Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Z.textTertiary, modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp))
        Title("观看状态")
        SegmentedWell(
            items = WatchFilter.entries.map { Segment(it.label) },
            selected = query.watched.ordinal,
            onSelect = { onChange(query.copy(watched = WatchFilter.entries[it])) },
            selectOnFocus = false,
            fontSize = 20,
            labelPrefix = "观看状态",
            modifier = Modifier.focusRequester(first),
        )
        Title("收藏与画质")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ToggleChip("只看收藏", query.favorite) { onChange(query.copy(favorite = !query.favorite)) }
            ToggleChip("4K", query.uhd) { onChange(query.copy(uhd = !query.uhd)) }
        }
        Title("年代")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BrowseQuery.DECADES.forEach { d ->
                ToggleChip("$d 年代", d in query.decades) { onChange(query.copy(decades = if (d in query.decades) query.decades - d else query.decades + d)) }
            }
        }
        if (genres.isNotEmpty()) {
            Title("类型")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                genres.forEach { g -> ToggleChip(g, g in query.genres) { onChange(query.copy(genres = if (g in query.genres) query.genres - g else query.genres + g)) } }
            }
        }
    }
}

@Composable
private fun ToggleChip(label: String, on: Boolean, onToggle: () -> Unit) {
    app.zmby.ui.components.GlassSurface(onToggle, on = on, label = "$label ${if (on) "已选" else "未选"}") {
        Row(Modifier.height(52.dp).padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (on) app.zmby.ui.components.Icon(Glyph.Check, size = 20.dp, strokeWidth = 2.6f)
            Text(label, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** A card over a dimmed screen; focus stays inside, Back closes it. */
@Composable
internal fun OverlayPanel(onDismiss: () -> Unit, width: androidx.compose.ui.unit.Dp, content: @Composable (first: FocusRequester) -> Unit) {
    BackHandler(onBack = onDismiss)
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
