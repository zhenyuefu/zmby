package app.zmby.ui.screens

import app.zmby.media.visibleText
import app.zmby.media.UNTITLED
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.zmby.data.ServerItem
import app.zmby.emby.BaseItemDto
import app.zmby.favorites.Favorite
import app.zmby.ui.Nav
import app.zmby.ui.Route
import app.zmby.ui.components.Health
import app.zmby.ui.components.PosterCard2x3
import app.zmby.ui.open
import app.zmby.ui.theme.Z
import app.zmby.zmby

/**
 * 收藏 (favorites spec): one card per work from the local list, with how many servers have it.
 * Opening the page pulls the two-way servers' favourites (at most every ten minutes).
 */
@Composable
internal fun FavoritesGrid(nav: Nav, search: FocusRequester, entry: FocusRequester) {
    val app = LocalContext.current.zmby
    val favorites by app.favorites.items.collectAsState()
    val servers by app.registry.servers.collectAsState()
    LaunchedEffect(Unit) { app.favorites.pull() }
    Column(Modifier.fillMaxSize()) {
    PageHeader("收藏", favorites.size.takeIf { it > 0 }?.let { "$it 部" }, search) { nav.go(Route.Search) }
    if (favorites.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("还没有收藏", fontSize = 26.sp, color = Z.textSecondary)
        }
        return@Column
    }
    LazyVerticalGrid(
        columns = GridCells.FixedSize(240.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 80.dp, end = 80.dp, top = 40.dp, bottom = 80.dp),
        horizontalArrangement = Arrangement.spacedBy(52.dp),
        verticalArrangement = Arrangement.spacedBy(40.dp),
    ) {
        itemsIndexed(favorites, key = { _, f -> f.workKey }, contentType = { _, _ -> "poster" }) { i, f ->
            // Shown from the first instance on a server that is on and reachable; offline instances stay listed.
            val usable = f.instances.sortedBy { i -> servers.firstOrNull { it.id == i.server }?.let { if (it.enabled && it.health != Health.Off) it.order else 1000 + it.order } ?: 9999 }
                .firstOrNull { i -> servers.any { it.id == i.server } }
            val ref = usable?.let { ServerItem(it.server, display(f, it.itemId)) }
            PosterCard2x3(
                imageUrl = ref?.let { app.library.primaryImage(it) },
                title = visibleText(f.title) ?: UNTITLED,
                onClick = { if (ref != null) nav.open(ref) },
                topEnd = if (f.instances.size > 1) "${f.instances.size} 个版本" else null,
                subtitle = listOfNotNull(if (f.type == "Series") "剧集" else "电影", f.year?.toString()).joinToString(" · "),
                width = 240.dp,
                focusKey = "fav:${f.workKey}",
                modifier = Modifier.then(if (i == 0) Modifier.focusRequester(entry) else Modifier).then(if (i < 6) Modifier.upToSearch(search) else Modifier),
            )
        }
    }
    }
}

private fun display(f: Favorite, itemId: String) = BaseItemDto(
    id = itemId,
    name = f.title,
    type = f.type,
    productionYear = f.year,
    imageTags = f.imageTag?.let { mapOf("Primary" to it) }.orEmpty(),
)
