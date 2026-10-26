package app.zmby.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.zmby.catalog.CatalogChart
import app.zmby.data.ServerStatus
import app.zmby.ui.components.GlassSurface
import app.zmby.ui.components.Glyph
import app.zmby.ui.components.Health
import app.zmby.ui.components.HealthDot
import app.zmby.ui.components.Icon
import app.zmby.ui.components.Segment
import app.zmby.ui.components.SegmentedWell
import app.zmby.ui.components.SelectableRow
import app.zmby.ui.components.ZmbyLogo
import app.zmby.ui.components.ZmbyMark
import app.zmby.ui.components.panelSurface
import app.zmby.ui.components.secondaryText
import app.zmby.ui.theme.Z
import app.zmby.zmby
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Navigation chrome of UI v3 (ui-spec §2.1, Main · MainSidebar · MainSource): the search pill in the
 * top bar, the source button with its panel, and the tvOS-like side bar that slides in from the left.
 */

/**
 * Pop-ups inside a home tab (sort, filters, 首页来源) count themselves here while shown: Left at the
 * edge of a pop-up must not open the side bar behind it.
 */
val LocalSideNavBlock = compositionLocalOf<MutableIntState?> { null }

/** Registers the calling pop-up with [LocalSideNavBlock] while it is composed. */
@Composable
fun BlockSideNav() {
    val block = LocalSideNavBlock.current ?: return
    DisposableEffect(block) {
        block.intValue++
        onDispose { block.intValue-- }
    }
}

/** Requests focus once the element is composed and attached (up to [timeoutMs]); true on success. */
suspend fun FocusRequester.requestWhenReady(timeoutMs: Long = 1_500): Boolean =
    withTimeoutOrNull(timeoutMs) {
        var ok = false
        while (!ok) {
            awaitFrame()
            ok = runCatching { requestFocus() }.getOrDefault(false)
        }
        ok
    } ?: false

/** 「搜索影片、剧集、人物」: the search-field-shaped pill (420 wide) that opens the search screen. */
@Composable
fun SearchPill(onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp = 420.dp) {
    GlassSurface(onClick, modifier, label = "搜索") { focused ->
        Row(
            Modifier.width(width).height(64.dp).padding(horizontal = 26.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Glyph.Search, size = 26.dp, strokeWidth = 2.2f)
            Text(
                "搜索影片、剧集、人物",
                fontSize = 21.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (focused) Z.onFocus else Z.textPrimary.copy(alpha = 0.75f),
                maxLines = 1,
            )
        }
    }
}

/** The overall health of the enabled servers: green when all are up, yellow when some are not. */
internal fun overallHealth(enabled: List<ServerStatus>): Health {
    val online = enabled.count { it.health != Health.Off }
    return when {
        online == 0 -> Health.Off
        online < enabled.size || enabled.any { it.health == Health.Warn } -> Health.Warn
        else -> Health.Ok
    }
}

/** 「6 ms」, 「离线」 or 「未测」 for one server's current line. */
internal fun latencyLabel(s: ServerStatus): String = when {
    s.health == Health.Off -> "离线"
    !s.measured -> "未测"
    else -> s.latencyMs?.let { "$it ms" } ?: "离线"
}

/**
 * The source button (ui-spec §2.1): 「全部 · 11 台在线」 or 「家里 NAS · 6 ms」, with the health of
 * everything or of that server; OK opens [SourcePanel].
 */
@Composable
fun SourceButton(servers: List<ServerStatus>, source: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val enabled = servers.filter { it.enabled }
    val chosen = enabled.firstOrNull { it.id == source }
    val label = chosen?.let { "${it.name} · ${latencyLabel(it)}" } ?: "全部 · ${enabled.count { it.health != Health.Off }} 台在线"
    GlassSurface(onClick, modifier, label = "首页来源") {
        Row(
            Modifier.height(64.dp).padding(horizontal = 26.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HealthDot(chosen?.health ?: overallHealth(enabled))
            Text(label, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 360.dp))
            Icon(Glyph.Down, size = 20.dp, strokeWidth = 2.4f)
        }
    }
}

/** Where the hero's titles come from (主推区 segment of the source panel). */
enum class HeroSource(val label: String) {
    /** Emby: 建议, then 推荐, then 最近更新 (ui-spec §2.1 主推区). */
    Server("媒体库推荐"),
    Tmdb("TMDB 榜单");

    companion object {
        fun of(value: String) = entries.firstOrNull { it.name == value } ?: Tmdb
    }
}

private val PanelShape = RoundedCornerShape(40.dp)

@Composable
private fun GroupTitle(text: String) =
    Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Z.textTertiary, modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 8.dp))

@Composable
private fun Divider() = Box(Modifier.padding(horizontal = 20.dp, vertical = 12.dp).fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.10f)))

/**
 * 首页来源 (MainSource.dc.html): 首页内容 (全部 and the servers in priority order, offline ones dimmed
 * but selectable, scrolling past six), 主推区 (媒体库推荐 · TMDB 榜单, with the chart or the TMDB token
 * row), and 服务器管理. Back closes it, or goes back from the chart list.
 */
@Composable
fun SourcePanel(
    servers: List<ServerStatus>,
    source: String,
    onSource: (String) -> Unit,
    onServers: () -> Unit,
    onToken: () -> Unit,
    onDismiss: () -> Unit,
) {
    BlockSideNav()
    val app = LocalContext.current.zmby
    val settings = app.settings
    val heroSource by settings.heroSource.flow.collectAsState()
    val chart by settings.tmdbChart.flow.collectAsState()
    val token by settings.tmdbToken.flow.collectAsState()
    val configured = token.isNotBlank()
    var charts by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler { if (charts) charts = false else onDismiss() }
    val first = remember(charts) { FocusRequester() }
    LaunchedEffect(charts) { first.requestWhenReady() }
    Box(Modifier.fillMaxSize().background(Color(0x8C050608))) {
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(end = 80.dp, top = 126.dp)
                .width(600.dp)
                .panelSurface(PanelShape)
                .padding(horizontal = 20.dp, vertical = 26.dp)
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup(),
        ) {
            if (charts) {
                GroupTitle("TMDB 榜单")
                CatalogChart.entries.forEach { c ->
                    SelectableRow(
                        { settings.tmdbChart.set(c.name); charts = false },
                        if (c.name == chart || (c == CatalogChart.Trending && CatalogChart.entries.none { it.name == chart })) Modifier.focusRequester(first) else Modifier,
                        selected = c == CatalogChart.of(chart),
                        radius = 18.dp,
                        label = "榜单 ${c.label}",
                        focusKey = null,
                    ) { Text(c.label, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)) }
                }
                return@Column
            }
            GroupTitle("首页内容")
            val enabled = servers.filter { it.enabled }
            val options = listOf<ServerStatus?>(null) + enabled
            val current = source.takeIf { s -> enabled.any { it.id == s } }.orEmpty()
            Column(Modifier.heightIn(max = 408.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                options.forEach { s ->
                    val id = s?.id.orEmpty()
                    SelectableRow(
                        { onSource(id) },
                        if (id == current) Modifier.focusRequester(first) else Modifier,
                        selected = id == current,
                        dimmed = s?.health == Health.Off,
                        radius = 18.dp,
                        padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 17.dp),
                        label = "首页来源 ${s?.name ?: "全部"}",
                        focusKey = null,
                    ) {
                        if (s != null) HealthDot(s.health) else Spacer(Modifier.size(12.dp))
                        Text(s?.name ?: "全部", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(if (s == null) "${enabled.size} 台合并" else latencyLabel(s), fontSize = 18.sp, color = secondaryText(), maxLines = 1)
                    }
                }
            }
            Divider()
            GroupTitle("主推区")
            val hero = HeroSource.of(heroSource)
            SegmentedWell(
                items = HeroSource.entries.map { Segment(it.label, dim = it == HeroSource.Tmdb && !configured) },
                selected = hero.ordinal,
                onSelect = { settings.heroSource.set(HeroSource.entries[it].name) },
                selectOnFocus = false,
                equalWidths = true,
                segHeight = 48.dp,
                fontSize = 19,
                labelPrefix = "主推区",
                modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 6.dp).fillMaxWidth(),
            )
            if (hero == HeroSource.Tmdb) {
                if (configured) {
                    PanelLinkRow("榜单", CatalogChart.of(chart).label) { charts = true }
                } else {
                    PanelLinkRow("设置 TMDB 令牌", null, onToken)
                }
            }
            Divider()
            PanelLinkRow("服务器管理", null, onServers)
        }
    }
}

@Composable
private fun PanelLinkRow(title: String, value: String?, onClick: () -> Unit) {
    SelectableRow(onClick, showCheck = false, radius = 18.dp, padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 17.dp), label = title, focusKey = null) {
        Text(title, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (value != null) Text(value, fontSize = 18.sp, color = secondaryText())
        Icon(Glyph.Chevron, size = 22.dp, strokeWidth = 2.4f)
    }
}

/** The places the side bar lists, in its order; [tab] is the home tab it shows, if it is one. */
enum class SideNavItem(val label: String, val glyph: Glyph, val tab: Int? = null) {
    Search("搜索", Glyph.Search),
    Home("首页", Glyph.Home, 0),
    Movies("电影", Glyph.Film, 1),
    Series("剧集", Glyph.Tv, 2),
    Favorites("收藏", Glyph.Heart, 3),
    Watchlist("待看", Glyph.Bookmark, 4),
    Stats("观影统计", Glyph.Chart),
    Servers("服务器", Glyph.Servers),
    Settings("设置", Glyph.Gear),
}

/** The 6×96 hint at the left edge that the side bar is there. */
@Composable
fun SideNavHint(modifier: Modifier = Modifier) {
    Box(modifier.offset(x = 22.dp, y = 492.dp).size(6.dp, 96.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.35f)))
}

/**
 * The side bar (MainSidebar.dc.html): a 440-wide frosted panel 28 from the edges over a darkened
 * page. Opening puts focus on the current page's row; Right closes it (Back is handled by the caller).
 * Rows carry no focus key, so closing the bar gives
 * focus back to what had it before.
 */
@Composable
fun SideNav(
    open: Boolean,
    currentTab: Int,
    onPick: (SideNavItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val app = LocalContext.current.zmby
    // While the bar slides out, focus may leave it: the caller is giving focus back to the page.
    val isOpen by rememberUpdatedState(open)
    val dim by animateFloatAsState(if (open) 1f else 0f, tween(250), label = "sideDim")
    if (dim > 0f) {
        Box(
            Modifier.fillMaxSize().background(
                androidx.compose.ui.graphics.Brush.horizontalGradient(
                    0f to Color(0xFF050608).copy(alpha = 0.85f * dim),
                    0.45f to Color(0xFF050608).copy(alpha = 0.55f * dim),
                    1f to Color(0xFF050608).copy(alpha = 0.35f * dim),
                ),
            ),
        )
    }
    AnimatedVisibility(
        visible = open,
        enter = slideInHorizontally(tween(260)) { -it } + fadeIn(tween(180)),
        exit = slideOutHorizontally(tween(220)) { -it } + fadeOut(tween(180)),
    ) {
        val favorites by app.favorites.items.collectAsState()
        val watchlist by app.watchlist.entries.collectAsState()
        val servers by app.registry.servers.collectAsState()
        val online = servers.count { it.enabled && it.health != Health.Off }
        val current = remember { FocusRequester() }
        LaunchedEffect(Unit) { current.requestWhenReady() }
        Column(
            Modifier
                .padding(28.dp)
                .width(440.dp)
                .fillMaxHeight()
                .panelSurface(PanelShape)
                .padding(horizontal = 24.dp, vertical = 36.dp)
                .onPreviewKeyEvent { e ->
                    if (e.key == Key.DirectionRight) {
                        if (e.type == KeyEventType.KeyDown) onDismiss()
                        true
                    } else false
                }
                .focusProperties { onExit = { if (isOpen) cancelFocusChange() } }
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(Modifier.padding(start = 24.dp, bottom = 26.dp), verticalAlignment = Alignment.CenterVertically) {
                ZmbyLogo(markSize = 40.dp, wordSize = 28)
            }
            SideNavItem.entries.forEach { item ->
                if (item == SideNavItem.Home || item == SideNavItem.Stats) {
                    Box(Modifier.padding(horizontal = 24.dp, vertical = 10.dp).fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.10f)))
                }
                val sub = when (item) {
                    SideNavItem.Favorites -> favorites.size.takeIf { it > 0 }?.toString()
                    SideNavItem.Watchlist -> watchlist.size.takeIf { it > 0 }?.toString()
                    SideNavItem.Servers -> "$online 台在线"
                    else -> null
                }
                SideNavRow(item, selected = item.tab == currentTab, sub = sub, modifier = if (item.tab == currentTab) Modifier.focusRequester(current) else Modifier) { onPick(item) }
            }
        }
    }
}

@Composable
private fun SideNavRow(item: SideNavItem, selected: Boolean, sub: String?, modifier: Modifier, onClick: () -> Unit) {
    SelectableRow(
        onClick,
        modifier,
        selected = selected,
        showCheck = false,
        radius = 22.dp,
        padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp, vertical = 0.dp),
        label = "侧边栏 ${item.label}",
        focusKey = null,
    ) {
        Row(Modifier.height(72.dp).weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Icon(item.glyph, size = 28.dp, strokeWidth = 2f)
            Text(item.label, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (sub != null) Text(sub, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = secondaryText())
        }
    }
}

/**
 * The top bar of 电影, 剧集, 收藏 and 待看 (Browse.dc.html): the page name (48/800) and its count on
 * the left, the search pill on the right; Up from the page's first row lands on [search].
 */
@Composable
fun PageHeader(title: String, count: String?, search: FocusRequester, down: FocusRequester? = null, onSearch: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 80.dp, end = 80.dp, top = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text(title, fontSize = 48.sp, fontWeight = FontWeight.ExtraBold)
        if (count != null) Text(count, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Z.textTertiary)
        Spacer(Modifier.weight(1f))
        SearchPill(onSearch, Modifier.focusRequester(search).then(if (down != null) Modifier.focusProperties { this.down = down } else Modifier))
    }
}

/** Up on this element (a page's first row) goes straight to the search pill. */
fun Modifier.upToSearch(search: FocusRequester?): Modifier = if (search == null) this else onPreviewKeyEvent { e ->
    if (e.key == Key.DirectionUp && e.type == KeyEventType.KeyDown) runCatching { search.requestFocus() }.getOrDefault(false) else false
}
