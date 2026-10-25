package app.zmby.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import app.zmby.ZmbyApp
import app.zmby.data.ServerItem
import app.zmby.data.ServerStatus
import app.zmby.emby.BaseItemDto
import app.zmby.favorites.WatchlistLogic
import app.zmby.favorites.WatchlistSort
import app.zmby.store.WatchlistRow
import app.zmby.ui.Nav
import app.zmby.ui.components.Health
import app.zmby.ui.components.PosterCard2x3
import app.zmby.ui.components.Segment
import app.zmby.ui.components.SegmentedWell
import app.zmby.ui.components.onMenuKey
import app.zmby.ui.open
import app.zmby.ui.theme.Z
import app.zmby.zmby
import kotlinx.coroutines.launch

/**
 * 待看 (favorites spec): one card per work from the local list, newest added first (or by title or
 * release date); opening a card goes to the first instance on a server that is on and reachable.
 * The menu key on a card takes it off the list.
 */
@Composable
internal fun WatchlistGrid(nav: Nav) {
    val app = LocalContext.current.zmby
    val entries by app.watchlist.entries.collectAsStateWithLifecycle()
    val servers by app.registry.servers.collectAsStateWithLifecycle()
    var sortIndex by rememberSaveable { mutableIntStateOf(0) }
    val sort = WatchlistSort.entries[sortIndex]
    val shown = remember(entries, sort) { WatchlistLogic.sorted(entries, sort) }
    val scope = rememberCoroutineScope()
    if (entries.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("待看是空的", fontSize = 26.sp, color = Z.textSecondary) }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.FixedSize(196.dp),
        modifier = Modifier.fillMaxSize().padding(top = 120.dp),
        contentPadding = PaddingValues(start = 80.dp, end = 80.dp, top = 30.dp, bottom = 80.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalArrangement = Arrangement.spacedBy(36.dp),
    ) {
        item(key = "sort", span = { GridItemSpan(maxLineSpan) }, contentType = "toolbar") {
            SegmentedWell(
                items = WatchlistSort.entries.map { Segment(it.label) },
                selected = sortIndex,
                onSelect = { sortIndex = it },
                fontSize = 21,
                horizontalPadding = 24.dp,
                labelPrefix = "待看排序",
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        items(shown, key = { it.workKey }, contentType = { "poster" }) { e ->
            val ref = watchlistRef(e, servers)
            PosterCard2x3(
                imageUrl = ref?.let { app.library.primaryImage(it) },
                title = e.title,
                onClick = { if (ref != null) nav.open(ref) },
                topEnd = if (e.instances.size > 1) "${e.instances.size} 个版本" else null,
                subtitle = listOfNotNull(if (e.type == "Series") "剧集" else "电影", e.year?.toString()).joinToString(" · "),
                focusKey = "watch:${e.workKey}",
                modifier = Modifier.onMenuKey { if (ref != null) scope.launch { app.watchlist.toggle(ref) } },
            )
        }
    }
}

/** The instance to open: the first on a server that is on and reachable; offline ones stay listed. */
internal fun watchlistRef(e: WatchlistRow, servers: List<ServerStatus>): ServerItem? {
    val pairs = e.instances.mapNotNull { p -> p.split('|', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
    val (server, id) = pairs.sortedBy { (s, _) -> servers.firstOrNull { it.id == s }?.let { if (it.enabled && it.health != Health.Off) it.order else 1000 + it.order } ?: 9999 }
        .firstOrNull { (s, _) -> servers.any { it.id == s } } ?: return null
    // The poster tag belongs to the first instance; another server's poster comes without a tag.
    val tag = e.imageTag?.takeIf { pairs.firstOrNull() == (server to id) }
    return ServerItem(server, BaseItemDto(id = id, name = e.title, type = e.type, productionYear = e.year, imageTags = tag?.let { mapOf("Primary" to it) }.orEmpty()))
}

/** Adds or removes [ref]'s work from 待看 (the menu key on posters, the 待看 buttons). */
internal fun ZmbyApp.toggleWatchlist(scope: kotlinx.coroutines.CoroutineScope, ref: ServerItem, instances: List<ServerItem> = emptyList()) {
    scope.launch { watchlist.toggle(ref, instances) }
}
