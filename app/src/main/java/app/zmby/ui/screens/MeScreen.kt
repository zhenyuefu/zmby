package app.zmby.ui.screens

import app.zmby.TraceRecomposition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import app.zmby.ZmbyApp
import app.zmby.catalog.CatalogId
import app.zmby.data.ServerItem
import app.zmby.data.SourceFeature
import app.zmby.emby.BaseItemDto
import app.zmby.log.ZLog
import app.zmby.stats.AiringEpisode
import app.zmby.stats.AiringRules
import app.zmby.stats.CalendarSeries
import app.zmby.stats.CalendarState
import app.zmby.stats.GenreTrend
import app.zmby.stats.HeatCell
import app.zmby.stats.MeStats
import app.zmby.stats.MeSummary
import app.zmby.stats.MeText
import app.zmby.stats.RecentEntry
import app.zmby.stats.TypeSplit
import app.zmby.stats.serverCount
import app.zmby.ui.Nav
import app.zmby.ui.Route
import app.zmby.ui.components.Focusable
import app.zmby.ui.components.GlassButton
import app.zmby.ui.components.Glyph
import app.zmby.ui.components.MediaCard16x9
import app.zmby.ui.components.groupCard
import app.zmby.ui.open
import app.zmby.ui.rememberInitialFocus
import app.zmby.ui.screenModel
import app.zmby.ui.theme.Z
import app.zmby.zmby
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.LocalDate
import java.time.ZoneId

/* 我的's chart colours (ui-spec §2.17): only on this page. */
private val Amber = Color(0xFFF5A524)
private val Indigo = Color(0xFF7B6BFF)
private val CardShape = RoundedCornerShape(32.dp)
private const val HOUR = 3_600_000L

/** 最近看过 with what its card shows. */
data class RecentCard(val entry: RecentEntry, val ref: ServerItem, val image: String?)

/** 我的's state: the summary and 最近看过 from the local store, the calendar from TMDB (cached), all off the main thread. */
class MeViewModel(private val app: ZmbyApp) : ViewModel() {
    val summary: StateFlow<MeSummary?> = app.me.summary().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val recent: StateFlow<List<RecentCard>> = app.me.recent().map { entries ->
        entries.map { e ->
            val item = runCatching { app.store.detail(e.server, e.itemId)?.item }.getOrNull()
                ?: BaseItemDto(id = e.itemId, name = e.title, type = if (e.episodeType) "Episode" else "Movie")
            val ref = ServerItem(e.server, item)
            RecentCard(e, ref, app.library.landscapeImage(ref))
        }
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val refreshing = app.stats.refreshing
    val failed = app.stats.failed
    val calendar = MutableStateFlow(CalendarState())
    /** Servers holding each aired episode, by [copyKey]; absent while not looked up. */
    val copies = MutableStateFlow<Map<String, List<ServerItem>>>(emptyMap())
    private val lookups = Semaphore(2)
    private val asked = HashSet<String>()

    fun refresh(force: Boolean) {
        viewModelScope.launch { app.stats.refresh(force) }
        viewModelScope.launch { refreshCalendar(force) }
    }

    private suspend fun refreshCalendar(force: Boolean) {
        val stored = app.airing.stored()
        calendar.value = CalendarState(stored?.episodes.orEmpty(), loading = true, failed = stored?.failed ?: 0)
        if (!app.catalog.configured) {
            calendar.value = CalendarState(emptyList(), loading = false, noToken = true)
            return
        }
        if (force) { copies.value = emptyMap(); synchronized(asked) { asked.clear() } }
        calendar.value = try {
            val built = app.airing.refresh(followedSeries(), LocalDate.now())
            // Every series failed and nothing was stored: say so and offer 重试 instead of an empty week.
            if (built.failed > 0 && built.episodes.isEmpty()) CalendarState(emptyList(), loading = false, failed = -1)
            else CalendarState(built.episodes, loading = false, failed = built.failed, fetched = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ZLog.w(TAG, "calendar refresh failed: ${e.message}")
            CalendarState(stored?.episodes.orEmpty(), loading = false, failed = -1)
        }
    }

    /** Series in 收藏, 待看 and 在看, each with a server copy when known. */
    private suspend fun followedSeries(): List<CalendarSeries> {
        val favorites = app.favorites.items.value.filter { it.type == "Series" }.map { f ->
            CalendarSeries(f.workKey, f.title, f.instances.firstOrNull()?.let { ServerItem(it.server, BaseItemDto(id = it.itemId, type = "Series", name = f.title)) })
        }
        val watchlist = app.watchlist.entries.value.filter { it.type == "Series" }.map { w ->
            val first = w.instances.firstOrNull()?.split('|', limit = 2)?.takeIf { it.size == 2 }
            CalendarSeries(w.workKey, w.title, first?.let { ServerItem(it[0], BaseItemDto(id = it[1], type = "Series", name = w.title)) })
        }
        val servers = app.sources.servers(SourceFeature.WatchHistory)
        val order = app.sources.order(SourceFeature.WatchHistory)
        val watching = runCatching { app.home.watching.cards(servers, order).first() }.getOrDefault(emptyList()).filter { it.series }.map { c ->
            val ep = c.shown.item
            CalendarSeries(c.key, ep.seriesName ?: ep.name.orEmpty(), ep.seriesId?.let { ServerItem(c.shown.server, BaseItemDto(id = it, type = "Series", name = ep.seriesName)) })
        }
        return favorites + watchlist + watching
    }

    /** Looks up which servers hold the aired episodes shown (resource lookup, cached for hours). */
    fun lookUp(episodes: List<AiringEpisode>, today: LocalDate) {
        val servers = app.sources.servers(SourceFeature.Aggregate)
        for (ep in episodes) {
            if (!AiringRules.aired(ep, today)) continue
            val key = copyKey(ep)
            if (!synchronized(asked) { asked.add(key) }) continue
            viewModelScope.launch {
                val found = lookups.withPermit {
                    runCatching { app.airing.copies(ep, servers) }.onFailure {
                        if (it is CancellationException) throw it
                        ZLog.w(TAG, "copies of ${ep.seriesKey} ${ep.season}x${ep.episode}: ${it.message}")
                    }.getOrDefault(emptyList())
                }
                copies.update { it + (key to found) }
            }
        }
    }

    companion object {
        private const val TAG = "Me"
        fun copyKey(ep: AiringEpisode) = "${ep.seriesKey}|${ep.catalogEpisodeId}"
    }
}

/**
 * 我的 (ui-spec §2.17, Me.dc.html; replaced 观影统计): 追剧日历, today, this week and the last seven
 * days, 26 weeks of viewing, 收藏 and 待看, this month with its genres, and 最近看过. The time charts
 * come only from sessions on this box (local-data spec, 我的：本机播放会话与统计口径).
 */
@Composable
fun MeScreen(nav: Nav) {
    TraceRecomposition("Me.root")
    val app = LocalContext.current.zmby
    val vm = screenModel("me") { MeViewModel(app) }
    val summary by vm.summary.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val failed by vm.failed.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh(force = false) }
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 80.dp, end = 80.dp, top = 44.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        Header(nav, summary?.since, zone, refreshing, failed) { vm.refresh(force = true) }
        AiringSection(nav, vm, today)
        val s = summary
        if (s == null) {
            Text("正在读取…", fontSize = 24.sp, color = Z.textSecondary)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                TodayCard(s, Modifier.weight(1f))
                WeekCard(s, Modifier.weight(1f))
                Last7Card(s, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                HeatCard(s.heat, Modifier.weight(2f))
                FavoritesCard(nav, Modifier.weight(1f))
                WatchlistCard(nav, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.Top) {
                MonthCard(s, Modifier.weight(1f))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                    GenreCard(s)
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        s.genreTrends.forEach { TrendCard(it, Modifier.weight(1f)) }
                        repeat(2 - s.genreTrends.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        RecentSection(nav, recent, today, zone)
    }
}

@Composable
private fun Header(nav: Nav, since: Long?, zone: ZoneId, refreshing: Set<String>, failed: Set<String>, onRefresh: () -> Unit) {
    val app = LocalContext.current.zmby
    val sourceRows by app.sources.rows.collectAsStateWithLifecycle()
    val servers by app.registry.servers.collectAsStateWithLifecycle()
    val label = remember(sourceRows, servers) {
        val value = app.sources.label(SourceFeature.Stats)
        if (value == "全部") "全部 ${servers.count { it.enabled }} 台" else value
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("我的", fontSize = 48.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp)
        Text(MeText.scope(since, zone), fontSize = 20.sp, color = Z.textTertiary, modifier = Modifier.weight(1f).padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        val status = when {
            refreshing.isNotEmpty() -> "正在更新 ${refreshing.size} 台"
            failed.isNotEmpty() -> "${failed.size} 台未响应"
            else -> null
        }
        if (status != null) Text(status, fontSize = 20.sp, color = if (failed.isNotEmpty() && refreshing.isEmpty()) Z.warn else Z.textSecondary)
        GlassButton("来源 · $label", { nav.go(Route.DataSource(SourceFeature.Stats)) }, height = 56.dp, horizontalPadding = 24.dp, fontSize = 20)
        GlassButton("刷新", onRefresh, glyph = Glyph.Refresh, height = 56.dp, horizontalPadding = 24.dp, fontSize = 20)
    }
}

/** ① 追剧日历: a Monday-first week of date blocks (Left on Monday and Right on Sunday change the week), the day's episodes below. */
@Composable
private fun AiringSection(nav: Nav, vm: MeViewModel, today: LocalDate) {
    val calendar by vm.calendar.collectAsStateWithLifecycle()
    val copies by vm.copies.collectAsStateWithLifecycle()
    var selectedText by rememberSaveable { mutableStateOf(today.toString()) }
    val selected = remember(selectedText) { LocalDate.parse(selectedText) }
    val week = remember(selected) { AiringRules.week(selected) }
    val slots = remember { List(7) { FocusRequester() } }
    val initial = rememberInitialFocus(Unit)
    val shown = remember(calendar.episodes, selected) { AiringRules.on(calendar.episodes, selected) }
    // Keyed on the state too: 刷新 forgets the copies found, and the same episodes must be looked up again.
    LaunchedEffect(shown, calendar) { vm.lookUp(shown, today) }
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Text("追剧日历", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                week.forEachIndexed { i, day ->
                    DayBlock(
                        day, today, selected = day == selected,
                        modifier = Modifier.focusRequester(slots[i]).then(if (day == today) Modifier.focusRequester(initial) else Modifier)
                            .onPreviewKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when {
                                    i == 0 && e.key == Key.DirectionLeft -> { selectedText = day.minusDays(1).toString(); runCatching { slots[6].requestFocus() }; true }
                                    i == 6 && e.key == Key.DirectionRight -> { selectedText = day.plusDays(1).toString(); runCatching { slots[0].requestFocus() }; true }
                                    else -> false
                                }
                            },
                        focusKey = "me:day:$i",
                    ) { selectedText = day.toString() }
                }
            }
        }
        when {
            calendar.noToken -> Text("填写 TMDB 令牌后显示追剧日历（设置 › 影视资料）", fontSize = 22.sp, color = Z.textSecondary)
            calendar.failed == -1 && calendar.episodes.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("播出日期暂时无法获取", fontSize = 22.sp, color = Z.warn)
                GlassButton("重试", { vm.refresh(force = true) }, height = 56.dp, horizontalPadding = 24.dp, fontSize = 20)
            }
            shown.isEmpty() -> Text(if (calendar.loading && calendar.episodes.isEmpty()) "正在读取播出日期…" else "这天没有播出的剧", fontSize = 22.sp, color = Z.textSecondary)
            else -> LazyRow(
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                contentPadding = PaddingValues(end = 40.dp),
                modifier = Modifier.fillMaxWidth().height(330.dp),
            ) {
                items(shown, key = { MeViewModel.copyKey(it) }, contentType = { "airing" }) { ep ->
                    val found = copies[MeViewModel.copyKey(ep)]
                    val aired = AiringRules.aired(ep, today)
                    val count = found?.serverCount() ?: 0
                    val waiting = !aired || (found != null && count == 0)
                    MediaCard16x9(
                        imageUrl = ep.image,
                        onClick = {
                            val copy = found?.firstOrNull { it.item.seriesId != null }
                            if (copy != null) nav.go(Route.Series(copy.server, copy.item.seriesId!!, copy.item.id))
                            else nav.go(Route.Catalog(CatalogId("tmdb", "tv", ep.tmdbId), ep.server, ep.seriesId))
                        },
                        width = 400.dp,
                        topEnd = when {
                            count > 0 -> "$count 台已有"
                            waiting -> "等待上线"
                            else -> null
                        },
                        dim = if (waiting) 0.6f else 1f,
                        focusKey = "me:airing:${MeViewModel.copyKey(ep)}",
                        // Up goes back to the day these episodes belong to, not the block that happens to be above.
                        modifier = Modifier.focusProperties { up = slots[week.indexOf(selected).coerceAtLeast(0)] },
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(ep.seriesTitle, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(AiringRules.line(ep, today), fontSize = 18.sp, color = Z.textSecondary, maxLines = 1)
                        }
                    }
                }
            }
        }
        if (calendar.failed > 0) Text("${calendar.failed} 部剧的播出日期暂时无法更新", fontSize = 18.sp, color = Z.textTertiary)
    }
}

@Composable
private fun DayBlock(day: LocalDate, today: LocalDate, selected: Boolean, modifier: Modifier, focusKey: String, onFocus: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Focusable(onFocus, modifier, shape = shape, focusedScale = 1.06f, outline = 0.dp, shadow = 0.dp, onFocus = onFocus, label = "日期 ${MeText.date(day)}", focusKey = focusKey) { focused ->
        Column(
            Modifier.size(96.dp).clip(shape)
                .background(if (focused) Z.focusFill else if (selected) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                .then(if (selected && !focused) Modifier.border(2.dp, Color.White.copy(alpha = 0.6f), shape) else Modifier),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            val ink = if (focused) Z.onFocus else Z.textPrimary
            Text("${day.dayOfMonth}", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = ink)
            Text(
                if (day == today) "今天" else MeText.weekday(day), fontSize = 17.sp,
                fontWeight = if (day == today) FontWeight.Bold else FontWeight.Normal,
                color = if (focused) Z.onFocusSecondary else if (selected || day == today) Z.textPrimary else Color.White.copy(alpha = 0.65f),
            )
        }
    }
}

/** A grey glass card (radius 32) that takes focus, so the remote can step through the page. */
@Composable
private fun StatCard(modifier: Modifier, label: String, onClick: () -> Unit = {}, padding: PaddingValues = PaddingValues(horizontal = 32.dp, vertical = 30.dp), content: @Composable BoxScope.() -> Unit) {
    Focusable(onClick, modifier, shape = CardShape, focusedScale = 1.02f, outline = 4.dp, shadow = 0.dp, label = label, focusKey = "me:$label") {
        Box(Modifier.fillMaxSize().clip(CardShape).groupCard(CardShape).padding(padding), content = content)
    }
}

@Composable
private fun CardTitle(left: String, right: String? = null, rightColor: Color = Z.textSecondary) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(left, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Z.textSecondary, modifier = Modifier.weight(1f))
        if (right != null) Text(right, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = rightColor)
    }
}

@Composable
private fun BigHours(ms: Long, size: Int = 52) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(MeText.hours(ms), fontSize = size.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp, lineHeight = size.sp)
        Text("小时", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.75f), modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
    }
}

@Composable
private fun DeltaText(current: Long, previous: Long) {
    val d = MeText.delta(current, previous) ?: return
    Text(d.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = if (d.up) Z.ok else Z.textSecondary)
}

private val SubText = Color.White.copy(alpha = 0.8f)
private val Axis = Color.White.copy(alpha = 0.55f)

@Composable
private fun TodayCard(s: MeSummary, modifier: Modifier) {
    StatCard(modifier.height(310.dp), "今天") {
        Column(Modifier.fillMaxSize()) {
            CardTitle("今天", "${MeText.date(s.today.date)} · ${MeText.weekday(s.today.date)}")
            Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                BigHours(s.today.watchedMs)
                Text(MeText.works(s.today.movies, s.today.episodes), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = SubText)
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().height(34.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                s.today.hours.forEach { ms ->
                    val level = when { ms <= 0 -> 0; ms < 20 * 60_000L -> 1; ms < 40 * 60_000L -> 2; else -> 3 }
                    Box(Modifier.weight(1f).height(if (level == 0) 8.dp else (10 + level * 8).dp).clip(CircleShape).background(if (level == 0) Color.White.copy(alpha = 0.14f) else Amber))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Text("0 点", fontSize = 16.sp, color = Axis)
                Spacer(Modifier.weight(1f)); Text("12 点", fontSize = 16.sp, color = Axis); Spacer(Modifier.weight(1f))
                Text("24 点", fontSize = 16.sp, color = Axis)
            }
        }
    }
}

@Composable
private fun WeekCard(s: MeSummary, modifier: Modifier) {
    StatCard(modifier.height(310.dp), "本周") {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("本周", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Z.textSecondary)
                Box(Modifier.padding(top = 8.dp)) { BigHours(s.week.watchedMs) }
                Text("${s.week.movies} 部电影", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = SubText)
                Text("${s.week.episodes} 集剧集", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = SubText)
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom)) {
                StackedBars(s.week.days, maxOf(4 * HOUR, s.week.days.maxOf { it.totalMs }), Modifier.fillMaxWidth().height(190.dp), barWidth = 26.dp, gap = 3.dp, radius = 8.dp, emptyHeight = 8.dp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("一", "二", "三", "四", "五", "六", "日").forEach { Text(it, fontSize = 15.sp, color = Axis, modifier = Modifier.width(26.dp), maxLines = 1) }
                }
            }
        }
    }
}

/** Stacked bars, evenly spread: films (indigo) over series (amber); an empty day is a short grey bar. */
@Composable
private fun StackedBars(days: List<TypeSplit>, scaleMs: Long, modifier: Modifier, barWidth: Dp, gap: Dp, radius: Dp, emptyHeight: Dp, emptyColor: Color = Color.White.copy(alpha = 0.14f)) {
    Canvas(modifier) {
        val bw = barWidth.toPx()
        val g = gap.toPx()
        val r = CornerRadius(radius.toPx(), radius.toPx())
        val step = if (days.size > 1) (size.width - bw) / (days.size - 1) else 0f
        days.forEachIndexed { i, d ->
            val x = i * step
            if (d.totalMs <= 0) {
                val h = emptyHeight.toPx()
                drawRoundRect(emptyColor, Offset(x, size.height - h), Size(bw, h), r)
                return@forEachIndexed
            }
            val series = if (d.episodeMs > 0) (d.episodeMs.toFloat() / scaleMs * size.height).coerceAtLeast(emptyHeight.toPx() / 2) else 0f
            val movies = if (d.movieMs > 0) (d.movieMs.toFloat() / scaleMs * size.height).coerceAtLeast(emptyHeight.toPx() / 2) else 0f
            var bottom = size.height
            if (series > 0f) { drawRoundRect(Amber, Offset(x, bottom - series), Size(bw, series), r); bottom -= series + g }
            if (movies > 0f) drawRoundRect(Indigo, Offset(x, bottom - movies), Size(bw, movies), r)
        }
    }
}

@Composable
private fun Last7Card(s: MeSummary, modifier: Modifier) {
    StatCard(modifier.height(310.dp), "最近 7 天", padding = PaddingValues(0.dp)) {
        AreaTrend(s.last7.points, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(170.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 30.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("最近 7 天", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                Text(MeText.amount(s.last7.watchedMs), fontSize = 20.sp, color = Color.White.copy(alpha = 0.7f))
            }
            DeltaText(s.last7.watchedMs, s.last7.previousMs)
        }
    }
}

/** A smoothed area line through [points] (oldest first), filling the box's lower part. */
@Composable
private fun AreaTrend(points: List<Long>, modifier: Modifier) {
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val max = points.max().coerceAtLeast(1)
        val top = size.height * 0.15f
        val base = size.height - 8.dp.toPx()
        val xs = points.indices.map { it * size.width / (points.size - 1) }
        val ys = points.map { base - (it.toFloat() / max) * (base - top) }
        val line = Path().apply {
            moveTo(xs[0], ys[0])
            for (i in 1 until points.size) {
                val cx = (xs[i - 1] + xs[i]) / 2
                cubicTo(cx, ys[i - 1], cx, ys[i], xs[i], ys[i])
            }
        }
        val area = Path().apply { addPath(line); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
        drawPath(area, Indigo.copy(alpha = 0.28f))
        drawPath(line, Indigo, style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun HeatCard(heat: List<HeatCell>, modifier: Modifier) {
    StatCard(modifier.height(330.dp), "观看记录") {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("观看记录 · 最近 ${MeStats.HEAT_WEEKS} 周", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Z.textSecondary, modifier = Modifier.weight(1f))
                Text("少", fontSize = 16.sp, color = Z.textSecondary)
                (0..3).forEach { Box(Modifier.padding(start = 6.dp).size(14.dp).clip(RoundedCornerShape(5.dp)).background(heatColor(it))) }
                Text("多", fontSize = 16.sp, color = Z.textSecondary, modifier = Modifier.padding(start = 6.dp))
            }
            Canvas(Modifier.fillMaxWidth().height(218.dp)) {
                val cols = MeStats.HEAT_WEEKS
                val gap = 6.dp.toPx()
                val w = (size.width - gap * (cols - 1)) / cols
                val h = (size.height - gap * 6) / 7
                val r = CornerRadius(7.dp.toPx(), 7.dp.toPx())
                heat.forEachIndexed { i, c ->
                    if (c.level < 0) return@forEachIndexed
                    drawRoundRect(heatColor(c.level), Offset((i / 7) * (w + gap), (i % 7) * (h + gap)), Size(w, h), r)
                }
            }
        }
    }
}

private fun heatColor(level: Int): Color = when (level) {
    0 -> Color.White.copy(alpha = 0.08f)
    1 -> Indigo.copy(alpha = 0.4f)
    2 -> Indigo.copy(alpha = 0.7f)
    else -> Indigo
}

@Composable
private fun FavoritesCard(nav: Nav, modifier: Modifier) {
    val app = LocalContext.current.zmby
    val favorites by app.favorites.items.collectAsStateWithLifecycle()
    val posters = remember(favorites) {
        favorites.take(2).mapNotNull { f -> f.instances.firstOrNull()?.let { app.library.primaryImage(ServerItem(it.server, favoriteItem(f, it.itemId))) } }
    }
    StatCard(modifier.height(330.dp), "收藏", onClick = { nav.home(3) }) {
        Column {
            Text("${favorites.size}", fontSize = 52.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp)
            Text("收藏", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.padding(top = 8.dp))
        }
        posters.getOrNull(0)?.let { Poster(it, Modifier.align(Alignment.BottomEnd).offset(x = (-118).dp, y = (-4).dp).rotate(-8f), 120.dp) }
        posters.getOrNull(1)?.let { Poster(it, Modifier.align(Alignment.BottomEnd).offset(x = (-8).dp, y = (-30).dp).rotate(9f), 120.dp) }
    }
}

@Composable
private fun WatchlistCard(nav: Nav, modifier: Modifier) {
    val app = LocalContext.current.zmby
    val entries by app.watchlist.entries.collectAsStateWithLifecycle()
    // The posters other pages show (one size, so one download and one cache entry each), from the
    // configured order: a speed test does not rebuild them.
    val servers by app.registry.lineup.collectAsStateWithLifecycle()
    val posters = remember(entries, servers) { entries.take(3).mapNotNull { e -> watchlistRef(e, servers)?.let { app.library.primaryImage(it) } } }
    StatCard(modifier.height(330.dp), "待看", onClick = { nav.home(4) }) {
        Column {
            Text("${entries.size}", fontSize = 52.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp)
            Text("待看", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.padding(top = 8.dp))
        }
        Row(Modifier.align(Alignment.BottomStart), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            posters.forEachIndexed { i, url -> Poster(url, if (i == 2) Modifier.graphicsLayer { alpha = 0.6f } else Modifier, 100.dp) }
        }
    }
}


@Composable
private fun Poster(url: String, modifier: Modifier, width: Dp) {
    AsyncImage(
        model = url, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        modifier = modifier.size(width, width * 3 / 2).clip(RoundedCornerShape(14.dp)).background(Color(0xFF2E3A4A)),
    )
}

@Composable
private fun MonthCard(s: MeSummary, modifier: Modifier) {
    val m = s.month
    StatCard(modifier.height(640.dp), "本月观看") {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            CardTitle("本月观看", MeText.monthName(m.month.monthValue), Z.textPrimary)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                BigHours(m.watchedMs)
                Box(Modifier.padding(bottom = 6.dp)) { DeltaText(m.watchedMs, m.previousMs) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) { Legend("剧集", Amber); Legend("电影", Indigo) }
            Box(Modifier.fillMaxWidth().height(200.dp).padding(top = 4.dp)) {
                Canvas(Modifier.fillMaxSize().padding(end = 50.dp)) {
                    val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))
                    listOf(0f, size.height / 2).forEach { y -> drawLine(Color.White.copy(alpha = 0.12f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = dash) }
                }
                StackedBars(m.days, m.scaleMs, Modifier.fillMaxSize().padding(end = 50.dp), barWidth = 10.dp, gap = 2.dp, radius = 6.dp, emptyHeight = 4.dp, emptyColor = Color.White.copy(alpha = 0.10f))
                Text(MeText.hoursShort(m.scaleMs), fontSize = 15.sp, color = Axis, modifier = Modifier.align(Alignment.TopEnd).offset(y = (-10).dp))
                Text(MeText.hoursShort(m.scaleMs / 2), fontSize = 15.sp, color = Axis, modifier = Modifier.align(Alignment.CenterEnd).offset(y = (-10).dp))
            }
            Row(Modifier.fillMaxWidth().padding(end = 50.dp)) {
                val month = m.month.monthValue
                Text("$month 月 1 日", fontSize = 15.sp, color = Axis)
                Spacer(Modifier.weight(1f)); Text("$month 月 15 日", fontSize = 15.sp, color = Axis); Spacer(Modifier.weight(1f))
                Text("$month 月 ${m.month.lengthOfMonth()} 日", fontSize = 15.sp, color = Axis)
            }
            Text("看得最多", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.6f), letterSpacing = 1.sp, modifier = Modifier.padding(top = 8.dp))
            if (m.top.isEmpty()) Text("本月还没有在本机看过", fontSize = 20.sp, color = Z.textTertiary)
            val most = m.top.firstOrNull()?.watchedMs?.coerceAtLeast(1) ?: 1
            m.top.forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(t.title, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(240.dp))
                    Box(Modifier.weight(1f).height(14.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth((t.watchedMs.toFloat() / most).coerceIn(0.02f, 1f)).clip(CircleShape).background(if (t.episode) Amber else Indigo))
                    }
                    Text(MeText.hoursShort(t.watchedMs), fontSize = 20.sp, color = Color.White.copy(alpha = 0.75f), modifier = Modifier.width(70.dp), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun Legend(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(14.dp).clip(RoundedCornerShape(5.dp)).background(color))
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = SubText)
    }
}

private val GenreAlphas = listOf(1f, 0.72f, 0.5f, 0.28f)

@Composable
private fun GenreCard(s: MeSummary) {
    val g = s.genres
    StatCard(Modifier.fillMaxWidth().height(330.dp), "类型分布") {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            CardTitle("类型分布 · ${MeText.monthName(s.month.month.monthValue)}")
            Row(Modifier.fillMaxWidth().height(18.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (g.slices.isEmpty()) Box(Modifier.weight(1f).fillMaxHeight().clip(CircleShape).background(Color.White.copy(alpha = 0.08f)))
                g.slices.forEachIndexed { i, slice ->
                    if (slice.fraction > 0f) Box(Modifier.weight(slice.fraction).fillMaxHeight().clip(CircleShape).background(Indigo.copy(alpha = GenreAlphas[i.coerceAtMost(3)])))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.Bottom) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigHours(g.watchedMs, size = 64)
                    Text("${g.movies} 部电影 · ${g.series} 部剧", fontSize = 20.sp, color = Color.White.copy(alpha = 0.7f))
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    g.slices.chunked(2).forEachIndexed { row, pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                            pair.forEachIndexed { j, slice -> Legend("${slice.name} ${(slice.fraction * 100).toInt()}%", Indigo.copy(alpha = GenreAlphas[(row * 2 + j).coerceAtMost(3)])) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrendCard(t: GenreTrend, modifier: Modifier) {
    StatCard(modifier.height(282.dp), "类型趋势 ${t.name}", padding = PaddingValues(0.dp)) {
        AreaTrend(t.trend.points, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(120.dp))
        Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 30.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.name, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                DeltaText(t.trend.watchedMs, t.trend.previousMs)
            }
            Text(MeText.amount(t.trend.watchedMs), fontSize = 20.sp, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** ⑤ 最近看过: newest first, this box's sessions with the servers' history of other clients. */
@Composable
private fun RecentSection(nav: Nav, recent: List<RecentCard>, today: LocalDate, zone: ZoneId) {
    val app = LocalContext.current.zmby
    val servers by app.registry.servers.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Text("最近看过", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        if (recent.isEmpty()) {
            Text("还没有观看记录", fontSize = 22.sp, color = Z.textSecondary)
            return@Column
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(32.dp), contentPadding = PaddingValues(end = 40.dp), modifier = Modifier.fillMaxWidth().height(300.dp)) {
            items(recent, key = { "${it.entry.key}|${it.entry.server}|${it.entry.itemId}" }, contentType = { "recent" }) { card ->
                val e = card.entry
                val line = remember(e, servers) { MeText.recentLine(e, today, zone, servers.firstOrNull { it.id == e.server }?.name) }
                MediaCard16x9(card.image, { nav.open(card.ref) }, width = 340.dp, focusKey = "me:recent:${e.key}") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(e.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(line, fontSize = 17.sp, color = Color.White.copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
