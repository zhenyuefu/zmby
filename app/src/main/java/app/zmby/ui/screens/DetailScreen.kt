package app.zmby.ui.screens

import app.zmby.TraceRecomposition
import app.zmby.ui.userMessage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusGroup
import app.zmby.log.ZLog
import kotlinx.coroutines.android.awaitFrame
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.zmby.data.ServerStatus
import app.zmby.emby.BaseItemDto
import app.zmby.emby.MediaSourceInfo
import app.zmby.media.KeyValue
import app.zmby.media.QualityTier
import app.zmby.media.TrackRow
import app.zmby.media.audioRows
import app.zmby.media.episodeCode
import app.zmby.media.fileRows
import app.zmby.media.formatClock
import app.zmby.media.formatDuration
import app.zmby.media.formatSize
import app.zmby.media.isDiscImage
import app.zmby.media.isProbed
import app.zmby.media.qualityTier
import app.zmby.media.subtitleRows
import app.zmby.media.versionBadges
import app.zmby.media.versionOrder
import app.zmby.media.versionTitle
import app.zmby.playback.TrackChoice
import app.zmby.playback.TrackPreference
import app.zmby.ui.Nav
import app.zmby.ui.LocalNavEntry
import app.zmby.ui.components.LoadState
import app.zmby.ui.Route
import app.zmby.ui.components.BackdropLayer
import app.zmby.ui.components.BackdropProvider
import app.zmby.ui.components.Badge
import app.zmby.ui.components.Focusable
import app.zmby.ui.components.GlassButton
import app.zmby.ui.components.ActionDivider
import app.zmby.ui.components.ActionFeedbackHost
import app.zmby.ui.components.Glyph
import app.zmby.ui.components.HealthDot
import app.zmby.ui.components.RowTitle
import app.zmby.ui.components.Scrims
import app.zmby.ui.components.Segment
import app.zmby.ui.components.SegmentedWell
import app.zmby.ui.components.SelectableRow
import app.zmby.ui.components.TitleLogo
import app.zmby.ui.components.groupCard
import app.zmby.ui.components.secondaryText
import app.zmby.ui.play
import app.zmby.ui.rememberInitialFocus
import app.zmby.ui.rememberResumeTick
import app.zmby.ui.rememberRetained
import app.zmby.ui.resumeMsOf
import app.zmby.ui.screenModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zmby.ui.theme.Mono
import app.zmby.ui.theme.Z
import app.zmby.ui.theme.detailPageScrollSpec
import app.zmby.ui.theme.defaultScrollSpec
import app.zmby.zidoo.ZidooIntents
import app.zmby.zidoo.ZidooTracks
import app.zmby.zmby
import app.zmby.ui.components.rememberTrailerPlayer
import kotlinx.coroutines.launch
import app.zmby.aggregate.Keys
import app.zmby.aggregate.tmdb
import app.zmby.catalog.cardRef
import app.zmby.catalog.CatalogId
import app.zmby.data.ServerItem
import app.zmby.data.withPlaybackProgress
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.onFocusChanged

@Composable
fun DetailScreen(nav: Nav, server: String, itemId: String, resourceOnly: Boolean = false, provider: app.zmby.catalog.CatalogId? = null) {
    TraceRecomposition("Detail.root")
    val context = LocalContext.current
    val app = context.zmby
    val library = app.library
    val tick = rememberResumeTick()
    // Local first: the cached item shows at once; canonical metadata replaces its fields in place.
    val vm = screenModel("detail:$server:$itemId") { MovieDetailViewModel(app, server, itemId, provider, resourceOnly) }
    val languages by app.settings.metadataLanguage.flow.collectAsStateWithLifecycle()
    val posters by app.settings.posterLanguage.flow.collectAsStateWithLifecycle()
    val logos by app.settings.logoLanguage.flow.collectAsStateWithLifecycle()
    val token by app.settings.tmdbToken.flow.collectAsStateWithLifecycle()
    LaunchedEffect(tick, languages, posters, logos, token) { vm.refresh(replace = true, force = tick > 0) }
    LaunchedEffect(tick) { vm.tick.value = tick }
    val canonical = vm.canonical.collectAsStateWithLifecycle().value
    val lookup by vm.lookup.collectAsStateWithLifecycle()
    val fallbackReady by vm.fallbackReady.collectAsStateWithLifecycle()
    val cached by vm.detail.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    // Versions live in the ViewModel and only the sections showing them read them (R4); the page needs
    // to know whether there is anything to show and the other servers' copies (for 为你推荐).
    val versionsShown by vm.versionsShown.collectAsStateWithLifecycle()
    val hasPlayable by vm.hasPlayable.collectAsStateWithLifecycle()
    val instances by vm.instances.collectAsStateWithLifecycle()
    val metadataItem = cached?.ref?.item ?: canonical?.item()
    val lookupWork = canonical ?: lookup
    val it = metadataItem
    if (it == null || canonical == null && !fallbackReady) {
        LoadState(error, onRetry = { if(server != "catalog") app.registry.retry(server); vm.refresh() }, onServers = { nav.go(Route.Servers) })
        return
    }
    val ref = cached?.ref ?: canonical?.cardRef() ?: ServerItem("catalog", it)
    // 影片信息来源 field by field (统一数据层 ④): the page's own copy and the other servers' copies.
    // The other catalogue's entry fills fields too (TMDB 与 Bangumi 互补); 演职人员 come from the first
    // source in that order that has people.
    val sourceRows by app.sources.rows.collectAsStateWithLifecycle()
    val companion by vm.companion.collectAsStateWithLifecycle()
    val catalogs = remember(canonical, companion) { catalogsOf(canonical, companion) }
    val copies = remember(ref, instances) { (listOf(ref) + instances).distinctBy { c -> c.key } }
    val display = remember(catalogs, copies, sourceRows) { MetadataMerge.display(app.sources.metadata(), catalogs, copies) { app.display(it) } }
    val castSource = remember(catalogs, copies, sourceRows) { MetadataMerge.cast(app.sources.metadata(), catalogs, copies) }
    // OK on a version selects it (the header's play button and the cards below use it); until then
    // the copy holding the merged 在看 resume point, the last successfully played version, then
    // VersionRanker's default, is selected. While focus is on the version cards, the focused one previews its tracks.
    val selection = remember(it.id) { EpisodeSelection() }
    // Track picks per version (Emby stream indexes); a version without a pick uses the preferences or the server's defaults.
    var picks by rememberRetained("picks", it.id) { mutableStateOf<Map<String, TrackChoice>>(emptyMap()) }
    fun tracksFor(v: Version?) = v?.let { picks[it.key] } ?: v?.let { app.playbackMemory.tracksFor(it.ref, it.source) } ?: TrackChoice()
    fun playVersion(v: Version, startMs: Long, versions: VersionsState) =
        play(context, v.ref, startMs, v.source.id, tracksFor(v), versions.alternatesTo(v), versions.copies)
    val scroll = rememberScrollState()
    val heading = rememberDetailHeading(scroll, gap = 22f)
    val accordion = rememberAccordion(SECTION_VERSIONS, scroll)

    var marking by remember { mutableStateOf<ServerItem?>(null) }
    val danmu = rememberDanmuPanel()
    val cast = remember(castSource) { castCards(app, nav, castSource) }
    val hasVersions = it.type in setOf("Movie", "Video", "Episode") || lookupWork != null || versionsShown
    // 为你推荐 (ui-spec §2.14): keyed by the work's external identity when it has one.
    val tmdbId = detailRecommendationId(it, lookupWork?.ref)
    val recKey = tmdbId?.let { r -> "movie:tmdb:${r.id}" } ?: Keys.work(ref.server, ref.item)
    val recCopies = remember(cached?.ref?.key, instances) { (listOfNotNull(cached?.ref) + instances).distinctBy { c -> c.key } }
    val recs = rememberRecommendations(recKey, recCopies, tmdbId, tick).orEmpty()
    // 合集 (2026-11-05): the collections this film is in on its own server, at the top like a series' 剧集.
    val collections = rememberMovieCollections(cached?.ref, tick)
    val hasCollections by remember(collections) { derivedStateOf { collections.value.isNotEmpty() } }
    // The blocks in page order; only those with something to show (ui-spec §2.14).
    accordion.arrange(listOfNotNull(
        SECTION_COLLECTION.takeIf { hasCollections },
        SECTION_VERSIONS.takeIf { hasVersions },
        SECTION_TRACKS.takeIf { hasPlayable },
        SECTION_CAST.takeIf { cast.isNotEmpty() },
        SECTION_RECS.takeIf { recs.isNotEmpty() },
    ))
    val headerPlay = remember { FocusRequester() }
    accordion.header = headerPlay
    // The work's IMDb trailer behind the backdrop (trailer design); released when the page pauses or leaves.
    val trailer = rememberTrailerPlayer()
    // The header's 预告片 button opens it full screen with sound (ui-spec §2.23).
    val fullTrailer = rememberFullTrailer()
    DetailTrailerHost(vm, trailer, heading, fullTrailer)
    BackdropProvider(display.backdrop, rememberDetailScrims(heading, trailer = trailer)) {
      ActionFeedbackHost(Modifier.fillMaxSize()) {
        BackdropLayer(Modifier.fillMaxSize(), blurProgress = { heading.backdropProgress }, trailer = trailer)
        // Section changes align the context row; inner card focus must not move the whole page.
        @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides detailPageScrollSpec) {
        Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
          @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
          androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides defaultScrollSpec) {
            Column(Modifier.fillMaxWidth()) {
                // The version that plays: its own item (another server's copy has its own resume point).
                DetailHeaderHost(vm, selection, ref, display, heading, accordion, danmu, headerPlay, fullTrailer, ::playVersion, onMarks = { marking = it })
                Spacer(Modifier.height(44.dp))
                if (hasCollections) {
                    // Like 剧集, it never collapses.
                    DetailBlock(accordion, SECTION_COLLECTION) {
                        MovieCollectionBlock(nav, collections.value, cached?.ref?.item?.id ?: itemId, accordion.entry(SECTION_COLLECTION))
                    }
                }
                if (hasVersions) {
                    DetailVersionsHost(vm, selection, accordion, ::tracksFor, ::playVersion) { v -> library.rememberVersion(Keys.work(server, it), v.server.id, v.source.id) }
                } else {
                    Text("暂无资源", fontSize = 21.sp, color = Z.textTertiary, modifier = Modifier.padding(horizontal = 80.dp))
                }
                if (hasPlayable) DetailTracks(vm, selection, accordion, ::tracksFor) { v, c -> picks = picks + (v.key to c) }
                // 演职人员 (ui-spec §2.2 4), then 为你推荐 (§2.14).
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
                CatalogRelated(nav, canonical, cached?.ref)
                Spacer(Modifier.height(600.dp))
            }
          }
        }
        }
        DetailCompactHeader(vm, selection, heading, display, detailMeta(it, display))
        marking?.let { m -> SegmentMarksPanel(m.server, m.item, onDismiss = { marking = null }) }
        DanmuPanelHost(danmu)
        DetailFullTrailer(vm, fullTrailer, display.title, trailer)
      }
    }
}

@Composable
internal fun DetailFullTrailer(vm: DetailViewModel, state: FullTrailerState, title: String, background: app.zmby.ui.components.TrailerPlayer) {
    val info by vm.trailer.collectAsStateWithLifecycle()
    FullTrailerHost(state, info, title, background)
}

@Composable
private fun DetailHeaderHost(vm: MovieDetailViewModel, selection: EpisodeSelection, ref: ServerItem, display: DetailDisplay, heading: DetailHeadingState,
    accordion: Accordion, danmu: DanmuPanelState, headerPlay: FocusRequester, fullTrailer: FullTrailerState, playVersion: (Version, Long, VersionsState) -> Unit, onMarks: (ServerItem?) -> Unit) {
    TraceRecomposition("Detail.header")
    val view by vm.view.collectAsStateWithLifecycle()
    val trailerInfo by vm.trailer.collectAsStateWithLifecycle()
    val versions = view.versions
    val selected = selection.selected(view)
    val playRef = selected?.ref
    // 「播放正片」 in the full-screen trailer plays what the main button would.
    val mainResume = playRef?.item?.let { resumeMsOf(it) } ?: 0L
    androidx.compose.runtime.SideEffect {
        fullTrailer.playMain = selected?.let { v -> { playVersion(v, mainResume, versions) } }
    }
    DetailHeader(
        ref,
        display,
        heading = heading,
        playItem = playRef?.item,
        watchedRef = if (ref.server == "catalog") ref.copy(item = ref.item.copy(userData = playRef?.item?.userData)) else playRef ?: ref,
        instances = versions.instances,
        onPlay = { startMs -> selected?.let { playVersion(it, startMs, versions) } },
        // Back on the header: scroll all the way up so the title shows again, not just the buttons.
        onFocused = { accordion.showHeader() },
        onMarks = { onMarks(playRef) },
        danmuRef = playRef, danmu = danmu,
        availability = if (versions.searching > 0) "正在查找资源…" else if (versions.unresponsive.isNotEmpty()) "资源状态待确认 · 重试" else "暂无资源",
        onRetry = versions.retry,
        playRequester = headerPlay,
        onDown = { accordion.order.firstOrNull()?.let(accordion::open) },
        trailer = trailerInfo,
        fullTrailer = fullTrailer,
    )
}

@Composable
private fun DetailVersionsHost(vm: MovieDetailViewModel, selection: EpisodeSelection, accordion: Accordion, tracksFor: (Version?) -> TrackChoice,
    playVersion: (Version, Long, VersionsState) -> Unit, onPicked: (Version) -> Unit) {
    TraceRecomposition("Detail.versions")
    val view by vm.view.collectAsStateWithLifecycle()
    val versions = view.versions
    val selected = selection.selected(view)
    // The hidden version cards catch up once the servers' answers settle (rememberSettled).
    val full = rememberSettled(view, accordion.isExpanded(SECTION_VERSIONS))
    CollapsibleSection(accordion, SECTION_VERSIONS, "版本", versionCount(versions, selected), summary = {
        selected?.let { VersionSummary(it, tracksFor(it)) }
            ?: SummaryText(if (versions.searching > 0) "正在查找资源…" else "暂无可播放的版本")
    }) {
        Box(Modifier.padding(vertical = 20.dp)) {
            VersionSection(full.versions, selection.selected(full), onSelect = { v -> selection.pick(null, v); onPicked(v) },
                onPreview = { v -> selection.preview(null, v) },
                onPlay = { card -> val v = full.merged.of(card); playVersion(v, v.item.resumeMs, full.versions) },
                entry = accordion.entry(SECTION_VERSIONS), hold = holdHint(accordion.order, SECTION_VERSIONS))
        }
    }
}

/** 音轨与字幕 of the previewed version, else the selected one. */
@Composable
private fun DetailTracks(vm: MovieDetailViewModel, selection: EpisodeSelection, accordion: Accordion, tracksFor: (Version?) -> TrackChoice, onChoice: (Version, TrackChoice) -> Unit) {
    TraceRecomposition("Detail.tracks")
    val view by vm.view.collectAsStateWithLifecycle()
    val v = selection.trackVersion(view) ?: return
    val full = rememberSettled(view, accordion.isExpanded(SECTION_TRACKS))
    CollapsibleSection(accordion, SECTION_TRACKS, "音轨与字幕", "${v.source.subtitleStreams.size} 条字幕", barHeight = 112.dp, summary = {
        TrackSummary(v, tracksFor(v))
    }) {
        val fv = selection.trackVersion(full) ?: v
        Box(Modifier.padding(vertical = 20.dp)) {
            TrackSection(fv, chapters = fv.item.chapters.size, runtimeTicks = fv.item.runTimeTicks, choice = tracksFor(fv), entry = accordion.entry(SECTION_TRACKS), hold = holdHint(accordion.order, SECTION_TRACKS)) { c ->
                onChoice(fv, c)
            }
        }
    }
}

@Composable
private fun DetailCompactHeader(vm: MovieDetailViewModel, selection: EpisodeSelection, heading: DetailHeadingState, display: DetailDisplay, meta: String) {
    TraceRecomposition("Detail.compactHeader")
    val view by vm.view.collectAsStateWithLifecycle()
    val playRef = selection.selected(view)?.ref
    val resume = playRef?.item?.let { resumeMsOf(it) } ?: 0L
    val ratings by vm.ratings.collectAsStateWithLifecycle()
    CompactHeader(heading, display, meta, if (playRef == null) null else if (resume > 0) "继续播放 · ${formatClock(resume)}" else "播放", ratings)
}

/** Episode provider IDs identify files, never a movie or series recommendation endpoint. */
internal fun detailRecommendationId(item: BaseItemDto, canonical: CatalogId? = null): CatalogId? {
    if (item.type !in setOf("Movie", "Series")) return null
    return canonical?.takeIf { it.source == "tmdb" } ?: item.tmdb()
        ?.takeIf { it.toLongOrNull()?.let { id -> id > 0 } == true }
        ?.let { CatalogId("tmdb", if (item.type == "Movie") "movie" else "tv", it) }
}

internal const val SECTION_EPISODES = "episodes"
internal const val SECTION_COLLECTION = "collection"
internal const val SECTION_VERSIONS = "versions"
internal const val SECTION_TRACKS = "tracks"
internal const val SECTION_CAST = "cast"
internal const val SECTION_RECS = "recs"

/** 「2024 · 科幻 · 冒险 · 2 小时 46 分」. */
internal fun detailMeta(item: BaseItemDto, display: DetailDisplay): String = listOfNotNull(
    if (item.type == "Episode") listOfNotNull(item.seriesName, episodeCode(item)).joinToString(" ") else null,
    display.year?.toString(),
    display.genres.take(2).joinToString(" · ").ifEmpty { null },
    formatDuration(display.runtimeMs),
).joinToString(" · ")

@Composable
private fun DetailHeader(ref: ServerItem, display: DetailDisplay, heading: DetailHeadingState, playItem: BaseItemDto?, watchedRef: ServerItem?, instances: List<ServerItem>, onPlay: (Long) -> Unit, onFocused: () -> Unit, onMarks: () -> Unit, danmuRef: ServerItem?, danmu: DanmuPanelState, availability: String = "暂无资源", onRetry: () -> Unit = {}, playRequester: FocusRequester? = null, onDown: () -> Unit = {}, trailer: app.zmby.trailer.TrailerInfo? = null, fullTrailer: FullTrailerState? = null) {
    val app = LocalContext.current.zmby
    val item = ref.item
    Column(
        Modifier
            .padding(start = 80.dp, top = 140.dp)
            .width(1760.dp)
            .graphicsLayer { alpha = 1f - heading.frame.progress }
            .onFocusChanged { if (it.hasFocus) onFocused() }
            .headerDown(onDown)
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        DetailHeadingSlots(heading)
        if (!display.overview.isNullOrBlank()) {
            Text(display.overview, fontSize = 23.sp, lineHeight = 37.sp, color = Z.textPrimary.copy(alpha = 0.75f), maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(820.dp))
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            val resume = playItem?.let { resumeMsOf(it) } ?: 0L
            GlassButton(
                if (playItem == null) availability else if (resume > 0) "继续播放 · ${formatClock(resume)}" else "播放",
                { if (playItem == null) onRetry() else onPlay(resume) },
                Modifier.focusRequester(rememberInitialFocus(item.id)).then(if (playRequester != null) Modifier.focusRequester(playRequester) else Modifier),
                glyph = Glyph.Play,
                label = "detailPlay:${item.id}",
            )
            if (resume > 0) GlassButton("从头播放", { onPlay(0) })
            // Then the icon buttons in their fixed order (ui-spec §2.15); unavailable ones stay in place, dimmed.
            ActionDivider()
            // 预告片 first, only once found (ui-spec §2.23).
            if (fullTrailer != null) TrailerAction(trailer, fullTrailer)
            WatchedAction(watchedRef, instances)
            val copies = (listOf(ref) + instances).distinctBy { it.key }
            FavoriteAction(ref, copies)
            WatchlistAction(ref, copies)
            // 本地手动标记: 片头结束 and 片尾开始 for skipping.
            MarksAction(ref = danmuRef, onClick = onMarks)
            // 弹幕 (ui-spec §2.19): last, after 片头片尾.
            DanmuAction(danmuRef, danmu)
        }
    }
}

/** "TMDB 8.1" when the community rating comes with a TMDB id, else "评分 8.1". */
internal fun ratingLabel(item: BaseItemDto): String? = item.communityRating?.let { r ->
    val tmdb = item.providerIds.keys.any { it.equals("Tmdb", ignoreCase = true) }
    "${if (tmdb) "TMDB" else "评分"} %.1f".format(r)
}

/**
 * Down from the header goes into the first block's default element (the accordion's blocks below
 * may be collapsed bars, which take no focus).
 */
internal fun Modifier.headerDown(onDown: () -> Unit): Modifier = this
    .onPreviewKeyEvent { e ->
        if (e.key == androidx.compose.ui.input.key.Key.DirectionDown && e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) { onDown(); true } else false
    }
