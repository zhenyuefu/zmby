package app.zmby.ui.screens

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import app.zmby.ui.components.GlassToggle
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import app.zmby.danmu.DanmuMatch
import app.zmby.danmu.DanmuQuery
import app.zmby.danmu.DanmuSelection
import app.zmby.data.ServerItem
import app.zmby.emby.BaseItemDto
import app.zmby.ui.components.ActionIconButton
import app.zmby.ui.components.GlassButton
import app.zmby.ui.components.Glyph
import app.zmby.ui.components.Icon
import app.zmby.ui.components.LocalBackdrop
import app.zmby.ui.components.SelectableRow
import app.zmby.ui.components.secondaryText
import app.zmby.ui.theme.Z
import app.zmby.ui.userMessage
import app.zmby.zmby
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the 弹幕 button shows for the version that plays (ui-spec §2.19). */
internal enum class DanmuStatus { Auto, Manual, Unmatched, Matching, Off }

/** 已关闭 > 匹配中 > 未匹配 > 已手动选择 > 自动匹配 (ui-spec §2.19 修订). */
internal fun danmuStatus(selection: DanmuSelection?, matching: Boolean, enabled: Boolean = true): DanmuStatus = when {
    !enabled -> DanmuStatus.Off
    matching -> DanmuStatus.Matching
    selection == null -> DanmuStatus.Unmatched
    selection.manual -> DanmuStatus.Manual
    else -> DanmuStatus.Auto
}

/** The focus name tag of the 弹幕 button. */
internal fun danmuTip(status: DanmuStatus): String = when (status) {
    DanmuStatus.Auto -> "弹幕 · 自动匹配"
    DanmuStatus.Manual -> "弹幕 · 已手动选择"
    DanmuStatus.Unmatched -> "弹幕 · 未匹配"
    DanmuStatus.Matching -> "弹幕 · 正在匹配…"
    DanmuStatus.Off -> "弹幕 · 已关闭"
}

/** 「第 1 季第 7 集」 beside the panel title; films have none. */
internal fun danmuEpisodeLabel(item: BaseItemDto): String? {
    if (item.type != "Episode") return null
    val episode = item.indexNumber ?: return null
    return listOfNotNull(item.parentIndexNumber?.let { "第 $it 季" }, "第 $episode 集").joinToString("")
}

/** The status card's second line: where the choice came from, and when it applies. */
internal fun danmuStatusSource(status: DanmuStatus, selection: DanmuSelection?): String = when (status) {
    DanmuStatus.Auto -> "按服务器季集识别 · 下次播放生效"
    DanmuStatus.Manual -> if (selection?.season == true) "本季沿用所选来源 · 下次播放生效"
        else "搜索「${selection?.searchTitle.orEmpty()}」后选择 · 下次播放生效"
    DanmuStatus.Unmatched -> "可搜索其他译名后手动选择"
    DanmuStatus.Matching -> "正在向弹幕服务器查询"
    DanmuStatus.Off -> "弹幕已关闭"
}

/** One anime of the search results with its episodes, in the server's order. */
internal data class DanmuGroup(val title: String, val episodes: List<DanmuSelection>)

internal fun danmuGroups(results: List<DanmuSelection>): List<DanmuGroup> =
    results.groupBy { it.target.animeTitle.ifBlank { "其他" } }.map { (title, list) -> DanmuGroup(title, list) }

/** A row's own text: the episode title, else the whole label. */
internal fun danmuRowLabel(selection: DanmuSelection): String =
    selection.target.episodeTitle.ifBlank { selection.target.label }.ifBlank { "分集 ${selection.target.episodeId}" }

/** The 弹幕 button and its side panel on one detail page: which version is open, and focus back. */
@Stable
internal class DanmuPanelState {
    var matchPending by mutableStateOf(false)
    var open by mutableStateOf<ServerItem?>(null)
    /** Bumped when the panel closes, so the button reads the (possibly changed) choice again. */
    var version by mutableIntStateOf(0)
    val button = FocusRequester()
}

@Composable
internal fun rememberDanmuPanel(): DanmuPanelState = remember { DanmuPanelState() }

/**
 * The round 弹幕 button at the end of the header actions (ui-spec §2.19 修订): shown with an address
 * set and a version to play, also while 弹幕 is off so it can be switched back on here. Automatic
 * match is the resting look, a manual choice has the white ring, no match has the amber dot,
 * matching spins; off is the crossed-out bubble at 55 % white.
 */
@Composable
internal fun DanmuAction(ref: ServerItem?, panel: DanmuPanelState) {
    val app = LocalContext.current.zmby
    val enabled by app.settings.danmu.flow.collectAsStateWithLifecycle()
    val address by app.settings.danmuApiBase.flow.collectAsStateWithLifecycle()
    if (address.isBlank()) return
    // No version yet (resources still being looked up): the button holds its place, dimmed, so the
    // header does not shift when one arrives (ui-spec §2.15).
    if (ref == null) {
        ActionIconButton(if (enabled) Glyph.Danmu else Glyph.DanmuOff, "弹幕 · 找到资源后可用", {}, enabled = false,
            solidWhenOn = false, focusKey = "action:danmu")
        return
    }
    var selection by remember(ref.key) { mutableStateOf<DanmuSelection?>(null) }
    var matching by remember(ref.key) { mutableStateOf(true) }
    LaunchedEffect(ref.key, panel.version, address, enabled) {
        // Off needs no match; the panel matches once 弹幕 is switched on there.
        if (!enabled) return@LaunchedEffect
        matching = true
        selection = try {
            app.danmuQueries.query(ref.server, ref.item)?.let { q -> app.danmu.current(app.danmuEndpoint(), ref.key, q, app.danmuQueries::enrich) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        matching = false
    }
    LaunchedEffect(panel.version) {
        // The panel just closed: focus comes back to this button.
        if (panel.version > 0) runCatching { panel.button.requestFocus() }
    }
    val status = danmuStatus(selection, matching, enabled)
    ActionIconButton(
        if (status == DanmuStatus.Off) Glyph.DanmuOff else Glyph.Danmu, danmuTip(status),
        onClick = { panel.matchPending = enabled && matching; panel.open = ref }, Modifier.focusRequester(panel.button),
        on = status == DanmuStatus.Manual, busy = status == DanmuStatus.Matching, solidWhenOn = false,
        focusKey = "action:danmu", dot = status == DanmuStatus.Unmatched,
        allowWhileBusy = true,
        restingGlyphAlpha = if (status == DanmuStatus.Off) 0.55f else 1f,
    )
}

/** Shows the side panel while [DanmuPanelState.open] is set. */
@Composable
internal fun DanmuPanelHost(panel: DanmuPanelState) {
    panel.open?.let { ref -> DanmuPanel(ref, panel.matchPending, onDismiss = { panel.open = null; panel.version++ }) }
}

private const val SEARCH_FAILED = "搜索失败"

/**
 * 弹幕 (DanmuPanel.dc.html): an 820 wide glass side panel sliding in from the right over the page,
 * dimmed 55 %. Status card, a search row prefilled with the title, the results grouped by anime
 * (confirm saves the episode as the manual choice and keeps the panel open), then 恢复自动匹配 and
 * 完成. Back closes it anywhere; focus returns to the button.
 */
@Composable
private fun DanmuPanel(ref: ServerItem, matchPending: Boolean, onDismiss: () -> Unit) {
    val app = LocalContext.current.zmby
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val endpoint = remember { app.danmuEndpoint() }
    val field = remember { FocusRequester() }
    val searchButton = remember { FocusRequester() }
    val chosenRow = remember { FocusRequester() }
    val firstRow = remember { FocusRequester() }
    val footer = remember { FocusRequester() }
    val toggle = remember { FocusRequester() }
    // 显示弹幕 in the panel is the same global setting as 设置 › 播放 › 弹幕 (ui-spec §2.19 修订).
    val enabled by app.settings.danmu.flow.collectAsStateWithLifecycle()
    // Switched on here: the content comes back and searches, but focus stays on the switch.
    var keepFocus by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    var query by remember(ref.key) { mutableStateOf<DanmuQuery?>(null) }
    val resolvedQuery = remember(ref.key) { kotlinx.coroutines.CompletableDeferred<DanmuQuery?>() }
    var current by remember(ref.key) { mutableStateOf<DanmuSelection?>(null) }
    var matching by remember(ref.key) { mutableStateOf(true) }
    var keyword by remember(ref.key) { mutableStateOf(ref.item.seriesName ?: ref.item.name.orEmpty()) }
    var results by remember(ref.key) { mutableStateOf<List<DanmuSelection>>(emptyList()) }
    var searched by remember(ref.key) { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var focusTick by remember { mutableIntStateOf(0) }
    var selectionRevision by remember(ref.key) { mutableIntStateOf(0) }

    var aliases by remember(ref.key) { mutableStateOf(emptyList<String>()) }
    var edited by remember(ref.key) { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    suspend fun runSearch(refresh: Boolean = false, initiatedByUser: Boolean = false) {
        val term = keyword.trim()
        if (term.isEmpty()) return
        searching = true
        note = null
        try {
            val found = app.danmu.search(endpoint, term, refresh)
            if (keyword.trim() != term || !initiatedByUser && edited) return
            results = found
            searched = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            results = emptyList()
            searched = true
            note = SEARCH_FAILED
        } finally {
            searching = false
        }
    }
    fun submit(alias: String? = null) {
        edited = true
        if (alias != null) keyword = alias
        searchJob?.cancel()
        searchJob = scope.launch {
            if (alias != null) query?.let { app.danmu.preferTitle(endpoint, ref.key, it, alias) }
            runSearch(refresh = alias == null, initiatedByUser = true)
            focusTick++
        }
    }

    fun search() = submit()

    LaunchedEffect(ref.key, endpoint) {
        if (matchPending && enabled) searchJob = scope.launch { runSearch(); focusTick++ }
    }
    LaunchedEffect(query) {
        query?.let { q ->
            aliases = q.names
            aliases = app.danmuQueries.enrich(q).names
        }
    }
    LaunchedEffect(ref.key, endpoint, enabled) {
        if (!enabled) return@LaunchedEffect
        val revision = selectionRevision
        try {
            val q = app.danmuQueries.query(ref.server, ref.item)
            query = q
            resolvedQuery.complete(q)
            val preferred = q?.let { app.danmu.preferredTitle(endpoint, ref.key, it) }
            val matched = q?.let { app.danmu.current(endpoint, ref.key, it, app.danmuQueries::enrich) }
            if (revision == selectionRevision) current = matched
            if (!edited && revision == selectionRevision) {
                if (preferred != null || matched != null) searchJob?.cancel()
                if (preferred != null && preferred != matched?.searchTitle) {
                    keyword = preferred
                    runSearch()
                } else if (matched != null) {
                    keyword = matched.searchTitle
                    val found = if (matched.manual) app.danmu.search(endpoint, matched.searchTitle)
                        else app.danmu.candidates(endpoint, ref.key, q, matched)
                    if (!edited) { results = found; searched = true }
                } else if (!matchPending) runSearch()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (revision == selectionRevision) note = e.userMessage()
            keyword = keyword.ifBlank { ref.item.seriesName ?: ref.item.name.orEmpty() }
        } finally {
            resolvedQuery.complete(query)
            matching = false
        }
        focusTick++
    }
    val groups by produceState(emptyList<DanmuGroup>(), results) {
        value = withContext(Dispatchers.Default) { danmuGroups(results) }
    }
    val names = remember(aliases, current?.searchTitle, groups) {
        (aliases + listOfNotNull(current?.searchTitle) + groups.mapNotNull { group ->
            group.episodes.firstOrNull()?.target?.animeTitle?.let(DanmuMatch::searchTitle)?.takeIf { it.isNotBlank() }
        }).distinct()
    }
    // Initial focus: the chosen episode when it is listed, else the search field.
    LaunchedEffect(focusTick, groups) {
        if (focusTick == 0) {
            delay(16)
            runCatching { if (enabled) field.requestFocus() else toggle.requestFocus() }
            return@LaunchedEffect
        }
        delay(16)
        val chosen = current?.target?.sourceKey
        val index = withContext(Dispatchers.Default) { rowIndex(groups, chosen) }
        if (index != null) {
            list.scrollToItem((index - 1).coerceAtLeast(0))
            delay(16)
            if (!keepFocus) runCatching { chosenRow.requestFocus() }
        }
    }

    fun choose(option: DanmuSelection) {
        if (saving) return
        selectionRevision++
        saving = true
        scope.launch {
            try {
                val q = query ?: resolvedQuery.await() ?: error("暂时无法确定影片，请关闭后重试")
                val saved = app.danmu.saveChoice(endpoint, ref.key, q, option, followSeason = !q.movie)
                current = saved
                matching = false
                note = if (!q.movie && !saved.season) "无法确认分集编号，仅保存当前集" else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                note = "保存失败 · ${e.userMessage()}"
            } finally {
                saving = false
            }
        }
    }
    fun resetToAuto() {
        if (saving) return
        selectionRevision++
        saving = true
        matching = true
        scope.launch {
            try {
                val q = query ?: resolvedQuery.await() ?: error("暂时无法确定影片，请关闭后重试")
                app.danmu.reset(endpoint, ref.key, q)
                current = app.danmu.current(endpoint, ref.key, q, app.danmuQueries::enrich)
                current?.let { matched ->
                    keyword = matched.searchTitle
                    results = app.danmu.candidates(endpoint, ref.key, q, matched)
                    searched = true
                    focusTick++
                }
                note = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                note = "恢复失败 · ${e.userMessage()}"
            } finally {
                saving = false
                matching = false
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        DialogDim(0.55f)
        CompositionLocalProvider(LocalDensity provides density, LocalBackdrop provides null) {
            val slide = remember { Animatable(1f) }
            LaunchedEffect(Unit) { slide.animateTo(0f, tween(220)) }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
                Column(
                    Modifier
                        .width(820.dp)
                        .fillMaxHeight()
                        .graphicsLayer { translationX = size.width * slide.value }
                        .background(Brush.verticalGradient(listOf(Color(0xF0282A32), Color(0xF5121318))))
                        .drawBehind { drawLine(Color.White.copy(alpha = 0.12f), Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx()) }
                        .padding(start = 48.dp, end = 48.dp, top = 48.dp, bottom = 40.dp)
                        .focusProperties { onExit = { cancelFocusChange() } }
                        .focusGroup(),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(Glyph.Danmu, size = 34.dp, strokeWidth = 2.2f)
                        Text("弹幕", fontSize = 36.sp, fontWeight = FontWeight.ExtraBold)
                        danmuEpisodeLabel(ref.item)?.let { Text(it, fontSize = 20.sp, color = Z.textSecondary) }
                    }
                    SelectableRow(
                        onClick = { keepFocus = !enabled; app.settings.danmu.set(!enabled) },
                        modifier = Modifier.focusRequester(toggle).height(76.dp),
                        tile = true,
                        showCheck = false,
                        radius = 22.dp,
                        padding = PaddingValues(start = 22.dp, end = 16.dp),
                        label = "显示弹幕 ${if (enabled) "开" else "关"}",
                        focusKey = "danmu:toggle",
                    ) {
                        Text("显示弹幕", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        GlassToggle(enabled)
                    }
                    // Off (DanmuPanelOff.dc.html): only the title, the switch and 完成.
                    if (!enabled) Spacer(Modifier.weight(1f))
                    if (enabled) {
                    val status = danmuStatus(current, matching)
                    StatusCard(status, current, note?.takeUnless { it == SEARCH_FAILED })
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f).focusProperties { down = searchButton; right = searchButton }) {
                            Field("剧名或其他译名", keyword, { edited = true; keyword = it }, KeyboardType.Text, last = true, onSubmit = ::search,
                                initialFocus = field, showLabel = false, height = 64.dp, fontSize = 22, leading = Glyph.Search)
                        }
                        GlassButton(if (searching) "搜索中…" else "搜索分集", ::search,
                            Modifier.focusRequester(searchButton).focusProperties {
                                up = field
                                left = field
                                if (results.isNotEmpty() && names.size <= 1) down = firstRow
                            },
                            height = 60.dp, horizontalPadding = 28.dp, fontSize = 21)
                    }
                    if (names.size > 1) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(names, key = { it }) { alias ->
                                GlassButton("译名 · $alias", { submit(alias) }, height = 48.dp, horizontalPadding = 16.dp, fontSize = 18)
                            }
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when {
                            note == SEARCH_FAILED -> GlassButton("搜索失败 · 重试", ::search, height = 60.dp, horizontalPadding = 28.dp, fontSize = 21)
                            searched && results.isEmpty() && !searching -> Text("没有找到分集", fontSize = 21.sp, color = Z.textSecondary, modifier = Modifier.padding(start = 18.dp, top = 14.dp))
                            else -> Results(groups, current?.target?.sourceKey, list, chosenRow, firstRow, footer, ::choose)
                        }
                    }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        // Down from the last result lands here, not on 完成, so a stray press does not close the panel.
                        if (enabled) GlassButton(if (current?.season == true) "本季恢复自动匹配" else "恢复自动匹配",
                            ::resetToAuto, Modifier.focusRequester(footer), height = 60.dp, horizontalPadding = 28.dp, fontSize = 21)
                        GlassButton("完成", onDismiss, height = 60.dp, horizontalPadding = 28.dp, fontSize = 21)
                        Spacer(Modifier.weight(1f))
                        KeyHint("返回", "关闭")
                    }
                }
            }
        }
    }
}

/** The list position of the chosen episode's row (group titles count as items), or null. */
internal fun rowIndex(groups: List<DanmuGroup>, sourceKey: String?): Int? {
    if (sourceKey == null) return null
    var index = 0
    for (g in groups) {
        index++ // the group title
        for (e in g.episodes) {
            if (e.target.sourceKey == sourceKey) return index
            index++
        }
    }
    return null
}

@Composable
private fun StatusCard(status: DanmuStatus, selection: DanmuSelection?, note: String?) {
    val (badge, tint) = when (status) {
        DanmuStatus.Auto -> "自动匹配" to Z.ok
        DanmuStatus.Manual -> "已手动选择" to Color.White
        DanmuStatus.Unmatched -> "未匹配" to Z.warn
        DanmuStatus.Matching -> "正在匹配…" to Z.textSecondary
        DanmuStatus.Off -> "已关闭" to Z.textSecondary
    }
    Row(
        Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.07f), RoundedCornerShape(22.dp)).padding(horizontal = 22.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(badge, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = tint,
            modifier = Modifier.background(tint.copy(alpha = 0.16f), RoundedCornerShape(percent = 50)).padding(horizontal = 12.dp, vertical = 5.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val line = selection?.target?.label?.ifBlank { null } ?: if (status == DanmuStatus.Matching) "正在查找对应的弹幕" else "没有找到对应的弹幕"
            Text(line, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(note ?: danmuStatusSource(status, selection), fontSize = 17.sp, color = if (note != null) Z.warn else Z.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Results(
    groups: List<DanmuGroup>, chosen: String?, list: androidx.compose.foundation.lazy.LazyListState,
    chosenRow: FocusRequester, firstRow: FocusRequester, footer: FocusRequester, onChoose: (DanmuSelection) -> Unit,
) {
    val last = groups.lastOrNull()?.episodes?.lastOrNull()?.target?.sourceKey
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(end = 16.dp), state = list, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            var first = true
            for (g in groups) {
                item(key = "group:${g.title}", contentType = "danmu-group") {
                    Row(Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                        Text(g.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Text("${g.episodes.size} 集", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.5f))
                    }
                }
                for (e in g.episodes) {
                    val isChosen = e.target.sourceKey == chosen
                    val isFirst = first
                    first = false
                    item(key = "ep:${e.target.sourceKey}", contentType = "danmu-episode") {
                        var m: Modifier = Modifier
                        if (isChosen) m = m.focusRequester(chosenRow)
                        if (isFirst) m = m.focusRequester(firstRow)
                        if (e.target.sourceKey == last) m = m.focusProperties { down = footer }
                        SelectableRow({ onChoose(e) }, m, selected = isChosen, label = danmuRowLabel(e), focusKey = "danmu:${e.target.sourceKey}",
                            padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 12.dp)) {
                            Text(danmuRowLabel(e), fontSize = 21.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (isChosen) Text("当前", fontSize = 17.sp, color = secondaryText())
                        }
                    }
                }
            }
        }
        ListScrollIndicator(list)
    }
}

@Composable
internal fun KeyHint(key: String, what: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(key, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.background(Color.White.copy(alpha = 0.16f), RoundedCornerShape(8.dp)).padding(horizontal = 7.dp, vertical = 3.dp))
        Text(what, fontSize = 17.sp, color = Color.White.copy(alpha = 0.6f))
    }
}
