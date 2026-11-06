package app.zmby.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import app.zmby.ZmbyApp
import app.zmby.data.ServerItem
import app.zmby.emby.BaseItemDto
import app.zmby.log.ZLog
import app.zmby.media.titleOf
import app.zmby.store.Freshness
import app.zmby.ui.Nav
import app.zmby.ui.components.Artwork
import app.zmby.ui.components.Focusable
import app.zmby.ui.components.GlassButton
import app.zmby.ui.components.Glyph
import app.zmby.ui.components.PosterCard2x3
import app.zmby.ui.components.RowTitle
import app.zmby.ui.open
import app.zmby.ui.rememberInitialFocus
import app.zmby.ui.rememberResumeTick
import app.zmby.ui.screenModel
import app.zmby.ui.theme.Z
import app.zmby.ui.userMessage
import app.zmby.zmby
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class CollectionState(
    val item: BaseItemDto? = null,
    val films: List<ServerItem> = emptyList(),
    val loaded: Boolean = false,
    /** Nothing stored and the server did not answer (shown with 重试). */
    val error: String? = null,
)

/**
 * 合集页 (电影 › 合集): one collection on its own server, never merged across servers. The films come
 * from the store first and the server after (an hour old or more, or back from the player).
 */
class CollectionViewModel(private val app: ZmbyApp, private val server: String, private val collectionId: String) : ViewModel() {
    private val _state = MutableStateFlow(CollectionState())
    val state: StateFlow<CollectionState> = _state.asStateFlow()
    private val key = "collection:$server:$collectionId"

    init { load(cacheFirst = true) }

    fun refresh() = load(cacheFirst = false)

    fun retry() {
        app.registry.retry(server)
        _state.update { it.copy(error = null) }
        load(cacheFirst = false)
    }

    private fun load(cacheFirst: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            launch {
                runCatching { app.library.item(server, collectionId) }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrNull()?.let { item -> _state.update { it.copy(item = item) } }
            }
            val cached = if (cacheFirst) app.store.list(key) else null
            if (cached != null && cached.items.isNotEmpty()) _state.update { it.copy(films = cached.items, loaded = true) }
            if (cached != null && !Freshness.due(Freshness.Kind.Browse, cached.fetchedAt, System.currentTimeMillis())) return@launch
            try {
                val films = app.library.collectionItems(server, collectionId)
                app.store.putList(key, server, films.map { it.item })
                _state.update { it.copy(films = films, loaded = true, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ZLog.w("Collection", "$collectionId on ${app.registry.get(server)?.name}: ${e.message}")
                if (!_state.value.loaded) _state.update { it.copy(loaded = true, error = "${app.registry.get(server)?.name ?: "这台服务器"}没有返回合集内容：${e.userMessage()}") }
            }
        }
    }
}

/** 「4 部 · 1999–2021」: how many titles and the years they span. */
internal fun collectionMeta(films: List<ServerItem>): String {
    val years = films.mapNotNull { it.item.productionYear }
    val span = when {
        years.isEmpty() -> null
        years.min() == years.max() -> "${years.min()}"
        else -> "${years.min()}–${years.max()}"
    }
    return listOfNotNull("${films.size} 部".takeIf { films.isNotEmpty() }, span).joinToString(" · ")
}

/**
 * 合集页, laid out like the person page: a fixed left column (poster, name, 「4 部 · 1999–2021」, the
 * server, a five-line overview with 完整简介 when cut) and the collection's titles on the right in a
 * five-column grid, oldest first. Focus starts on the first title.
 */
@Composable
fun CollectionScreen(nav: Nav, server: String, collectionId: String) {
    val app = LocalContext.current.zmby
    val vm = screenModel("collection:$server:$collectionId") { CollectionViewModel(app, server, collectionId) }
    val st by vm.state.collectAsStateWithLifecycle()
    val faces = app.cardFaces.version.collectAsStateWithLifecycle()
    val tick = rememberResumeTick()
    LaunchedEffect(tick) { if (tick > 0) vm.refresh() }
    var fullText by remember { mutableStateOf(false) }
    val item = st.item
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(600.dp).fillMaxHeight().padding(start = 80.dp, top = 80.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            val poster = item?.let { app.library.primaryImage(ServerItem(server, it), maxHeight = 600) }
            Artwork(poster, Modifier.size(260.dp, 390.dp).clip(RoundedCornerShape(20.dp)))
            Text(item?.let(::titleOf).orEmpty(), fontSize = 48.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 56.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(500.dp))
            val meta = listOf(collectionMeta(st.films), app.registry.get(server)?.name.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, fontSize = 22.sp, color = Z.textSecondary)
            val overview = item?.overview?.takeIf { it.isNotBlank() }
            if (overview != null) {
                var cut by remember(overview) { mutableStateOf(false) }
                Text(overview, fontSize = 21.sp, lineHeight = 34.sp, color = Z.textPrimary.copy(alpha = 0.78f), maxLines = 5, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(500.dp), onTextLayout = { cut = it.hasVisualOverflow })
                if (cut) GlassButton("完整简介", { fullText = true }, height = 56.dp, horizontalPadding = 28.dp, fontSize = 20)
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentPadding = PaddingValues(start = 20.dp, end = 80.dp, top = 80.dp, bottom = 80.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalArrangement = Arrangement.spacedBy(36.dp),
        ) {
            item(key = "title", span = { GridItemSpan(maxLineSpan) }, contentType = "title") {
                RowTitle(if (st.films.isEmpty()) "合集内容" else "合集内 ${st.films.size} 部")
            }
            st.error?.let { message ->
                item(key = "error", span = { GridItemSpan(maxLineSpan) }, contentType = "title") {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Text(message, fontSize = 24.sp, color = Z.textSecondary, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
                        GlassButton("重试", vm::retry, glyph = Glyph.Refresh, height = 64.dp)
                    }
                }
            }
            if (st.loaded && st.error == null && st.films.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }, contentType = "title") {
                    Text("这个合集是空的", fontSize = 24.sp, color = Z.textSecondary)
                }
            }
            items(st.films, key = { it.key }, contentType = { "poster" }) { ref ->
                val first = ref === st.films.firstOrNull()
                val face = remember(faces.value, ref.key) { app.cardFaces.face(ref) }
                PosterCard2x3(
                    imageUrl = face?.poster ?: app.library.primaryImage(ref),
                    title = face?.title ?: titleOf(ref.item),
                    onClick = { nav.open(ref) },
                    width = 196.dp,
                    topEnd = unwatchedBadge(ref.item),
                    subtitle = browseCaption(ref.item),
                    focusKey = "collection:${ref.key}",
                    modifier = if (first) Modifier.focusRequester(rememberInitialFocus(ref.key)) else Modifier,
                )
            }
        }
    }
    if (fullText && item?.overview != null) {
        // The whole overview in a card; up and down scroll it, Back closes.
        OverlayPanel({ fullText = false }, width = 1100.dp) { first ->
            Focusable({ fullText = false }, Modifier.focusRequester(first), outline = 0.dp, shadow = 0.dp, focusedScale = 1f, label = "完整简介") {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(titleOf(item), fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
                    Text(item.overview, fontSize = 22.sp, lineHeight = 36.sp)
                }
            }
        }
    }
}
