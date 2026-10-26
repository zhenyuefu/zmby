package app.zmby.ui.screens

import app.zmby.media.titleOf
import app.zmby.data.SourceFeature
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.zmby.aggregate.Hit
import app.zmby.aggregate.Merger
import app.zmby.aggregate.MergeResult
import app.zmby.aggregate.SearchSnapshot
import app.zmby.aggregate.SearchState
import app.zmby.data.ServerItem
import app.zmby.data.ServerStatus
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import app.zmby.ui.Nav
import androidx.compose.ui.graphics.Color
import app.zmby.ui.components.Focusable
import app.zmby.ui.components.GlassChip
import app.zmby.ui.components.Glyph
import app.zmby.ui.components.Health
import app.zmby.ui.components.Icon
import app.zmby.ui.components.PosterCard2x3
import app.zmby.ui.components.RowTitle
import app.zmby.ui.components.SelectableRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import app.zmby.search.LiveSearch
import app.zmby.ui.components.groupCard
import app.zmby.ui.components.liftShadow
import app.zmby.ui.components.onFill
import app.zmby.ui.components.well
import app.zmby.ui.open
import app.zmby.ui.rememberInitialFocus
import app.zmby.ui.rememberRetained
import app.zmby.ui.theme.Z
import app.zmby.zmby
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import app.zmby.log.ZLog
import app.zmby.catalog.CatalogWork
import kotlinx.coroutines.CancellationException
import app.zmby.search.Candidate
import app.zmby.search.Pinyin
import app.zmby.search.PinyinCandidates

private val KEYS = "abcdefghijklmnopqrstuvwxyz1234567890".map { it.toString() }

/** Both modes search as typed; pinyin also offers candidates below the keyboard. */
private enum class KeyMode { Pinyin, Abc }

@Composable
fun SearchScreen(nav: Nav) {
    val app = LocalContext.current.zmby
    val library = app.library
    val servers by library.servers.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(KeyMode.Pinyin) }
    var snapshot by rememberRetained("snapshot") { mutableStateOf<SearchSnapshot?>(null) }
    val scope = rememberCoroutineScope()
    val candidates = remember { PinyinCandidates(scope, library::candidateServers, library::localTitles) }
    val live = remember { LiveSearch(scope, { term, reuse ->
        app.search.search(app.sources.servers(SourceFeature.Search).mapNotNull { id -> app.registry.get(id)?.let { id to it.order } }, term, reuse)
    }, library::rememberSearch) }
    val liveState by live.state.collectAsState()
    val localCandidates by candidates.candidates.collectAsState()
    var rawHits by rememberRetained("pinyinHits", query) { mutableStateOf<List<Hit>>(emptyList()) }
    var pendingResultFocus by remember { mutableStateOf<String?>(null) }
    val keyA = remember { FocusRequester() }
    val candidateEntry = remember { FocusRequester() }
    val resultEntry = remember { FocusRequester() }
    val resultGrid = rememberLazyGridState()
    DisposableEffect(Unit) { onDispose { candidates.clear(); live.close() } }

    LaunchedEffect(query, mode) {
        // Full live results provide remote candidates, avoiding a second set of candidate requests.
        // Matching local titles converts up to 2000 of them to pinyin: off the main thread.
        withContext(Dispatchers.Default) { candidates.onInput(if (mode == KeyMode.Pinyin) query else "", fetchRemote = false) }
    }
    LaunchedEffect(query, mode) {
        live.submit(query)
    }
    LaunchedEffect(liveState) {
        snapshot = liveState.snapshot
        if (liveState.term == query.trim() && mode == KeyMode.Pinyin && !Pinyin.hasHan(query)) {
            rawHits = liveState.snapshot?.hits.orEmpty()
        }
    }
    // A paused result is retryable after its deadline (or the server page's 立即重试).
    LaunchedEffect(liveState, servers) {
        val snap = liveState.snapshot ?: return@LaunchedEffect
        if (!snap.done) return@LaunchedEffect
        val deadlines = snap.states.filterValues { it == SearchState.Skipped }.keys.mapNotNull { id ->
            app.registry.get(id)?.coolingUntil
        }
        if (deadlines.isNotEmpty()) {
            delay((deadlines.min() - System.currentTimeMillis()).coerceAtLeast(0) + 50)
            live.refresh()
        }
    }
    // Ranking converts every candidate title to pinyin: on Dispatchers.Default, not in composition.
    val shownCandidates by produceState(emptyList<Candidate>(), query, mode, localCandidates, rawHits) {
        value = if (mode == KeyMode.Pinyin && query.isNotBlank() && !Pinyin.hasHan(query)) withContext(Dispatchers.Default) {
            PinyinCandidates.rank(query, localCandidates + rawHits.filter { Pinyin.hasHan(it.item.name.orEmpty()) }.map { h ->
                Candidate(h.item.name.orEmpty(), h.item.productionYear, h.item, h.server, h.order)
            })
        } else emptyList()
    }

    fun changeQuery(text: String) {
        pendingResultFocus = null
        query = text
        rawHits = emptyList()
        live.submit("") // Cancel the previous HTTP work immediately, before the debounce.
    }
    fun pick(c: Candidate) {
        ZLog.i("Search", "picked candidate '${c.title}' from ${c.serverKey ?: "local titles"}")
        changeQuery(c.title)
        pendingResultFocus = c.title.trim()
        // Keep a usable focus target while the candidate disappears and new results arrive.
        scope.launch {
            awaitFrame()
            if (pendingResultFocus != null) keyA.requestFocus()
        }
        val reuse = c.item?.let { item -> c.serverKey?.let { id -> Hit(ServerItem(id, item), app.registry.get(id)?.order ?: 0) } }
        live.submit(c.title, reuse, immediate = true, saveHistory = true)
    }

    val tmdbSearch by app.settings.tmdbSearch.flow.collectAsState()
    val tmdbLanguage by app.settings.metadataLanguage.flow.collectAsState()
    val tmdbToken by app.settings.tmdbToken.flow.collectAsState()
    var tmdbError by remember(query) { mutableStateOf<String?>(null) }
    val tmdbWorks by produceState<List<CatalogWork>>(emptyList(), query, tmdbSearch, tmdbLanguage, tmdbToken) {
        value = emptyList()
        if (query.isNotBlank() && tmdbSearch && app.catalog.configured) {
            delay(350)
            withContext(Dispatchers.IO) {
                try { value = app.catalog.search(query); tmdbError = null }
                catch (e: CancellationException) { throw e } catch (e: Exception) { tmdbError = e.message }
            }
        }
    }
    val resultHits = snapshot?.hits
    // Keep cards mounted while merging incremental hits, and tag their source to reject stale focus.
    val mergedInput by produceState<Pair<List<Hit>?, MergeResult?>>(null to null, resultHits) {
        value = resultHits to resultHits?.let { withContext(Dispatchers.Default) { Merger.merge(it) } }
    }
    val merged = mergedInput.second
    LaunchedEffect(pendingResultFocus, liveState, snapshot, mergedInput) {
        val term = pendingResultFocus ?: return@LaunchedEffect
        if (liveState.term != term || snapshot != liveState.snapshot) return@LaunchedEffect
        if (mergedInput.first != resultHits) return@LaunchedEffect
        val results = merged ?: return@LaunchedEffect
        if (results.works.isNotEmpty() || results.unmerged.isNotEmpty()) {
            resultGrid.scrollToItem(0)
            awaitFrame()
            if (resultEntry.requestFocus()) pendingResultFocus = null
        } else if (liveState.snapshot?.done == true) {
            awaitFrame()
            keyA.requestFocus()
            pendingResultFocus = null
        }
    }
    val showCandidates = mode == KeyMode.Pinyin && query.isNotBlank() && !Pinyin.hasHan(query)
    Row(Modifier.fillMaxSize().onPreviewKeyEvent {
        // A later remote press takes control; delayed results must not steal that focus.
        if (it.type == KeyEventType.KeyDown) pendingResultFocus = null
        false
    }) {
        SearchAside(
            query = query,
            mode = mode,
            onKey = { changeQuery(query + it) },
            onSpace = { if (query.isNotEmpty() && !query.endsWith(' ')) changeQuery(query + " ") },
            onDelete = { changeQuery(query.dropLast(1)) },
            onClear = { changeQuery("") },
            onMode = { pendingResultFocus = null; mode = if (mode == KeyMode.Pinyin) KeyMode.Abc else KeyMode.Pinyin },
            onSearch = { if (query.isNotBlank()) live.submit(query, immediate = true, saveHistory = true) },
            keyA = keyA,
            candidates = if (showCandidates) shownCandidates else emptyList(),
            candidateEntry = candidateEntry,
            onPick = ::pick,
        )
        Column(
            Modifier.weight(1f).fillMaxHeight().padding(start = 40.dp, end = 60.dp, top = 64.dp),
            verticalArrangement = Arrangement.spacedBy(30.dp),
        ) {
            if (tmdbWorks.isNotEmpty()) CatalogCards(nav, tmdbWorks, "TMDB · 影视目录")
            tmdbError?.let { Text(it, color = Z.warn, fontSize = 18.sp) }
            val snap = snapshot
            if (snap != null) StatusRow(snap, servers)
            val works = merged?.works.orEmpty()
            val loose = merged?.unmerged.orEmpty()
            if (works.isNotEmpty() || loose.isNotEmpty()) {
                LazyVerticalGrid(
                    columns = GridCells.FixedSize(196.dp),
                    state = resultGrid,
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalArrangement = Arrangement.spacedBy(32.dp),
                    contentPadding = PaddingValues(bottom = 60.dp, end = 30.dp),
                ) {
                    if (works.isNotEmpty()) item(key = "works", span = { GridItemSpan(maxLineSpan) }, contentType = "title") { RowTitle("影片与剧集") }
                    items(works, key = { it.key + ":" + it.lead.ref.key }, contentType = { "poster" }) { work ->
                        val lead = work.lead
                        val item = lead.item
                        PosterCard2x3(
                            imageUrl = library.primaryImage(lead.ref),
                            title = titleOf(item),
                            onClick = { nav.open(lead.ref) },
                            topEnd = "${work.entries.size} 个版本",
                            subtitle = listOfNotNull(if (item.type == "Series") "剧集" else "电影", item.productionYear?.toString()).joinToString(" · "),
                            focusKey = "result:${lead.ref.key}",
                            modifier = if (work == works.first()) Modifier.focusRequester(resultEntry) else Modifier,
                        )
                    }
                    if (loose.isNotEmpty()) {
                        item(key = "loose", span = { GridItemSpan(maxLineSpan) }, contentType = "title") {
                            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("未能合并", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    loose.forEachIndexed { i, h ->
                                        LooseChip(h, library.status(h.server)?.name.orEmpty(),
                                            modifier = if (works.isEmpty() && i == 0) Modifier.focusRequester(resultEntry) else Modifier,
                                        ) { nav.open(h.ref) }
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (snap?.done == true) {
                val failed = snap.states.values.count { it != SearchState.Done }
                Text(
                    if (failed == 0) "所选服务器暂无「${liveState.term}」资源" else "已响应的服务器暂无「${liveState.term}」资源，其余待确认",
                    fontSize = 24.sp,
                    color = Z.textSecondary,
                )
            }
        }
    }
}

/** 「已返回 9 台，共 12 台」 and a chip per server: querying (warn), failed or timed out (off), done. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusRow(snap: SearchSnapshot, servers: List<ServerStatus>) {
    val shown = servers.filter { it.id in snap.states }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), maxLines = 2) {
        Text(returnedLabel(snap.returned, snap.states.size), fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 6.dp, top = 8.dp))
        // Problems first, then the rest, so a long list still shows what went wrong.
        val sorted = shown.sortedBy { if (snap.states[it.id] == SearchState.Done) 1 else 0 }
        sorted.take(MAX_CHIPS).forEach { s ->
            val (health, label) = when (snap.states[s.id]) {
                SearchState.Querying -> Health.Warn to "${s.name} · 查询中"
                SearchState.Failed -> Health.Off to "${s.name} · 未搜成"
                SearchState.TimedOut -> Health.Off to "${s.name} · 超时"
                SearchState.Skipped -> Health.Off to "${s.name} · 暂停"
                else -> Health.Ok to s.name
            }
            GlassChip(label, health = health, fontSize = 17, height = 44.dp, horizontalPadding = 18.dp)
        }
        if (sorted.size > MAX_CHIPS) GlassChip("+${sorted.size - MAX_CHIPS}", fontSize = 17, height = 44.dp, horizontalPadding = 18.dp)
    }
}

private const val MAX_CHIPS = 6

/** 「标题 · 服务器」 for hits that could not be merged (no external IDs). */
@Composable
private fun LooseChip(h: Hit, server: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Focusable(onClick, modifier = modifier, shape = shape, focusedScale = 1.04f, outline = 0.dp, shadow = 0.dp, label = "未合并 ${h.item.name}", focusKey = "loose:${h.ref.key}") { focused ->
        Row(
            Modifier.then(if (focused) Modifier.liftShadow(shape).onFill(shape) else Modifier.groupCard(shape)).padding(horizontal = 22.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val title = listOfNotNull(titleOf(h.item), h.item.productionYear?.let { "($it)" }).joinToString(" ")
            Text(title, fontSize = 19.sp, color = if (focused) Z.onFocus else Z.textPrimary, maxLines = 1)
            Text("· $server", fontSize = 19.sp, color = if (focused) Z.onFocusSecondary else Z.textTertiary, maxLines = 1)
        }
    }
}

/** "已返回 9 台，共 12 台" (no slash); everything back reads "12 台全部返回". */
internal fun returnedLabel(returned: Int, total: Int): String = if (returned >= total) "$total 台全部返回" else "已返回 $returned 台，共 $total 台"

@Composable
private fun SearchAside(
    query: String,
    mode: KeyMode,
    onKey: (String) -> Unit,
    onSpace: () -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    onMode: () -> Unit,
    onSearch: () -> Unit,
    keyA: FocusRequester,
    candidates: List<Candidate>,
    candidateEntry: FocusRequester,
    onPick: (Candidate) -> Unit,
) {
    val searchKey = remember { FocusRequester() }
    // A new input starts at index zero instead of keeping a former candidate as the scroll anchor.
    val candidateList = remember(query, mode) { LazyListState() }
    var candidateFocused by remember { mutableStateOf(false) }
    var enterCandidates by remember(query, mode) { mutableStateOf(false) }
    val candidateKeys = remember(candidates) { candidates.map { it.title to it.year } }
    LaunchedEffect(candidateList, candidateKeys, candidateFocused, enterCandidates) {
        if (candidates.isEmpty()) return@LaunchedEffect
        if (!candidateFocused || enterCandidates) candidateList.scrollToItem(0)
        if (enterCandidates) {
            // Lazy items outside the viewport have no focus target until they are laid out.
            awaitFrame()
            if (candidateEntry.requestFocus()) enterCandidates = false
        }
    }
    Column(
        Modifier.width(520.dp).fillMaxHeight().background(Z.aside).padding(start = 64.dp, end = 40.dp, top = 64.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(84.dp).well(RoundedCornerShape(percent = 50)).padding(horizontal = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(Glyph.Search, size = 30.dp)
            Text(
                query.ifEmpty { "影片、剧集" },
                fontSize = 32.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (query.isEmpty()) Z.textTertiary else Z.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(if (mode == KeyMode.Pinyin) "拼音" else "ABC", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Z.textSecondary)
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val first = rememberInitialFocus(Unit)
            KEYS.chunked(6).forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { key ->
                        val base = Modifier.weight(1f)
                        val m = if (key == KEYS.first()) base.focusRequester(first).focusRequester(keyA) else base
                        Key(key, m, fontSize = 26) { onKey(key) }
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Key("空格", Modifier.weight(1f), onClick = onSpace)
                Key("删除", Modifier.weight(1f), onClick = onDelete)
                Key("清空", Modifier.weight(1f), onClick = onClear)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val downToCandidates = if (candidates.isNotEmpty()) Modifier.onPreviewKeyEvent {
                    if (it.key == Key.DirectionDown) {
                        if (it.type == KeyEventType.KeyDown) enterCandidates = true
                        true
                    } else false
                } else Modifier
                Key(if (mode == KeyMode.Pinyin) "切到 ABC" else "切到拼音", Modifier.weight(1f).then(downToCandidates), onClick = onMode)
                Key("搜索", Modifier.weight(1f).focusRequester(searchKey).then(downToCandidates), onClick = onSearch)
            }
        }
        if (candidates.isNotEmpty()) CandidateColumn(
            candidates = candidates,
            entry = candidateEntry,
            keyboard = searchKey,
            state = candidateList,
            modifier = Modifier.weight(1f).onFocusChanged { candidateFocused = it.hasFocus }.focusGroup(),
            onPick = onPick,
        )
    }
}

/** Candidates only submit on confirmation; moving focus leaves the current results intact. */
@Composable
private fun CandidateColumn(
    candidates: List<Candidate>,
    entry: FocusRequester,
    keyboard: FocusRequester,
    state: LazyListState,
    modifier: Modifier,
    onPick: (Candidate) -> Unit,
) {
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("候选", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
            itemsIndexed(candidates, key = { _, c -> "${c.title}:${c.year}" }, contentType = { _, _ -> "candidate" }) { i, c ->
                SelectableRow(
                    onClick = { onPick(c) },
                    modifier = if (i == 0) Modifier.focusRequester(entry).focusProperties { up = keyboard } else Modifier,
                    tile = true,
                    showCheck = false,
                    label = "候选 ${c.title}",
                    focusKey = "candidate:${c.title}:${c.year}",
                ) { focused ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(c.title, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        c.year?.let { Text(it.toString(), fontSize = 17.sp, color = if (focused) Z.onFocusSecondary else Z.textSecondary) }
                    }
                }
            }
        }
    }
}

/** `.key`: 64 high, radius 20, 7 % white with a top highlight; focused = `glassOn`, scale 1.1. */
@Composable
private fun Key(label: String, modifier: Modifier, fontSize: Int = 21, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Focusable(onClick, modifier, shape = shape, focusedScale = 1.1f, outline = 0.dp, shadow = 0.dp, label = "key $label") { focused ->
        Box(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .then(if (focused) Modifier.liftShadow(shape, 14.dp).onFill(shape) else Modifier.groupCard(shape, Color.White.copy(alpha = 0.07f))),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, fontSize = fontSize.sp, fontWeight = FontWeight.SemiBold, color = if (focused) Z.onFocus else Z.textPrimary, textAlign = TextAlign.Center)
        }
    }
}
