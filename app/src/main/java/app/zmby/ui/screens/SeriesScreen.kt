package app.zmby.ui.screens

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.zmby.aggregate.*
import app.zmby.catalog.*
import app.zmby.data.*
import app.zmby.emby.BaseItemDto
import app.zmby.media.*
import app.zmby.playback.TrackChoice
import app.zmby.ui.*
import app.zmby.ui.components.*
import app.zmby.ui.theme.Z
import app.zmby.zmby
import kotlinx.coroutines.*

private val SERIES_SCRIMS = Scrims(listOf(0f to 0.96f, 0.4f to 0.75f, 0.75f to 0.15f), bottomFrom = 300f / 1080f, bottomSolid = 0.55f)

/** A coordinate belongs to the work, so replacing metadata never replaces the focused row key. */
internal fun episodeSlot(season: Int?, number: Int?, fallback: String): String =
    if (season != null && number != null) "s$season:e$number" else "extra:$fallback"

private data class EpisodeRow(val key: String, val item: BaseItemDto, val image: String?, val canonical: CatalogEpisode? = null, val resources: List<ServerItem> = emptyList())

/** The original series layout is shared by server entries, provider search and cached metadata. */
@Composable
fun SeriesScreen(nav: Nav, server: String, seriesId: String, episodeId: String? = null, provider: CatalogId? = null) {
    val context = LocalContext.current
    val app = context.zmby
    val tick = rememberResumeTick()
    val vm = screenModel("series:$server:$seriesId") { DetailViewModel(app, server, seriesId, provider) }
    val languages by app.settings.metadataLanguage.flow.collectAsState()
    val posters by app.settings.posterLanguage.flow.collectAsState()
    val logos by app.settings.logoLanguage.flow.collectAsState()
    val token by app.settings.tmdbToken.flow.collectAsState()
    LaunchedEffect(tick, languages, posters, logos, token) { vm.refresh(replace = true) }
    val cached by vm.detail.collectAsState()
    val providerWork by vm.catalog.collectAsState()
    val canonical = providerWork?.takeIf { vm.catalogLanguageKey == listOf(languages,posters,logos,token) }
    val lookup by vm.lookup.collectAsState()
    val fallbackReady by vm.fallbackReady.collectAsState()
    val error by vm.error.collectAsState()
    val resourceWork = canonical ?: lookup
    val fallback = cached?.ref
    val s = fallback?.item ?: canonical?.item()
    var seasonNumber by rememberSaveable { mutableIntStateOf(-1) }
    var currentKey by rememberSaveable { mutableStateOf<String?>(null) }
    var anchorApplied by rememberSaveable { mutableStateOf(false) }
    var serverSeasons by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
    var serverEpisodes by remember { mutableStateOf<List<BaseItemDto>>(emptyList()) }
    var anchor by remember { mutableStateOf<BaseItemDto?>(null) }
    var resourceRetry by remember { mutableIntStateOf(0) }
    var episodeError by remember { mutableStateOf<String?>(null) }
    // No full server metadata is needed for an externally identified series. These requests only
    // obtain resource directories and progress, independently of the provider request.
    LaunchedEffect(server, seriesId, tick, resourceWork?.ref, resourceRetry) {
        if (server == "catalog") return@LaunchedEffect
        anchor = episodeId?.let { app.store.detail(server, it)?.item }
        if (seasonNumber < 0) anchor?.parentIndexNumber?.let { seasonNumber = it }
        if (resourceWork == null) try {
            serverSeasons = app.library.seasons(server, seriesId)
            if (seasonNumber < 0) seasonNumber = serverSeasons.firstOrNull { (it.indexNumber ?: 0) > 0 }?.indexNumber ?: 0
            if (anchor == null) anchor = app.library.nextUp(server, seriesId)
            if (!anchorApplied && currentKey == null) anchor?.let { seasonNumber = it.parentIndexNumber ?: seasonNumber }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }
    val seasons = canonical?.seasons ?: serverSeasons.map { CatalogSeason(it.indexNumber ?: 0, it.name.orEmpty(), it.childCount ?: 0) }
    LaunchedEffect(seasons) {
        if (seasonNumber < 0 && seasons.isNotEmpty()) seasonNumber = seasons.firstOrNull { it.number > 0 }?.number ?: seasons.first().number
    }
    LaunchedEffect(server, seasonNumber, serverSeasons, canonical?.ref, resourceRetry, tick) {
        if (server == "catalog" || canonical != null) return@LaunchedEffect
        val season = serverSeasons.firstOrNull { it.indexNumber == seasonNumber } ?: return@LaunchedEffect
        try { serverEpisodes = app.library.episodes(server, seriesId, season.id); episodeError = null }
        catch (e: CancellationException) { throw e } catch (_: Exception) { episodeError = "剧集目录暂时无法加载" }
    }
    val page by produceState<Pair<Int,List<CatalogEpisode>>?>(null, canonical, seasonNumber, resourceRetry) {
        val work = canonical ?: return@produceState
        value = null
        if (seasonNumber >= 0) withContext(Dispatchers.IO) {
            try { value = seasonNumber to app.catalog.episodes(work, seasonNumber); episodeError = null }
            catch (e: CancellationException) { throw e } catch (_: Exception) { episodeError = "剧集目录暂时无法加载" }
        }
    }
    val servers by app.registry.servers.collectAsState()
    val sourceRows by app.sources.rows.collectAsState()
    val targets = remember(servers, sourceRows, server) { (app.sources.servers(SourceFeature.Aggregate) + listOfNotNull(server.takeUnless { it == "catalog" })).distinct() }
    var batches by remember { mutableStateOf<List<ResourceBatch>>(emptyList()) }
    var pending by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(resourceWork?.ref, resourceWork?.ids, targets, tick, resourceRetry) {
        val work = resourceWork ?: return@LaunchedEffect
        batches = emptyList(); pending = targets.size; failed = emptyList()
        CatalogResources(app.library, app.fanOut).series(targets, work, fallback).collect { result ->
            when (result) {
                is Outcome.Ok -> batches = batches.filter { it.server != result.server } + result.value
                else -> failed = failed + result.server
            }
            pending--
        }
    }
    val catalogEpisodes = page?.takeIf { it.first == seasonNumber }?.second
    val mapped by produceState(EpisodeResources(emptyMap(), emptyList()), canonical, catalogEpisodes, batches) {
        val work = canonical ?: return@produceState
        value = withContext(Dispatchers.Default) { ResourceMapping.episodes(work, catalogEpisodes.orEmpty(), batches) }
    }
    val episodes by produceState<List<EpisodeRow>>(emptyList(), canonical, catalogEpisodes, serverEpisodes, seasonNumber, mapped) {
      value = withContext(Dispatchers.Default) {
        val work = canonical
        val counts=serverEpisodes.groupingBy { it.parentIndexNumber to it.indexNumber }.eachCount()
        if (work != null && catalogEpisodes != null) catalogEpisodes.map { ep ->
            val key = episodeSlot(ep.season, ep.number, ep.id)
            val resources = mapped.matched[ep.id].orEmpty()
            val progress = resources.firstOrNull()?.item?.userData
            EpisodeRow(key, work.item(ep).copy(id = key, overview = ep.overview, userData = progress), ep.image, ep, resources)
        } else serverEpisodes.filter { it.parentIndexNumber == seasonNumber }.map { ep ->
            val unique = counts[ep.parentIndexNumber to ep.indexNumber] == 1
            val key = episodeSlot(ep.parentIndexNumber.takeIf { unique }, ep.indexNumber, ep.id)
            EpisodeRow(key, ep, app.library.landscapeImage(ServerItem(server, ep)), resources = listOf(ServerItem(server, ep)))
        }
    }
    }
    LaunchedEffect(episodes, anchor?.id, batches) {
        if (anchorApplied || currentKey != null) return@LaunchedEffect
        val opened = anchor ?: batches.flatMap { it.episodes }.firstOrNull { it.server == server && it.item.id == episodeId }?.item
        if (opened != null) {
            anchorApplied = true
            if (opened.parentIndexNumber != null && opened.parentIndexNumber != seasonNumber) seasonNumber = opened.parentIndexNumber
            currentKey = episodes.firstOrNull { row -> row.resources.any { it.server == server && it.item.id == opened.id } }?.key
                ?: episodeSlot(opened.parentIndexNumber, opened.indexNumber, opened.id)
        }
    }
    val shown = episodes.firstOrNull { it.key == currentKey } ?: episodes.firstOrNull()
    var settled by remember(shown?.key) { mutableStateOf(false) }
    LaunchedEffect(shown?.key) { delay(600); settled = true }
    val versions = when {
        canonical != null -> rememberCatalogVersions(canonical!!, shown?.canonical, if (settled) shown?.resources.orEmpty() else emptyList(), tick + resourceRetry, fallback)
        shown != null && s != null -> rememberVersions(server, shown.item, s, lookUp = settled)
        else -> VersionsState(emptyList(), 0, null)
    }
    var picked by remember(shown?.key) { mutableStateOf<Version?>(null) }
    val selected = picked?.takeIf { p -> versions.all.any { it.ref.key == p.ref.key && it.source.id == p.source.id } } ?: versions.default
    var preview by remember(shown?.key) { mutableStateOf<Version?>(null) }
    var picks by rememberRetained("picks", seriesId) { mutableStateOf<Map<String, TrackChoice>>(emptyMap()) }
    fun trackKey(v: Version) = "${v.server.id}:${v.source.id}"
    fun tracks(v: Version) = picks[trackKey(v)] ?: TrackChoice()
    val scroll = rememberScrollState()
    val rowState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val currentCard = remember { FocusRequester() }
    val playButton = remember { FocusRequester() }
    var headerFocused by remember { mutableStateOf(false) }
    val trackEntry = remember { FocusRequester() }
    var marking by remember { mutableStateOf<ServerItem?>(null) }
    var extrasExpanded by rememberSaveable { mutableStateOf(false) }
    if (s == null || canonical == null && !fallbackReady) {
        LoadState(error, onRetry = { vm.refresh(replace = true); resourceRetry++ }, onServers = { nav.go(Route.Servers) }); return
    }
    val ref = fallback ?: ServerItem("catalog", s)
    val display = canonical?.display() ?: app.display(ref, null)
    val availability = when {
        pending > 0 || versions.searching > 0 -> "正在查找资源…"
        failed.isNotEmpty() || versions.unresponsive.isNotEmpty() -> "资源状态待确认 · 重试"
        else -> "暂无资源"
    }
    LaunchedEffect(tick,episodes,headerFocused) {
        if(tick > 0 && headerFocused && shown?.item?.userData?.played == true) {
            val index=episodes.indexOfFirst { it.key == shown.key }
            episodes.drop(index+1).firstOrNull { it.item.userData?.played != true }?.let { currentKey=it.key }
        }
    }
    var rowSeason by rememberSaveable { mutableIntStateOf(-1) }
    LaunchedEffect(seasonNumber) {
        if(rowSeason >= 0 && rowSeason != seasonNumber) rowState.scrollToItem(0)
        rowSeason = seasonNumber
    }
    LaunchedEffect(currentKey, episodes.isNotEmpty()) {
        val index = episodes.indexOfFirst { it.key == currentKey }
        if (index > 0 && rowState.firstVisibleItemIndex == 0) rowState.scrollToItem((index - 1).coerceAtLeast(0))
    }
    BackdropProvider(display.backdrop, SERIES_SCRIMS) {
      Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
            BackdropLayer(Modifier.fillMaxWidth().height(1080.dp))
            Column(Modifier.fillMaxWidth()) {
                SeriesHeader(ref, display, seasons.size, episodes.size, shown?.item, selected,
                    onPlay = { _, v, start -> v?.let { play(context, it.ref, start, it.source.id, tracks(it), versions.alternatesTo(it)) } },
                    currentCard = currentCard.takeIf { shown != null }, playButton = playButton,
                    initialFocusKey = seriesId, onFocusChanged = { headerFocused = it; if (it && scroll.value > 0) scope.launch { scroll.scrollToTopFirmly() } },
                    onMarks = { selected?.let { marking = it.ref } }, availability = availability,
                    onRetry = { versions.retry(); failed.forEach(app.registry::retry); resourceRetry++ })
                Column(Modifier.padding(top = 56.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
                    if (seasons.size > 1) SegmentedWell(seasons.map { Segment(it.title) }, seasons.indexOfFirst { it.number == seasonNumber }.coerceAtLeast(0),
                        { anchorApplied = true; seasonNumber = seasons[it].number; currentKey = null }, Modifier.padding(start = 80.dp), labelPrefix = "季")
                    episodeError?.let { Text(it, color = Z.warn, modifier = Modifier.padding(horizontal = 80.dp)); GlassButton("重试目录", { resourceRetry++ }, Modifier.padding(start = 80.dp)) }
                    LazyRow(modifier = Modifier.horizontalRow(up = playButton.takeIf { seasons.size <= 1 }, enter = currentCard.takeIf { shown != null }), state = rowState,
                        contentPadding = PaddingValues(start = 80.dp, end = 120.dp, bottom = 30.dp), horizontalArrangement = Arrangement.spacedBy(30.dp)) {
                        items(episodes, key = { it.key }, contentType = { "episode" }) { row ->
                            MediaCard16x9(row.image, onClick = {
                                    currentKey = row.key
                                    if(row.key == shown?.key && selected != null) play(context,selected.ref,selected.item.resumeMs,selected.source.id,tracks(selected),versions.alternatesTo(selected))
                                    else row.resources.firstOrNull()?.let { play(context,it,it.item.resumeMs) }
                                }, width = 340.dp,
                                topEnd = if (row.resources.isNotEmpty()) "${row.resources.map { it.server }.distinct().size} 台有资源" else if (pending > 0) "查询中" else if (failed.isNotEmpty()) "待确认" else "暂无资源",
                                watched = row.item.userData?.played == true, progress = progressFraction(row.item), onFocus = { currentKey = row.key },
                                focusKey = "episode:${row.key}", modifier = if (row.key == shown?.key) Modifier.focusRequester(currentCard) else Modifier) {
                                Column { Text("第 ${row.item.indexNumber ?: "?"} 集", fontSize = 18.sp); Text(titleOf(row.item), fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                    }
                }
                shown?.let { row ->
                    Column(Modifier.padding(start = 80.dp, top = 26.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("第 ${row.item.indexNumber ?: "?"} 集 · ${titleOf(row.item)}", fontSize = 36.sp, fontWeight = FontWeight.ExtraBold)
                        row.item.overview?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 21.sp, color = Z.textSecondary, maxLines = 3, modifier = Modifier.width(1100.dp)) }
                    }
                }
                if (versions.all.isNotEmpty() || versions.searching > 0 || versions.unresponsive.isNotEmpty()) {
                    Spacer(Modifier.height(48.dp))
                    VersionSection(versions, selected, { picked = it }, { preview = it }, { play(context, it.ref, it.item.resumeMs, it.source.id, tracks(it), versions.alternatesTo(it)) }, downTarget = trackEntry)
                }
                (preview ?: selected)?.let { v -> Spacer(Modifier.height(56.dp)); TrackSection(v, v.item.chapters.size, v.item.runTimeTicks, tracks(v), trackEntry) { picks = picks + (trackKey(v) to it) } }
                val unknown = mapped.unmatched.filter { r -> r.item.parentIndexNumber == seasonNumber || seasons.none { it.number == r.item.parentIndexNumber } }
                if (unknown.isNotEmpty()) {
                    GlassButton("未匹配条目（${unknown.size}） · ${if (extrasExpanded) "收起" else "展开"}", { extrasExpanded = !extrasExpanded }, Modifier.padding(80.dp, 30.dp))
                    if (extrasExpanded) LazyRow(contentPadding = PaddingValues(horizontal = 80.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        items(unknown, key = { it.key }, contentType = { "unmapped" }) { r ->
                            MediaCard16x9(app.library.landscapeImage(r), { nav.go(Route.Detail(r.server, r.item.id, resourceOnly = true)) }, width = 340.dp, topEnd = app.registry.get(r.server)?.name) { Text(titleOf(r.item), maxLines = 2) }
                        }
                    }
                }
                if (canonical != null) CanonicalCast(nav, canonical!!.cast) else ServerCast(nav, ref)
                CatalogRelated(nav, canonical, fallback)
                Spacer(Modifier.height(80.dp))
            }
        }
        marking?.let { SegmentMarksPanel(it.server, it.item, onDismiss = { marking = null }) }
      }
    }
}

@Composable
private fun SeriesHeader(
    ref: ServerItem,
    display: DetailDisplay,
    seasonCount: Int,
    episodeCount: Int,
    /** The episode the main button plays: the focused (or last focused) one, else the next to watch. */
    current: BaseItemDto?,
    /** Its selected version; null until the version list knows it (then the server's own copy plays). */
    selected: Version?,
    onPlay: (episode: BaseItemDto, version: Version?, startMs: Long) -> Unit,
    currentCard: FocusRequester?,
    playButton: FocusRequester,
    initialFocusKey: String?,
    onFocusChanged: (Boolean) -> Unit,
    onMarks: (BaseItemDto) -> Unit = {},
    availability: String? = null,
    onRetry: () -> Unit = {},
) {
    val context = LocalContext.current
    val app = context.zmby
    val library = app.library
    val scope = rememberCoroutineScope()
    val s = ref.item
    val favorites by app.favorites.items.collectAsState()
    val favorite = remember(favorites, ref.key) { app.favorites.isFavorite(ref) }
    Column(
        Modifier
            .padding(start = 80.dp, top = 140.dp)
            .width(860.dp)
            .onFocusChanged { onFocusChanged(it.hasFocus) }
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        TitleLogo(display.logo, display.title, maxWidth = 520.dp, maxHeight = 150.dp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val counts = listOfNotNull(
                if (seasonCount > 0) "$seasonCount 季" else null,
                if (episodeCount > 0 && seasonCount == 1) "$episodeCount 集" else null,
            ).joinToString(" · ").ifEmpty { null }
            val meta = listOfNotNull(display.year?.toString(), display.genres.take(2).joinToString(" · ").ifEmpty { null }, counts).joinToString("  ·  ")
            Text(meta, fontSize = 22.sp, color = Z.textPrimary.copy(alpha = 0.78f), maxLines = 1)
            display.rating?.let { r -> Badge("${if (display.ratingTmdb) "TMDB" else "评分"} %.1f".format(r), fill = true) }
        }
        if (!display.overview.isNullOrBlank()) Text(display.overview, fontSize = 23.sp, lineHeight = 37.sp, color = Z.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            val playItem = selected?.item
            val resumeMs = playItem?.let { resumeMsOf(it) } ?: 0L
            val label = if(current != null && playItem != null) seriesPlayLabel(episodeCode(current),resumeMs,remainingLabel(playItem)) else availability ?: "暂无资源"
            GlassButton(label, { if(current != null && selected != null) onPlay(current,selected,resumeMs) else onRetry() },
                Modifier.focusRequester(playButton).focusRequester(rememberInitialFocus(initialFocusKey ?: s.id))
                    .focusProperties { if(currentCard != null) down = currentCard }, glyph = Glyph.Play, label = "seriesPlay:$initialFocusKey")
            if(current != null && selected != null && resumeMs > 0) GlassButton("从头播放", { onPlay(current,selected,0) })
            if (ref.server != "catalog") GlassIconButton(
                if (favorite) Glyph.Check else Glyph.Plus,
                label = if (favorite) "已收藏" else "加入收藏",
                modifier = Modifier,
                onClick = { scope.launch { app.favorites.toggle(ref, listOf(ref)) } },
            )
            // 待看 next to 收藏 (favorites spec); white when the work is on the list.
            val watchEntries by app.watchlist.entries.collectAsState()
            val inWatchlist = remember(watchEntries, ref.key) { app.watchlist.contains(ref) }
            if (ref.server != "catalog") GlassIconButton(Glyph.Bookmark, label = if (inWatchlist) "已在待看" else "加入待看", on = inWatchlist, onClick = {
                app.toggleWatchlist(scope, ref, emptyList())
            })
            // 本地手动标记 of the episode the main button plays (or its whole season).
            if (current != null && selected != null) GlassIconButton(Glyph.Marks, label = "片头片尾", onClick = { onMarks(current) })
        }
    }
}

/**
 * The episode the series page is on: the one focus last rested on (looked up in the freshly loaded
 * list, so progress is current), else the anchor (the episode the page was opened for, or the next
 * to watch), else the season's first episode. Null while the season has no episodes.
 */
internal fun currentEpisode(episodes: List<BaseItemDto>, currentId: String?, anchor: BaseItemDto?): BaseItemDto? =
    episodes.firstOrNull { it.id == currentId }
        ?: episodes.firstOrNull { it.id == anchor?.id }
        ?: episodes.firstOrNull()

/** 「继续 S1 E7 · 剩余 12 分钟」 when there is a resume point, else 「播放 S1 E8」. */
internal fun seriesPlayLabel(code: String?, resumeMs: Long, remaining: String?): String =
    if (resumeMs > 0) {
        listOfNotNull(listOfNotNull("继续", code).joinToString(" "), remaining).joinToString(" · ")
    } else {
        listOfNotNull("播放", code).joinToString(" ")
    }
