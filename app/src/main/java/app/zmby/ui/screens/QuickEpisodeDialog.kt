package app.zmby.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import app.zmby.catalog.CatalogSeason
import app.zmby.ui.components.*
import app.zmby.ui.theme.Z
import kotlinx.coroutines.android.awaitFrame

/** 8 columns; more than 5 rows scroll inside the panel (ui-spec §2.15 快速选集). */
internal const val QUICK_PICK_COLUMNS = 8
internal const val QUICK_PICK_ROWS = 5

/** 「漫长的季节 · 当前第 7 集」 under the title; only the name when no episode is current. */
internal fun quickPickSubtitle(series: String, current: Int?): String =
    listOfNotNull(series.ifBlank { null }, current?.let { "当前第 $it 集" }).joinToString(" · ")

/**
 * `.panel` for the detail dialogs: the design frosts what lies behind with a 40 px blur, which API 30
 * cannot do live, so an opaque underlay keeps the page's text from showing through the panel.
 */
internal fun Modifier.dialogPanel(shape: androidx.compose.ui.graphics.Shape): Modifier =
    background(Color(0xFF16171C), shape).panelSurface(shape)

/** The page's dialog dim (`rgba(5,6,8,.62)` in the design) instead of the platform default. */
@Composable
internal fun DialogDim(amount: Float) {
    val view = LocalView.current
    SideEffect { (view.parent as? DialogWindowProvider)?.window?.setDimAmount(amount) }
}

/**
 * 快速选集 (QuickPick.dc.html): a centred 1240 panel with the season segments (each with its count)
 * and 8 columns of episode numbers. The current episode has a 3 px ring, the focused one is white;
 * episodes without a resource or not aired yet are 38 % but can still be picked.
 */
@Composable
internal fun QuickEpisodeDialog(
    seriesName: String,
    seasons: List<CatalogSeason>, season: Int, episodes: List<EpisodeRow>, currentKey: String?,
    onSeason: (Int) -> Unit, onSelect: (EpisodeRow) -> Unit, onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val current = remember { FocusRequester() }
    val seasonRow = remember { FocusRequester() }
    val visible = episodes.takeIf { it.firstOrNull()?.item?.parentIndexNumber == season }.orEmpty()
    val initial = visible.indexOfFirst { it.key == currentKey }.coerceAtLeast(0)
    val grid = rememberLazyGridState(initialFirstVisibleItemIndex = initial / QUICK_PICK_COLUMNS * QUICK_PICK_COLUMNS)
    val currentNumber = remember(currentKey) { episodes.firstOrNull { it.key == currentKey }?.item?.indexNumber }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        DialogDim(0.62f)
        CompositionLocalProvider(LocalDensity provides density, LocalBackdrop provides null) {
            Column(Modifier.width(1240.dp).dialogPanel(RoundedCornerShape(40.dp)).padding(start = 48.dp, end = 48.dp, top = 44.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(30.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Text("快速选集", fontSize = 36.sp, fontWeight = FontWeight.ExtraBold)
                    Text(quickPickSubtitle(seriesName, currentNumber), fontSize = 21.sp, color = Color.White.copy(alpha = 0.65f), maxLines = 1)
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).focusRequester(seasonRow).focusGroup()) {
                    SegmentedWell(seasons.map { Segment(it.title, it.episodes.takeIf { n -> n > 0 }?.toString()) }, seasons.indexOfFirst { it.number == season },
                        { onSeason(seasons[it].number) }, labelPrefix = "快速选集季")
                }
                if (visible.isEmpty()) Text("暂无剧集，目录加载完成后可选集", fontSize = 22.sp, color = Z.textSecondary)
                else {
                    val cell = 92.dp
                    val gap = 16.dp
                    // Room for the focused cell to grow by 8 % without being clipped.
                    val pad = 8.dp
                    LazyVerticalGrid(GridCells.Fixed(QUICK_PICK_COLUMNS), state = grid,
                        modifier = Modifier.fillMaxWidth().heightIn(max = cell * QUICK_PICK_ROWS + gap * (QUICK_PICK_ROWS - 1) + pad * 2),
                        contentPadding = PaddingValues(pad), horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
                        items(visible, key = { it.key }, contentType = { "episode-number" }) { row ->
                            EpisodeNumberCell(row, isCurrent = row.key == currentKey, dim = row.resources.isEmpty(),
                                modifier = if (row.key == currentKey) Modifier.focusRequester(current) else Modifier) { onSelect(row) }
                        }
                    }
                }
                QuickPickFooter()
            }
            LaunchedEffect(Unit) {
                awaitFrame()
                if (!runCatching { current.requestFocus() }.getOrDefault(false)) runCatching { seasonRow.requestFocus() }
            }
            LaunchedEffect(season) { grid.scrollToItem(if (season == episodes.firstOrNull()?.item?.parentIndexNumber) initial / QUICK_PICK_COLUMNS * QUICK_PICK_COLUMNS else 0) }
        }
    }
}

private val CellShape = RoundedCornerShape(22.dp)

/** `.num`: 92 high, 34/700, just the number. */
@Composable
private fun EpisodeNumberCell(row: EpisodeRow, isCurrent: Boolean, dim: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Focusable(onClick, modifier, shape = CellShape, focusedScale = 1.08f, outline = 0.dp, shadow = 0.dp,
        label = "quick-episode:${row.key}", focusKey = "quick-episode:${row.key}") { focused ->
        Box(
            Modifier
                .fillMaxWidth()
                .height(92.dp)
                .alpha(if (dim && !focused) 0.38f else 1f)
                .then(if (focused) Modifier.liftShadow(CellShape).onFill(CellShape) else Modifier.background(Color.White.copy(alpha = 0.08f), CellShape))
                .then(if (isCurrent && !focused) Modifier.border(3.dp, Color.White.copy(alpha = 0.85f), CellShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(row.item.indexNumber?.toString() ?: "?", fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                color = if (focused) Z.onFocus else Z.textPrimary)
        }
    }
}

@Composable
private fun QuickPickFooter() {
    val hint = Color.White.copy(alpha = 0.65f)
    CompositionLocalProvider(LocalContentColor provides hint) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { KeyCap("OK"); Text("跳到这一集", fontSize = 18.sp, color = hint) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { KeyCap("返回"); Text("关闭", fontSize = 18.sp, color = hint) }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(22.dp).border(2.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(7.dp)))
                Text("当前集", fontSize = 18.sp, color = hint)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(22.dp).alpha(0.5f).background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(7.dp)))
                Text("暂无资源或未播出", fontSize = 18.sp, color = hint)
            }
        }
    }
}
