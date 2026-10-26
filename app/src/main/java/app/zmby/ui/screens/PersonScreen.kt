package app.zmby.ui.screens

import app.zmby.media.titleOf
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import app.zmby.store.CastRules
import app.zmby.ui.Nav
import app.zmby.ui.components.GlassButton
import app.zmby.ui.components.Focusable
import app.zmby.ui.components.PosterCard2x3
import app.zmby.ui.components.RowTitle
import app.zmby.ui.components.Segment
import app.zmby.ui.components.SegmentedWell
import app.zmby.ui.open
import app.zmby.ui.rememberInitialFocus
import app.zmby.ui.screenModel
import app.zmby.ui.theme.Z
import app.zmby.zmby

/**
 * Person.dc.html (ui-spec §2.9): a fixed left column (avatar, name, 「1995 年生 · 演员」, a six-line
 * biography with 「完整简介」 when it is cut) and the works on the right — 「作品 n 部」, a type
 * segment when there are films and series, a five-column grid newest first. Focus starts on the
 * first work; the only focusable thing on the left is 「完整简介」.
 */
@Composable
fun PersonScreen(nav: Nav, personKey: String, server: String, personId: String) {
    val app = LocalContext.current.zmby
    val vm = screenModel("person:$server:$personId") { PersonViewModel(app, personKey, server, personId) }
    val person by vm.person.collectAsStateWithLifecycle()
    val works by vm.works.collectAsStateWithLifecycle()
    var fullBio by remember { mutableStateOf(false) }
    val p = person
    if (p?.tmdb?.toLongOrNull()?.let { it > 0 } == true && app.catalog.configured) {
        CatalogPersonScreen(nav, "tmdb", p.tmdb!!)
        return
    }
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(600.dp).fillMaxHeight().padding(start = 80.dp, top = 80.dp, end = 0.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Box(Modifier.width(520.dp)) {
                Avatar(p?.name.orEmpty(), p?.image?.let { app.library.imageUrl(it, "Primary", 600) }, 300.dp, focused = false, letterSize = 110)
            }
            Text(p?.name.orEmpty(), fontSize = 52.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 60.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(520.dp))
            val meta = CastRules.personMeta(p?.birthDate, p?.birthPlace, works.kinds)
            if (meta.isNotEmpty()) Text(meta, fontSize = 22.sp, color = Z.textSecondary)
            val bio = p?.overview?.takeIf { it.isNotBlank() }
            if (bio != null) {
                var cut by remember(bio) { mutableStateOf(false) }
                Text(bio, fontSize = 21.sp, lineHeight = 34.sp, color = Z.textPrimary.copy(alpha = 0.78f), maxLines = 6, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(520.dp), onTextLayout = { cut = it.hasVisualOverflow })
                if (cut) GlassButton("完整简介", { fullBio = true }, height = 56.dp, horizontalPadding = 28.dp, fontSize = 20)
            } else {
                Text("暂无人物简介", fontSize = 21.sp, color = Z.textSecondary)
            }
        }
        var kindIndex by rememberSaveable { mutableIntStateOf(0) }
        val movies = works.works.count { it.lead.item.type == "Movie" }
        val series = works.works.count { it.lead.item.type == "Series" }
        val kinds = listOf(null, "Movie", "Series")
        val shown = remember(works, kindIndex) { kinds[kindIndex]?.let { k -> works.works.filter { it.lead.item.type == k } } ?: works.works }
        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentPadding = PaddingValues(start = 20.dp, end = 80.dp, top = 80.dp, bottom = 80.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalArrangement = Arrangement.spacedBy(36.dp),
        ) {
            item(key = "title", span = { GridItemSpan(maxLineSpan) }, contentType = "title") {
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    RowTitle("作品 ${works.works.size} 部")
                    // Only when there is more than one kind.
                    if (movies > 0 && series > 0) {
                        SegmentedWell(
                            items = listOf(Segment("全部", "${works.works.size}"), Segment("电影", "$movies"), Segment("剧集", "$series")),
                            selected = kindIndex,
                            onSelect = { kindIndex = it },
                            fontSize = 20,
                            labelPrefix = "作品类型",
                        )
                    }
                }
            }
            if (works.loaded && works.works.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }, contentType = "title") {
                    Text("没有找到作品", fontSize = 24.sp, color = Z.textSecondary)
                }
            }
            items(shown, key = { it.lead.key }, contentType = { "poster" }) { w ->
                val first = w === shown.firstOrNull()
                PosterCard2x3(
                    imageUrl = app.library.primaryImage(w.lead),
                    title = titleOf(w.lead.item),
                    onClick = { nav.open(w.lead) },
                    width = 196.dp,
                    topEnd = if (w.versions > 1) "${w.versions} 个版本" else null,
                    subtitle = listOfNotNull(w.year?.toString(), w.part).joinToString(" · ").ifEmpty { null },
                    focusKey = "work:${w.lead.key}",
                    modifier = if (first) Modifier.focusRequester(rememberInitialFocus(w.lead.key)) else Modifier,
                )
            }
        }
    }
    if (fullBio && p?.overview != null) {
        // The whole biography in a card; up and down scroll it, Back closes.
        OverlayPanel({ fullBio = false }, width = 1100.dp) { first ->
            Focusable({ fullBio = false }, Modifier.focusRequester(first), outline = 0.dp, shadow = 0.dp, focusedScale = 1f, label = "完整简介") {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(p.name, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
                    Text(p.overview, fontSize = 22.sp, lineHeight = 36.sp)
                }
            }
        }
    }
}
