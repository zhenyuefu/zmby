package app.zmby.ui.screens

import app.zmby.data.SourceFeature
import androidx.compose.foundation.background
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
import app.zmby.search.Candidate
import app.zmby.search.Pinyin
import app.zmby.search.PinyinCandidates

private val KEYS = "abcdefghijklmnopqrstuvwxyz1234567890".map { it.toString() }

/** Both modes search as typed; pinyin also offers an independent, focusable candidate column. */
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
    var preview by remember { mutableStateOf<Candidate?>(null) }
    val keyA = remember { FocusRequester() }
    val candidateEntry = remember { FocusRequester() }
    DisposableEffect(Unit) { onDispose { candidates.clear(); live.close() } }

    LaunchedEffect(query, mode) {
        // Full live results provide remote candidates, avoiding a second set of candidate requests.
        candidates.onInput(if (mode == KeyMode.Pinyin) query else "", fetchRemote = false)
    }
    LaunchedEffect(query, mode, preview) {
        live.submit(preview?.title ?: query, preview?.let { c ->
            c.item?.let { item -> c.serverKey?.let { id -> Hit(ServerItem(id, item), app.registry.get(id)?.order ?: 0) } }
        })
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
    val shownCandidates = if (mode == KeyMode.Pinyin && query.isNotBlank() && !Pinyin.hasHan(query)) {
        PinyinCandidates.rank(query, localCandidates + rawHits.filter { Pinyin.hasHan(it.item.name.orEmpty()) }.map { h ->
            Candidate(h.item.name.orEmpty(), h.item.productionYear, h.item, h.server, h.order)
        })
    } else emptyList()

    fun changeQuery(text: String) {
        preview = null
        query = text
        rawHits = emptyList()
        live.submit("") // Cancel the previous HTTP work immediately, before the debounce.
    }
    fun pick(c: Candidate) {
        ZLog.i("Search", "picked candidate '${c.title}' from ${c.serverKey ?: "local titles"}")
        changeQuery(c.title)
        scope.launch { awaitFrame(); runCatching { keyA.requestFocus() } }
        val reuse = c.item?.let { item -> c.serverKey?.let { id -> Hit(ServerItem(id, item), app.registry.get(id)?.order ?: 0) } }
        live.submit(c.title, reuse, immediate = true, saveHistory = true)
    }

    val resultHits = snapshot?.hits
    val merged by produceState<MergeResult?>(null, resultHits) {
        value = resultHits?.let { withContext(Dispatchers.Default) { Merger.merge(it) } }
    }
    Row(Modifier.fillMaxSize()) {
        SearchAside(
            query = query,
            mode = mode,
            onKey = { changeQuery(query + it) },
            onSpace = { if (query.isNotEmpty() && !query.endsWith(' ')) changeQuery(query + " ") },
            onDelete = { changeQuery(query.dropLast(1)) },
            onClear = { changeQuery("") },
            onMode = { preview = null; mode = if (mode == KeyMode.Pinyin) KeyMode.Abc else KeyMode.Pinyin },
            onSearch = { if (query.isNotBlank()) { preview = null; live.submit(query, immediate = true, saveHistory = true) } },
            keyA = keyA,
            candidateEntry = candidateEntry.takeIf { shownCandidates.isNotEmpty() },
        )
        if (mode == KeyMode.Pinyin) CandidateColumn(shownCandidates, candidateEntry, keyA, onPreview = { preview = it }, onPick = ::pick)
        Column(
            Modifier.weight(1f).fillMaxHeight().padding(start = 40.dp, end = 60.dp, top = 64.dp),
            verticalArrangement = Arrangement.spacedBy(30.dp),
        ) {
            val snap = snapshot
            if (snap != null) StatusRow(snap, servers)
            val works = merged?.works.orEmpty()
            val loose = merged?.unmerged.orEmpty()
            if (works.isNotEmpty() || loose.isNotEmpty()) {
                LazyVerticalGrid(
                    columns = GridCells.FixedSize(196.dp),
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
                            title = item.name.orEmpty(),
                            onClick = { nav.open(lead.ref) },
                            topEnd = "${work.entries.size} 个版本",
                            subtitle = listOfNotNull(if (item.type == "Series") "剧集" else "电影", item.productionYear?.toString()).joinToString(" · "),
                            focusKey = "result:${lead.ref.key}",
                        )
                    }
                    if (loose.isNotEmpty()) {
                        item(key = "loose", span = { GridItemSpan(maxLineSpan) }, contentType = "title") {
                            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("未能合并", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    loose.forEach { h -> LooseChip(h, library.status(h.server)?.name.orEmpty()) { nav.open(h.ref) } }
                                }
                            }
                        }
                    }
                }
            } else if (snap?.done == true) {
                val failed = snap.states.values.count { it != SearchState.Done }
                Text(
                    if (failed == 0) "没有找到「${liveState.term}」" else "已返回的服务器里没有「${liveState.term}」",
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
private fun LooseChip(h: Hit, server: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Focusable(onClick, shape = shape, focusedScale = 1.04f, outline = 0.dp, shadow = 0.dp, label = "未合并 ${h.item.name}", focusKey = "loose:${h.ref.key}") { focused ->
        Row(
            Modifier.then(if (focused) Modifier.liftShadow(shape).onFill(shape) else Modifier.groupCard(shape)).padding(horizontal = 22.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val title = listOfNotNull(h.item.name, h.item.productionYear?.let { "($it)" }).joinToString(" ")
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
    candidateEntry: FocusRequester?,
) {
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
                        val base = if (key == row.last() && candidateEntry != null) Modifier.weight(1f).focusProperties { right = candidateEntry } else Modifier.weight(1f)
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
                Key(if (mode == KeyMode.Pinyin) "切到 ABC" else "切到拼音", Modifier.weight(1f), onClick = onMode)
                Key("搜索", Modifier.weight(1f), onClick = onSearch)
            }
        }
    }
}

/** A stable vertical column; candidate focus previews its full Chinese search on the right. */
@Composable
private fun CandidateColumn(
    candidates: List<Candidate>,
    entry: FocusRequester,
    keyboard: FocusRequester,
    onPreview: (Candidate) -> Unit,
    onPick: (Candidate) -> Unit,
) {
    Column(
        Modifier.width(336.dp).fillMaxHeight().padding(start = 20.dp, end = 20.dp, top = 64.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text("拼音候选", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
            itemsIndexed(candidates, key = { _, c -> "${c.title}:${c.year}" }, contentType = { _, _ -> "candidate" }) { i, c ->
                SelectableRow(
                    onClick = { onPick(c) },
                    modifier = Modifier.then(if (i == 0) Modifier.focusRequester(entry) else Modifier)
                        .focusProperties { left = keyboard },
                    tile = true,
                    showCheck = false,
                    onFocus = { onPreview(c) },
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
