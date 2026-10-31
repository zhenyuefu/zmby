package app.zmby.ui.screens

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import app.zmby.aggregate.*
import app.zmby.catalog.*
import app.zmby.data.ServerItem
import app.zmby.data.withPlaybackProgress
import app.zmby.data.SourceFeature
import app.zmby.playback.TrackChoice
import app.zmby.ui.components.Health
import app.zmby.zmby
import kotlinx.coroutines.*

private fun catalogVersionKey(work: CatalogWork, episode: CatalogEpisode?): Any =
    listOf("catalog-versions", work.ref, work.ids, episode?.id)

/** Resolve sources from already mapped concrete addresses. Never guess the first numbered copy. */
@Composable
internal fun rememberCatalogVersions(
    work: CatalogWork,
    episode: CatalogEpisode?,
    resources: List<ServerItem>,
    refresh: Int,
    initial: ServerItem? = null,
    /** The version picked by hand last time (server, media source id); it comes first, as in [rememberVersions]. */
    manual: Pair<String, String>? = null,
    onRefresh: (() -> Unit)? = null,
    lookUp: Boolean = true,
    directoryPending: Boolean = false,
): VersionsState {
    val app = LocalContext.current.zmby
    val servers by app.registry.servers.collectAsState()
    val progress by app.recentProgress.collectAsState()
    val rows by app.sources.rows.collectAsState()
    val allowed = remember(servers, rows, resources, initial) { (app.sources.servers(SourceFeature.Aggregate) + resources.map { it.server } + listOfNotNull(initial?.server)).distinct() }
    val lookupKey = remember(work.ref, work.ids, episode?.id) {
        catalogVersionKey(work, episode)
    }
    val targets = if (work.ref.kind == "movie") allowed else resources.map { it.server }.distinct()
    val contexts = remember(resources, initial, targets) { targets.associateWith { server ->
        listOf(resources.filter { it.server == server }.map { it.key }.sorted(),
            initial?.takeIf { it.server == server }?.let { listOf(it.key, it.item.providerIds) })
    } }
    // The resource scope is part of state identity: late directory answers must not show stale mappings.
    val cached = remember(lookupKey, targets, contexts) {
        app.fanOut.cached<List<Version>>(targets, FanOut.SEARCH_TIMEOUT_MS, lookupKey, FanOut.RESOURCE_CACHE_MS) { contexts[it] }
    }
    var results by remember(lookupKey, targets, contexts) { mutableStateOf<Map<String, Outcome<List<Version>>>>(cached) }
    var attempt by remember(lookupKey) { mutableIntStateOf(0) }
    var pending by remember(lookupKey, targets, contexts) { mutableStateOf(targets.filter { it !in cached }) }
    LaunchedEffect(lookupKey, resources, allowed, attempt, refresh, initial, lookUp) {
        if (work.ref.kind == "tv" && episode == null) { results = emptyMap(); pending = emptyList(); return@LaunchedEffect }
        if (!lookUp) return@LaunchedEffect
        results = results.filterKeys { it in targets }
        pending = targets
        app.fanOut.run(targets, FanOut.SEARCH_TIMEOUT_MS, share = lookupKey, cacheMs = FanOut.RESOURCE_CACHE_MS,
            context = { contexts[it] }) { server ->
            val status = app.registry.get(server) ?: return@run emptyList<Version>()
            val items = if (work.ref.kind == "movie") {
                val direct = initial?.takeIf { it.server == server && ResourceMapping.same(work.item(), it.item) }?.item
                val fetched = try { app.library.byProviderIds(server, "Movie", work.ids.map { (k,v) -> "${k.lowercase()}.$v" }) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { if (direct == null) throw e else listOf(direct) }
                (fetched + listOfNotNull(direct)).filter { ResourceMapping.same(work.item(), it) }.distinctBy { it.id }
            } else resources.filter { it.server == server }.map { it.item }
            uniqueVersions(items.flatMap { item ->
                val full = app.library.resourceItem(server, item.id)
                require(full.id == item.id && full.type == item.type) { "片源身份发生变化" }
                val sources = app.library.sources(server, full.id).ifEmpty { full.mediaSources }
                sources.map { Version(status, full, it, episode) }
            })
        }.collect { result ->
            if (result is Outcome.Ok) result.value.forEach { app.playbackArtwork.remember(result.server, it.item.id, work.ref, it.item.seriesId) }
            results = results + (result.server to result); pending = pending - result.server
        }
    }
    LaunchedEffect(results, servers, pending) {
        if (pending.isNotEmpty()) return@LaunchedEffect
        val failed = results.values.filter { it is Outcome.Skipped || it is Outcome.Failed && FanOut.isTransient(it.error) }.map { it.server }
        if (failed.isEmpty()) return@LaunchedEffect
        val now = System.currentTimeMillis()
        val next = failed.minOf { app.registry.get(it)?.coolingUntil?.takeIf { until -> until > now } ?: (now + FailureTracker.COOL_MS) }
        delay((next - now).coerceAtLeast(0) + 50)
        attempt++
    }
    val pattern by app.settings.versionPattern.flow.collectAsState()
    val quality by app.settings.quality.flow.collectAsState()
    val nonDisc by app.settings.preferNonDisc.flow.collectAsState()
    return remember(servers, results, targets, pending, pattern, quality, nonDisc, manual, onRefresh, lookupKey, progress, episode, directoryPending) {
        val groups = results.values.filterIsInstance<Outcome.Ok<List<Version>>>().mapNotNull { o ->
            val status = servers.firstOrNull { it.id == o.server } ?: return@mapNotNull null
            if (o.value.isEmpty()) null else VersionGroup(status, o.value.map { Version(status, it.ref.withPlaybackProgress(progress).item, it.source, episode) }, "作品与季集已匹配")
        }.sortedBy { it.server.order }
        val prefs = VersionPrefs(manual = manual, pattern = VersionPrefs.pattern(pattern), quality = quality, preferNonDisc = nonDisc)
        val ordered = rankCatalogVersions(groups.flatMap { it.versions }, prefs)
        val failed = results.values.filter { it !is Outcome.Ok }.map { it.server }
        VersionsState(groups, pending.size.coerceAtLeast(if (directoryPending) 1 else 0), ordered.firstOrNull(), failed, ordered = ordered, retry = {
            failed.forEach { app.registry.retry(it) }; attempt++
        }, refresh = {
            targets.forEach(app.registry::retry)
            app.fanOut.invalidate(lookupKey, targets)
            onRefresh?.invoke()
            attempt++
        })
    }
}

/** Catalog lookups ranked like [rememberVersions] ranks its own: the hand pick first, then [VersionRanker]. */
internal fun rankCatalogVersions(versions: List<Version>, prefs: VersionPrefs): List<Version> {
    val comparator = VersionRanker.comparator(prefs)
    fun facts(v: Version) = VersionFacts(v.server.id, v.server.order, v.server.health != Health.Off, v.server.latencyMs, v.source)
    return versions.sortedWith { a, b -> comparator.compare(facts(a), facts(b)) }
}
