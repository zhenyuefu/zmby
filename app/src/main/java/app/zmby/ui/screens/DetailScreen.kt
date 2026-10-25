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
import app.zmby.ui.components.scrollToTopFirmly
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
import app.zmby.zidoo.ZidooIntents
import app.zmby.zidoo.ZidooTracks
import app.zmby.zmby
import kotlinx.coroutines.launch
import app.zmby.aggregate.Keys
import app.zmby.data.ServerItem
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.onFocusChanged

/** Detail.dc.html: left→right 0.97 → 0.8 (40 %) → 0.15 (75 %); bottom scrim from 360, solid for its lower half. */
internal val DETAIL_SCRIMS = Scrims(listOf(0f to 0.97f, 0.4f to 0.8f, 0.75f to 0.15f), bottomFrom = 360f / 1080f, bottomSolid = 0.5f)

@Composable
fun DetailScreen(nav: Nav, server: String, itemId: String) {
    val context = LocalContext.current
    val app = context.zmby
    val library = app.library
    val tick = rememberResumeTick()
    // Local first: the cached item, merged work and cast show at once; the refresh updates them in place.
    val vm = screenModel("detail:$server:$itemId") { DetailViewModel(app, server, itemId) }
    LaunchedEffect(tick) { vm.refresh() }
    val cached by vm.detail.collectAsStateWithLifecycle()
    val meta by vm.meta.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val cachedVersions by vm.cachedVersions.collectAsStateWithLifecycle()
    val it = cached?.ref?.item
    if (it == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(error?.let { "加载失败：$it" } ?: "", fontSize = 24.sp, color = Z.textSecondary)
        }
        return
    }
    val ref = ServerItem(server, it)
    val display = remember(ref, meta) { app.display(ref, meta) }
    val versions = rememberVersions(server, it, cached = cachedVersions, forKey = cached?.workKey)
    // OK on a version selects it (the header's play button and the cards below use it); until then
    // the default version (VersionRanker) is selected. While focus is on the version cards, the
    // focused one previews its tracks.
    var picked by remember(it.id) { mutableStateOf<Version?>(null) }
    val selected = picked?.takeIf { p -> versions.all.any { v -> v.server.id == p.server.id && v.source.id == p.source.id } } ?: versions.default
    var preview by remember(it.id) { mutableStateOf<Version?>(null) }
    val shown = preview ?: selected
    // Track picks per version (Emby stream indexes); a version without a pick uses the preferences or the server's defaults.
    var picks by rememberRetained("picks", it.id) { mutableStateOf<Map<String, TrackChoice>>(emptyMap()) }
    fun pickKey(v: Version?) = v?.let { "${it.server.id}:${it.source.id}" }
    fun tracksFor(v: Version?) = picks[pickKey(v)] ?: TrackChoice()
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()

    BackdropProvider(display.backdrop, DETAIL_SCRIMS) {
        // The backdrop scrolls away with the page, as in the design (it is the top 1080 of a taller page).
        Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
            BackdropLayer(Modifier.fillMaxWidth().height(1080.dp))
            Column(Modifier.fillMaxWidth()) {
                // The version that plays: its own item (another server's copy has its own resume point).
                val playRef = selected?.ref ?: ref
                DetailHeader(
                    ref,
                    display,
                    playItem = playRef.item,
                    instances = versions.instances,
                    onPlay = { startMs -> play(context, playRef, startMs, selected?.source?.id, tracksFor(selected)) },
                    // Back on the header: scroll all the way up so the title shows again, not just the buttons.
                    onFocused = { if (scroll.value > 0) scope.launch { scroll.scrollToTopFirmly() } },
                )
                Spacer(Modifier.height(64.dp))
                val trackEntry = remember { FocusRequester() }
                if (versions.all.isNotEmpty() || versions.searching > 0 || versions.unresponsive.isNotEmpty()) {
                    VersionSection(versions, selected, onSelect = { v ->
                        picked = v
                        library.rememberVersion(Keys.work(server, it), v.server.id, v.source.id)
                    }, onPreview = { v -> preview = v }, onPlay = { v ->
                        play(context, v.ref, v.item.resumeMs, v.source.id, tracksFor(v))
                    }, downTarget = trackEntry)
                } else {
                    Text("服务器还没有这部片的版本信息", fontSize = 21.sp, color = Z.textTertiary, modifier = Modifier.padding(horizontal = 80.dp))
                }
                shown?.let { v ->
                    Spacer(Modifier.height(56.dp))
                    TrackSection(v, chapters = v.item.chapters.size, runtimeTicks = v.item.runTimeTicks, choice = tracksFor(v), entry = trackEntry) { c ->
                        picks = picks + (pickKey(v)!! to c)
                    }
                }
                // 演职人员: the page's last block (ui-spec §2.2 4).
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

@Composable
private fun DetailHeader(ref: ServerItem, display: DetailDisplay, playItem: BaseItemDto, instances: List<ServerItem>, onPlay: (Long) -> Unit, onFocused: () -> Unit) {
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
            val resume = resumeMsOf(playItem)
            GlassButton(
                if (resume > 0) "继续播放 · ${formatClock(resume)}" else "播放",
                { onPlay(resume) },
                Modifier.focusRequester(rememberInitialFocus(item.id)),
                glyph = Glyph.Play,
            )
            if (resume > 0) GlassButton("从头播放", { onPlay(0) })
            GlassIconButton(Glyph.Check, label = if (played) "已看（取消）" else "标记为已看", onClick = {
                scope.launch { runCatching { library.setPlayed(ref.server, item.id, !played) }.onSuccess { played = it.played } }
            })
            GlassIconButton(if (favorite) Glyph.Check else Glyph.Plus, label = if (favorite) "已收藏" else "加入收藏", onClick = {
                scope.launch { app.favorites.toggle(ref, (listOf(ref) + instances).distinctBy { it.key }) }
            })
            // 待看 next to 收藏 (favorites spec); white when the work is on the list.
            val watchEntries by app.watchlist.entries.collectAsState()
            val inWatchlist = remember(watchEntries, ref.key) { app.watchlist.contains(ref) }
            GlassIconButton(Glyph.Bookmark, label = if (inWatchlist) "已在待看" else "加入待看", on = inWatchlist, onClick = {
                app.toggleWatchlist(scope, ref, (listOf(ref) + instances).distinctBy { it.key })
            })
        }
    }
}

/** "TMDB 8.1" when the community rating comes with a TMDB id, else "评分 8.1". */
internal fun ratingLabel(item: BaseItemDto): String? = item.communityRating?.let { r ->
    val tmdb = item.providerIds.keys.any { it.equals("Tmdb", ignoreCase = true) }
    "${if (tmdb) "TMDB" else "评分"} %.1f".format(r)
}
