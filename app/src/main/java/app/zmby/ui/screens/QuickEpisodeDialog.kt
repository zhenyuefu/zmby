package app.zmby.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import app.zmby.catalog.CatalogSeason
import app.zmby.ui.components.*
import app.zmby.ui.theme.Z
import kotlinx.coroutines.android.awaitFrame

/** A compact grid reaches a distant episode without traversing every landscape card. */
@Composable
internal fun QuickEpisodeDialog(
    seasons: List<CatalogSeason>, season: Int, episodes: List<EpisodeRow>, currentKey: String?,
    onSeason: (Int) -> Unit, onSelect: (EpisodeRow) -> Unit, onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val current = remember { FocusRequester() }
    val close = remember { FocusRequester() }
    val visible = episodes.takeIf { it.firstOrNull()?.item?.parentIndexNumber == season }.orEmpty()
    val initial = visible.indexOfFirst { it.key == currentKey }.coerceAtLeast(0)
    val grid = rememberLazyGridState(initialFirstVisibleItemIndex = initial / 8 * 8)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalDensity provides density, LocalBackdrop provides null) {
            Column(Modifier.width(1360.dp).heightIn(max = 900.dp).panelSurface(RoundedCornerShape(30.dp)).padding(36.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("快速选集", fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    GlassButton("关闭", onDismiss, Modifier.focusRequester(close))
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    SegmentedWell(seasons.map { Segment(it.title) }, seasons.indexOfFirst { it.number == season },
                        { onSeason(seasons[it].number) }, labelPrefix = "快速选集季")
                }
                if (visible.isEmpty()) Text("暂无剧集，目录加载完成后可选集", fontSize = 22.sp, color = Z.textSecondary)
                else LazyVerticalGrid(GridCells.Fixed(8), state = grid, modifier = Modifier.heightIn(max = 600.dp),
                    contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    items(visible, key = { it.key }, contentType = { "episode-number" }) { row ->
                        val selected = row.key == currentKey
                        GlassSurface({ onSelect(row) },
                            Modifier.then(if (selected) Modifier.focusRequester(current) else Modifier),
                            on = selected, label = "quick-episode:${row.key}") {
                            Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                                Text(row.item.indexNumber?.toString() ?: "?",
                                    fontSize = 24.sp, textAlign = TextAlign.Center, maxLines = 1)
                            }
                        }
                    }
                }
            }
            LaunchedEffect(Unit) {
                awaitFrame()
                if (!runCatching { current.requestFocus() }.getOrDefault(false)) close.requestFocus()
            }
            LaunchedEffect(season) { grid.scrollToItem(if (season == episodes.firstOrNull()?.item?.parentIndexNumber) initial / 8 * 8 else 0) }
        }
    }
}
