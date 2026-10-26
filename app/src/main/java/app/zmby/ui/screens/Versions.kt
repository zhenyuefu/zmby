package app.zmby.ui.screens

import app.zmby.data.SourceFeature
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
import app.zmby.ui.components.secondaryText
import app.zmby.ui.play
import app.zmby.ui.rememberInitialFocus
import app.zmby.ui.rememberResumeTick
import app.zmby.ui.rememberRetained
import app.zmby.ui.resumeMsOf
import app.zmby.ui.theme.Mono
import app.zmby.ui.theme.Z
import app.zmby.zidoo.ZidooIntents
import app.zmby.zidoo.ZidooTracks
import app.zmby.zmby
import kotlinx.coroutines.launch
import app.zmby.aggregate.Confidence
import app.zmby.aggregate.Keys
import app.zmby.aggregate.ServerVersions
import app.zmby.aggregate.VersionFacts
import app.zmby.aggregate.VersionPrefs
import app.zmby.aggregate.VersionRanker
import app.zmby.data.ServerItem
import app.zmby.ui.components.Health

/** One playable version: a media source of an item on a server. */
data class Version(val server: ServerStatus, val item: BaseItemDto, val source: MediaSourceInfo) {
    val tier: QualityTier get() = qualityTier(source)
    val ref: ServerItem get() = ServerItem(server.id, item)
}

/** One server's card in 版本: only servers that have a playable version get one. */
data class VersionGroup(
    val server: ServerStatus,
    val versions: List<Version>,
    /** 「当前服务器」 or the match reason. */
    val note: String,
)

/**
 * The versions of the work on screen, across servers, as they come in. Servers that failed, timed
 * out or are paused get no card ([unresponsive], retried with [retry]); servers without the work
 * or the episode simply have none.
 */
class VersionsState(
    val groups: List<VersionGroup>,
    val searching: Int,
    val default: Version?,
    val unresponsive: List<String> = emptyList(),
    val retry: () -> Unit = {},
    /** Every version in default-version order (VersionRanker): what 播放失败自动换源 tries in turn. */
    val ordered: List<Version> = emptyList(),
) {
    val all: List<Version> get() = groups.flatMap { it.versions }

    /** The versions to fall back on when [chosen] (null: the default) fails to start. */
    fun alternatesTo(chosen: Version?): List<app.zmby.playback.Alternate> {
        val first = chosen ?: default
        return ordered.filter { first == null || it.server.id != first.server.id || it.source.id != first.source.id }
            .take(MAX_ALTERNATES)
            .map { app.zmby.playback.Alternate(it.server.id, it.item, it.source.id, "${it.server.name} · ${versionTitle(it.source)}") }
    }

    private companion object { const val MAX_ALTERNATES = 6 }
    /** Items of this work on other servers (for favourites). */
    val instances: List<ServerItem> get() = groups.mapNotNull { g -> g.versions.firstOrNull()?.ref }
}

/**
 * Looks up [item] (an episode with its [series]) on the other aggregating servers and keeps the
 * groups in server priority order, the open server first; results fill in as servers answer.
 */
@Composable
internal fun rememberVersions(
    server: String,
    item: BaseItemDto,
    series: BaseItemDto? = null,
    lookUp: Boolean = true,
    /** Last visit's cards (local first), shown for a server until this lookup answers for it. */
    cached: Map<String, ServerVersions.Found> = emptyMap(),
    /** The work on screen, under which found cards are kept for next time. */
    forKey: String? = null,
): VersionsState {
    val app = LocalContext.current.zmby
    val servers by app.registry.servers.collectAsState()
    var found by remember(server, item.id) { mutableStateOf<Map<String, ServerVersions>>(emptyMap()) }
    var retry by remember(server, item.id) { mutableStateOf(0) }
    val self = servers.firstOrNull { it.id == server }
    // 设置 › 数据来源 › 资源聚合: the servers asked for other versions (the open one is its own card).
    val sourceRows by app.sources.rows.collectAsState()
    val others = remember(servers, server, sourceRows) { app.sources.servers(SourceFeature.Aggregate).filter { it != server } }
    LaunchedEffect(found, servers, lookUp) {
        if (!lookUp) return@LaunchedEffect
        val paused = found.values.filterIsInstance<ServerVersions.Skipped>()
        val deadline = paused.mapNotNull { app.registry.get(it.server)?.coolingUntil }.minOrNull()
            ?: return@LaunchedEffect
        kotlinx.coroutines.delay((deadline - System.currentTimeMillis()).coerceAtLeast(0) + 50)
        val ready = paused.filter { !app.registry.isCooling(it.server) }.map { it.server }.toSet()
        if (ready.isNotEmpty()) {
            found = found.filterKeys { it !in ready }
            retry++
        }
    }
    LaunchedEffect(server, item.id, series?.id, others, lookUp, retry) {
        if (others.isEmpty() || !lookUp) return@LaunchedEffect
        val todo = others.filter { it !in found }
        if (todo.isEmpty()) return@LaunchedEffect
        val flow = when {
            item.type == "Episode" && series != null -> app.versions.episode(todo, item, series)
            item.type == "Movie" || item.type == "Video" -> app.versions.movie(todo, item)
            else -> return@LaunchedEffect
        }
        flow.collect { r ->
            if (r !is ServerVersions.Found) ZLog.d("Versions", "${app.registry.get(r.server)?.name}: $r")
            found = found + (r.server to r)
            if (forKey != null) DetailViewModel.rememberVersions(app, forKey, r)
        }
    }
    val settings = app.settings
    val pattern by settings.versionPattern.flow.collectAsState()
    val quality by settings.quality.flow.collectAsState()
    val nonDisc by settings.preferNonDisc.flow.collectAsState()
    return remember(servers, found, cached, item, pattern, quality, nonDisc, lookUp) {
        val (groups, unresponsive) = versionCards(self, item, servers.filter { it.id in others }, found, cached)
        val prefs = VersionPrefs(
            manual = app.library.pickedVersion(Keys.work(server, if (item.type == "Episode" && series != null) series else item) + episodeSuffix(item)),
            pattern = VersionPrefs.pattern(pattern),
            quality = quality,
            preferNonDisc = nonDisc,
        )
        val all = groups.flatMap { it.versions }
        val facts = all.map { it to VersionFacts(it.server.id, it.server.order, it.server.health != Health.Off, it.server.latencyMs, it.source) }
        val comparator = VersionRanker.comparator(prefs)
        val ordered = facts.sortedWith { a, b -> comparator.compare(a.second, b.second) }.map { it.first }
        val default = ordered.firstOrNull()
        VersionsState(groups, if (lookUp) others.count { it !in found } else 0, default, unresponsive, ordered = ordered, retry = {
            // 「n 台未响应」 pressed: lift their cool-downs and ask those servers again.
            unresponsive.forEach { app.registry.retry(it) }
            found = found.filterKeys { it !in unresponsive }
            retry++
        })
    }
}

/**
 * The version cards (aggregation spec, 只显示有资源的服务器): the open server first when it has
 * sources, then every other server in priority order that found a playable version. Servers without
 * the work or the episode get nothing; failed, timed-out and paused ones get no card either but are
 * returned as unresponsive (「n 台未响应」).
 */
internal fun versionCards(
    self: ServerStatus?,
    item: BaseItemDto,
    others: List<ServerStatus>,
    results: Map<String, ServerVersions>,
    /** Last visit's cards: shown until this lookup answers, and kept when it fails (the page is not cleared). */
    cached: Map<String, ServerVersions.Found> = emptyMap(),
): Pair<List<VersionGroup>, List<String>> {
    val groups = mutableListOf<VersionGroup>()
    if (self != null && item.mediaSources.isNotEmpty()) {
        groups += VersionGroup(self, item.mediaSources.sortedWith(versionOrder).map { Version(self, item, it) }, "当前服务器")
    }
    val unresponsive = mutableListOf<String>()
    fun card(s: ServerStatus, r: ServerVersions.Found) {
        if (r.sources.isEmpty()) return
        val note = r.verdict.reason + if (r.verdict.confidence == Confidence.Possible) " · 可能匹配" else ""
        groups += VersionGroup(s, r.sources.sortedWith(versionOrder).map { Version(s, r.item, it) }, note)
    }
    for (s in others) {
        when (val r = results[s.id]) {
            is ServerVersions.Found -> card(s, r)
            is ServerVersions.Failed, is ServerVersions.Skipped -> {
                unresponsive += s.id
                cached[s.id]?.let { card(s, it) }
            }
            null -> cached[s.id]?.let { card(s, it) }
            is ServerVersions.Missing, is ServerVersions.NotFound -> Unit
        }
    }
    return groups to unresponsive
}

/** The work key of an episode is its series' key plus season and episode. */
internal fun episodeSuffix(item: BaseItemDto): String =
    if (item.type == "Episode") ":s%02d:e%02d".format(item.parentIndexNumber ?: 0, item.indexNumber ?: 0) else ""

/**
 * 版本: quality segments with their counts (empty tiers hidden), then one card per server. OK on a
 * version selects it (✓); OK on the selected one plays it. The cards below show the focused version
 * while focus is on the cards and the selected one otherwise, so its tracks can be reached.
 */
@Composable
internal fun VersionSection(
    state: VersionsState,
    selected: Version?,
    onSelect: (Version) -> Unit,
    /** The focused version while focus is on the cards, null once it leaves them. */
    onPreview: (Version?) -> Unit,
    onPlay: (Version) -> Unit,
    /** Where Down from the cards goes (the first track row), once the cards below show the selected version again. */
    downTarget: FocusRequester? = null,
) {
    val app = LocalContext.current.zmby
    val scope = rememberCoroutineScope()
    val versions = state.all
    val tiers = QualityTier.entries.filter { t -> versions.any { it.tier == t } }
    var tier by remember(state.default?.tier) { mutableStateOf(state.default?.tier ?: tiers.firstOrNull() ?: QualityTier.OTHER) }
    if (tier !in tiers && tiers.isNotEmpty()) tier = tiers.first()
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(Modifier.padding(horizontal = 80.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            RowTitle("版本")
            if (tiers.isNotEmpty()) {
                SegmentedWell(
                    items = tiers.map { t -> Segment(t.label, versions.count { it.tier == t }.toString()) },
                    selected = tiers.indexOf(tier).coerceAtLeast(0),
                    onSelect = { tier = tiers[it] },
                    labelPrefix = "画质",
                )
            }
            if (state.searching > 0) {
                Text("还在查找 ${state.searching} 台", fontSize = 20.sp, color = Z.textTertiary)
            } else if (state.unresponsive.isNotEmpty()) {
                // The servers that could not answer, in one focusable pill: OK asks them again.
                GlassButton("${state.unresponsive.size} 台未响应", state.retry, height = 52.dp, horizontalPadding = 26.dp, fontSize = 20, label = "未响应的服务器 重试")
            }
        }
        // One card per server, in server order; versions on the same server stack inside it.
        val cards = state.groups.mapNotNull { g -> g.copy(versions = g.versions.filter { it.tier == tier }).takeIf { it.versions.isNotEmpty() } }
        LazyRow(
            modifier = Modifier
                .onFocusChanged { if (!it.hasFocus) onPreview(null) }
                .focusProperties {
                    onExit = {
                        when (requestedFocusDirection) {
                            // A row: Left/Right at either end stay put (see horizontalRow).
                            FocusDirection.Left, FocusDirection.Right -> cancelFocusChange()
                            // The cards below change with the version, so the search has to wait for them.
                            FocusDirection.Down -> if (downTarget != null) {
                                cancelFocusChange()
                                onPreview(null)
                                scope.launch {
                                    awaitFrame()
                                    if (runCatching { downTarget.requestFocus() }.getOrDefault(false) != true) ZLog.d("Focus", "no track row to enter")
                                }
                            }
                            else -> Unit
                        }
                    }
                }
                .focusGroup(),
            contentPadding = PaddingValues(horizontal = 80.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.Top,
        ) {
            items(cards, key = { it.server.id }, contentType = { "server" }) { group ->
                ServerVersionCard(
                    group,
                    isDefault = { it == state.default },
                    isSelected = { it == selected },
                    onFocusVersion = { v -> onPreview(v) },
                    onClick = { v -> if (v == selected) onPlay(v) else onSelect(v) },
                    onLine = { lineId -> app.registry.selectLine(group.server.id, lineId) },
                    modifier = Modifier.width(CARD_WIDTH),
                )
            }
        }
    }
}

private val CARD_WIDTH = 570.dp
private val CardShape = RoundedCornerShape(28.dp)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServerVersionCard(
    group: VersionGroup,
    isDefault: (Version) -> Boolean,
    isSelected: (Version) -> Boolean,
    onFocusVersion: (Version) -> Unit,
    onClick: (Version) -> Unit,
    onLine: (String) -> Unit,
    modifier: Modifier,
) {
    val server = group.server
    Column(modifier.groupCard(CardShape).padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ServerCardHead(server, "${group.versions.size} 个版本", group.note)
        // 线路 · 延迟: switching changes the server's line everywhere; timed-out lines stay selectable.
        val lines = server.config.lines
        SegmentedWell(
            items = lines.map { l ->
                val measured = l.id in server.lineLatency
                val ms = server.lineLatency[l.id]
                Segment("${l.name} · ${if (!measured) "未测" else ms?.let { "$it ms" } ?: "超时"}", dim = measured && ms == null)
            },
            selected = lines.indexOfFirst { it.id == server.config.lineId }.coerceAtLeast(0),
            onSelect = { i -> onLine(lines[i].id) },
            selectOnFocus = false,
            equalWidths = true,
            segHeight = 44.dp,
            fontSize = 17,
            horizontalPadding = 12.dp,
            labelPrefix = "线路",
            focusKeyPrefix = "line:${server.id}",
            modifier = Modifier.fillMaxWidth(),
        )
        group.versions.forEach { v ->
            SelectableRow(
                onClick = { onClick(v) },
                tile = true,
                selected = isSelected(v),
                padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
                onFocus = { onFocusVersion(v) },
                label = "${server.name} ${versionTitle(v.source)}",
                focusKey = "version:${server.id}:${v.source.id}",
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(versionTitle(v.source), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (isDefault(v)) Badge("默认", fill = true)
                            if (isDiscImage(v.source)) Badge("原盘 ISO", fill = true)
                        }
                        formatSize(v.source.size)?.let { Text(it, fontSize = 17.sp, color = secondaryText()) }
                    }
                    val badges = versionBadges(v.source)
                    if (badges.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            badges.forEach { Badge(it) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ServerCardHead(server: ServerStatus, trailing: String, note: String? = null) {
    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HealthDot(server.health)
                Text(server.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(trailing, fontSize = 17.sp, color = Z.textSecondary)
        }
        // 匹配理由: why this server's copy is taken for the same work.
        if (note != null) Text(note, fontSize = 16.sp, color = Z.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 24.dp))
    }
}

/** 音轨与字幕: audio, subtitle and file cards for one version (columns 1 : 1 : 1.2). */
@Composable
internal fun TrackSection(v: Version, chapters: Int, runtimeTicks: Long?, choice: TrackChoice, entry: FocusRequester? = null, onChoice: (TrackChoice) -> Unit) {
    val context = LocalContext.current
    // com.zidoo.player gets an external subtitle as a downloaded file, which works for SRT/ASS/SSA only.
    val player = remember { ZidooIntents.installedPlayer(context) }
    val unusable = remember(v.source.id, player) {
        v.source.subtitleStreams.filter { it.isExternal && player?.supportsExternal(it.codec) == false }.map { it.index }.toSet()
    }
    val audio = audioRows(v.source)
    val subs = subtitleRows(v.source)
    val probed = isProbed(v.source)
    // What will play: the pick, else the language preference, else the server's default for this
    // user, else the file's default track (the preparer applies the same TrackPreference).
    val settings = LocalContext.current.zmby.settings
    val audioLanguage by settings.audioLanguage.flow.collectAsState()
    val subtitleLanguage by settings.subtitleLanguage.flow.collectAsState()
    val effective = TrackPreference.choose(v.source, choice, audioLanguage, subtitleLanguage)
    val audioOn = effective.audio ?: v.source.defaultAudioStreamIndex ?: audio.firstOrNull { it.tag == "默认" }?.embyIndex ?: audio.firstOrNull()?.embyIndex
    val subOn = (effective.subtitle ?: v.source.defaultSubtitleStreamIndex?.takeIf { it >= 0 } ?: ZidooTracks.SUBTITLE_OFF)
        .takeIf { idx -> idx !in unusable }
    val subRows = if (subs.isEmpty()) subs else listOf(TrackRow(0, "关闭", "", null, ZidooTracks.SUBTITLE_OFF)) + subs
    Column(Modifier.padding(horizontal = 80.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        RowTitle("音轨与字幕")
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).groupCard(CardShape).padding(24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TrackList("音轨", audio, twoLine = true, probed = probed, selected = audioOn, keyPrefix = "audio:${v.source.id}", entry = entry) { r ->
                    onChoice(choice.copy(audio = r.embyIndex))
                }
            }
            Column(Modifier.weight(1f).groupCard(CardShape).padding(24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TrackList("字幕", subRows, twoLine = false, probed = probed, selected = subOn, keyPrefix = "sub:${v.source.id}", unusable = unusable, entry = entry.takeIf { audio.isEmpty() }) { r ->
                    onChoice(choice.copy(subtitle = r.embyIndex))
                }
            }
            FileCard(
                v,
                fileRows(v.source, chapters, runtimeTicks) + KeyValue("服务器", "${v.server.name} · ${v.server.lineName}"),
                Modifier.weight(1.2f).then(if (entry != null && audio.isEmpty() && subRows.isEmpty()) Modifier.focusRequester(entry) else Modifier),
            )
        }
    }
}

/** The file card; focusable only so the remote can scroll down to it. */
@Composable
private fun FileCard(v: Version, rows: List<KeyValue>, modifier: Modifier) {
    Focusable({}, modifier, shape = CardShape, focusedScale = 1f, outline = 3.dp, shadow = 0.dp, label = "文件信息") {
        Column(Modifier.fillMaxWidth().groupCard(CardShape).padding(28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("文件", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            v.source.fileName?.let { Text(it, fontSize = 17.sp, lineHeight = 25.sp, fontFamily = Mono) }
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(row.key, fontSize = 19.sp, color = Z.textTertiary, modifier = Modifier.width(110.dp))
                    Text(row.value, fontSize = 19.sp, lineHeight = 27.sp)
                }
            }
        }
    }
}

/** Audio or subtitle tracks; picking a row decides what the player starts with (audio_idx / subtitle_idx). */
@Composable
private fun TrackList(
    title: String,
    rows: List<TrackRow>,
    twoLine: Boolean,
    probed: Boolean,
    selected: Int?,
    keyPrefix: String,
    /** Emby indexes the player cannot show (shown dimmed, not selectable). */
    unusable: Set<Int> = emptySet(),
    /** Attached to the first row. */
    entry: FocusRequester? = null,
    onPick: (TrackRow) -> Unit,
) {
    Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp))
    if (rows.isEmpty()) {
        Text(if (probed) "无" else "服务器尚未探测", fontSize = 19.sp, color = Z.textTertiary, modifier = Modifier.padding(horizontal = 16.dp))
    }
    rows.forEachIndexed { i, r ->
        val usable = r.embyIndex !in unusable
        SelectableRow(
            { if (usable) onPick(r) },
            modifier = if (i == 0 && entry != null) Modifier.focusRequester(entry) else Modifier,
            selected = r.embyIndex == selected,
            dimmed = !usable,
            padding = PaddingValues(horizontal = 16.dp, vertical = if (twoLine) 12.dp else 10.dp),
            label = "$title ${r.number} ${r.language}",
            focusKey = "$keyPrefix:${r.embyIndex}",
        ) {
            val tag = if (usable) r.tag else "外挂 · 此格式暂不支持"
            val format = listOfNotNull(r.format.ifEmpty { null }, tag).joinToString(" · ")
            if (twoLine) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(r.language, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    if (format.isNotEmpty()) Text(format, fontSize = 17.sp, color = secondaryText(), maxLines = 2)
                }
            } else {
                Text(r.language, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (format.isNotEmpty()) Text(format, fontSize = 17.sp, color = secondaryText(), maxLines = 1)
            }
        }
    }
}
