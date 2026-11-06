package app.zmby.ui.screens

import app.zmby.ZmbyApp
import app.zmby.aggregate.FailureTracker
import app.zmby.aggregate.FanOut
import app.zmby.aggregate.Outcome
import app.zmby.aggregate.ServerVersions
import app.zmby.aggregate.VersionPrefs
import app.zmby.aggregate.VersionRanker
import app.zmby.aggregate.versionPrefs
import app.zmby.aggregate.versionPrefsFlow
import app.zmby.catalog.CatalogEpisode
import app.zmby.catalog.CatalogWork
import app.zmby.catalog.ResourceMapping
import app.zmby.data.ServerItem
import app.zmby.data.ServerStatus
import app.zmby.data.SourceFeature
import app.zmby.data.withPlaybackProgress
import app.zmby.emby.BaseItemDto
import app.zmby.emby.UserItemData
import app.zmby.log.ZLog
import app.zmby.aggregate.Keys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import app.zmby.data.newestProgress
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A [VersionsState] and the lookup it belongs to, so a screen never shows another item's versions. */
internal class TaggedVersions(val identity: Any?, val value: VersionsState)

/** The servers 资源聚合 asks, in priority order; changes only with the configuration and the data sources. */
/** One copy found by a catalogue version lookup, as stored (统一数据层 ③). */
@kotlinx.serialization.Serializable
internal data class StoredCopy(val item: app.zmby.emby.BaseItemDto, val source: app.zmby.emby.MediaSourceInfo)

private val CATALOG_PERSIST = app.zmby.aggregate.Persist(FanOut.KIND_VERSIONS + ":catalog", kotlinx.serialization.builtins.ListSerializer(StoredCopy.serializer()))

private fun aggregating(app: ZmbyApp) = combine(app.registry.lineup, app.sources.rows) { _, _ -> app.sources.servers(SourceFeature.Aggregate) }
    .distinctUntilChanged()

/**
 * The versions of a server item across servers ([rememberVersions], performance plan R2): asks the
 * other aggregating servers, retries paused ones after their cool-down, and builds and ranks the
 * cards on Dispatchers.Default. Lives as long as [scope]; [update] gives it what the screen shows.
 */
internal class ServerVersionLookup(private val app: ZmbyApp, private val scope: CoroutineScope) {
    data class Input(
        val server: String,
        val item: BaseItemDto,
        val series: BaseItemDto?,
        val lookUp: Boolean,
        val cached: Map<String, ServerVersions.Found>,
        val forKey: String?,
    ) {
        val identity: Any get() = listOf(server, item.id)
    }

    private class Found(val identity: Any?, val map: Map<String, ServerVersions>)
    private data class LookupKey(val server: String, val itemId: String, val type: String?, val seriesId: String?, val lookUp: Boolean)

    private val input = MutableStateFlow<Input?>(null)
    private val found = MutableStateFlow(Found(null, emptyMap()))
    private val retry = MutableStateFlow(0)
    @Volatile var onRefresh: (() -> Unit)? = null

    private val others = combine(input.filterNotNull().map { it.server }.distinctUntilChanged(), aggregating(app)) { server, all -> all.filter { it != server } }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    fun update(next: Input) {
        if (input.value?.identity != next.identity) {
            found.value = Found(next.identity, emptyMap())
            retry.value = 0
        }
        input.value = next
    }

    private fun foundFor(identity: Any?) = found.value.takeIf { it.identity == identity }?.map.orEmpty()

    val state: StateFlow<TaggedVersions?> = combine(input.filterNotNull(), found, app.registry.servers, app.settings.versionPrefsFlow(), others.filterNotNull()) { i, f, unordered, prefs, o ->
        val servers = app.sources.inOrder(SourceFeature.Aggregate, unordered)
        TaggedVersions(i.identity, compute(i, f.takeIf { it.identity == i.identity }?.map.orEmpty(), servers, prefs, o))
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, null)

    /** The value for [i] right now, for the frame before [state] has caught up with a new item. */
    fun computeNow(i: Input): VersionsState =
        compute(i, foundFor(i.identity), app.sources.inOrder(SourceFeature.Aggregate, app.registry.all), app.settings.versionPrefs(), app.sources.servers(SourceFeature.Aggregate).filter { it != i.server })

    init {
        scope.launch {
            combine(input.filterNotNull().map { LookupKey(it.server, it.item.id, it.item.type, it.series?.id, it.lookUp) }.distinctUntilChanged(), others.filterNotNull(), retry) { k, o, _ -> k to o }
                .collectLatest { (key, todoServers) ->
                    val now = input.value ?: return@collectLatest
                    if (todoServers.isEmpty() || !key.lookUp) return@collectLatest
                    val todo = todoServers.filter { it !in foundFor(now.identity) }
                    if (todo.isEmpty()) return@collectLatest
                    val flow = when {
                        now.item.type == "Episode" && now.series != null -> app.versions.episode(todo, now.item, now.series)
                        now.item.type == "Movie" || now.item.type == "Video" -> app.versions.movie(todo, now.item)
                        else -> return@collectLatest
                    }
                    flow.collect { r ->
                        if (r !is ServerVersions.Found) ZLog.d("Versions", "${app.registry.get(r.server)?.name}: $r")
                        found.update { f -> if (f.identity == now.identity) Found(f.identity, f.map + (r.server to r)) else f }
                        now.forKey?.let { DetailViewModel.rememberVersions(app, it, r) }
                    }
                }
        }
        // Paused and transiently failed servers are asked again when their cool-down ends.
        scope.launch {
            combine(found, input.filterNotNull().map { it.lookUp }.distinctUntilChanged()) { f, l -> f to l }.collectLatest { (f, lookUp) ->
                if (!lookUp) return@collectLatest
                val paused = f.map.values.filter { it is ServerVersions.Skipped || it is ServerVersions.Failed && it.retryable }
                if (paused.isEmpty()) return@collectLatest
                val now = System.currentTimeMillis()
                val deadline = paused.minOf { app.registry.get(it.server)?.coolingUntil?.takeIf { until -> until > now } ?: (now + FailureTracker.COOL_MS) }
                delay((deadline - System.currentTimeMillis()).coerceAtLeast(0) + 50)
                val ready = paused.filter { !app.registry.isCooling(it.server) }.map { it.server }.toSet()
                if (ready.isNotEmpty()) {
                    found.update { cur -> if (cur.identity == f.identity) Found(cur.identity, cur.map.filterKeys { it !in ready }) else cur }
                    retry.value++
                }
            }
        }
    }

    private fun compute(i: Input, found: Map<String, ServerVersions>, servers: List<ServerStatus>, rankPrefs: VersionPrefs, others: List<String>): VersionsState {
        val self = servers.firstOrNull { it.id == i.server }
        val (groups, unresponsive) = versionCards(self, i.item, servers.filter { it.id in others }, found, i.cached)
        val prefs = rankPrefs.copy(
            manual = app.library.pickedVersion(Keys.work(i.server, if (i.item.type == "Episode" && i.series != null) i.series else i.item) + episodeSuffix(i.item)),
        )
        val all = groups.flatMap { it.versions }
        val ordered = VersionRanker.rank(all, prefs, ::versionFacts)
        return VersionsState(groups, if (i.lookUp) others.count { it !in found } else 0, ordered.firstOrNull(), unresponsive,
            ordered = ordered, marks = versionMarks(all, prefs),
            retry = {
                // 「n 台未响应」 pressed: lift their cool-downs and ask those servers again.
                unresponsive.forEach { app.registry.retry(it) }
                this.found.update { f -> if (f.identity == i.identity) Found(f.identity, f.map.filterKeys { it !in unresponsive }) else f }
                retry.value++
            },
            refresh = {
                others.forEach(app.registry::retry)
                app.versions.invalidate(others, i.item, i.series)
                this.found.update { f -> if (f.identity == i.identity) Found(f.identity, emptyMap()) else f }
                onRefresh?.invoke()
                retry.value++
            })
    }
}

internal fun catalogVersionKey(work: CatalogWork, episode: CatalogEpisode?): Any =
    listOf("catalog-versions", work.ref, work.ids, episode?.id)

/**
 * The versions of a catalogue work or episode from its mapped server copies ([rememberCatalogVersions],
 * performance plan R2): asks the servers holding them, retries transient failures after the
 * cool-down and ranks on Dispatchers.Default. Never guesses the first numbered copy.
 */
internal class CatalogVersionLookup(private val app: ZmbyApp, private val scope: CoroutineScope) {
    data class Input(
        val work: CatalogWork,
        val episode: CatalogEpisode?,
        val resources: List<ServerItem>,
        val refresh: Int,
        val initial: ServerItem?,
        val manual: Pair<String, String>?,
        val lookUp: Boolean,
        val directoryPending: Boolean,
    )

    /** What the input means with the current data sources. */
    private data class Scope(val allowed: List<String>, val lookupKey: Any, val targets: List<String>, val contexts: Map<String, Any?>, val stateKey: Any)

    private class Results(val stateKey: Any?, val map: Map<String, Outcome<List<Version>>>, val pending: List<String>)

    private data class Active(val input: Input, val scope: Scope)

    private val active = MutableStateFlow<Active?>(null)
    private val results = MutableStateFlow(Results(null, emptyMap(), emptyList()))
    private val attempt = MutableStateFlow(0)
    @Volatile var onRefresh: (() -> Unit)? = null

    private fun scopeOf(i: Input, aggregating: List<String>): Scope {
        val allowed = (aggregating + i.resources.map { it.server } + listOfNotNull(i.initial?.server)).distinct()
        val lookupKey = catalogVersionKey(i.work, i.episode)
        val targets = if (i.work.ref.kind == "movie") allowed else i.resources.map { it.server }.distinct()
        val contexts = targets.associateWith { server ->
            listOf(i.resources.filter { it.server == server }.map { it.key }.sorted(),
                i.initial?.takeIf { it.server == server }?.let { listOf(it.key, it.item.providerIds) })
        }
        // For episodes the resource scope is part of state identity: late directory answers must not show
        // stale mappings. A film keeps its answers while the lookup reruns with fuller IDs (the TMDB entry
        // knows only the TMDB id until the catalogue answers); each server's answer replaces its own as it
        // comes, so the play button does not fall back to 「正在查找资源…」 and back.
        val stateKey: Any = if (i.work.ref.kind == "movie") listOf(i.work.ref, targets) else listOf(lookupKey, targets, contexts)
        return Scope(allowed, lookupKey, targets, contexts, stateKey)
    }

    /** The memory-cached answers for the first frame; they are stored copies, made into versions here. */
    private fun cachedResults(i: Input, s: Scope): Results {
        val cached = app.fanOut.cached<List<StoredCopy>>(s.targets, FanOut.SEARCH_TIMEOUT_MS, s.lookupKey, FanOut.RESOURCE_CACHE_MS) { s.contexts[it] }
            .mapValues { (_, ok) -> versionsOf(ok, i.episode) }
        return Results(s.stateKey, cached, s.targets.filter { it !in cached })
    }

    /** A lookup's stored copies as versions of this server, with its current status. */
    private fun versionsOf(stored: Outcome.Ok<List<StoredCopy>>, episode: app.zmby.catalog.CatalogEpisode?): Outcome.Ok<List<Version>> {
        val status = app.registry.get(stored.server)
        return Outcome.Ok(stored.server, if (status == null) emptyList() else uniqueVersions(stored.value.map { Version(status, it.item, it.source, episode) }), stored.ms)
    }

    private fun apply(i: Input) {
        val s = scopeOf(i, app.sources.servers(SourceFeature.Aggregate))
        val previous = active.value?.scope
        if (previous?.stateKey != s.stateKey) results.value = cachedResults(i, s)
        if (previous?.lookupKey != s.lookupKey) attempt.value = 0
        active.value = Active(i, s)
    }

    fun update(next: Input) {
        if (active.value?.input != next) apply(next)
    }

    val state: StateFlow<TaggedVersions?> = combine(active.filterNotNull(), results, app.registry.servers, app.settings.versionPrefsFlow(), app.recentProgress) { a, r, unordered, prefs, progress ->
        val servers = app.sources.inOrder(SourceFeature.Aggregate, unordered)
        TaggedVersions(a.scope.lookupKey, compute(a, r.takeIf { it.stateKey == a.scope.stateKey } ?: Results(a.scope.stateKey, emptyMap(), a.scope.targets), servers, prefs, progress))
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, null)

    /** The value for [i] right now, for the frame before [state] has caught up with another episode. */
    fun computeNow(i: Input): VersionsState {
        val s = scopeOf(i, app.sources.servers(SourceFeature.Aggregate))
        val r = results.value.takeIf { it.stateKey == s.stateKey } ?: cachedResults(i, s)
        return compute(Active(i, s), r, app.sources.inOrder(SourceFeature.Aggregate, app.registry.all), app.settings.versionPrefs(), app.recentProgress.value)
    }

    init {
        // A data source switched on or off changes who is asked.
        scope.launch { aggregating(app).drop(1).collect { active.value?.input?.let(::apply) } }
        scope.launch {
            combine(active.filterNotNull(), attempt) { a, t -> a to t }
                .distinctUntilChanged { old, new ->
                    old.second == new.second && old.first.scope.lookupKey == new.first.scope.lookupKey && old.first.scope.allowed == new.first.scope.allowed &&
                        old.first.input.let { o -> new.first.input.let { n -> o.resources == n.resources && o.refresh == n.refresh && o.initial == n.initial && o.lookUp == n.lookUp } }
                }
                .collectLatest { (a, _) -> lookUp(a) }
        }
        // Remember danmaku queries for what was found (off the main thread).
        scope.launch {
            results.collectLatest { r ->
                val work = active.value?.input?.work ?: return@collectLatest
                withContext(Dispatchers.Default) {
                    r.map.values.filterIsInstance<Outcome.Ok<List<Version>>>().forEach { result -> result.value.forEach { v ->
                        app.danmuQueries.remember(v.server.id, v.item.id, work, v.canonicalEpisode, v.item.seriesId)
                    } }
                }
            }
        }
        // Transient failures are asked again when their cool-down ends.
        scope.launch {
            results.collectLatest { r ->
                if (r.pending.isNotEmpty()) return@collectLatest
                val failed = r.map.values.filter { it is Outcome.Skipped || it is Outcome.Failed && FanOut.isTransient(it.error) }.map { it.server }
                if (failed.isEmpty()) return@collectLatest
                val now = System.currentTimeMillis()
                val next = failed.minOf { app.registry.get(it)?.coolingUntil?.takeIf { until -> until > now } ?: (now + FailureTracker.COOL_MS) }
                delay((next - now).coerceAtLeast(0) + 50)
                attempt.value++
            }
        }
    }

    private suspend fun lookUp(a: Active) {
        val (i, s) = a
        if (i.work.ref.kind == "tv" && i.episode == null) { results.value = Results(s.stateKey, emptyMap(), emptyList()); return }
        if (!i.lookUp) return
        results.update { r -> if (r.stateKey == s.stateKey) Results(r.stateKey, r.map.filterKeys { it in s.targets }, s.targets) else r }
        // The shared (and stored) answer is each copy's item and source; versions are made from it
        // here, with the server's current status (统一数据层 ③: kept across restarts).
        app.fanOut.run(s.targets, FanOut.SEARCH_TIMEOUT_MS, share = s.lookupKey, cacheMs = FanOut.RESOURCE_CACHE_MS,
            context = { s.contexts[it] }, persist = CATALOG_PERSIST) { server ->
            if (app.registry.get(server) == null) return@run emptyList<StoredCopy>()
            val items = if (i.work.ref.kind == "movie") {
                val direct = i.initial?.takeIf { it.server == server && ResourceMapping.same(i.work.item(), it.item) }?.item
                val fetched = try { app.library.byProviderIds(server, "Movie", i.work.ids.map { (k, v) -> "${k.lowercase()}.$v" }) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { if (direct == null) throw e else listOf(direct) }
                (fetched + listOfNotNull(direct)).filter { ResourceMapping.same(i.work.item(), it) }.distinctBy { it.id }
            } else i.resources.filter { it.server == server }.map { it.item }
            items.flatMap { item ->
                val full = app.library.resourceItem(server, item.id)
                require(full.id == item.id && full.type == item.type) { "片源身份发生变化" }
                val sources = app.library.sources(server, full.id).ifEmpty { full.mediaSources }
                sources.map { StoredCopy(full, it) }
            }
        }.collect { stored ->
            val result: Outcome<List<Version>> = when (stored) {
                is Outcome.Ok -> versionsOf(stored, i.episode)
                is Outcome.Failed -> stored
                is Outcome.Skipped -> stored
            }
            if (result is Outcome.Ok) result.value.forEach { app.playbackArtwork.remember(result.server, it.item.id, i.work.ref, it.item.seriesId) }
            results.update { r -> if (r.stateKey == s.stateKey) Results(r.stateKey, r.map + (result.server to result), r.pending - result.server) else r }
        }
    }

    private fun compute(a: Active, r: Results, servers: List<ServerStatus>, rankPrefs: VersionPrefs, progress: Map<String, UserItemData>): VersionsState {
        val (i, s) = a
        val groups = r.map.values.filterIsInstance<Outcome.Ok<List<Version>>>().mapNotNull { o ->
            val status = servers.firstOrNull { it.id == o.server } ?: return@mapNotNull null
            if (o.value.isEmpty()) null else VersionGroup(status, o.value.map { v ->
                val item = v.ref.withPlaybackProgress(progress).item
                Version(status, item.copy(originalLanguage = item.originalLanguage ?: i.work.originalLanguage), v.source, i.episode)
            }, "")
        }.sortedBy { it.server.order }
        val prefs = rankPrefs.copy(manual = i.manual)
        val all = groups.flatMap { it.versions }
        val ordered = rankCatalogVersions(all, prefs)
        val failed = r.map.values.filter { it !is Outcome.Ok }.map { it.server }
        return VersionsState(groups, r.pending.size.coerceAtLeast(if (i.directoryPending) 1 else 0), ordered.firstOrNull(), failed,
            ordered = ordered, marks = versionMarks(all, prefs),
            retry = { failed.forEach { app.registry.retry(it) }; attempt.value++ },
            refresh = {
                s.targets.forEach(app.registry::retry)
                app.fanOut.invalidate(s.lookupKey, s.targets, CATALOG_PERSIST) { s.contexts[it] }
                onRefresh?.invoke()
                attempt.value++
            })
    }
}

/** What a page asks the version lookup: one item in catalogue or server terms, or nothing yet. */
internal sealed interface VersionQuery {
    data class Catalog(val input: CatalogVersionLookup.Input) : VersionQuery
    data class Server(val input: ServerVersionLookup.Input) : VersionQuery
    data object None : VersionQuery
}

/**
 * The versions of what a detail or series page shows and their merged progress, off the main thread
 * (performance plan R3 / R4): feeds [queries] to the two lookups, never pairs an item with another
 * item's versions, and merges each copy's progress (store, this run's reports, the last start here).
 * Only the page sections that show versions read [view].
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class VersionPresenter(
    private val app: ZmbyApp,
    scope: CoroutineScope,
    queries: kotlinx.coroutines.flow.Flow<Pair<EpisodeRow?, VersionQuery>>,
    tick: kotlinx.coroutines.flow.Flow<Int>,
    onServerRefresh: (() -> Unit)?,
    onCatalogRefresh: (() -> Unit)?,
) {
    private val serverVersions = ServerVersionLookup(app, scope).also { it.onRefresh = onServerRefresh }
    private val catalogVersions = CatalogVersionLookup(app, scope).also { it.onRefresh = onCatalogRefresh }

    @Volatile private var lastServer: ServerVersionLookup.Input? = null
    @Volatile private var lastCatalog: CatalogVersionLookup.Input? = null

    /**
     * The lookup not in use is paused (its last input with lookUp off), as leaving the page section did
     * before: a film page asks in server terms until the external IDs are known, then in catalogue terms,
     * and the first lookup must not keep running and writing cards beside the second.
     */
    private fun ask(q: VersionQuery) {
        if (q !is VersionQuery.Server) lastServer?.takeIf { it.lookUp }?.let { serverVersions.update(it.copy(lookUp = false).also { p -> lastServer = p }) }
        if (q !is VersionQuery.Catalog) lastCatalog?.takeIf { it.lookUp }?.let { catalogVersions.update(it.copy(lookUp = false).also { p -> lastCatalog = p }) }
        when (q) {
            is VersionQuery.Catalog -> { lastCatalog = q.input; catalogVersions.update(q.input) }
            is VersionQuery.Server -> { lastServer = q.input; serverVersions.update(q.input) }
            VersionQuery.None -> Unit
        }
    }

    private val versions: kotlinx.coroutines.flow.Flow<Pair<EpisodeRow?, VersionsState>> = queries.onEach { (_, q) -> ask(q) }.flatMapLatest { (row, q) ->
        when (q) {
            is VersionQuery.Catalog -> catalogVersions.state.map { t ->
                row to (t?.takeIf { it.identity == catalogVersionKey(q.input.work, q.input.episode) }?.value ?: catalogVersions.computeNow(q.input))
            }
            is VersionQuery.Server -> serverVersions.state.map { t ->
                row to (t?.takeIf { it.identity == q.input.identity }?.value ?: serverVersions.computeNow(q.input))
            }
            VersionQuery.None -> flowOf(row to VersionsState(emptyList(), 0, null))
        }
    }.flowOn(Dispatchers.Default)

    /** Each copy's progress from the store, kept while the copies change (no resume point drops for a moment). */
    private val storedProgress = versions.map { (_, v) -> v.ordered.map { it.ref }.distinctBy { it.key } }
        .distinctUntilChanged { a, b -> a.map { it.key } == b.map { it.key } }
        .flatMapLatest { copies -> if (copies.isEmpty()) flowOf(emptyMap()) else app.store.progressFlow(copies) }

    val view: StateFlow<EpisodeView> = combine(versions, storedProgress.onStart { emit(emptyMap()) }, app.recentProgress, app.settings.watchedPercent, tick) { (row, v), stored, local, percent, _ ->
        val records = newestProgress(stored, local)
        val ordered = v.ordered.map { it.copy(item = it.ref.withPlaybackProgress(records).item) }
        val memory = app.playbackMemory.latest(v.ordered.map { it.ref }.distinctBy { it.key })
        EpisodeView(row, v, VersionProgress(ordered, playbackDefault(ordered, memory, percent)))
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, EpisodeView(null, VersionsState(emptyList(), 0, null), VersionProgress(emptyList(), null)))

    /** Whether there is a version to play: the one thing about versions a page layout needs. */
    val hasPlayable: StateFlow<Boolean> = view.map { it.merged.default != null }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** Whether the versions section has anything to say (versions, a search running, servers not answering). */
    val anything: StateFlow<Boolean> = view.map { v -> v.versions.all.isNotEmpty() || v.versions.searching > 0 || v.versions.unresponsive.isNotEmpty() }
        .distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, false)

    /** The other servers' copies of the item (by key), for favourites and 为你推荐. */
    val instances: StateFlow<List<ServerItem>> = view.map { it.versions.instances }.distinctUntilChanged { a, b -> a.map { it.key } == b.map { it.key } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
}

