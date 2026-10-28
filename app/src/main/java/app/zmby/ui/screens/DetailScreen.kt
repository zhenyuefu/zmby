package app.zmby.ui.screens

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import app.zmby.ui.components.GlassIconButton
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
import kotlinx.coroutines.launch
import app.zmby.aggregate.Keys
import app.zmby.aggregate.tmdb
import app.zmby.catalog.CatalogId
import app.zmby.data.ServerItem
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.onFocusChanged

/** Detail.dc.html: left→right 0.97 → 0.8 (40 %) → 0.15 (75 %); bottom scrim from 360, solid for its lower half. */
internal val DETAIL_SCRIMS = Scrims(listOf(0f to 0.97f, 0.4f to 0.8f, 0.75f to 0.15f), bottomFrom = 360f / 1080f, bottomSolid = 0.5f)

@Composable
fun DetailScreen(nav: Nav, server: String, itemId: String, resourceOnly: Boolean = false, provider: app.zmby.catalog.CatalogId? = null) {
    val context = LocalContext.current
    val app = context.zmby
    val library = app.library
    val tick = rememberResumeTick()
    // Local first: the cached item shows at once; canonical metadata replaces its fields in place.
    val vm = screenModel("detail:$server:$itemId") { DetailViewModel(app, server, itemId, provider, resourceOnly) }
    val languages by app.settings.metadataLanguage.flow.collectAsState()
    val posters by app.settings.posterLanguage.flow.collectAsState()
    val logos by app.settings.logoLanguage.flow.collectAsState()
    val token by app.settings.tmdbToken.flow.collectAsState()
    LaunchedEffect(tick, languages, posters, logos, token) { vm.refresh(replace = true) }
    val providerWork by vm.catalog.collectAsStateWithLifecycle()
    val canonical = providerWork?.takeIf { vm.catalogLanguageKey == listOf(languages,posters,logos,token) }
    val lookup by vm.lookup.collectAsStateWithLifecycle()
    val fallbackReady by vm.fallbackReady.collectAsStateWithLifecycle()
    val cached by vm.detail.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val cachedVersions by vm.cachedVersions.collectAsStateWithLifecycle()
    val metadataItem = cached?.ref?.item ?: canonical?.item()
    val lookupWork = canonical ?: lookup
    // Resource lookup starts as soon as the external identity is known, while metadata is loading.
    // The key DetailScreen saves a hand pick under (OK on a version card, below).
    val manualPick = remember(server, metadataItem?.id) { metadataItem?.let { item -> app.library.pickedVersion(Keys.work(server, item)) } }
    val versions = when {
        lookupWork != null -> rememberCatalogVersions(lookupWork, null, emptyList(), tick, cached?.ref, manual = manualPick)
        metadataItem != null && server != "catalog" -> rememberVersions(server, metadataItem, cached = cachedVersions, forKey = cached?.workKey,
            onRefresh = { vm.refresh(replace = true) })
        else -> VersionsState(emptyList(),0,null)
    }
    val it = metadataItem
    if (it == null || canonical == null && !fallbackReady) {
        LoadState(error, onRetry = { if(server != "catalog") app.registry.retry(server); vm.refresh() }, onServers = { nav.go(Route.Servers) })
        return
    }
    val ref = cached?.ref ?: ServerItem("catalog",it)
    val display = canonical?.display() ?: app.display(ref)
    // OK on a version selects it (the header's play button and the cards below use it); until then
    // the default version (VersionRanker) is selected. While focus is on the version cards, the
    // focused one previews its tracks.
    var picked by remember(it.id) { mutableStateOf<Version?>(null) }
    val selected = picked?.let { p -> versions.all.firstOrNull { v -> v.key == p.key } } ?: versions.default
    var preview by remember(it.id) { mutableStateOf<Version?>(null) }
    val shown = preview ?: selected
    // Track picks per version (Emby stream indexes); a version without a pick uses the preferences or the server's defaults.
    var picks by rememberRetained("picks", it.id) { mutableStateOf<Map<String, TrackChoice>>(emptyMap()) }
    fun pickKey(v: Version?) = v?.key
    fun tracksFor(v: Version?) = picks[pickKey(v)] ?: TrackChoice()
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val accordion = rememberAccordion(SECTION_VERSIONS, scroll)
    val density = androidx.compose.ui.platform.LocalDensity.current.density

    var marking by remember { mutableStateOf<ServerItem?>(null) }
    val cast = remember(canonical?.cast, ref.key, ref.item.people) {
        if (canonical != null) catalogCastCards(nav, canonical.cast) else serverCastCards(app, nav, ref)
    }
    val hasVersions = it.type in setOf("Movie", "Video", "Episode") || lookupWork != null || versions.all.isNotEmpty() || versions.searching > 0 || versions.unresponsive.isNotEmpty()
    // 为你推荐 (ui-spec §2.14): keyed by the work's external identity when it has one.
    val tmdbId = (lookupWork?.ref ?: it.tmdb()?.let { id -> CatalogId("tmdb", "movie", id) })?.takeIf { r -> r.source == "tmdb" }
    val recKey = tmdbId?.let { r -> "movie:tmdb:${r.id}" } ?: Keys.work(ref.server, ref.item)
    val recCopies = remember(cached?.ref?.key, versions.instances.map { c -> c.key }) { (listOfNotNull(cached?.ref) + versions.instances).distinctBy { c -> c.key } }
    val recs = rememberRecommendations(recKey, recCopies, tmdbId, tick).orEmpty()
    // The blocks in page order; only those with something to show (ui-spec §2.14).
    accordion.order = listOfNotNull(
        SECTION_VERSIONS.takeIf { hasVersions },
        SECTION_TRACKS.takeIf { shown != null },
        SECTION_CAST.takeIf { cast.isNotEmpty() },
        SECTION_RECS.takeIf { recs.isNotEmpty() },
    )
    if (accordion.active !in accordion.order && accordion.order.isNotEmpty()) accordion.active = accordion.order.first()
    val headerPlay = remember { FocusRequester() }
    accordion.header = headerPlay
    val playRef = selected?.ref
    val resume = playRef?.item?.let { resumeMsOf(it) } ?: 0L
    BackdropProvider(display.backdrop, DETAIL_SCRIMS) {
      Box(Modifier.fillMaxSize()) {
        // The backdrop scrolls away with the page, as in the design (it is the top 1080 of a taller page).
        // Section changes align the context row; inner card focus must not move the whole page.
        @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides detailPageScrollSpec) {
        Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
          @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
          androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides defaultScrollSpec) {
            BackdropLayer(Modifier.fillMaxWidth().height(1080.dp))
            Column(Modifier.fillMaxWidth()) {
                // The version that plays: its own item (another server's copy has its own resume point).
                DetailHeader(
                    ref,
                    display,
                    playItem = playRef?.item,
                    instances = versions.instances,
                    onPlay = { startMs -> playRef?.let { play(context, it, startMs, selected?.source?.id, tracksFor(selected), versions.alternatesTo(selected)) } },
                    // Back on the header: scroll all the way up so the title shows again, not just the buttons.
                    onFocused = { accordion.showHeader() },
                    onMarks = { marking = playRef },
                    availability = if (versions.searching > 0) "正在查找资源…" else if (versions.unresponsive.isNotEmpty()) "资源状态待确认 · 重试" else "暂无资源",
                    onRetry = versions.retry,
                    playRequester = headerPlay,
                    onDown = { accordion.order.firstOrNull()?.let(accordion::open) },
                )
                Spacer(Modifier.height(44.dp))
                if (hasVersions) {
                    CollapsibleSection(accordion, SECTION_VERSIONS, "版本", versionCount(versions, selected), summary = {
                        SummaryText(selected?.let(::versionSummary) ?: if (versions.searching > 0) "正在查找资源…" else "暂无可播放的版本")
                    }) {
                        Box(Modifier.padding(vertical = 20.dp)) {
                            VersionSection(versions, selected, onSelect = { v ->
                                picked = v
                                library.rememberVersion(Keys.work(server, it), v.server.id, v.source.id)
                            }, onPreview = { v -> preview = v }, onPlay = { v ->
                                play(context, v.ref, v.item.resumeMs, v.source.id, tracksFor(v), versions.alternatesTo(v))
                            }, entry = accordion.entry(SECTION_VERSIONS))
                        }
                    }
                } else {
                    Text("暂无资源", fontSize = 21.sp, color = Z.textTertiary, modifier = Modifier.padding(horizontal = 80.dp))
                }
                shown?.let { v ->
                    CollapsibleSection(accordion, SECTION_TRACKS, "音轨与字幕", "${v.source.subtitleStreams.size} 条字幕", barHeight = 112.dp, summary = {
                        TrackSummary(v, tracksFor(v))
                    }) {
                        Box(Modifier.padding(vertical = 20.dp)) {
                            TrackSection(v, chapters = v.item.chapters.size, runtimeTicks = v.item.runTimeTicks, choice = tracksFor(v), entry = accordion.entry(SECTION_TRACKS)) { c ->
                                picks = picks + (pickKey(v)!! to c)
                            }
                        }
                    }
                }
                // 演职人员 (ui-spec §2.2 4), then 为你推荐 (§2.14).
                if (cast.isNotEmpty()) {
                    DetailBlock(accordion, SECTION_CAST) {
                        CastBlock(cast, accordion.entry(SECTION_CAST))
                    }
                }
                if (recs.isNotEmpty()) {
                    DetailBlock(accordion, SECTION_RECS) {
                        RecommendationsBlock(nav, recs, accordion.entry(SECTION_RECS))
                    }
                }
                CatalogRelated(nav, canonical, cached?.ref)
                Spacer(Modifier.height(600.dp))
            }
          }
        }
        }
        val compact by remember { androidx.compose.runtime.derivedStateOf { scroll.value > 300 * density } }
        CompactHeader(
            compact,
            display,
            detailMeta(it, display),
            if (playRef == null) null else if (resume > 0) "继续播放 · ${formatClock(resume)}" else "播放",
        )
        marking?.let { m -> SegmentMarksPanel(m.server, m.item, onDismiss = { marking = null }) }
      }
    }
}

internal const val SECTION_EPISODES = "episodes"
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
private fun DetailHeader(ref: ServerItem, display: DetailDisplay, playItem: BaseItemDto?, instances: List<ServerItem>, onPlay: (Long) -> Unit, onFocused: () -> Unit, onMarks: () -> Unit, availability: String = "暂无资源", onRetry: () -> Unit = {}, playRequester: FocusRequester? = null, onDown: () -> Unit = {}) {
    val app = LocalContext.current.zmby
    val library = app.library
    val item = ref.item
    val scope = rememberCoroutineScope()
    var played by remember(item.id, item.userData) { mutableStateOf(item.userData?.played == true) }
    val favorites by app.favorites.items.collectAsState()
    val favorite = remember(favorites, ref.key) { app.favorites.isFavorite(ref) }
    val isEpisode = item.type == "Episode"
    Column(
        Modifier
            .padding(start = 80.dp, top = 140.dp)
            .width(900.dp)
            .onFocusChanged { if (it.hasFocus) onFocused() }
            .headerDown(onDown)
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        TitleLogo(display.logo, display.title, maxWidth = 520.dp, maxHeight = 150.dp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val meta = listOfNotNull(
                if (isEpisode) listOfNotNull(item.seriesName, episodeCode(item)).joinToString(" ") else null,
                display.year?.toString(),
                display.genres.take(2).joinToString(" · ").ifEmpty { null },
                formatDuration(display.runtimeMs),
            )
            Text(meta.joinToString("  ·  "), fontSize = 22.sp, color = Z.textPrimary.copy(alpha = 0.78f), maxLines = 1)
            display.rating?.let { r -> Badge("${if (display.ratingTmdb) "TMDB" else "评分"} %.1f".format(r), fill = true) }
        }
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
            if (ref.server != "catalog") GlassIconButton(Glyph.Check, label = if (played) "已看（取消）" else "标记为已看", onClick = {
                scope.launch { runCatching { library.setPlayed(ref.server, item.id, !played) }.onSuccess { played = it.played } }
            })
            if (ref.server != "catalog") GlassIconButton(if (favorite) Glyph.Check else Glyph.Plus, label = if (favorite) "已收藏" else "加入收藏", onClick = {
                scope.launch { app.favorites.toggle(ref, (listOf(ref) + instances).distinctBy { it.key }) }
            })
            // 待看 next to 收藏 (favorites spec); white when the work is on the list.
            val watchEntries by app.watchlist.entries.collectAsState()
            val inWatchlist = remember(watchEntries, ref.key) { app.watchlist.contains(ref) }
            if (ref.server != "catalog") GlassIconButton(Glyph.Bookmark, label = if (inWatchlist) "已在待看" else "加入待看", on = inWatchlist, onClick = {
                app.toggleWatchlist(scope, ref, (listOf(ref) + instances).distinctBy { it.key })
            })
            // 本地手动标记: 片头结束 and 片尾开始 for skipping.
            if (playItem != null) GlassIconButton(Glyph.Marks, label = "片头片尾", onClick = onMarks)
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
