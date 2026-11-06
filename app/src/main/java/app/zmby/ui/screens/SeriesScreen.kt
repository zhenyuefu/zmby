package app.zmby.ui.screens

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zmby.TraceRecomposition
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.graphicsLayer
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
import app.zmby.ui.theme.detailPageScrollSpec
import app.zmby.ui.theme.defaultScrollSpec
import app.zmby.ui.theme.Mono
import app.zmby.zmby
import app.zmby.ui.components.rememberTrailerPlayer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.android.awaitFrame

/** A coordinate belongs to the work, so replacing metadata never replaces the focused row key. */
internal fun episodeSlot(season: Int?, number: Int?, fallback: String): String =
    if (season != null && number != null) "s$season:e$number" else "extra:$fallback"

internal data class EpisodeRow(val key: String, val item: BaseItemDto, val image: String?, val canonical: CatalogEpisode? = null, val resources: List<ServerItem> = emptyList())

/** The original series layout is shared by server entries, provider search and cached metadata. */
@Composable
fun SeriesScreen(nav: Nav, server: String, seriesId: String, episodeId: String? = null, provider: CatalogId? = null) {
    TraceRecomposition("Series.root")
    val context = LocalContext.current
    val app = context.zmby
    val tick = rememberResumeTick()
    // Metadata and the episode pipeline (directories, resources, identities, rows) live in the
    // screen's ViewModel and are worked out off the main thread (performance plan R2).
    val vm = screenModel("series:$server:$seriesId") { SeriesViewModel(app, server, seriesId, episodeId, provider) }
    val pipeline = vm.pipeline
    val languages by app.settings.metadataLanguage.flow.collectAsStateWithLifecycle()
    val posters by app.settings.posterLanguage.flow.collectAsStateWithLifecycle()
    val logos by app.settings.logoLanguage.flow.collectAsStateWithLifecycle()
    val token by app.settings.tmdbToken.flow.collectAsStateWithLifecycle()
    LaunchedEffect(tick, languages, posters, logos, token) { vm.refresh(replace = true, force = tick > 0) }
    LaunchedEffect(tick) { pipeline.tick.value = tick }
    val cached by vm.detail.collectAsStateWithLifecycle()
    val canonical = vm.canonical.collectAsStateWithLifecycle().value
    val lookup by vm.lookup.collectAsStateWithLifecycle()
    val fallbackReady by vm.fallbackReady.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val fallback = cached?.ref
    val s = fallback?.item ?: canonical?.item()
    // The root reads only the page's shape (directory, seasons, size of the season shown): each server's
    // resources recompose the episode block and the header's availability line, not the page.
    val shape by pipeline.shape.collectAsStateWithLifecycle()
    // The catalogue work whose episode directory is in use: null without a TMDB token or while TMDB is
    // unreachable. It decides catalogue or server episodes, versions and mappings; [canonical] still names the page.
    val directoryWork = shape.directoryWork
    val seasonNumber = shape.season
    // The card focus rests on (performance plan R3): read only by the episode text under the row, so a
    // D-pad move does not recompose the page; the page follows once focus has rested for a moment.
    val focusedKey = remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pipeline) { pipeline.picks.collect { focusedKey.value = it } }
    LaunchedEffect(focusedKey) {
        snapshotFlow { focusedKey.value }.collectLatest { key ->
            if (key == null || key == pipeline.currentKey.value) return@collectLatest
            delay(FOCUS_SETTLE_MS)
            pipeline.currentKey.value = key
        }
    }
    /** The page's episode set directly (quick pick, after the player): focus follows it. */
    fun pick(key: String?) = pipeline.pick(key)
    val seasons = shape.seasons
    // The shown episode, its versions and selection are read only by the sections that show them
    // (performance plan R3): the page itself needs to know only whether there is something to play.
    val hasPlayable by vm.hasPlayable.collectAsStateWithLifecycle()
    val selection = remember { EpisodeSelection() }
    var picks by rememberRetained("picks", seriesId) { mutableStateOf<Map<String, TrackChoice>>(emptyMap()) }
    fun tracks(v: Version) = picks[v.key] ?: app.playbackMemory.tracksFor(v.ref, v.source) ?: TrackChoice()
    fun playVersion(v: Version, startMs: Long, versions: VersionsState) =
        play(context, v.ref, startMs, v.source.id, tracks(v), versions.alternatesTo(v), versions.copies)
    val scroll = rememberScrollState()
    val heading = rememberDetailHeading(scroll, gap = 20f)
    val rowState = rememberLazyListState()
    val currentCard = remember { FocusRequester() }
    val playButton = remember { FocusRequester() }
    val headerFocused = remember { mutableStateOf(false) }
    var marking by remember { mutableStateOf<ServerItem?>(null) }
    val danmu = rememberDanmuPanel()
    var unsorted by rememberSaveable { mutableStateOf(false) }
    var mappingsOpen by remember { mutableStateOf(false) }
    var mappingSource by remember { mutableStateOf<ServerItem?>(null) }
    var quickSelect by remember { mutableStateOf(false) }
    val quickPickKey = remember { mutableStateOf<String?>(null) }
    // Read at click time, so the cards' callback never changes.
    val playRow: (EpisodeRow) -> Unit = remember(vm) {
        { row ->
            val view = vm.episodeView.value
            val selected = selection.selected(view)
            pick(row.key)
            if (row.key == view.shown?.key && selected != null) playVersion(selected, selected.item.resumeMs, view.versions)
            else row.resources.firstOrNull()?.let { play(context, it, it.item.resumeMs) }
        }
    }
    if (s == null || canonical == null && !fallbackReady) {
        LoadState(error, onRetry = { vm.refresh(replace = true); pipeline.resourceRetry.value++ }, onServers = { nav.go(Route.Servers) }); return
    }
    val ref = fallback ?: canonical?.cardRef() ?: ServerItem("catalog", s)
    // 影片信息来源 field by field (统一数据层 ④); the series' own copy is the server member.
    val sourceRows by app.sources.rows.collectAsStateWithLifecycle()
    val companion by vm.companion.collectAsStateWithLifecycle()
    val catalogs = remember(canonical, companion) { catalogsOf(canonical, companion) }
    val display = remember(catalogs, ref, sourceRows) { MetadataMerge.display(app.sources.metadata(), catalogs, listOf(ref)) { app.display(it) } }
    val castSource = remember(catalogs, ref, sourceRows) { MetadataMerge.cast(app.sources.metadata(), catalogs, listOf(ref)) }
    var rowSeason by rememberSaveable { mutableIntStateOf(-1) }
    LaunchedEffect(seasonNumber) {
        if(rowSeason >= 0 && rowSeason != seasonNumber) rowState.scrollToItem(0)
        rowSeason = seasonNumber
    }
    val accordion = rememberAccordion(SECTION_EPISODES, scroll)
    val cast = remember(castSource) { castCards(app, nav, castSource) }
    // 为你推荐 of the series itself, not of the focused episode (ui-spec §2.14).
    val tmdbId = detailRecommendationId(s, (canonical ?: lookup)?.ref)
    val recKey = tmdbId?.let { r -> "series:tmdb:${r.id}" } ?: Keys.work(ref.server, ref.item)
    val seriesCopies by pipeline.seriesCopies.collectAsStateWithLifecycle()
    val recCopies = remember(fallback?.key, seriesCopies) { (listOfNotNull(fallback) + seriesCopies).distinctBy { c -> c.server } }
    val recs = rememberRecommendations(recKey, recCopies, tmdbId, tick).orEmpty()
    // An episode is on screen whenever the season lists any (the versions section then says what it found).
    val hasVersions = !unsorted && (shape.episodeCount > 0 || directoryWork != null)
    val hasTracks = !unsorted && hasPlayable
    accordion.arrange(listOfNotNull(
        SECTION_EPISODES,
        SECTION_VERSIONS.takeIf { hasVersions },
        SECTION_TRACKS.takeIf { hasTracks },
        SECTION_CAST.takeIf { cast.isNotEmpty() },
        SECTION_RECS.takeIf { recs.isNotEmpty() },
    ), fallback = SECTION_EPISODES)
    accordion.header = playButton
    val today = remember { java.time.LocalDate.now().toString() }
    if (mappingsOpen && directoryWork != null) SeriesMappingHost(pipeline, directoryWork, mappingSource, onDismiss = { mappingsOpen = false; mappingSource = null })
    if (quickSelect) QuickEpisodeHost(pipeline, display.title, seasons, seasonNumber,
        onSeason = { number -> pipeline.chooseSeason(number); unsorted = false },
        onSelect = { row -> pick(row.key); unsorted = false; quickSelect = false; quickPickKey.value = row.key },
        onDismiss = { quickSelect = false })
    // The work's IMDb trailer behind the backdrop (trailer design); released when the page pauses or leaves.
    val trailer = rememberTrailerPlayer()
    DetailTrailerHost(vm, trailer, heading)
    BackdropProvider(display.backdrop, rememberDetailScrims(heading, series = true)) {
      ActionFeedbackHost(Modifier.fillMaxSize()) {
        BackdropLayer(Modifier.fillMaxSize(), blurProgress = { heading.backdropProgress }, trailer = trailer)
        // Section changes align the context row; inner card focus must not move the whole page.
        @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides detailPageScrollSpec) {
        Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
          @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
          androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides defaultScrollSpec) {
            Column(Modifier.fillMaxWidth()) {
                SeriesHeaderHost(vm, selection, ref, display, heading, recCopies, directoryWork,
                    playVersion = ::playVersion,
                    playButton = playButton,
                    initialFocusKey = seriesId, onFocusChanged = { headerFocused.value = it; if (it) accordion.showHeader() },
                    onMarks = { marking = it },
                    danmu = danmu,
                    onQuickSelect = { quickSelect = true },
                    onRetry = { versions, failed -> versions.retry(); failed.forEach(app.registry::retry); pipeline.resourceRetry.value++ },
                    onDown = { accordion.open(SECTION_EPISODES) })
                Spacer(Modifier.height(44.dp))
                // 剧集 never collapses: the season row and the current episode stay in place (ui-spec §2.14).
                DetailBlock(accordion, SECTION_EPISODES) {
                    SeriesEpisodesBlock(nav, pipeline, accordion, unsorted, { unsorted = it }, today, rowState, currentCard, focusedKey, playRow,
                        tick, headerFocused, quickPickKey, onMappings = { source -> mappingSource = source; mappingsOpen = true })
                }
                if (hasVersions) SeriesVersionsHost(vm, selection, accordion, ::tracks, ::playVersion)
                if (hasTracks) SeriesTracksHost(vm, selection, accordion, ::tracks) { v, choice -> picks = picks + (v.key to choice) }
                if (cast.isNotEmpty()) {
                    DetailBlock(accordion, SECTION_CAST) {
                        AfterFirstFrame { CastBlock(cast, accordion.entry(SECTION_CAST)) }
                    }
                }
                if (recs.isNotEmpty()) {
                    DetailBlock(accordion, SECTION_RECS) {
                        AfterFirstFrame { RecommendationsBlock(nav, recs, accordion.entry(SECTION_RECS)) }
                    }
                }
                CatalogRelated(nav, canonical, fallback)
                Spacer(Modifier.height(600.dp))
            }
          }
        }
        }
        SeriesCompactHeader(vm, selection, heading, display,
            listOfNotNull(display.year?.toString(), display.genres.take(2).joinToString(" · ").ifEmpty { null },
                if (seasons.isNotEmpty()) "${seasons.size} 季" else null,
                if (shape.episodeCount > 0 && seasons.size == 1) "${shape.episodeCount} 集" else null).joinToString(" · "))
        marking?.let { SegmentMarksPanel(it.server, it.item, onDismiss = { marking = null }) }
        DanmuPanelHost(danmu)
      }
    }
}

/**
 * 剧集: the identity banner, season row, mapping chip, notes and the episode row. It reads the whole
 * [SeriesData], so each server's resources recompose this block, not the page around it.
 */
@Composable
private fun SeriesEpisodesBlock(
    nav: Nav, pipeline: SeriesPipeline, accordion: Accordion, unsorted: Boolean, setUnsorted: (Boolean) -> Unit, today: String,
    rowState: androidx.compose.foundation.lazy.LazyListState, currentCard: FocusRequester, focusedKey: MutableState<String?>,
    playRow: (EpisodeRow) -> Unit, tick: Int, headerFocused: State<Boolean>, quickPickKey: MutableState<String?>,
    onMappings: (ServerItem?) -> Unit,
) {
    TraceRecomposition("Series.episodes")
    val data by pipeline.state.collectAsStateWithLifecycle()
    val directoryWork = data.directoryWork
    val seasons = data.seasons
    val seasonNumber = data.season
    val episodes = data.episodes
    val pending = data.pending
    val identities = data.identities
    val mapped = data.mapped
    val episodeError = data.episodeError
    // One whole-series list; changing season must not hide entries or reclassify other seasons.
    val unknown = mapped.unmatched
    // Back from the player on the header: a watched episode moves on to the next unwatched one.
    var handledResumeTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick, episodes, headerFocused.value) {
        if (tick > handledResumeTick && headerFocused.value && episodes.isNotEmpty()) {
            handledResumeTick = tick
            val shown = episodes.firstOrNull { it.key == pipeline.currentKey.value } ?: episodes.firstOrNull()
            if (shown?.item?.userData?.played != true) return@LaunchedEffect
            val index = episodes.indexOfFirst { it.key == shown.key }
            episodes.drop(index + 1).firstOrNull { it.item.userData?.played != true }?.let { pipeline.pick(it.key) }
        }
    }
    // 快速选集 chose an episode: scroll the row to it and focus it once it is listed.
    LaunchedEffect(quickPickKey.value, episodes) {
        val key = quickPickKey.value ?: return@LaunchedEffect
        val index = episodes.indexOfFirst { it.key == key }
        if (index < 0) return@LaunchedEffect
        rowState.scrollToItem(index)
        awaitFrame()
        accordion.open(SECTION_EPISODES, focusKey = "episode:$key")
        quickPickKey.value = null
    }
  Column(Modifier.padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
    if (directoryWork != null && identities.values.any { it.state == EpisodeIdentityState.Unavailable }) Row(Modifier.fillMaxWidth().padding(horizontal = 80.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        val unavailable = identities.values.count { it.state == EpisodeIdentityState.Unavailable }
        if (unavailable > 0) GlassButton("$unavailable 条身份待确认 · 重试", { pipeline.identityRetry.value++ })
    }
    val segments = seasons.map { Segment(it.title) } + listOfNotNull(if (unknown.isNotEmpty() || unsorted) Segment("未归类", unknown.size.toString()) else null)
    Row(Modifier.fillMaxWidth().padding(horizontal = 80.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp)) {
      Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
       if (segments.isNotEmpty()) SegmentedWell(segments, if (unsorted) seasons.size else seasons.indexOfFirst { it.number == seasonNumber }.coerceAtLeast(0),
        { i ->
            pipeline.anchorApplied.value = true
            if (i >= seasons.size) setUnsorted(true) else {
                setUnsorted(false)
                pipeline.chooseSeason(seasons[i].number)
            }
        }, labelPrefix = "季")
      }
      if (directoryWork != null) MappingChip(data.mappings.size) { onMappings(null) }
    }
    episodeError?.let { Text(it, color = Z.warn, modifier = Modifier.padding(horizontal = 80.dp)); GlassButton("重试目录", { pipeline.resourceRetry.value++ }, Modifier.padding(start = 80.dp)) }
    data.directoryNote?.let { note ->
        Row(Modifier.padding(horizontal = 80.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Text(note, fontSize = 21.sp, color = Z.textTertiary)
            GlassButton("重试 TMDB", { pipeline.resourceRetry.value++ })
        }
    }
    if (episodes.isEmpty() && !unsorted && episodeError == null) {
        Text(if (data.loading) "正在加载剧集目录…" else "这一季还没有剧集", fontSize = 21.sp, color = Z.textTertiary, modifier = Modifier.padding(horizontal = 80.dp))
    }
    if (unsorted) {
        if (unknown.isEmpty() && pending == 0 && mapped.awaitingIdentity.isEmpty()) Text("没有未归类条目",
            fontSize = 21.sp, color = Z.textTertiary, modifier = Modifier.padding(horizontal = 80.dp))
        UnsortedRow(nav, unknown, mapped.reasons, accordion.entry(SECTION_EPISODES)) { onMappings(it) }
    } else {
        EpisodeRowSection(pipeline, episodes, searching = pending > 0, failed = data.failed.isNotEmpty(), today, rowState, currentCard,
            accordion.entry(SECTION_EPISODES), focusedKey, playRow)
    }
  }
}

/** 剧集映射 with the whole series' resources; subscribes only while it is open. */
@Composable
private fun SeriesMappingHost(pipeline: SeriesPipeline, directoryWork: CatalogWork, source: ServerItem?, onDismiss: () -> Unit) {
    val data by pipeline.state.collectAsStateWithLifecycle()
    EpisodeMappingDialog(directoryWork, data.allCatalog, data.allResources, data.identities, data.mappings, data.season, source,
        unsorted = data.mapped.unmatched, reasons = data.mapped.reasons, onDismiss = onDismiss)
}

/** How long focus rests on an episode card before the page (versions, play button) follows it. */
private const val FOCUS_SETTLE_MS = 250L

/**
 * The version picked with OK and the one previewed while focus is on the version cards, for one
 * episode: both lapse when the page moves to another episode. Read only by the sections that show them.
 */
@Stable
internal class EpisodeSelection {
    private var key by mutableStateOf<String?>(null)
    private var picked by mutableStateOf<Version?>(null)
    private var previewed by mutableStateOf<Version?>(null)

    fun pick(episode: String?, v: Version) { if (key != episode) { key = episode; previewed = null }; picked = v }
    fun preview(episode: String?, v: Version?) { if (key != episode) { key = episode; picked = null }; previewed = v }

    /** Until a pick: the copy holding the merged 在看 resume point, the last successful start, then the ranked first. */
    fun selected(view: EpisodeView): Version? =
        picked.takeIf { key == view.shown?.key }?.let { p -> view.merged.ordered.firstOrNull { it.key == p.key } } ?: view.merged.default

    /** The version whose tracks show: the previewed one, else the selected one. */
    fun trackVersion(view: EpisodeView): Version? = previewed.takeIf { key == view.shown?.key } ?: selected(view)
}

@Composable
private fun SeriesHeaderHost(
    vm: SeriesViewModel, selection: EpisodeSelection, ref: ServerItem, display: DetailDisplay, heading: DetailHeadingState,
    seriesCopies: List<ServerItem>, directoryWork: CatalogWork?,
    playVersion: (Version, Long, VersionsState) -> Unit, playButton: FocusRequester, initialFocusKey: String?,
    onFocusChanged: (Boolean) -> Unit, onMarks: (ServerItem) -> Unit, danmu: DanmuPanelState?, onQuickSelect: () -> Unit,
    onRetry: (VersionsState, List<String>) -> Unit, onDown: () -> Unit,
) {
    TraceRecomposition("Series.header")
    val view by vm.episodeView.collectAsStateWithLifecycle()
    val resources by vm.pipeline.availability.collectAsStateWithLifecycle()
    val pending = resources.pending
    val failed = resources.failed
    val shown = view.shown
    val versions = view.versions
    val selected = selection.selected(view)
    val availability = when {
        pending > 0 || versions.searching > 0 -> "正在查找资源…"
        failed.isNotEmpty() || versions.unresponsive.isNotEmpty() -> "资源状态待确认 · 重试"
        else -> "暂无资源"
    }
    SeriesHeader(ref, display, heading, shown?.item, selected, seriesCopies,
        watchedRef = shown?.canonical?.let { ep -> directoryWork?.let { ServerItem("catalog", it.item(ep).copy(userData = shown.item.userData)) } } ?: selected?.ref,
        episodeCopies = versions.instances + shown?.resources.orEmpty(),
        onPlay = { _, v, start -> v?.let { playVersion(it, start, versions) } },
        playButton = playButton,
        initialFocusKey = initialFocusKey, onFocusChanged = onFocusChanged,
        onMarks = { selected?.let { onMarks(it.ref) } },
        danmu = danmu, availability = availability,
        onQuickSelect = onQuickSelect,
        onRetry = { onRetry(versions, failed) },
        onDown = onDown)
}

@Composable
private fun SeriesVersionsHost(vm: SeriesViewModel, selection: EpisodeSelection, accordion: Accordion, tracks: (Version) -> TrackChoice,
    playVersion: (Version, Long, VersionsState) -> Unit) {
    TraceRecomposition("Series.versions")
    val view by vm.episodeView.collectAsStateWithLifecycle()
    val versions = view.versions
    val selected = selection.selected(view)
    // The hidden version cards catch up when the row comes to rest (rememberSettled).
    val full = rememberSettled(view, accordion.isExpanded(SECTION_VERSIONS))
    CollapsibleSection(accordion, SECTION_VERSIONS, "版本", versionCount(versions, selected), summary = {
        selected?.let { VersionSummary(it, tracks(it)) }
            ?: SummaryText(if (versions.searching > 0) "正在查找资源…" else "这一集暂无可播放的版本")
    }) {
        val key = full.shown?.key
        Box(Modifier.padding(vertical = 20.dp)) {
            VersionSection(full.versions, selection.selected(full), { selection.pick(key, it) }, { selection.preview(key, it) },
                { card -> val v = full.merged.of(card); playVersion(v, v.item.resumeMs, full.versions) },
                entry = accordion.entry(SECTION_VERSIONS), hold = holdHint(accordion.order, SECTION_VERSIONS))
        }
    }
}

@Composable
private fun SeriesTracksHost(vm: SeriesViewModel, selection: EpisodeSelection, accordion: Accordion, tracks: (Version) -> TrackChoice,
    onChoice: (Version, TrackChoice) -> Unit) {
    TraceRecomposition("Series.tracks")
    val view by vm.episodeView.collectAsStateWithLifecycle()
    val v = selection.trackVersion(view) ?: return
    val full = rememberSettled(view, accordion.isExpanded(SECTION_TRACKS))
    CollapsibleSection(accordion, SECTION_TRACKS, "音轨与字幕", "${v.source.subtitleStreams.size} 条字幕", barHeight = 112.dp, summary = { TrackSummary(v, tracks(v)) }) {
        val fv = selection.trackVersion(full) ?: v
        Box(Modifier.padding(vertical = 20.dp)) {
            TrackSection(fv, fv.item.chapters.size, fv.item.runTimeTicks, tracks(fv), accordion.entry(SECTION_TRACKS), holdHint(accordion.order, SECTION_TRACKS)) { onChoice(fv, it) }
        }
    }
}

@Composable
private fun SeriesCompactHeader(vm: SeriesViewModel, selection: EpisodeSelection, heading: DetailHeadingState, display: DetailDisplay, meta: String) {
    TraceRecomposition("Series.compactHeader")
    val view by vm.episodeView.collectAsStateWithLifecycle()
    val shown = view.shown
    val playItem = selection.selected(view)?.item
    val ratings by vm.ratings.collectAsStateWithLifecycle()
    CompactHeader(heading, display, meta,
        if (shown != null && playItem != null) seriesPlayLabel(episodeCode(shown.item), resumeMsOf(playItem), remainingLabel(playItem)) else null, ratings)
}

/** 快速选集 with the episode the page is on; subscribes only while it is open. */
@Composable
private fun QuickEpisodeHost(pipeline: SeriesPipeline, title: String, seasons: List<CatalogSeason>, season: Int,
    onSeason: (Int) -> Unit, onSelect: (EpisodeRow) -> Unit, onDismiss: () -> Unit) {
    val currentKey by pipeline.currentKey.collectAsStateWithLifecycle()
    val episodes = pipeline.state.collectAsStateWithLifecycle().value.episodes
    val shownKey = (episodes.firstOrNull { it.key == currentKey } ?: episodes.firstOrNull())?.key
    QuickEpisodeDialog(title, seasons, season, episodes, shownKey, onSeason = onSeason, onSelect = onSelect, onDismiss = onDismiss)
}

/** The episode row and the focused episode's text: the part of the page that follows the remote along the row. */
@Composable
private fun EpisodeRowSection(
    pipeline: SeriesPipeline, episodes: List<EpisodeRow>, searching: Boolean, failed: Boolean, today: String,
    rowState: androidx.compose.foundation.lazy.LazyListState, currentCard: FocusRequester, entry: FocusRequester,
    focusedKey: MutableState<String?>, playRow: (EpisodeRow) -> Unit,
) {
    TraceRecomposition("Series.row")
    val currentKey by pipeline.currentKey.collectAsStateWithLifecycle()
    val shownKey = (episodes.firstOrNull { it.key == currentKey } ?: episodes.firstOrNull())?.key
    LaunchedEffect(currentKey, episodes.isNotEmpty()) {
        val index = episodes.indexOfFirst { it.key == currentKey }
        if (index > 0 && rowState.firstVisibleItemIndex == 0) rowState.scrollToItem((index - 1).coerceAtLeast(0))
    }
    LazyRow(modifier = Modifier.horizontalRow(enter = currentCard.takeIf { shownKey != null }), state = rowState,
        contentPadding = PaddingValues(start = 80.dp, end = 120.dp, bottom = 30.dp), horizontalArrangement = Arrangement.spacedBy(30.dp)) {
        items(episodes, key = { it.key }, contentType = { "episode" }) { row ->
            val status = episodeStatus(row.resources, row.canonical?.date, today, searching = searching, failed = failed)
            // Lambdas that capture only stable values, so cards whose own data did not change skip.
            MediaCard16x9(row.image, onClick = { playRow(row) }, width = 340.dp,
                topEnd = status.label,
                watched = row.item.userData?.played == true, progress = progressFraction(row.item), onFocus = { focusedKey.value = row.key },
                focusKey = "episode:${row.key}",
                dim = if (status.dim) 0.4f else 1f,
                modifier = if (row.key == shownKey) Modifier.focusRequester(currentCard).focusRequester(entry) else Modifier) {
                val number = "第 ${row.item.indexNumber ?: "?"} 集"
                val name = episodeName(row.item)
                // Without a name of its own the number takes the title line; the line above stays for alignment.
                Column { Text(if (name != null) number else "", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Z.textSecondary); Text(name ?: number, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
    EpisodeText(episodes, focusedKey, shownKey)
}

/** The focused episode's number, name and overview under the row; the only part that follows each D-pad move. */
@Composable
private fun EpisodeText(episodes: List<EpisodeRow>, focusedKey: State<String?>, shownKey: String?) {
    TraceRecomposition("Series.episodeText")
    val row = (focusedKey.value ?: shownKey)?.let { key -> episodes.firstOrNull { it.key == key } } ?: return
    Column(Modifier.padding(start = 80.dp, end = 80.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(listOfNotNull("第 ${row.item.indexNumber ?: "?"} 集", episodeName(row.item)).joinToString(" · "), fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
        row.item.overview?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 21.sp, lineHeight = 32.sp, color = Z.textSecondary, maxLines = 3, modifier = Modifier.width(1100.dp)) }
    }
}

/** An episode card's corner and whether it is dimmed (ui-spec §2.3 资源状态). */
internal data class EpisodeStatus(val label: String, val dim: Boolean)

/**
 * 「n 个版本」 when servers have it; while the lookup runs 「查找中」; a catalogue episode that has not
 * aired yet 「11 月 2 日播出」; otherwise 「无资源」 (「待确认」 when some server did not answer).
 * Without resources the card is dimmed to 40 % but stays focusable.
 */
internal fun episodeStatus(resources: List<ServerItem>, airDate: String?, today: String, searching: Boolean, failed: Boolean): EpisodeStatus {
    if (resources.isNotEmpty()) {
        val versions = resources.sumOf { it.item.mediaSources.size.coerceAtLeast(1) }
        return EpisodeStatus("$versions 个版本", false)
    }
    val date = airDate?.takeIf { it.length >= 10 }
    if (date != null && date > today) {
        val month = date.substring(5, 7).toIntOrNull()
        val day = date.substring(8, 10).toIntOrNull()
        if (month != null && day != null) return EpisodeStatus("$month 月 $day 日播出", true)
    }
    return when {
        searching -> EpisodeStatus("查找中", false)
        failed -> EpisodeStatus("待确认", true)
        else -> EpisodeStatus("无资源", true)
    }
}

/**
 * 未归类 (SeriesUnsorted.dc.html): 16:9 cards with the server name, the server's numbering or the
 * reason, and the entry's name; under the focused one an information card with 播放 and 详情.
 */
@Composable
private fun UnsortedRow(nav: Nav, items: List<ServerItem>, reasons: Map<String, String>, entry: FocusRequester, onMap: (ServerItem) -> Unit) {
    val context = LocalContext.current
    val app = context.zmby
    var focusedKey by rememberSaveable { mutableStateOf(items.firstOrNull()?.key) }
    val rowState = rememberLazyListState()
    val focused = items.firstOrNull { it.key == focusedKey } ?: items.firstOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
        LazyRow(Modifier.horizontalRow(), state = rowState, contentPadding = PaddingValues(start = 80.dp, end = 120.dp), horizontalArrangement = Arrangement.spacedBy(30.dp)) {
            items(items, key = { it.key }, contentType = { "unsorted" }) { r ->
                val code = unsortedCode(r.item) ?: reasons[r.key] ?: "没有季集编号"
                MediaCard16x9(app.library.landscapeImage(r), { nav.go(Route.Detail(r.server, r.item.id, resourceOnly = true)) }, width = 340.dp,
                    topStart = app.registry.get(r.server)?.name, onFocus = { focusedKey = r.key }, focusKey = "unsorted:${r.key}",
                    modifier = if (r.key == focused?.key) Modifier.focusRequester(entry) else Modifier) {
                    Column {
                        Text(code, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Z.textSecondary, maxLines = 1)
                        Text(titleOf(r.item), fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        focused?.let { r ->
            val server = app.registry.get(r.server)
            val source = r.item.mediaSources.firstOrNull()
            Column(Modifier.padding(start = 80.dp).width(1180.dp).groupCard(androidx.compose.foundation.shape.RoundedCornerShape(28.dp)).padding(horizontal = 34.dp, vertical = 30.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(titleOf(r.item), fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2)
                @Composable
                fun line(k: String, v: String?, mono: Boolean = false) {
                    if (v.isNullOrBlank()) return
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text(k, fontSize = 20.sp, color = Z.textTertiary, modifier = Modifier.width(170.dp))
                        Text(v, fontSize = if (mono) 17.sp else 20.sp, fontFamily = if (mono) Mono else null, lineHeight = 29.sp)
                    }
                }
                line("服务器", listOfNotNull(server?.name, server?.lineName).joinToString(" · "))
                line("服务器上的编号", listOfNotNull(unsortedCode(r.item) ?: "没有季集编号", formatDuration(r.item.runtimeMs)).joinToString(" · "))
                line("文件", (source?.path ?: r.item.path)?.replace('\\', '/')?.substringAfterLast('/'), mono = true)
                line("未归入原因", reasons[r.key])
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    GlassButton("播放", { play(context, r, r.item.resumeMs) }, glyph = Glyph.Play, label = "播放未归类 ${r.key}")
                    GlassButton("映射季集", { onMap(r) }, label = "映射季集 ${r.key}")
                    GlassButton("详情", { nav.go(Route.Detail(r.server, r.item.id, resourceOnly = true)) }, label = "未归类详情 ${r.key}")
                }
            }
        }
    }
}

/** 「n 条规则」 after 季集映射 on the season row, nothing without rules. */
internal fun mappingChipCount(rules: Int): String? = if (rules > 0) "$rules 条规则" else null

/** 季集映射 on the season row (Series.dc.html): 60 high glass pill, swap arrows, then the rule count. */
@Composable
private fun MappingChip(rules: Int, onClick: () -> Unit) {
    GlassSurface(onClick, label = "季集映射", focusKey = "season-mapping") {
        Row(Modifier.height(60.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Glyph.Swap, size = 20.dp, strokeWidth = 2.2f)
            Text("季集映射", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            mappingChipCount(rules)?.let { Text(it, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, color = androidx.tv.material3.LocalContentColor.current.copy(alpha = 0.6f)) }
        }
    }
}

/** 「第 1 季 第 1 集」 from the server's numbers, or null when it has none. */
internal fun unsortedCode(item: BaseItemDto): String? {
    val e = item.indexNumber ?: return null
    return item.parentIndexNumber?.let { "第 $it 季 第 $e 集" } ?: "第 $e 集"
}

@Composable
private fun SeriesHeader(
    ref: ServerItem,
    display: DetailDisplay,
    heading: DetailHeadingState,
    /** The episode the main button plays: the focused (or last focused) one, else the next to watch. */
    current: BaseItemDto?,
    /** Its selected version; null until the version list knows it (then the server's own copy plays). */
    selected: Version?,
    seriesCopies: List<ServerItem>,
    watchedRef: ServerItem?,
    episodeCopies: List<ServerItem>,
    onPlay: (episode: BaseItemDto, version: Version?, startMs: Long) -> Unit,
    playButton: FocusRequester,
    initialFocusKey: String?,
    onFocusChanged: (Boolean) -> Unit,
    onMarks: (BaseItemDto) -> Unit = {},
    danmu: DanmuPanelState? = null,
    onQuickSelect: () -> Unit = {},
    availability: String? = null,
    onRetry: () -> Unit = {},
    onDown: () -> Unit = {},
) {
    val s = ref.item
    Column(
        Modifier
            .padding(start = 80.dp, top = 140.dp)
            .width(1760.dp)
            .graphicsLayer { alpha = 1f - heading.frame.progress }
            .onFocusChanged { onFocusChanged(it.hasFocus) }
            .headerDown(onDown)
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        DetailHeadingSlots(heading)
        if (!display.overview.isNullOrBlank()) Text(display.overview, fontSize = 23.sp, lineHeight = 37.sp, color = Z.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(860.dp))
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            val playItem = selected?.item
            val resumeMs = playItem?.let { resumeMsOf(it) } ?: 0L
            val label = if(current != null && playItem != null) seriesPlayLabel(episodeCode(current),resumeMs,remainingLabel(playItem)) else availability ?: "暂无资源"
            GlassButton(label, { if(current != null && selected != null) onPlay(current,selected,resumeMs) else onRetry() },
                Modifier.focusRequester(playButton).focusRequester(rememberInitialFocus(initialFocusKey ?: s.id)), glyph = Glyph.Play, label = "seriesPlay:$initialFocusKey")
            if(current != null && selected != null && resumeMs > 0) GlassButton("从头播放", { onPlay(current,selected,0) })
            // Then the icon buttons in their fixed order (ui-spec §2.15); unavailable ones stay in place, dimmed.
            ActionDivider()
            QuickPickAction(onQuickSelect)
            WatchedAction(watchedRef, episodeCopies)
            FavoriteAction(ref, (listOf(ref) + seriesCopies).distinctBy { it.key })
            WatchlistAction(ref, seriesCopies)
            // 本地手动标记 of the episode the main button plays (or its whole season).
            MarksAction(ref = selected?.ref) { current?.let(onMarks) }
            // 弹幕 (ui-spec §2.19): last, after 片头片尾; for the episode the main button plays.
            danmu?.let { DanmuAction(selected?.ref, it) }
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
