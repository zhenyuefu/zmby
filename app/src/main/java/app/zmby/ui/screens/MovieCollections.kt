package app.zmby.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
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
import app.zmby.TraceRecomposition
import app.zmby.ZmbyApp
import app.zmby.data.ServerItem
import app.zmby.log.ZLog
import app.zmby.media.progressFraction
import app.zmby.media.titleOf
import app.zmby.store.Freshness
import app.zmby.ui.Nav
import app.zmby.ui.Route
import app.zmby.ui.components.GlassButton
import app.zmby.ui.components.MediaCard16x9
import app.zmby.ui.components.Segment
import app.zmby.ui.components.SegmentedWell
import app.zmby.ui.components.horizontalRow
import app.zmby.ui.screenModel
import app.zmby.ui.theme.Z
import app.zmby.zmby
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import app.zmby.data.withPlaybackProgress

/** One collection a film is in, with its titles oldest first. */
@Immutable
data class MovieCollection(val box: ServerItem, val films: List<ServerItem>)

/**
 * The collections a film is in on its own server (电影详情 › 合集), from the store first and the
 * server after (an hour old or more, or back from the player). Nothing shows for a film in none, or
 * on a server that cannot say.
 */
class MovieCollectionsModel(private val app: ZmbyApp, private val server: String, private val itemId: String) : ViewModel() {
    private val _collections = MutableStateFlow<List<MovieCollection>>(emptyList())
    /** With this box's newest reports merged in, as on every page (统一数据层 · 观看进度). */
    val collections: StateFlow<List<MovieCollection>> = kotlinx.coroutines.flow.combine(_collections, app.recentProgress) { list, progress ->
        if (progress.isEmpty()) list else list.map { c -> c.copy(films = c.films.map { it.withPlaybackProgress(progress) }) }
    }.distinctUntilChanged().flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val key = "in-collections:$server:$itemId"

    init { load(cacheFirst = true) }

    fun refresh() = load(cacheFirst = false)

    private fun load(cacheFirst: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val cached = if (cacheFirst) app.store.list(key) else null
            if (cached != null) {
                val stored = cached.items.mapNotNull { box -> app.store.list(collectionKey(box.item.id))?.items?.let { MovieCollection(box, it) } }
                _collections.value = keepContaining(itemId, stored)
                if (!Freshness.due(Freshness.Kind.Browse, cached.fetchedAt, System.currentTimeMillis())) return@launch
            }
            try {
                val found = app.library.collectionsOf(server, itemId)
                found.forEach { (box, films) -> app.store.putList(collectionKey(box.item.id), server, films.map { it.item }) }
                app.store.putList(key, server, found.map { it.first.item })
                _collections.value = found.map { (box, films) -> MovieCollection(box, films) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ZLog.d("Collection", "collections of $itemId on ${app.registry.get(server)?.name}: ${e.message}")
            }
        }
    }

    /** The collection page's key for the same titles ([CollectionViewModel]), so both read one stored list. */
    private fun collectionKey(collectionId: String) = "collection:$server:$collectionId"

    companion object {
        fun keepContaining(itemId: String, list: List<MovieCollection>): List<MovieCollection> =
            list.filter { c -> c.films.any { it.item.id == itemId } }
    }
}

/**
 * The collections [ref] is in: empty for anything but a server's film. A [State] so the page reads
 * only whether there are any and the block reads the list (performance plan R4: the page root does
 * not recompose when the collections arrive or refresh).
 */
@Composable
fun rememberMovieCollections(ref: ServerItem?, tick: Int): State<List<MovieCollection>> {
    if (ref == null || ref.server == "catalog" || ref.item.type != "Movie") return remember { mutableStateOf(emptyList()) }
    val app = LocalContext.current.zmby
    val vm = screenModel("collections:${ref.server}:${ref.item.id}") { MovieCollectionsModel(app, ref.server, ref.item.id) }
    LaunchedEffect(tick) { if (tick > 0) vm.refresh() }
    return vm.collections.collectAsStateWithLifecycle()
}

/** 「2014 · 8.1」 under a collection's film card. */
internal fun collectionCardLine(ref: ServerItem): String =
    listOfNotNull(ref.item.productionYear?.toString(), ref.item.communityRating?.takeIf { it > 0 }?.let { "%.1f".format(java.util.Locale.ROOT, it) }).joinToString(" · ")

/**
 * 合集 at the top of a film's detail page, laid out like a series' 剧集: a segment per collection
 * (the film may be in several), 「合集页」 for the whole collection, a row of 16:9 cards oldest
 * first with this film marked 「当前」 and focused on entry, and the focused title's name and
 * overview under the row. OK on another title opens its detail page.
 */
@Composable
fun MovieCollectionBlock(nav: Nav, collections: List<MovieCollection>, currentId: String, entry: FocusRequester) {
    TraceRecomposition("Detail.collection")
    val app = LocalContext.current.zmby
    var chosen by remember(collections.map { it.box.key }) { mutableIntStateOf(0) }
    val collection = collections.getOrNull(chosen) ?: collections.firstOrNull() ?: return
    val films = collection.films
    val focusedKey = remember(collection.box.key) { mutableStateOf<String?>(null) }
    val rowState = rememberLazyListState()
    val currentIndex = films.indexOfFirst { it.item.id == currentId }
    val shownKey = (films.getOrNull(currentIndex) ?: films.firstOrNull())?.key
    LaunchedEffect(collection.box.key, currentIndex) {
        if (currentIndex > 0) rowState.scrollToItem((currentIndex - 1).coerceAtLeast(0)) else rowState.scrollToItem(0)
    }
    Column(Modifier.padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 80.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            SegmentedWell(
                collections.map { Segment(titleOf(it.box.item), "${it.films.size}") },
                chosen.coerceIn(0, collections.lastIndex),
                { chosen = it },
                labelPrefix = "合集",
            )
            GlassButton("合集页", { nav.go(Route.Collection(collection.box.server, collection.box.item.id)) }, height = 56.dp, horizontalPadding = 26.dp, fontSize = 20)
        }
        LazyRow(
            modifier = Modifier.horizontalRow(enter = entry.takeIf { shownKey != null }),
            state = rowState,
            contentPadding = PaddingValues(start = 80.dp, end = 120.dp, bottom = 30.dp),
            horizontalArrangement = Arrangement.spacedBy(30.dp),
        ) {
            items(films, key = { it.key }, contentType = { "collectionFilm" }) { film ->
                val current = film.item.id == currentId
                MediaCard16x9(
                    app.library.backdrop(film, maxHeight = 400) ?: app.library.primaryImage(film),
                    onClick = { if (!current) nav.go(Route.Detail(film.server, film.item.id)) },
                    width = 340.dp,
                    topEnd = if (current) "当前" else null,
                    watched = film.item.userData?.played == true,
                    progress = progressFraction(film.item),
                    onFocus = { focusedKey.value = film.key },
                    focusKey = "collectionFilm:${film.key}",
                    modifier = if (film.key == shownKey) Modifier.focusRequester(entry) else Modifier,
                ) {
                    Column {
                        Text(collectionCardLine(film), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Z.textSecondary)
                        Text(titleOf(film.item), fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        CollectionFilmText(films, focusedKey, shownKey)
    }
}

/** The focused title's name, year and overview under the row; the only part that follows each move. */
@Composable
private fun CollectionFilmText(films: List<ServerItem>, focusedKey: MutableState<String?>, shownKey: String?) {
    val film = (focusedKey.value ?: shownKey)?.let { key -> films.firstOrNull { it.key == key } } ?: return
    Column(Modifier.padding(horizontal = 80.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(listOfNotNull(titleOf(film.item), film.item.productionYear?.toString()).joinToString(" · "), fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
        film.item.overview?.takeIf { it.isNotBlank() }?.let {
            Text(it, fontSize = 21.sp, lineHeight = 32.sp, color = Z.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(1100.dp))
        }
    }
}
