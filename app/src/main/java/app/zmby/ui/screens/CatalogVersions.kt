package app.zmby.ui.screens

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import app.zmby.aggregate.*
import app.zmby.catalog.*
import app.zmby.data.ServerItem
import app.zmby.data.SourceFeature
import app.zmby.playback.TrackChoice
import app.zmby.ui.components.Health
import app.zmby.zmby
import kotlinx.coroutines.*

/** Resolve sources from already mapped concrete addresses. Never guess the first numbered copy. */
@Composable
internal fun rememberCatalogVersions(work: CatalogWork, episode: CatalogEpisode?, resources: List<ServerItem>, refresh: Int, initial: ServerItem? = null): VersionsState {
    val app = LocalContext.current.zmby
    val servers by app.registry.servers.collectAsState()
    val rows by app.sources.rows.collectAsState()
    val allowed = remember(servers, rows, resources, initial) { (app.sources.servers(SourceFeature.Aggregate) + resources.map { it.server } + listOfNotNull(initial?.server)).distinct() }
    var results by remember(work.ref.key, episode?.id) { mutableStateOf<Map<String, Outcome<List<Version>>>>(emptyMap()) }
    var attempt by remember(work.ref.key, episode?.id) { mutableIntStateOf(0) }
    LaunchedEffect(work.ref.key, work.ids, episode?.id, resources, allowed, attempt, refresh, initial) {
        results = emptyMap()
        if (work.ref.kind == "tv" && episode == null) return@LaunchedEffect
        val targets = if (work.ref.kind == "movie") allowed else resources.map { it.server }.distinct()
        app.fanOut.run(targets, FanOut.SEARCH_TIMEOUT_MS) { server ->
            val status = app.registry.get(server) ?: return@run emptyList<Version>()
            val items = if (work.ref.kind == "movie") {
                val direct = initial?.takeIf { it.server == server && ResourceMapping.same(work.item(), it.item) }?.item
                val fetched = try { app.library.byProviderIds(server, "Movie", work.ids.map { (k,v) -> "${k.lowercase()}.$v" }) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { if (direct == null) throw e else listOf(direct) }
                (fetched + listOfNotNull(direct)).filter { ResourceMapping.same(work.item(), it) }.distinctBy { it.id }
            } else resources.filter { it.server == server }.map { it.item }
            items.flatMap { item ->
                val full = app.library.resourceItem(server, item.id)
                require(full.id == item.id && full.type == item.type) { "片源身份发生变化" }
                val sources = app.library.sources(server, full.id).ifEmpty { full.mediaSources }
                sources.map { Version(status, full, it) }
            }
        }.collect { results = results + (it.server to it) }
    }
    val pattern by app.settings.versionPattern.flow.collectAsState()
    val quality by app.settings.quality.flow.collectAsState()
    val nonDisc by app.settings.preferNonDisc.flow.collectAsState()
    val targets = if (work.ref.kind == "movie") allowed else resources.map { it.server }.distinct()
    return remember(servers, results, targets, pattern, quality, nonDisc) {
        val groups = results.values.filterIsInstance<Outcome.Ok<List<Version>>>().mapNotNull { o ->
            val status = servers.firstOrNull { it.id == o.server } ?: return@mapNotNull null
            if (o.value.isEmpty()) null else VersionGroup(status, o.value, "外部 ID 与季集映射已确认")
        }.sortedBy { it.server.order }
        val comparator = VersionRanker.comparator(VersionPrefs(pattern = VersionPrefs.pattern(pattern), quality = quality, preferNonDisc = nonDisc))
        val ordered = groups.flatMap { it.versions }.sortedWith { a,b ->
            comparator.compare(VersionFacts(a.server.id, a.server.order, a.server.health != Health.Off, a.server.latencyMs, a.source), VersionFacts(b.server.id, b.server.order, b.server.health != Health.Off, b.server.latencyMs, b.source))
        }
        val failed = results.values.filter { it !is Outcome.Ok }.map { it.server }
        VersionsState(groups, targets.count { it !in results }, ordered.firstOrNull(), failed, ordered = ordered, retry = {
            failed.forEach { app.registry.retry(it) }; attempt++
        })
    }
}
