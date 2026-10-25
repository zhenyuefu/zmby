package app.zmby.ui.screens

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.zmby.aggregate.Keys
import app.zmby.aggregate.ServerVersions
import app.zmby.store.StoreKeys
import androidx.compose.runtime.produceState
import app.zmby.data.ServerItem
import app.zmby.emby.BaseItemDto
import app.zmby.media.episodeCode
import app.zmby.media.progressFraction
import app.zmby.media.remainingLabel
import app.zmby.playback.TrackChoice
import app.zmby.ui.Nav
import app.zmby.ui.components.BackdropLayer
import app.zmby.ui.components.BackdropProvider
import app.zmby.ui.components.Badge
import app.zmby.ui.components.GlassButton
import app.zmby.ui.components.GlassIconButton
import app.zmby.ui.components.Glyph
import app.zmby.ui.components.MediaCard16x9
import app.zmby.ui.components.Scrims
import app.zmby.ui.components.Segment
import app.zmby.ui.components.SegmentedWell
import app.zmby.ui.components.TitleLogo
import app.zmby.ui.components.horizontalRow
import app.zmby.ui.components.scrollToTopFirmly
import app.zmby.ui.play
import app.zmby.ui.rememberInitialFocus
import app.zmby.ui.rememberResumeTick
import app.zmby.ui.rememberRetained
import app.zmby.ui.resumeMsOf
import app.zmby.ui.screenModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zmby.ui.theme.Z
import app.zmby.zmby
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Series.dc.html: left→right 0.96 → 0.75 (40 %) → 0.15 (75 %); bottom scrim from 300, solid for its lower 55 %. */
private val SERIES_SCRIMS = Scrims(listOf(0f to 0.96f, 0.4f to 0.75f, 0.75f to 0.15f), bottomFrom = 300f / 1080f, bottomSolid = 0.55f)

/**
 * The series page, built like the movie page (ui-spec §2.3): header, seasons, episodes, then the
 * focused episode's versions (this server and the others) and tracks. Episodes have no page of
 * their own; opened for an episode ([episodeId]) the page starts on its season with it as the
 * episode the header plays and the row scrolled to it.
 */
@Composable
fun SeriesScreen(nav: Nav, server: String, seriesId: String, episodeId: String? = null) {
    val context = LocalContext.current
    val app = context.zmby
    val library = app.library
    val store = app.store
    val tick = rememberResumeTick()
    // The series item and its merged work come from the store first (local-data spec), then refresh.
    val vm = screenModel("series:$server:$seriesId") { DetailViewModel(app, server, seriesId) }
    LaunchedEffect(tick) { vm.refresh() }
    val cachedSeries by vm.detail.collectAsStateWithLifecycle()
    val seriesMeta by vm.meta.collectAsStateWithLifecycle()
    val series = cachedSeries?.ref?.item
    var seasons by rememberRetained("seasons") { mutableStateOf<List<BaseItemDto>>(emptyList()) }
    var anchor by rememberRetained("anchor") { mutableStateOf<BaseItemDto?>(null) }
    var seasonIndex by rememberRetained("seasonIndex") { mutableIntStateOf(-1) }
    var episodes by rememberRetained("episodes") { mutableStateOf<List<BaseItemDto>>(emptyList()) }
    // The episode the page is on (ui-spec §2.3): the last episode card that had focus. It stays when
    // focus moves on to the versions or tracks, so the header and the cards below keep matching.
    var currentId by rememberRetained("currentEpisode") { mutableStateOf<String?>(null) }
    var headerFocused by remember { mutableStateOf(false) }

    LaunchedEffect(server, seriesId, tick) {
        val seasonsKey = "seasons:$server:$seriesId"
        fun pickSeason() {
            if (seasonIndex < 0 && seasons.isNotEmpty()) {
                seasonIndex = seasons.indexOfFirst { it.id == anchor?.seasonId || (it.indexNumber != null && it.indexNumber == anchor?.parentIndexNumber) }.coerceAtLeast(0)
            }
        }
        // What the store has: seasons and the episode opened for (offline, or before the servers answer).
        if (seasons.isEmpty()) store.list(seasonsKey)?.items?.map { it.item }?.let { seasons = it }
        if (anchor == null && episodeId != null) anchor = store.detail(server, episodeId)?.item
        if (anchor != null) pickSeason()
        runCatching {
            seasons = library.seasons(server, seriesId).also { store.putList(seasonsKey, server, it) }
            // The episode the page is about: the one it was opened for, else the next one to watch. Back
            // from the player it is the next one to watch (the episode opened for may be finished now).
            anchor = episodeId?.takeIf { tick == 0 }?.let { id -> runCatching { library.item(server, id) }.getOrNull() }
                ?: library.nextUp(server, seriesId)
            pickSeason()
        }.onFailure { pickSeason() }
    }
    val season = seasons.getOrNull(seasonIndex)
    var seenTick by remember { mutableIntStateOf(tick) }
    LaunchedEffect(season?.id, tick) {
        val key = season?.let { "episodes:$server:${it.id}" }
        if (key != null) store.list(key)?.items?.map { it.item }?.takeIf { it.isNotEmpty() }?.let { episodes = it }
        season?.let { runCatching { library.episodes(server, seriesId, it.id) }.getOrNull() }?.let { fresh ->
            episodes = fresh
            store.putList(key!!, server, fresh)
        } ?: run { if (season == null) episodes = emptyList() }
        // Back from the player with focus on the header (it was played from there) and that episode
        // now watched: the header moves on to the next one instead of offering it again.
        if (tick != seenTick) {
            if (headerFocused && episodes.firstOrNull { it.id == currentId }?.userData?.played == true) currentId = null
            seenTick = tick
        }
    }

    val currentCard = remember { FocusRequester() }
    val playButton = remember { FocusRequester() }
    val s = series ?: return
    val shown = currentEpisode(episodes, currentId, anchor)
    var picks by rememberRetained("picks", seriesId) { mutableStateOf<Map<String, TrackChoice>>(emptyMap()) }
    // The version selected for the shown episode (its default until OK picks another), for the header.
    var selection by remember { mutableStateOf<Pair<String, Version?>?>(null) }
    val selected = selection?.takeIf { it.first == shown?.id }?.second
    fun tracksFor(v: Version?) = v?.let { picks["${it.server.id}:${it.source.id}"] } ?: TrackChoice()
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()

    val display = remember(s, seriesMeta) { app.display(ServerItem(server, s), seriesMeta) }
    BackdropProvider(display.backdrop, SERIES_SCRIMS) {
        Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
            BackdropLayer(Modifier.fillMaxWidth().height(1080.dp))
            Column(Modifier.fillMaxWidth()) {
                SeriesHeader(
                    ServerItem(server, s),
                    display,
                    seasons.size,
                    episodes.size,
                    // Before the season's episodes arrive: the anchor (its season may not be loaded yet).
                    current = shown ?: anchor,
                    selected = selected,
                    onPlay = { ep, v, startMs ->
                        // The selected version plays its own item (another server's copy has its own resume point).
                        play(context, v?.ref ?: ServerItem(server, ep), startMs, v?.source?.id, tracksFor(v))
                    },
                    currentCard = currentCard.takeIf { shown != null },
                    playButton = playButton,
                    initialFocusKey = anchor?.id,
                    onFocusChanged = { focused ->
                        headerFocused = focused
                        // Back on the header: scroll all the way up so the title shows again.
                        if (focused && scroll.value > 0) scope.launch { scroll.scrollToTopFirmly() }
                    },
                )

                Column(Modifier.padding(top = 56.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
                    if (seasons.size > 1) {
                        SegmentedWell(
                            items = seasons.map { Segment(it.name ?: "第 ${it.indexNumber} 季") },
                            selected = seasonIndex,
                            onSelect = { seasonIndex = it },
                            modifier = Modifier.padding(start = 80.dp),
                            labelPrefix = "季",
                        )
                    }
                    val rowState = rememberLazyListState()
                    LaunchedEffect(episodes, anchor?.id) {
                        val i = episodes.indexOfFirst { it.id == anchor?.id }
                        if (i > 0) rowState.scrollToItem((i - 1).coerceAtLeast(0))
                    }
                    LazyRow(
                        // Up from an episode goes to the button that now plays it (seasons in between take their
                        // turn); coming back into the row lands on the current episode, not the nearest card,
                        // so passing through the row on the way up does not change the episode.
                        modifier = Modifier.horizontalRow(
                            up = playButton.takeIf { seasons.size <= 1 && shown != null },
                            enter = currentCard.takeIf { shown != null },
                        ),
                        state = rowState,
                        contentPadding = PaddingValues(start = 80.dp, end = 120.dp, bottom = 30.dp),
                        horizontalArrangement = Arrangement.spacedBy(30.dp),
                    ) {
                        items(episodes, key = { it.id }, contentType = { "episode" }) { ep ->
                            val versions = ep.mediaSources.size
                            MediaCard16x9(
                                imageUrl = library.landscapeImage(ServerItem(server, ep)),
                                onClick = { play(context, ServerItem(server, ep), ep.resumeMs) },
                                width = 340.dp,
                                topEnd = if (versions > 0) "$versions 个版本" else null,
                                watched = ep.userData?.played == true,
                                progress = if (ep.userData?.played == true) 0f else progressFraction(ep),
                                onFocus = { currentId = ep.id },
                                focusKey = "episode:${ep.id}",
                                modifier = if (ep.id == shown?.id) Modifier.focusRequester(currentCard) else Modifier,
                            ) {
                                Column {
                                    Text("第 ${ep.indexNumber ?: "?"} 集", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Z.textPrimary.copy(alpha = 0.62f))
                                    Text(ep.name.orEmpty(), fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                        }
                    }
                }

                val trackEntry = remember { FocusRequester() }
                shown?.let { ep ->
                    EpisodeVersions(server, s, cachedSeries?.workKey, ep, picks, trackEntry, onSelected = { v -> selection = ep.id to v }) { k, c -> picks = picks + (k to c) }
                }
                // 演职人员 of the series (not the episode's guests); it does not follow the focused episode.
                val cast by vm.cast.collectAsStateWithLifecycle()
                if (cast.isNotEmpty()) {
                    Spacer(Modifier.height(56.dp))
                    CastRow(nav, server, cast, up = trackEntry.takeIf { shown != null }, entry = remember { FocusRequester() })
                }
                Spacer(Modifier.height(80.dp))
            }
        }
    }
}

/** The shown episode's title, versions across servers and tracks; the lookup waits until focus rests on it. */
@Composable
private fun EpisodeVersions(
    server: String,
    series: BaseItemDto,
    seriesKey: String?,
    ep: BaseItemDto,
    picks: Map<String, TrackChoice>,
    trackEntry: FocusRequester,
    onSelected: (Version?) -> Unit,
    onPick: (String, TrackChoice) -> Unit,
) {
    val context = LocalContext.current
    val library = context.zmby.library
    Column(Modifier.padding(start = 80.dp, end = 80.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val head = listOfNotNull(ep.indexNumber?.let { "第 $it 集" }, ep.name?.takeIf { it.isNotBlank() }).joinToString(" · ")
        Text(head, fontSize = 36.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        ep.overview?.takeIf { it.isNotBlank() }?.let {
            Text(it, fontSize = 21.sp, lineHeight = 33.sp, color = Z.textPrimary.copy(alpha = 0.75f), maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(1100.dp))
        }
    }
    // Moving along the row should not ask every server about every episode passed on the way.
    var settled by remember(ep.id) { mutableStateOf(false) }
    LaunchedEffect(ep.id) { delay(LOOKUP_DELAY_MS); settled = true }
    // Last visit's cards for this episode (local first), kept under the episode's work key.
    val forKey = remember(ep.id, seriesKey) { StoreKeys.work(server, ep, seriesKey) }
    val cachedVersions by produceState(emptyMap<String, ServerVersions.Found>(), forKey) { value = DetailViewModel.cachedVersions(context.zmby, forKey) }
    val versions = rememberVersions(server, ep, series, lookUp = settled, cached = cachedVersions, forKey = forKey)
    var picked by remember(ep.id) { mutableStateOf<Version?>(null) }
    val selected = picked?.takeIf { p -> versions.all.any { v -> v.server.id == p.server.id && v.source.id == p.source.id } } ?: versions.default
    LaunchedEffect(ep.id, selected) { onSelected(selected) }
    var preview by remember(ep.id) { mutableStateOf<Version?>(null) }
    fun key(v: Version) = "${v.server.id}:${v.source.id}"
    Spacer(Modifier.height(48.dp))
    if (versions.all.isNotEmpty() || versions.searching > 0 || versions.unresponsive.isNotEmpty()) {
        VersionSection(versions, selected, onSelect = { v ->
            picked = v
            library.rememberVersion(Keys.work(server, series) + episodeSuffix(ep), v.server.id, v.source.id)
        }, onPreview = { preview = it }, onPlay = { v ->
            play(context, v.ref, v.item.resumeMs, v.source.id, picks[key(v)] ?: TrackChoice())
        }, downTarget = trackEntry)
    }
    (preview ?: selected)?.let { v ->
        Spacer(Modifier.height(56.dp))
        TrackSection(v, chapters = v.item.chapters.size, runtimeTicks = v.item.runTimeTicks, choice = picks[key(v)] ?: TrackChoice(), entry = trackEntry) { c -> onPick(key(v), c) }
    }
}

private const val LOOKUP_DELAY_MS = 600L

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
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            if (current != null) {
                // Progress comes from the copy that plays: another server keeps its own resume point.
                val playItem = selected?.item ?: current
                val resumeMs = resumeMsOf(playItem)
                val label = seriesPlayLabel(episodeCode(current), resumeMs, remainingLabel(playItem))
                GlassButton(
                    label,
                    { onPlay(current, selected, resumeMs) },
                    Modifier
                        .focusRequester(playButton)
                        // Keyed on the anchor: changing episodes must not count as a new screen.
                        .focusRequester(rememberInitialFocus(initialFocusKey ?: current.id))
                        // Down goes to the episode this button plays, not the nearest card.
                        .focusProperties { if (currentCard != null) down = currentCard },
                    glyph = Glyph.Play,
                )
                if (resumeMs > 0) GlassButton("从头播放", { onPlay(current, selected, 0) })
            }
            GlassIconButton(
                if (favorite) Glyph.Check else Glyph.Plus,
                label = if (favorite) "已收藏" else "加入收藏",
                modifier = if (current == null) Modifier.focusRequester(rememberInitialFocus(s.id)) else Modifier,
                onClick = { scope.launch { app.favorites.toggle(ref, listOf(ref)) } },
            )
            // 待看 next to 收藏 (favorites spec); white when the work is on the list.
            val watchEntries by app.watchlist.entries.collectAsState()
            val inWatchlist = remember(watchEntries, ref.key) { app.watchlist.contains(ref) }
            GlassIconButton(Glyph.Bookmark, label = if (inWatchlist) "已在待看" else "加入待看", on = inWatchlist, onClick = {
                app.toggleWatchlist(scope, ref, emptyList())
            })
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
