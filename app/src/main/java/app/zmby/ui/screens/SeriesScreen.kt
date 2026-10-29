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
import app.zmby.ui.theme.detailPageScrollSpec
import app.zmby.ui.theme.defaultScrollSpec
import app.zmby.ui.theme.Mono
import app.zmby.zmby
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import androidx.compose.ui.platform.LocalDensity

private val SERIES_SCRIMS = Scrims(listOf(0f to 0.96f, 0.4f to 0.75f, 0.75f to 0.15f), bottomFrom = 300f / 1080f, bottomSolid = 0.55f)

/** A coordinate belongs to the work, so replacing metadata never replaces the focused row key. */
internal fun episodeSlot(season: Int?, number: Int?, fallback: String): String =
    if (season != null && number != null) "s$season:e$number" else "extra:$fallback"

private data class EpisodeRow(val key: String, val item: BaseItemDto, val image: String?, val canonical: CatalogEpisode? = null, val resources: List<ServerItem> = emptyList())

private suspend fun serverDirectory(app: app.zmby.ZmbyApp, server: String, seriesId: String, seasonId: String? = null,
    load: suspend () -> List<BaseItemDto>): List<BaseItemDto> =
    when (val result = app.fanOut.run(listOf(server), FanOut.SEARCH_TIMEOUT_MS,
        share = listOf("server-directory", seriesId, seasonId), cacheMs = FanOut.RESOURCE_CACHE_MS) { load() }.first()) {
        is Outcome.Ok -> result.value
        is Outcome.Failed -> throw result.error
        is Outcome.Skipped -> throw java.io.IOException("服务器暂时未响应")
    }

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
    var recoverable by remember { mutableStateOf<List<String>>(emptyList()) }
    // No full server metadata is needed for an externally identified series. These requests only
    // obtain resource directories and progress, independently of the provider request.
    LaunchedEffect(server, seriesId, tick, resourceWork?.ref, resourceRetry) {
        if (server == "catalog") return@LaunchedEffect
        anchor = episodeId?.let { app.store.detail(server, it)?.item }
        if (seasonNumber < 0) anchor?.parentIndexNumber?.let { seasonNumber = it }
        // Without a TMDB token a known external ID gives no catalogue directory: list the server's own seasons.
        if (resourceWork == null || !app.catalog.configured) try {
            serverSeasons = serverDirectory(app, server, seriesId) { app.library.seasons(server, seriesId) }
            if (seasonNumber < 0) seasonNumber = serverSeasons.firstOrNull { (it.indexNumber ?: 0) > 0 }?.indexNumber ?: 0
            if (anchor == null) anchor = app.library.nextUp(server, seriesId)
            if (!anchorApplied && currentKey == null) anchor?.let { seasonNumber = it.parentIndexNumber ?: seasonNumber }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            episodeError = "剧集目录暂时无法加载"
            if (FanOut.isTransient(e)) recoverable = (recoverable + server).distinct()
        }
    }
    val seasons = canonical?.seasons ?: serverSeasons.map { CatalogSeason(it.indexNumber ?: 0, it.name.orEmpty(), it.childCount ?: 0) }
    LaunchedEffect(seasons) {
        if (seasons.isNotEmpty() && (seasonNumber < 0 || canonical != null && seasons.none { it.number == seasonNumber }))
            seasonNumber = seasons.firstOrNull { it.number > 0 }?.number ?: seasons.first().number
    }
    LaunchedEffect(server, seasonNumber, serverSeasons, canonical?.ref, resourceRetry, tick) {
        if (server == "catalog" || canonical != null) return@LaunchedEffect
        val season = serverSeasons.firstOrNull { it.indexNumber == seasonNumber } ?: return@LaunchedEffect
        try {
            serverEpisodes = serverDirectory(app, server, seriesId, season.id) {
                app.library.episodes(server, seriesId, season.id).map { it.copy(parentIndexNumber = it.parentIndexNumber ?: season.indexNumber, seriesId = it.seriesId ?: seriesId) }
            }
            episodeError = null; recoverable = recoverable - server
        }
        catch (e: CancellationException) { throw e } catch (e: Exception) {
            episodeError = "剧集目录暂时无法加载"
            if (FanOut.isTransient(e)) recoverable = (recoverable + server).distinct()
        }
    }
    var directory by remember(canonical) { mutableStateOf(SeriesDirectory()) }
    var directoryRetry by remember(canonical) { mutableIntStateOf(resourceRetry) }
    LaunchedEffect(canonical, seasonNumber, resourceRetry) {
        val work = canonical ?: return@LaunchedEffect
        if (seasonNumber < 0) return@LaunchedEffect
        val previous = if (directoryRetry == resourceRetry) directory.pages else emptyMap()
        directoryRetry = resourceRetry
        seriesDirectory(work.seasons.map { it.number }, seasonNumber, previous) { app.catalog.episodes(work, it) }.collect {
            directory = it
            episodeError = if (it.failed.isEmpty()) null else "${it.failed.size} 季目录暂时无法加载"
        }
    }
    val servers by app.registry.servers.collectAsState()
    val sourceRows by app.sources.rows.collectAsState()
    val targets = remember(servers, sourceRows, server) { (app.sources.servers(SourceFeature.Aggregate) + listOfNotNull(server.takeUnless { it == "catalog" })).distinct() }
    val resourceLookup = remember(app) { CatalogResources(app.library, app.fanOut) }
    var batches by remember(resourceWork?.ref) { mutableStateOf<List<ResourceBatch>>(emptyList()) }
    var pending by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(resourceWork?.ref, resourceWork?.ids, targets, tick, resourceRetry, fallback?.key, fallback?.item?.providerIds) {
        val work = resourceWork ?: return@LaunchedEffect
        batches = batches.filter { it.server in targets }; pending = targets.size; failed = emptyList(); recoverable = emptyList()
        resourceLookup.series(targets, work, fallback).collect { result ->
            when (result) {
                is Outcome.Ok -> batches = batches.filter { it.server != result.server } + result.value
                else -> {
                    failed = failed + result.server
                    if (result is Outcome.Skipped || result is Outcome.Failed && FanOut.isTransient(result.error)) recoverable = recoverable + result.server
                }
            }
            pending--
        }
    }
    LaunchedEffect(recoverable, pending, servers) {
        if (pending > 0 || recoverable.isEmpty()) return@LaunchedEffect
        val now = System.currentTimeMillis()
        val next = recoverable.minOf { app.registry.get(it)?.coolingUntil?.takeIf { until -> until > now } ?: (now + FailureTracker.COOL_MS) }
        delay((next - now).coerceAtLeast(0) + 50)
        resourceRetry++
    }
    fun refreshResources() {
        targets.forEach(app.registry::retry)
        resourceWork?.let { resourceLookup.invalidate(targets, it, fallback) }
        app.fanOut.invalidate(listOf("server-directory", seriesId, null), listOf(server))
        serverSeasons.forEach { app.fanOut.invalidate(listOf("server-directory", seriesId, it.id), listOf(server)) }
        resourceRetry++
    }
    val catalogEpisodes = directory.pages[seasonNumber]
    val progress by app.recentProgress.collectAsState()
    val localMappings by remember(canonical?.ref?.key) { app.store.episodeMappings.observe(canonical?.ref?.key.orEmpty()) }.collectAsState(initial = emptyList())
    var identities by remember(canonical?.ref) { mutableStateOf<Map<String, EpisodeIdentity>>(emptyMap()) }
    var identityInputs by remember(canonical?.ref) { mutableStateOf<Map<String, Map<String, String>>>(emptyMap()) }
    var identityRetry by remember(canonical?.ref) { mutableIntStateOf(0) }
    var identityEpoch by remember(canonical?.ref) { mutableStateOf(resourceRetry to identityRetry) }
    LaunchedEffect(canonical?.ref, canonical?.ids, batches, resourceRetry, identityRetry) {
        val work = canonical ?: return@LaunchedEffect
        val refs = withContext(Dispatchers.Default) { batches.filter { it.verified }.flatMap { it.episodes }.distinctBy { it.key } }
        val inputs = withContext(Dispatchers.Default) { refs.associate { it.key to it.item.providerIds } }
        val epoch = resourceRetry to identityRetry
        val previous = if (identityEpoch == epoch) identities.filterKeys { identityInputs[it] == inputs[it] } else emptyMap()
        identityInputs = inputs; identityEpoch = epoch
        episodeIdentities(work, refs, previous) { app.catalog.episodeIdentity(work, it, refresh = resourceRetry > 0 || identityRetry > 0) }
            .collect { identities = it }
    }
    val allResources by produceState<List<ServerItem>>(emptyList(), batches) {
        value = withContext(Dispatchers.Default) { batches.flatMap { it.episodes }.distinctBy { it.key } }
    }
    val allCatalog by produceState<List<CatalogEpisode>>(emptyList(), directory.pages) {
        value = withContext(Dispatchers.Default) { directory.pages.values.flatten() }
    }
    val mapped by produceState(EpisodeResources(emptyMap(), emptyList()), canonical, directory, batches, progress, identities, localMappings) {
        val work = canonical ?: run { value = EpisodeResources(emptyMap(), emptyList()); return@produceState }
        value = withContext(Dispatchers.Default) { ResourceMapping.episodes(work, directory.pages.values.flatten(),
            batches.map { batch -> batch.copy(episodes = batch.episodes.map { it.withPlaybackProgress(progress) }) },
            (work.seasons.map { it.number }.toSet() + directory.seasons) - directory.pages.keys, identities, localMappings) }
    }
    val episodes by produceState<List<EpisodeRow>>(emptyList(), canonical, catalogEpisodes, serverEpisodes, seasonNumber, mapped, progress) {
      value = withContext(Dispatchers.Default) {
        val work = canonical
        val counts=serverEpisodes.groupingBy { it.parentIndexNumber to it.indexNumber }.eachCount()
        if (work != null && catalogEpisodes != null) catalogEpisodes.map { ep ->
            val key = episodeSlot(ep.season, ep.number, ep.id)
            val resources = mapped.matched[ep.id].orEmpty()
            val progress = resources.firstOrNull()?.item?.userData
            EpisodeRow(key, work.item(ep).copy(id = key, overview = ep.overview, userData = progress), ep.image, ep, resources)
        } else serverEpisodes.filter { it.parentIndexNumber == seasonNumber }.map { original ->
            val ep = ServerItem(server, original).withPlaybackProgress(progress).item
            val unique = counts[ep.parentIndexNumber to ep.indexNumber] == 1
            val key = episodeSlot(ep.parentIndexNumber.takeIf { unique }, ep.indexNumber, ep.id)
            EpisodeRow(key, ep, app.library.landscapeImage(ServerItem(server, ep)), resources = listOf(ServerItem(server, ep)))
        }
    }
    }
    LaunchedEffect(episodes, anchor?.id, batches, mapped, directory.pages, allCatalog) {
        if (anchorApplied || currentKey != null) return@LaunchedEffect
        val opened = anchor ?: batches.flatMap { it.episodes }.firstOrNull { it.server == server && it.item.id == episodeId }?.item
        if (opened != null) {
            if (canonical != null) {
                val target = allCatalog.firstOrNull { ep -> mapped.matched[ep.id].orEmpty().any { it.server == server && it.item.id == opened.id } }
                    ?: return@LaunchedEffect
                anchorApplied = true
                seasonNumber = target.season
                currentKey = episodeSlot(target.season, target.number, target.id)
                return@LaunchedEffect
            }
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
        canonical != null -> rememberCatalogVersions(canonical!!, shown?.canonical, if (settled) shown?.resources.orEmpty() else emptyList(), tick + resourceRetry, fallback,
            onRefresh = ::refreshResources)
        shown != null && s != null -> rememberVersions(server, shown.item, s, lookUp = settled, onRefresh = ::refreshResources)
        else -> VersionsState(emptyList(), 0, null)
    }
    var picked by remember(shown?.key) { mutableStateOf<Version?>(null) }
    val selected = picked?.let { p -> versions.all.firstOrNull { it.key == p.key } } ?: versions.default
    var preview by remember(shown?.key) { mutableStateOf<Version?>(null) }
    var picks by rememberRetained("picks", seriesId) { mutableStateOf<Map<String, TrackChoice>>(emptyMap()) }
    fun trackKey(v: Version) = v.key
    fun tracks(v: Version) = picks[trackKey(v)] ?: TrackChoice()
    val scroll = rememberScrollState()
    val rowState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val currentCard = remember { FocusRequester() }
    val playButton = remember { FocusRequester() }
    var headerFocused by remember { mutableStateOf(false) }
    val trackEntry = remember { FocusRequester() }
    var marking by remember { mutableStateOf<ServerItem?>(null) }
    var unsorted by rememberSaveable { mutableStateOf(false) }
    var mappingsOpen by remember { mutableStateOf(false) }
    var mappingSource by remember { mutableStateOf<ServerItem?>(null) }
    if (s == null || canonical == null && !fallbackReady) {
        LoadState(error, onRetry = { vm.refresh(replace = true); resourceRetry++ }, onServers = { nav.go(Route.Servers) }); return
    }
    val ref = fallback ?: ServerItem("catalog", s)
    val display = canonical?.display() ?: app.display(ref)
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
    val accordion = rememberAccordion(SECTION_EPISODES, scroll)
    val density = LocalDensity.current.density
    val cast = remember(canonical?.cast, ref.key, ref.item.people) {
        canonical?.let { catalogCastCards(nav, it.cast) } ?: serverCastCards(app, nav, ref)
    }
    // One whole-series list; changing season must not hide entries or reclassify other seasons.
    val unknown = mapped.unmatched
    if (unsorted && unknown.isEmpty()) unsorted = false
    // 为你推荐 of the series itself, not of the focused episode (ui-spec §2.14).
    val tmdbId = (resourceWork?.ref ?: s.tmdb()?.let { id -> CatalogId("tmdb", "tv", id) })?.takeIf { r -> r.source == "tmdb" }
    val recKey = tmdbId?.let { r -> "series:tmdb:${r.id}" } ?: Keys.work(ref.server, ref.item)
    val recCopies = remember(fallback?.key, batches) {
        (listOfNotNull(fallback) + batches.flatMap { b ->
            b.episodes.filter { b.verified }.mapNotNull { e -> e.item.seriesId }.distinct().map { id -> ServerItem(b.server, BaseItemDto(id = id, type = "Series")) }
        }).distinctBy { c -> c.server }
    }
    val recs = rememberRecommendations(recKey, recCopies, tmdbId, tick).orEmpty()
    val hasVersions = !unsorted && (shown != null || canonical != null || versions.all.isNotEmpty() || versions.searching > 0 || versions.unresponsive.isNotEmpty())
    val trackVersion = (preview ?: selected).takeIf { !unsorted }
    accordion.order = listOfNotNull(
        SECTION_EPISODES,
        SECTION_VERSIONS.takeIf { hasVersions },
        SECTION_TRACKS.takeIf { trackVersion != null },
        SECTION_CAST.takeIf { cast.isNotEmpty() },
        SECTION_RECS.takeIf { recs.isNotEmpty() },
    )
    if (accordion.active !in accordion.order) accordion.active = SECTION_EPISODES
    accordion.header = playButton
    val today = remember { java.time.LocalDate.now().toString() }
    if (mappingsOpen && canonical != null) EpisodeMappingDialog(canonical, allCatalog, allResources, identities, localMappings, seasonNumber, mappingSource,
        onDismiss = { mappingsOpen = false; mappingSource = null })
    BackdropProvider(display.backdrop, SERIES_SCRIMS) {
      Box(Modifier.fillMaxSize()) {
        // Section changes align the context row; inner card focus must not move the whole page.
        @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides detailPageScrollSpec) {
        Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
          @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
          androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides defaultScrollSpec) {
            BackdropLayer(Modifier.fillMaxWidth().height(1080.dp))
            Column(Modifier.fillMaxWidth()) {
                SeriesHeader(ref, display, seasons.size, episodes.size, shown?.item, selected,
                    onPlay = { _, v, start -> v?.let { play(context, it.ref, start, it.source.id, tracks(it), versions.alternatesTo(it)) } },
                    playButton = playButton,
                    initialFocusKey = seriesId, onFocusChanged = { headerFocused = it; if (it) accordion.showHeader() },
                    onMarks = { selected?.let { marking = it.ref } }, availability = availability,
                    onRetry = { versions.retry(); failed.forEach(app.registry::retry); resourceRetry++ },
                    onDown = { accordion.open(SECTION_EPISODES) })
                Spacer(Modifier.height(44.dp))
                val watched = episodes.count { it.item.userData?.played == true }
                // 剧集 never collapses: the season row and the current episode stay in place (ui-spec §2.14).
                DetailBlock(accordion, SECTION_EPISODES) {
                  Column(Modifier.padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
                    if (canonical != null) Row(Modifier.fillMaxWidth().padding(horizontal = 80.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        if (mapped.awaitingIdentity.isNotEmpty()) Text("正在核对 ${mapped.awaitingIdentity.size} 条单集身份…", fontSize = 18.sp, color = Z.textSecondary)
                        val unavailable = identities.values.count { it.state == EpisodeIdentityState.Unavailable }
                        if (unavailable > 0) GlassButton("$unavailable 条身份待确认 · 重试", { identityRetry++ })
                        Spacer(Modifier.weight(1f))
                        GlassButton("季集映射", { mappingSource = null; mappingsOpen = true })
                    }
                    val segments = seasons.map { Segment(it.title) } + listOfNotNull(if (unknown.isNotEmpty()) Segment("未归类", unknown.size.toString()) else null)
                    if (segments.size > 1) SegmentedWell(segments, if (unsorted) seasons.size else seasons.indexOfFirst { it.number == seasonNumber }.coerceAtLeast(0),
                        { i ->
                            anchorApplied = true
                            if (i >= seasons.size) unsorted = true else { unsorted = false; seasonNumber = seasons[i].number; currentKey = null }
                        }, Modifier.padding(start = 80.dp), labelPrefix = "季")
                    episodeError?.let { Text(it, color = Z.warn, modifier = Modifier.padding(horizontal = 80.dp)); GlassButton("重试目录", { resourceRetry++ }, Modifier.padding(start = 80.dp)) }
                    if (episodes.isEmpty() && !unsorted && episodeError == null) {
                        val loading = if (canonical != null) catalogEpisodes == null else serverEpisodes.isEmpty() && serverSeasons.isEmpty()
                        Text(if (loading) "正在加载剧集目录…" else "这一季还没有剧集", fontSize = 21.sp, color = Z.textTertiary, modifier = Modifier.padding(horizontal = 80.dp))
                    }
                    if (unsorted) {
                        UnsortedRow(nav, unknown, mapped.reasons, accordion.entry(SECTION_EPISODES)) { mappingSource = it; mappingsOpen = true }
                    } else {
                    LazyRow(modifier = Modifier.horizontalRow(enter = currentCard.takeIf { shown != null }), state = rowState,
                        contentPadding = PaddingValues(start = 80.dp, end = 120.dp, bottom = 30.dp), horizontalArrangement = Arrangement.spacedBy(30.dp)) {
                        items(episodes, key = { it.key }, contentType = { "episode" }) { row ->
                            val status = episodeStatus(row.resources, row.canonical?.date, today, searching = pending > 0, failed = failed.isNotEmpty())
                            MediaCard16x9(row.image, onClick = {
                                    currentKey = row.key
                                    if(row.key == shown?.key && selected != null) play(context,selected.ref,selected.item.resumeMs,selected.source.id,tracks(selected),versions.alternatesTo(selected))
                                    else row.resources.firstOrNull()?.let { play(context,it,it.item.resumeMs) }
                                }, width = 340.dp,
                                topEnd = status.label,
                                watched = row.item.userData?.played == true, progress = progressFraction(row.item), onFocus = { currentKey = row.key },
                                focusKey = "episode:${row.key}",
                                dim = if (status.dim) 0.4f else 1f,
                                modifier = if (row.key == shown?.key) Modifier.focusRequester(currentCard).focusRequester(accordion.entry(SECTION_EPISODES)) else Modifier) {
                                val number = "第 ${row.item.indexNumber ?: "?"} 集"
                                val name = episodeName(row.item)
                                // Without a name of its own the number takes the title line; the line above stays for alignment.
                                Column { Text(if (name != null) number else "", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Z.textSecondary); Text(name ?: number, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                    }
                    shown?.let { row ->
                        Column(Modifier.padding(start = 80.dp, end = 80.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(listOfNotNull("第 ${row.item.indexNumber ?: "?"} 集", episodeName(row.item)).joinToString(" · "), fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
                            row.item.overview?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 21.sp, lineHeight = 32.sp, color = Z.textSecondary, maxLines = 3, modifier = Modifier.width(1100.dp)) }
                        }
                    }
                    }
                  }
                }
                if (hasVersions) {
                    CollapsibleSection(accordion, SECTION_VERSIONS, "版本", versionCount(versions, selected), summary = {
                        selected?.let { VersionSummary(it, tracks(it)) }
                            ?: SummaryText(if (versions.searching > 0) "正在查找资源…" else "这一集暂无可播放的版本")
                    }) {
                        Box(Modifier.padding(vertical = 20.dp)) {
                            VersionSection(versions, selected, { picked = it }, { preview = it }, { play(context, it.ref, it.item.resumeMs, it.source.id, tracks(it), versions.alternatesTo(it)) }, entry = accordion.entry(SECTION_VERSIONS))
                        }
                    }
                }
                trackVersion?.let { v ->
                    CollapsibleSection(accordion, SECTION_TRACKS, "音轨与字幕", "${v.source.subtitleStreams.size} 条字幕", barHeight = 112.dp, summary = { TrackSummary(v, tracks(v)) }) {
                        Box(Modifier.padding(vertical = 20.dp)) {
                            TrackSection(v, v.item.chapters.size, v.item.runTimeTicks, tracks(v), accordion.entry(SECTION_TRACKS)) { picks = picks + (trackKey(v) to it) }
                        }
                    }
                }
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
                CatalogRelated(nav, canonical, fallback)
                Spacer(Modifier.height(600.dp))
            }
          }
        }
        }
        val compact by remember { derivedStateOf { scroll.value > 300 * density } }
        val playItem = selected?.item
        val resumeMs = playItem?.let { resumeMsOf(it) } ?: 0L
        CompactHeader(
            compact,
            display,
            listOfNotNull(display.year?.toString(), display.genres.take(2).joinToString(" · ").ifEmpty { null }, if (seasons.isNotEmpty()) "${seasons.count { it.number > 0 }.coerceAtLeast(1)} 季" else null).joinToString(" · "),
            if (shown != null && playItem != null) seriesPlayLabel(episodeCode(shown.item), resumeMs, remainingLabel(playItem)) else null,
        )
        marking?.let { SegmentMarksPanel(it.server, it.item, onDismiss = { marking = null }) }
      }
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
    var focusedKey by remember { mutableStateOf(items.firstOrNull()?.key) }
    val focused = items.firstOrNull { it.key == focusedKey } ?: items.firstOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
        LazyRow(Modifier.horizontalRow(), contentPadding = PaddingValues(start = 80.dp, end = 120.dp), horizontalArrangement = Arrangement.spacedBy(30.dp)) {
            items(items, key = { it.key }, contentType = { "unsorted" }) { r ->
                val code = unsortedCode(r.item) ?: reasons[r.key] ?: "没有季集编号"
                MediaCard16x9(app.library.landscapeImage(r), { nav.go(Route.Detail(r.server, r.item.id, resourceOnly = true)) }, width = 340.dp,
                    topStart = app.registry.get(r.server)?.name, onFocus = { focusedKey = r.key }, focusKey = "unsorted:${r.key}",
                    modifier = if (r == items.first()) Modifier.focusRequester(entry) else Modifier) {
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

/** 「第 1 季 第 1 集」 from the server's numbers, or null when it has none. */
internal fun unsortedCode(item: BaseItemDto): String? {
    val e = item.indexNumber ?: return null
    return item.parentIndexNumber?.let { "第 $it 季 第 $e 集" } ?: "第 $e 集"
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
    playButton: FocusRequester,
    initialFocusKey: String?,
    onFocusChanged: (Boolean) -> Unit,
    onMarks: (BaseItemDto) -> Unit = {},
    availability: String? = null,
    onRetry: () -> Unit = {},
    onDown: () -> Unit = {},
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
            .headerDown(onDown)
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
                Modifier.focusRequester(playButton).focusRequester(rememberInitialFocus(initialFocusKey ?: s.id)), glyph = Glyph.Play, label = "seriesPlay:$initialFocusKey")
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
