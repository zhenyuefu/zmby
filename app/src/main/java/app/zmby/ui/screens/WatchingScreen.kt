package app.zmby.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import app.zmby.aggregate.EpisodeBar
import app.zmby.aggregate.WatchCard
import app.zmby.aggregate.WatchState
import app.zmby.aggregate.WatchingMerge
import app.zmby.media.formatDuration
import app.zmby.media.workTitleOf
import app.zmby.playback.WatchThreshold
import app.zmby.playback.resolveRememberedPlayback
import app.zmby.ui.LocalNavEntry
import app.zmby.ui.Nav
import app.zmby.ui.Route
import app.zmby.ui.components.ActionFeedbackHost
import app.zmby.ui.components.Artwork
import app.zmby.ui.components.Focusable
import app.zmby.ui.components.GlassButton
import app.zmby.ui.components.Glyph
import app.zmby.ui.components.Health
import app.zmby.ui.components.Icon
import app.zmby.ui.components.LocalActionFeedback
import app.zmby.ui.components.MediaCard16x9
import app.zmby.ui.components.ProgressBar
import app.zmby.ui.components.Segment
import app.zmby.ui.components.SegmentedWell
import app.zmby.ui.components.SelectableRow
import app.zmby.ui.open
import app.zmby.ui.play
import app.zmby.ui.screenModel
import app.zmby.ui.theme.Z
import app.zmby.zmby
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.launch

/** 在看's type filter (顶栏分段). */
private enum class WatchingFilter(val label: String) { All("全部"), Series("剧集"), Movies("电影") }

/**
 * 在看 (ui-spec §2.18, Watching.dc.html): every work being watched, two large cards a row, newest
 * activity first and 等待更新 last; the same list as the home row. OK resumes a remembered choice
 * or opens details; Menu offers 打开详情, 标记为已观看 and 从在看中移除.
 */
@Composable
internal fun WatchingPage(nav: Nav, search: FocusRequester, entry: FocusRequester, onHome: () -> Unit) {
    val context = LocalContext.current
    val app = context.zmby
    val vm = screenModel("home") { HomeViewModel(app) }
    val openWatching = rememberWatchingPlayback(nav)
    val cards by vm.watching.collectAsStateWithLifecycle()
    var filterIndex by rememberSaveable { mutableIntStateOf(0) }
    val filter = WatchingFilter.entries[filterIndex]
    val shown = remember(cards, filter) {
        when (filter) {
            WatchingFilter.All -> cards
            WatchingFilter.Series -> cards.filter { it.series }
            WatchingFilter.Movies -> cards.filter { !it.series }
        }
    }
    var menu by remember { mutableStateOf<WatchCard?>(null) }
    // Focus goes back to the card when the menu closes; once a removed card is gone, to its neighbour.
    val memory = LocalNavEntry.current?.focus
    val neighbor = remember { FocusRequester() }
    var removed by remember { mutableStateOf<Pair<String, Int>?>(null) }
    LaunchedEffect(shown) {
        val (key, index) = removed ?: return@LaunchedEffect
        if (shown.any { it.key == key }) return@LaunchedEffect
        // The neighbour keeps its requester until it has focus.
        if (shown.isEmpty() && cards.isEmpty()) entry.requestWhenReady() else neighbor.requestWhenReady()
        removed = null
    }
    fun closeMenu() {
        menu = null
        memory?.beginRestore()
    }
    val now = remember(cards) { System.currentTimeMillis() }
    ActionFeedbackHost(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 80.dp, end = 80.dp, top = 44.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Text("在看", fontSize = 48.sp, fontWeight = FontWeight.ExtraBold)
                if (cards.isNotEmpty()) Text("${cards.size} 部", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Z.textTertiary)
                if (cards.isNotEmpty()) {
                    SegmentedWell(
                        items = listOf(
                            Segment(WatchingFilter.All.label),
                            Segment("${WatchingFilter.Series.label} ${cards.count { it.series }}"),
                            Segment("${WatchingFilter.Movies.label} ${cards.count { !it.series }}"),
                        ),
                        selected = filterIndex,
                        onSelect = { filterIndex = it },
                        fontSize = 20,
                        labelPrefix = "在看类型",
                        modifier = Modifier.padding(start = 12.dp).upToSearch(search),
                    )
                }
                Spacer(Modifier.weight(1f))
                SearchPill({ nav.go(Route.Search) }, Modifier.focusRequester(search))
            }
            if (cards.isEmpty()) {
                Column(Modifier.padding(start = 80.dp, top = 120.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                    Text("还没有在看的内容", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    GlassButton("去首页看看", onHome, Modifier.focusRequester(entry).upToSearch(search))
                }
                return@Column
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 80.dp, end = 80.dp, top = 36.dp, bottom = 80.dp),
                horizontalArrangement = Arrangement.spacedBy(36.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                itemsIndexed(shown, key = { _, c -> c.key }, contentType = { _, c -> if (c.series) "series" else "movie" }) { i, card ->
                    WatchingCard(
                        card, now,
                        onClick = { openWatching(card) },
                        modifier = Modifier
                            .then(if (i == 0) Modifier.focusRequester(entry) else Modifier)
                            .then(if (removed != null && i == minOf(removed!!.second, shown.lastIndex)) Modifier.focusRequester(neighbor) else Modifier)
                            .then(if (i < 2) Modifier.upToSearch(search) else Modifier)
                            .watchingCardInput(onPlay = { openWatching(card) }, onMenu = { menu = card }),
                    )
                }
                if (shown.isEmpty()) item(key = "none", span = { GridItemSpan(maxLineSpan) }, contentType = "empty") {
                    Text("这一类没有在看的内容", fontSize = 24.sp, color = Z.textSecondary)
                }
            }
        }
        WatchingMenuHost(menu, nav, vm, onClose = ::closeMenu, onRemove = { card ->
            removed = card.key to shown.indexOfFirst { it.key == card.key }.coerceAtLeast(0)
        })
    }
}

/** Shared by the home row and the full page, including their result capsules. */
@Composable
internal fun WatchingMenuHost(card: WatchCard?, nav: Nav, vm: HomeViewModel, onClose: () -> Unit, onRemove: (WatchCard) -> Unit) {
    if (card == null) return
    val app = LocalContext.current.zmby
    val feedback = LocalActionFeedback.current
    fun markWatched() {
        vm.markWatched(card) { error ->
            if (error != null) feedback?.failure(error) { markWatched() }
            else feedback?.success(watchedNotice(
                card.target?.item?.indexNumber.takeIf { card.target?.item?.type == "Episode" },
                played = true,
                server = card.target?.server?.let { app.registry.get(it)?.name },
            ))
        }
    }
    WatchingMenu(
        card,
        onDismiss = onClose,
        onDetail = { onClose(); nav.open(card.target ?: card.shown) },
        onWatched = { onClose(); markWatched() },
        onRemove = {
            onClose()
            onRemove(card)
            vm.remove(card) { error ->
                if (error != null) feedback?.failure(error)
                else feedback?.success("已从在看中移除")
            }
        },
    )
}

/** Menu on a 在看 card: 打开详情, 标记为已观看 (only when there is an episode or film to mark) and 从在看中移除. */
@Composable
private fun WatchingMenu(card: WatchCard, onDismiss: () -> Unit, onDetail: () -> Unit, onWatched: () -> Unit, onRemove: () -> Unit) {
    OverlayPanel(onDismiss, 560.dp) { first ->
        Text(workTitleOf(card.shown.item), fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 10.dp))
        @Composable
        fun Option(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) =
            SelectableRow(onClick, modifier, showCheck = false, radius = 18.dp, padding = PaddingValues(horizontal = 18.dp, vertical = 0.dp), label = text, focusKey = null) {
                Text(text, fontSize = 23.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.height(64.dp).padding(top = 17.dp))
            }
        Option("打开详情", Modifier.focusRequester(first), onDetail)
        if (card.target != null) Option("标记为已观看", onClick = onWatched)
        Option("从在看中移除", onClick = onRemove)
    }
}

private val CardShape = RoundedCornerShape(32.dp)

/** Watching.dc.html `.wc`: 236 high; a 360×204 picture with this session's progress, then title, when and where, state and the series bar. */
@Composable
private fun WatchingCard(card: WatchCard, now: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val app = LocalContext.current.zmby
    val item = card.shown.item
    Focusable(onClick, modifier.height(236.dp).fillMaxWidth(), shape = CardShape, focusedScale = 1.02f, shadow = 30.dp,
        label = "在看 ${workTitleOf(item)}", focusKey = "watching:${card.key}", dimmed = card.state == WatchState.Waiting) { focused ->
        Row(
            Modifier.fillMaxSize().background(Color.White.copy(alpha = if (focused) 0.12f else 0.06f), CardShape).padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Box(Modifier.size(360.dp, 204.dp).clip(RoundedCornerShape(20.dp))) {
                Artwork(app.library.landscapeImage(card.shown), Modifier.fillMaxSize())
                Row(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().height(56.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Glyph.Play, size = 22.dp)
                    if (card.state == WatchState.Resume) {
                        ProgressBar(card.progress, Modifier.width(120.dp))
                        Text("${(card.progress * 100).toInt()}%", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(top = 6.dp)) {
                Text(workTitleOf(item), fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(whereLine(card, now, app.registry.get(card.server)?.name), fontSize = 20.sp, color = Z.textSecondary, maxLines = 1, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.weight(1f))
                Text(stateLine(card), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                card.bar?.let { SeriesBar(it, Modifier.padding(top = 14.dp)) }
            }
        }
    }
}

/** 剧集整体进度: a segment per episode (white seen, the current one part-filled, 18 % unseen), or one bar past 30. */
@Composable
private fun SeriesBar(bar: EpisodeBar, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        val track = Color.White.copy(alpha = 0.18f)
        if (bar.segments != null) {
            Row(Modifier.weight(1f).height(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                bar.segments.forEach { fill ->
                    Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(track)) {
                        if (fill > 0f) Box(Modifier.fillMaxHeight().fillMaxWidth(fill).background(Color.White))
                    }
                }
            }
        } else {
            ProgressBar(bar.fraction, Modifier.weight(1f), height = 8.dp, track = track)
        }
        val season = bar.season?.let { "第 $it 季 " }.orEmpty()
        Text("${season}已看 ${bar.watched} 集 · 共 ${bar.total} 集", fontSize = 18.sp, color = Z.textSecondary, maxLines = 1)
    }
}

/** The home row's card: 16:9 with the server (and 「n 台」) on the picture and the state underneath. */
@Composable
internal fun WatchingRowCard(card: WatchCard, onClick: () -> Unit, onMenu: () -> Unit, focusKey: String) {
    val app = LocalContext.current.zmby
    MediaCard16x9(
        imageUrl = app.library.landscapeImage(card.shown),
        onClick = onClick,
        modifier = Modifier.watchingCardInput(onClick, onMenu),
        title = workTitleOf(card.shown.item),
        topStart = app.registry.get(card.server)?.name,
        topEnd = if (card.servers.size > 1) "${card.servers.size} 台" else null,
        progress = if (card.state == WatchState.Resume) card.progress else 0f,
        focusKey = focusKey,
        dim = if (card.state == WatchState.Waiting) 0.6f else 1f,
    ) {
        Text(rowLine(card), fontSize = 19.sp, color = Z.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Restores this film/episode's last successfully played source and tracks after checking them.
 * Without an exact usable memory, opens details for the user to choose. Repeated OK is ignored
 * while checking; leaving the screen cancels the lookup before it can launch playback.
 */
@Composable
internal fun rememberWatchingPlayback(nav: Nav): (WatchCard) -> Unit {
    val context = LocalContext.current
    val app = context.zmby
    val scope = rememberCoroutineScope()
    var opening by remember { mutableStateOf(false) }
    return { card ->
        if (!opening) {
            opening = true
            scope.launch {
                try {
                    val candidates = (listOfNotNull(card.target) + card.alternates).distinctBy { it.key }
                    val saved = if (card.target != null) app.playbackMemory.latest(candidates) else null
                    val restored = resolveRememberedPlayback(saved, candidates,
                        usable = { ref -> app.registry.get(ref.server)?.let { it.enabled && it.health != Health.Off } == true },
                        sources = { ref -> app.library.sources(ref.server, ref.item.id) },
                    )
                    if (restored == null) nav.open(card.target ?: card.shown)
                    else {
                        val start = WatchThreshold.startPosition(card.startMs, restored.ref.item.runtimeMs, app.settings.watchedPercent.value)
                        play(context, restored.ref, start, restored.sourceId, restored.tracks)
                    }
                } finally { opening = false }
            }
        }
    }
}

/** 「第 1 季第 7 集」. */
private fun episodeLabel(card: WatchCard): String? = card.slot?.let { "第 ${it.season} 季第 ${it.episode} 集" }

/** 「1 小时前 · 朋友的服 · 2 台」. */
private fun whereLine(card: WatchCard, now: Long, server: String?): String =
    listOfNotNull(card.lastActivity?.let { activityLabel(it, now) }, server, if (card.servers.size > 1) "${card.servers.size} 台" else null).joinToString(" · ")

/** The page's state line: 剩余 for films, the episode and 剩余 for series, 下一集 and 等待更新. */
private fun stateLine(card: WatchCard): String {
    val item = card.shown.item
    return when (card.state) {
        WatchState.Resume -> {
            val left = item.runtimeMs?.let { formatDuration(it - card.startMs) }?.let { "剩余 $it" }
            if (card.series) listOfNotNull(episodeLabel(card), left).joinToString(" · ")
            else listOfNotNull(left, formatDuration(item.runtimeMs)?.let { "共 $it" }).joinToString(" · ").ifEmpty { "继续" }
        }
        WatchState.Next -> listOfNotNull("下一集", episodeLabel(card), "未开始").joinToString(" · ")
        WatchState.Waiting -> waitingLine(card)
    }
}

/** The home card's line: 「继续 · 剩余 18 分钟」「下一集 · 未开始」「等待更新 · 第 8 集 11 月 7 日」. */
private fun rowLine(card: WatchCard): String = when (card.state) {
    WatchState.Resume -> listOfNotNull("继续", card.slot?.let { "S${it.season} E${it.episode}" },
        card.shown.item.runtimeMs?.let { formatDuration(it - card.startMs) }?.let { "剩余 $it" }).joinToString(" · ")
    WatchState.Next -> listOfNotNull("下一集", card.slot?.let { "S${it.season} E${it.episode}" }, "未开始").joinToString(" · ")
    WatchState.Waiting -> waitingLine(card)
}

private fun waitingLine(card: WatchCard): String {
    val date = card.airDate?.let(WatchingMerge::time)?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
        ?.let { "${it.monthValue} 月 ${it.dayOfMonth} 日" }
    val episode = card.slot?.let { "第 ${it.episode} 集" }
    return listOfNotNull("等待更新", listOfNotNull(episode, date).joinToString(" ").ifEmpty { null }).joinToString(" · ")
}

/** 「刚刚」「n 分钟前」「n 小时前」「昨天」「10 月 28 日」 (no slashes, ui-spec §5). */
internal fun activityLabel(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val minutes = (now - at) / 60_000
    val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        day == today -> "${minutes / 60} 小时前"
        day == today.minusDays(1) -> "昨天"
        else -> "${day.monthValue} 月 ${day.dayOfMonth} 日"
    }
}
