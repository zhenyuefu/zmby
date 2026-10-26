package app.zmby.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.zmby.aggregate.*
import app.zmby.catalog.*
import app.zmby.data.ServerItem
import app.zmby.data.SourceFeature
import app.zmby.emby.BaseItemDto
import app.zmby.store.ImageRef
import app.zmby.playback.TrackChoice
import app.zmby.ui.*
import app.zmby.ui.components.*
import app.zmby.ui.theme.Z
import app.zmby.zmby
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Alternate database directories require an explicit choice when season offsets are unknown. */
@Composable
internal fun CatalogRelated(nav: Nav, entry: CatalogWork?, fallback: ServerItem?) {
    val work = entry ?: return
    val app = LocalContext.current.zmby
    val scope = rememberCoroutineScope()
    if (work.ref.source == "tmdb") {
        val aliases by app.settings.animeAliases.flow.collectAsState()
        val related by produceState<CatalogId?>(null, work.ref, aliases) {
            if (aliases && work.genres.any { it in setOf("动画", "動畫", "Animation", "アニメーション", "애니메이션") }) withContext(Dispatchers.IO) {
                try {
                    app.catalog.alias(work.item())?.let { row -> AnimeAliases.subject(row) }?.let { value = CatalogId("bangumi", work.ref.kind, it) }
                } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
        }
        related?.let { bgm -> GlassButton("Bangumi 番剧资料与章节（独立编号）", { nav.go(Route.Catalog(bgm, fallback?.server, fallback?.item?.id)) }, Modifier.padding(horizontal = 80.dp)) }
    }
    if (work.ref.source == "bangumi") {
        var candidates by remember(work.ref.key) { mutableStateOf<List<CatalogWork>?>(null) }
        var matchingError by remember(work.ref.key) { mutableStateOf<String?>(null) }
        GlassButton("查找对应 TMDB 作品", { scope.launch(Dispatchers.IO) {
            try { candidates = app.catalog.tmdbCandidates(work); matchingError = null }
            catch (e: CancellationException) { throw e } catch (e: Exception) { matchingError = e.message }
        } }, Modifier.padding(horizontal = 80.dp))
        matchingError?.let { Text(it, color = Z.warn, modifier = Modifier.padding(horizontal = 80.dp)) }
        candidates?.let { rows -> CatalogCards(nav, rows, "TMDB 候选 · 请核对作品与季", fallback) }
    }
}

@Composable
internal fun CanonicalCast(nav: Nav, cast: List<CatalogPerson>) {
    if (cast.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("演职人员", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 80.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 80.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            items(cast, key = { it.key }, contentType = { "catalogPerson" }) { person ->
                Focusable({ nav.go(Route.CatalogPerson(person.source, person.id)) }, Modifier.width(160.dp), focusKey = "catalogCast:${person.key}", label = person.name) { focused ->
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Avatar(person.name, person.image, 140.dp, focused, 52)
                        Text(person.name, fontSize = 20.sp, maxLines = 1)
                        Text(person.role, fontSize = 17.sp, maxLines = 2, color = Z.textSecondary)
                    }
                }
            }
        }
    }
}

@Composable
internal fun CatalogCards(nav: Nav, works: List<CatalogWork>, title: String, fallback: ServerItem? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        if (works.isEmpty()) Text("没有找到匹配条目", color = Z.textSecondary)
        LazyRow(contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            items(works, key = { it.ref.key }, contentType = { "catalogPoster" }) { w ->
                CatalogPoster(nav, w, fallback)
            }
        }
    }
}

@Composable
fun CatalogPersonScreen(nav: Nav, source: String, id: String) {
    val app = LocalContext.current.zmby
    val language by app.settings.metadataLanguage.flow.collectAsState()
    var retry by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val data by produceState<CatalogBiography?>(null, source, id, language, retry) {
        withContext(Dispatchers.IO) {
            value = app.catalog.cachedPerson(source, id)
            try { value = app.catalog.person(source, id); error = null }
            catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
        }
    }
    val person = data
    if (person == null) { LoadState(error, { retry++ }); return }
    val focus = rememberInitialFocus("$source:$id")
    Row(Modifier.fillMaxSize().padding(80.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        Column(Modifier.width(500.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Avatar(person.name, person.image, 220.dp, false, 72)
            Text(person.name, fontSize = 42.sp, fontWeight = FontWeight.Bold)
            if (person.birthDate != null || person.birthPlace != null) Text(listOfNotNull(person.birthDate, person.birthPlace).joinToString(" · "), fontSize = 20.sp)
            Text(person.overview.ifBlank { "暂无人物简介" }, fontSize = 21.sp, color = Z.textSecondary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Text("作品 ${person.works.size} 部", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            LazyVerticalGrid(columns = GridCells.Adaptive(196.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(28.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                items(person.works, key = { it.ref.key }, contentType = { "personWork" }) { w ->
                    CatalogPoster(nav, w, null, if (w.ref == person.works.first().ref) Modifier.focusRequester(focus) else Modifier)
                }
            }
        }
    }
}

@Composable
fun CatalogRouteScreen(nav: Nav, route: Route.Catalog) {
    val server = route.fallbackServer ?: "catalog"
    val id = route.fallbackId ?: route.ref.key
    if (route.ref.kind == "tv") SeriesScreen(nav, server, id, provider = route.ref)
    else DetailScreen(nav, server, id, provider = route.ref)
}

/** The first response from the selected server, before a catalogue identity can be resolved. */
@Composable
internal fun ServerCast(nav: Nav, ref: ServerItem) {
    val app = LocalContext.current.zmby
    val people = remember(ref.item.people) { ref.item.people.filter { it.id != null && !it.name.isNullOrBlank() && it.type in setOf("Actor", "Director", "Writer") }
        .groupBy { it.id }.values.map { it.first().copy(role = it.mapNotNull { p -> p.role ?: p.type }.distinct().joinToString(" / ")) } }
    if (people.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("演职人员", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 80.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 80.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            items(people, key = { it.id!! }, contentType = { "serverPerson" }) { p ->
                Focusable({
                    val tmdb = p.providerIds.entries.firstOrNull { it.key.equals("tmdb", true) }?.value
                    if (tmdb?.toLongOrNull()?.let { it > 0 } == true) nav.go(Route.CatalogPerson("tmdb", tmdb))
                    else nav.go(Route.Person("local:${ref.server}:${p.id}", ref.server, p.id!!))
                }, Modifier.width(160.dp), focusKey = "serverCast:${ref.server}:${p.id}", label = p.name.orEmpty()) { focused ->
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Avatar(p.name.orEmpty(), p.primaryImageTag?.let { app.library.imageUrl(ImageRef(ref.server, p.id!!, it), "Primary", 280) }, 140.dp, focused, 52)
                        Text(p.name.orEmpty(), fontSize = 20.sp, maxLines = 1)
                        Text(p.role.orEmpty(), fontSize = 17.sp, maxLines = 2)
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogPoster(nav: Nav, brief: CatalogWork, fallback: ServerItem?, modifier: Modifier = Modifier) {
    val app = LocalContext.current.zmby
    val text by app.settings.metadataLanguage.flow.collectAsState()
    val poster by app.settings.posterLanguage.flow.collectAsState()
    val logo by app.settings.logoLanguage.flow.collectAsState()
    val full by produceState(brief, brief.ref, listOf(text, poster, logo)) {
        withContext(Dispatchers.IO) {
            value = app.catalog.cached(brief.ref) ?: brief
            try { value = app.catalog.work(brief.ref) }
            catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }
    PosterCard2x3(full.poster, full.title, { nav.go(Route.Catalog(full.ref, fallback?.server, fallback?.item?.id)) }, subtitle = full.year?.toString(), focusKey = "catalog:${full.ref.key}", modifier = modifier)
}
