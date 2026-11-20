package app.zmby.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import app.zmby.catalog.CatalogId
import app.zmby.ui.open
import app.zmby.ui.rememberRetained
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.zmby.TraceRecomposition
import app.zmby.ZmbyApp
import app.zmby.data.ServerItem
import app.zmby.media.progressFraction
import app.zmby.media.titleOf
import app.zmby.ui.Nav
import app.zmby.ui.Route
import app.zmby.ui.components.GlassButton
import app.zmby.ui.components.MediaCard16x9
import app.zmby.ui.components.Segment
import app.zmby.ui.components.SegmentedWell
import app.zmby.ui.components.horizontalRow
import app.zmby.ui.theme.Z
import app.zmby.zmby
import kotlinx.coroutines.flow.StateFlow

/** 「2014 · 8.1」 under a collection's film card. */
internal fun collectionCardLine(ref: ServerItem): String =
    listOfNotNull(ref.item.productionYear?.toString(), ref.item.communityRating?.takeIf { it > 0 }?.let { "%.1f".format(java.util.Locale.ROOT, it) }).joinToString(" · ")

internal fun MovieCollection.sourceLabel(app: ZmbyApp): String =
    if (catalog) "TMDB" else sources.joinToString("、") { app.registry.get(it)?.name ?: it }

/** Pass the known TMDB identity as well as the preferred server copy when opening the member. */
internal fun Nav.openCollectionMember(film: ServerItem, copy: ServerItem?) {
    val id = if (film.server == "catalog") CatalogId.fromItemId(film.item.id) else null
    if (id != null) go(Route.Catalog(id, copy?.server, copy?.item?.id)) else open(copy ?: film)
}

/**
 * 合集 at the top of a film's detail page, laid out like a series' 剧集: a segment per collection
 * (the film may be in several), 「合集页」 for the whole collection, a row of 16:9 cards oldest
 * first with this film marked 「当前」 and focused on entry, and the focused title's name and
 * overview under the row. OK on another title opens its detail page.
 */
@Composable
fun MovieCollectionBlock(nav: Nav, collections: List<MovieCollection>, directory: StateFlow<List<MovieCollection>>, entry: FocusRequester) {
    TraceRecomposition("Detail.collection")
    val app = LocalContext.current.zmby
    var selected by rememberRetained("selectedCollection") { mutableStateOf<String?>(null) }
    val collection = collections.firstOrNull { it.represents(selected) } ?: collections.firstOrNull() ?: return
    val chosen = collections.indexOf(collection)
    val films = collection.films
    val focusedKey = remember(collection.key) { mutableStateOf<String?>(null) }
    val rowState = rememberLazyListState()
    val currentIndex = collection.currentIndex
    val shownKey = (films.getOrNull(currentIndex) ?: films.firstOrNull())?.key
    LaunchedEffect(collection.key, currentIndex) {
        if (currentIndex > 0) rowState.scrollToItem((currentIndex - 1).coerceAtLeast(0)) else rowState.scrollToItem(0)
    }
    Column(Modifier.padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 80.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            SegmentedWell(
                collections.map { Segment("${titleOf(it.box.item)} · ${it.sourceLabel(app)}", "${it.films.size}") },
                chosen.coerceIn(0, collections.lastIndex),
                { selected = collections[it].key },
                modifier = Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
                selectOnFocus = false,
                labelPrefix = "合集",
            )
            GlassButton("合集页", { nav.go(Route.WorkCollection(collection.key, directory)) }, height = 56.dp, horizontalPadding = 26.dp, fontSize = 20)
        }
        if (collection.catalog) Text("系列共 ${films.size} 部 · 已找到 ${collection.foundCount} 部片源", fontSize = 21.sp, color = Z.textSecondary, modifier = Modifier.padding(horizontal = 80.dp))
        LazyRow(
            modifier = Modifier.horizontalRow(enter = entry.takeIf { shownKey != null }),
            state = rowState,
            contentPadding = PaddingValues(start = 80.dp, end = 120.dp, bottom = 30.dp),
            horizontalArrangement = Arrangement.spacedBy(30.dp),
        ) {
            items(films, key = { it.key }, contentType = { "collectionFilm" }) { film ->
                val current = film.key == collection.currentKey
                val copy = collection.available(film).firstOrNull()
                MediaCard16x9(
                    app.library.backdrop(film, maxHeight = 400) ?: app.library.primaryImage(copy ?: film),
                    onClick = { if (!current) nav.openCollectionMember(film, copy) },
                    width = 340.dp,
                    topEnd = if (current) "当前" else collection.status(film),
                    watched = (copy ?: film).item.userData?.played == true,
                    progress = progressFraction((copy ?: film).item),
                    onFocus = { focusedKey.value = film.key; selected = collection.key },
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
