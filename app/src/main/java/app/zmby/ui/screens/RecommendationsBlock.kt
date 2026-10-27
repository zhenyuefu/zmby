package app.zmby.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.zmby.aggregate.FanOut
import app.zmby.aggregate.Keys
import app.zmby.aggregate.Outcome
import app.zmby.aggregate.Recommendation
import app.zmby.aggregate.RecommendationMerge
import app.zmby.aggregate.StoredRecommendations
import app.zmby.catalog.CatalogId
import app.zmby.data.ServerItem
import app.zmby.data.SourceFeature
import app.zmby.log.ZLog
import app.zmby.media.titleOf
import app.zmby.ui.Nav
import app.zmby.ui.Route
import app.zmby.ui.components.PosterCard2x3
import app.zmby.ui.components.RowTitle
import app.zmby.ui.components.horizontalRow
import app.zmby.ui.open
import app.zmby.zmby
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 为你推荐 of the work on the page (ui-spec §2.14): the stored list at once, then each server copy's
 * Similar (only servers in 资源聚合, and the page's own) and, with a TMDB token and ID, TMDB's
 * recommendations, merged by [RecommendationMerge] and stored again. Null until something is known.
 */
@Composable
internal fun rememberRecommendations(workKey: String, copies: List<ServerItem>, tmdb: CatalogId?, refresh: Int): List<Recommendation>? {
    val app = LocalContext.current.zmby
    val token by app.settings.tmdbToken.flow.collectAsState()
    val sourceRows by app.sources.rows.collectAsState()
    val servers by app.registry.servers.collectAsState()
    var recs by remember(workKey) { mutableStateOf<List<Recommendation>?>(null) }
    LaunchedEffect(workKey) {
        app.catalog.storedRecommendations(workKey)?.let { stored -> if (recs == null) recs = stored.items }
    }
    // One copy per server, from servers that take part in 资源聚合 (the page's own server always).
    val allowed = remember(sourceRows, servers) { app.sources.servers(SourceFeature.Aggregate).toSet() }
    val asked = copies.filter { it.server != "catalog" }.distinctBy { it.server }
        .filter { it.server in allowed || it == copies.firstOrNull() }
    val askedKey = asked.joinToString { it.key }
    LaunchedEffect(workKey, askedKey, tmdb?.key, token, refresh) {
        // Copies come in as servers answer the version lookup: ask once they have settled.
        delay(800)
        val order = servers.associate { it.id to it.order }
        val answers = app.fanOut.all(asked.map { it.server }, FanOut.LIST_TIMEOUT_MS) { server ->
            app.library.similar(server, asked.first { it.server == server }.item.id)
        }
        answers.filterIsInstance<Outcome.Failed>().forEach { ZLog.d("Recs", "Similar from ${app.registry.get(it.server)?.name}: ${it.error.message}") }
        val lists = answers.filterIsInstance<Outcome.Ok<List<app.zmby.emby.BaseItemDto>>>().sortedBy { order[it.server] ?: Int.MAX_VALUE }.map { it.server to it.value }
        val fromTmdb = if (tmdb == null || token.isBlank()) emptyList() else try {
            app.catalog.recommendations(tmdb)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            ZLog.d("Recs", "TMDB recommendations: ${e.message}"); emptyList()
        }
        // Nothing answered: keep what is shown (the stored list) rather than clearing it.
        if (lists.isEmpty() && fromTmdb.isEmpty() && answers.none { it is Outcome.Ok }) return@LaunchedEffect
        val exclude = buildSet {
            add(workKey)
            copies.forEach { c ->
                add(Keys.local(c.server, c.item))
                RecommendationMerge.key(c.item)?.let(::add)
            }
            tmdb?.let { add("${if (it.kind == "movie") "movie" else "series"}:${it.source}:${it.id}") }
        }
        val merged = withContext(Dispatchers.Default) { RecommendationMerge.merge(lists, fromTmdb, exclude) }
        recs = merged
        try {
            app.catalog.storeRecommendations(workKey, StoredRecommendations(merged, System.currentTimeMillis()))
        } catch (e: CancellationException) { throw e } catch (e: Exception) { ZLog.w("Recs", "not stored: ${e.message}") }
    }
    return recs
}

/** The poster of a recommendation: the first copy's, else the TMDB entry's (fetched once in full). */
@Composable
private fun posterOf(rec: Recommendation): String? {
    val app = LocalContext.current.zmby
    val ref = rec.refs.firstOrNull()
    if (ref != null) return app.library.primaryImage(ref)
    val brief = rec.catalog ?: return null
    val full by produceState(brief.poster, brief.ref.key) {
        withContext(Dispatchers.IO) {
            app.catalog.cached(brief.ref)?.poster?.let { value = it }
            if (value == null) try { value = app.catalog.work(brief.ref).poster } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }
    return full
}

/** 「2021 · 7.8」, or 「2024 · 剧集」 for a series. */
internal fun recommendationCaption(rec: Recommendation): String {
    val item = rec.copies.firstOrNull()?.item
    val year = rec.catalog?.year ?: item?.productionYear
    val series = rec.catalog?.ref?.kind == "tv" || item?.type == "Series"
    val rating = rec.catalog?.rating?.takeIf { it > 0 } ?: item?.communityRating?.takeIf { it > 0 }
    return listOfNotNull(year?.toString(), if (series) "剧集" else rating?.let { "%.1f".format(java.util.Locale.ROOT, it) }).joinToString(" · ").ifEmpty { " " }
}

internal fun recommendationTitle(rec: Recommendation): String =
    rec.catalog?.title?.takeIf { it.isNotBlank() } ?: rec.copies.firstOrNull()?.item?.let(::titleOf).orEmpty()

/**
 * 为你推荐 expanded (DetailRecs.dc.html): 240 × 360 posters in one row; 「n 个版本」 in a glass corner,
 * or an outlined 「无资源」 on a half-transparent card that opens the TMDB page.
 */
@Composable
internal fun RecommendationsBlock(nav: Nav, recs: List<Recommendation>, entry: FocusRequester) {
    val app = LocalContext.current.zmby
    val servers by app.registry.servers.collectAsState()
    Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Box(Modifier.padding(horizontal = 80.dp)) { RowTitle("为你推荐", trailing = "${recs.size} 部") }
        LazyRow(Modifier.horizontalRow(), contentPadding = PaddingValues(start = 80.dp, end = 120.dp, bottom = 40.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            itemsIndexed(recs, key = { _, r -> r.key }, contentType = { _, _ -> "recommendation" }) { i, rec ->
                val open = {
                    val ref = rec.refs.sortedBy { r -> servers.firstOrNull { it.id == r.server }?.order ?: Int.MAX_VALUE }.firstOrNull()
                    if (ref != null) nav.open(ref) else rec.catalog?.let { nav.go(Route.Catalog(it.ref)) }
                }
                PosterCard2x3(
                    imageUrl = posterOf(rec),
                    title = recommendationTitle(rec),
                    onClick = { open() },
                    width = 240.dp,
                    topEnd = if (rec.hasResource) "${rec.versions} 个版本" else null,
                    subtitle = recommendationCaption(rec),
                    focusKey = "rec:${rec.key}",
                    dim = if (rec.hasResource) 1f else 0.5f,
                    modifier = if (i == 0) Modifier.focusRequester(entry) else Modifier,
                ) {
                    if (!rec.hasResource) {
                        Text(
                            "无资源",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
                                .border(1.5.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(percent = 50))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}
