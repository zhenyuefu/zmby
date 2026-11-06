package app.zmby.ui.screens

import app.zmby.aggregate.FailureTracker
import app.zmby.aggregate.FanOut
import app.zmby.aggregate.Outcome
import app.zmby.catalog.CatalogEpisode
import app.zmby.catalog.CatalogSeason
import app.zmby.catalog.CatalogWork
import app.zmby.catalog.EpisodeIdentity
import app.zmby.catalog.EpisodeResources
import app.zmby.catalog.ResourceBatch
import app.zmby.catalog.ResourceMapping
import app.zmby.catalog.SeriesDirectory
import app.zmby.catalog.episodeIdentities
import app.zmby.catalog.seriesDirectory
import app.zmby.data.ServerItem
import app.zmby.data.SourceRules
import app.zmby.media.episodeName
import app.zmby.data.withPlaybackProgress
import app.zmby.emby.BaseItemDto
import app.zmby.emby.UserItemData
import app.zmby.store.EpisodeMappingRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import app.zmby.log.ZLog
import kotlinx.coroutines.withContext

/** What the series page pipeline reads from the servers, the catalogue and the store (faked in tests). */
internal interface SeriesSource {
    /** False while the catalogue recently failed or was too slow; the page starts on the server's seasons. */
    fun directoryReachable(): Boolean
    /** Whether the catalogue listed the chosen season ([ok]) or failed or timed out. */
    fun directoryAnswered(ok: Boolean)
    suspend fun storedItem(server: String, id: String): BaseItemDto?
    suspend fun serverSeasons(server: String, seriesId: String): List<BaseItemDto>
    suspend fun serverEpisodes(server: String, seriesId: String, season: BaseItemDto): List<BaseItemDto>
    suspend fun nextUp(server: String, seriesId: String): BaseItemDto?
    suspend fun catalogEpisodes(work: CatalogWork, season: Int): List<CatalogEpisode>
    fun resources(targets: List<String>, work: CatalogWork, fallback: ServerItem?): Flow<Outcome<List<ResourceBatch>>>
    suspend fun episodeIdentity(work: CatalogWork, item: BaseItemDto, refresh: Boolean): EpisodeIdentity
    fun mappings(workKey: String): Flow<List<EpisodeMappingRule>>
    fun landscapeImage(ref: ServerItem): String?
    /** 影片信息来源 in order: which source each episode field (name, overview, still) comes from. */
    val metadataOrder: Flow<List<SourceRules.MetaSource>>
    /** When [server]'s cool-down ends, if it is cooling. */
    fun coolingUntil(server: String): Long?
}

/** Everything the series page shows about its episodes, worked out off the main thread. */
internal data class SeriesData(
    val seasons: List<CatalogSeason> = emptyList(),
    val season: Int = -1,
    val episodes: List<EpisodeRow> = emptyList(),
    val directory: SeriesDirectory = SeriesDirectory(),
    val serverSeasons: List<BaseItemDto> = emptyList(),
    /** Nothing listed for the season yet (no directory answer). */
    val loading: Boolean = true,
    val episodeError: String? = null,
    /** Servers still looking for this work's resources, and those that failed. */
    val pending: Int = 0,
    val failed: List<String> = emptyList(),
    val batches: List<ResourceBatch> = emptyList(),
    val identities: Map<String, EpisodeIdentity> = emptyMap(),
    val mapped: EpisodeResources = EpisodeResources(emptyMap(), emptyList()),
    val allResources: List<ServerItem> = emptyList(),
    val allCatalog: List<CatalogEpisode> = emptyList(),
    val mappings: List<EpisodeMappingRule> = emptyList(),
    /** The catalogue work whose directory lists the episodes; null when the server's own seasons do. */
    val directoryWork: CatalogWork? = null,
    /** Why the server's seasons are shown although there is a catalogue work (TMDB unreachable). */
    val directoryNote: String? = null,
)

/** The part of [SeriesData] the series page root reads (see [SeriesPipeline.shape]). */
internal data class SeriesShape(
    val directoryWork: CatalogWork? = null,
    val seasons: List<CatalogSeason> = emptyList(),
    val season: Int = -1,
    val episodeCount: Int = 0,
) {
    companion object {
        fun of(d: SeriesData) = SeriesShape(d.directoryWork, d.seasons, d.season, d.episodes.size)
    }
}

internal data class ResourceAvailability(val pending: Int = 0, val failed: List<String> = emptyList())

/**
 * The series page's data (performance plan R2): season directories from the catalogue or the server,
 * the work's resources on every aggregating server, episode identities, the mapping of resources to
 * episodes and the episode rows, as flows on Dispatchers.Default instead of effects in the page.
 * [catalogWork] is the catalogue work whose directory may list the episodes (it falls back to the
 * server's own seasons when the catalogue fails or is too slow), [lookup] the work known only by its
 * external IDs, [fallback] the opened server item. The page writes [currentKey] (focus has rested),
 * [pick] (the page chose an episode), [chooseSeason] and the retry counters.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class SeriesPipeline(
    private val scope: CoroutineScope,
    private val server: String,
    private val seriesId: String,
    private val episodeId: String?,
    private val source: SeriesSource,
    private val catalogWork: StateFlow<CatalogWork?>,
    lookup: StateFlow<CatalogWork?>,
    private val fallback: StateFlow<ServerItem?>,
    private val targets: StateFlow<List<String>>,
    private val progress: StateFlow<Map<String, UserItemData>>,
    private val now: () -> Long = System::currentTimeMillis,
    /** Where merging, mapping and rows are worked out (a test dispatcher in tests). */
    private val compute: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default,
) {
    val tick = MutableStateFlow(0)
    val season = MutableStateFlow(-1)
    val currentKey = MutableStateFlow<String?>(null)
    val anchorApplied = MutableStateFlow(false)
    val resourceRetry = MutableStateFlow(0)
    val identityRetry = MutableStateFlow(0)

    private val _picks = MutableSharedFlow<String?>(extraBufferCapacity = 8)
    /** Episodes the page chose itself (opened episode, quick pick, season change): focus follows them. */
    val picks: SharedFlow<String?> = _picks.asSharedFlow()

    /** The page sets the episode it is on. */
    fun pick(key: String?) {
        currentKey.value = key
        _picks.tryEmit(key)
    }

    /** A season chip or the quick pick dialog chose [number]. */
    fun chooseSeason(number: Int) {
        anchorApplied.value = true
        if (number != season.value) {
            season.value = number
            pick(null)
        }
    }

    /** False after the catalogue failed or was too slow for this page; a retry tries it again. */
    private val directoryUsable = MutableStateFlow(source.directoryReachable())
    private val directoryNote = MutableStateFlow<String?>(if (directoryUsable.value) null else UNREACHABLE)
    /** The catalogue work while its directory is usable: what decides catalogue or server episodes. */
    private val canonical: StateFlow<CatalogWork?> = combine(catalogWork, directoryUsable) { w, ok -> w?.takeIf { ok } }
        .stateIn(scope, SharingStarted.Eagerly, catalogWork.value?.takeIf { directoryUsable.value })
    // Resources are found by external IDs and titles, which needs no catalogue connection.
    private val resourceWork = combine(catalogWork, lookup) { c, l -> c ?: l }.stateIn(scope, SharingStarted.Eagerly, catalogWork.value ?: lookup.value)
    private val serverSeasons = MutableStateFlow<List<BaseItemDto>>(emptyList())
    private val serverEpisodes = MutableStateFlow<List<BaseItemDto>>(emptyList())
    private val anchor = MutableStateFlow<BaseItemDto?>(null)
    private val episodeError = MutableStateFlow<String?>(null)
    private val recoverable = MutableStateFlow<List<String>>(emptyList())
    private val directory = MutableStateFlow(SeriesDirectory())
    private val batches = MutableStateFlow<List<ResourceBatch>>(emptyList())
    private val pending = MutableStateFlow(0)
    private val failed = MutableStateFlow<List<String>>(emptyList())
    private val identities = MutableStateFlow<Map<String, EpisodeIdentity>>(emptyMap())

    private val seasons: StateFlow<List<CatalogSeason>> = combine(canonical, serverSeasons) { c, ss ->
        c?.seasons ?: ss.map { CatalogSeason(it.indexNumber ?: 0, it.name.orEmpty(), it.childCount ?: 0) }
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val mappings: StateFlow<List<EpisodeMappingRule>> = canonical.map { it?.ref?.key.orEmpty() }.distinctUntilChanged()
        .flatMapLatest { key -> if (key.isEmpty()) flowOf(emptyList()) else source.mappings(key) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val allResources: StateFlow<List<ServerItem>> = batches.map { b -> b.flatMap { it.episodes }.distinctBy { it.key } }
        .flowOn(compute).stateIn(scope, SharingStarted.Eagerly, emptyList())
    private val allCatalog: StateFlow<List<CatalogEpisode>> = directory.map { d -> d.pages.values.flatten() }
        .flowOn(compute).stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val mapped: StateFlow<EpisodeResources> = combine(canonical, directory, batches, progress, identities, mappings) { args ->
        @Suppress("UNCHECKED_CAST")
        val work = args[0] as CatalogWork? ?: return@combine EpisodeResources(emptyMap(), emptyList())
        val dir = args[1] as SeriesDirectory
        @Suppress("UNCHECKED_CAST") val b = args[2] as List<ResourceBatch>
        @Suppress("UNCHECKED_CAST") val p = args[3] as Map<String, UserItemData>
        @Suppress("UNCHECKED_CAST") val ids = args[4] as Map<String, EpisodeIdentity>
        @Suppress("UNCHECKED_CAST") val rules = args[5] as List<EpisodeMappingRule>
        ResourceMapping.episodes(work, dir.pages.values.flatten(),
            b.map { batch -> batch.copy(episodes = batch.episodes.map { it.withPlaybackProgress(p) }) },
            (work.seasons.map { it.number }.toSet() + dir.seasons) - dir.pages.keys, ids, rules)
    }.flowOn(compute).stateIn(scope, SharingStarted.Eagerly, EpisodeResources(emptyMap(), emptyList()))

    private val metadataOrder = source.metadataOrder.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val episodes: StateFlow<List<EpisodeRow>> = combine(canonical, directory, serverEpisodes, season, mapped, progress, metadataOrder) { args ->
        @Suppress("UNCHECKED_CAST")
        rows(args[0] as CatalogWork?, args[1] as SeriesDirectory, args[2] as List<BaseItemDto>, args[3] as Int, args[4] as EpisodeResources, args[5] as Map<String, UserItemData>,
            args[6] as List<SourceRules.MetaSource>)
    }.flowOn(compute).stateIn(scope, SharingStarted.Eagerly, emptyList())

    private fun rows(work: CatalogWork?, dir: SeriesDirectory, serverEps: List<BaseItemDto>, seasonNumber: Int, mapped: EpisodeResources, progress: Map<String, UserItemData>,
        order: List<SourceRules.MetaSource>): List<EpisodeRow> {
        val catalogEpisodes = dir.pages[seasonNumber]
        return if (work != null && catalogEpisodes != null) catalogEpisodes.map { ep ->
            val key = episodeSlot(ep.season, ep.number, ep.id)
            val resources = mapped.matched[ep.id].orEmpty()
            // 影片信息来源 field by field: the directory's catalogue episode or a server's copy of it.
            val byServer = resources.groupBy { it.server }
            val fields = EpisodeFields.pick(order, work.ref.source, EpisodeFields.Fields(ep.title, ep.overview, ep.image)) { s ->
                byServer[s]?.first()?.let { r -> EpisodeFields.Fields(episodeName(r.item), r.item.overview, source.landscapeImage(r)) }
            }
            val item = work.item(ep).copy(name = fields.name ?: ep.title, overview = fields.overview ?: ep.overview, userData = resources.firstOrNull()?.item?.userData)
            EpisodeRow(key, ServerItem("catalog", item).withPlaybackProgress(progress).item.copy(id = key), fields.image ?: ep.image, ep, resources)
        } else {
            val counts = serverEps.groupingBy { it.parentIndexNumber to it.indexNumber }.eachCount()
            serverEps.filter { it.parentIndexNumber == seasonNumber }.map { original ->
                val ep = ServerItem(server, original).withPlaybackProgress(progress).item
                val unique = counts[ep.parentIndexNumber to ep.indexNumber] == 1
                val key = episodeSlot(ep.parentIndexNumber.takeIf { unique }, ep.indexNumber, ep.id)
                EpisodeRow(key, ep, source.landscapeImage(ServerItem(server, ep)), resources = listOf(ServerItem(server, ep)))
            }
        }
    }

    val state: StateFlow<SeriesData> = combine(
        listOf<Flow<Any?>>(seasons, season, episodes, directory, serverSeasons, serverEpisodes, episodeError, pending, failed, batches,
            identities, mapped, allResources, allCatalog, mappings, canonical, directoryNote, catalogWork),
    ) { a ->
        @Suppress("UNCHECKED_CAST")
        val dir = a[3] as SeriesDirectory
        val seasonNumber = a[1] as Int
        @Suppress("UNCHECKED_CAST")
        SeriesData(
            seasons = a[0] as List<CatalogSeason>, season = seasonNumber, episodes = a[2] as List<EpisodeRow>,
            directory = dir, serverSeasons = a[4] as List<BaseItemDto>,
            loading = if (a[15] != null) dir.pages[seasonNumber] == null else (a[5] as List<*>).isEmpty() && (a[4] as List<*>).isEmpty(),
            episodeError = a[6] as String?, pending = a[7] as Int, failed = a[8] as List<String>, batches = a[9] as List<ResourceBatch>,
            identities = a[10] as Map<String, EpisodeIdentity>, mapped = a[11] as EpisodeResources, allResources = a[12] as List<ServerItem>,
            allCatalog = a[13] as List<CatalogEpisode>, mappings = a[14] as List<EpisodeMappingRule>,
            directoryWork = a[15] as CatalogWork?, directoryNote = (a[16] as String?).takeIf { a[15] == null && a[17] != null },
        )
    }.flowOn(compute).stateIn(scope, SharingStarted.Eagerly, SeriesData())

    /**
     * What the page root reads: the directory, seasons and the size of the season shown. It does not
     * change as each server's resources arrive (they change [state]), so the root, header and backdrop
     * do not recompose then; the episode block and the header's availability line read their own parts.
     */
    val shape: StateFlow<SeriesShape> = state.map(SeriesShape::of).distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, SeriesShape.of(state.value))

    /** Servers still looking for the work's resources, and those that failed (the header's line under the title). */
    val availability: StateFlow<ResourceAvailability> = state.map { ResourceAvailability(it.pending, it.failed) }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, ResourceAvailability())

    /** The series on each server whose resources verified, one copy per server (为你推荐 asks them). */
    val seriesCopies: StateFlow<List<ServerItem>> = batches.map { list ->
        list.flatMap { b ->
            b.episodes.filter { b.verified }.mapNotNull { e -> e.item.seriesId }.distinct().map { id -> ServerItem(b.server, BaseItemDto(id = id, type = "Series")) }
        }.distinctBy { c -> c.server }
    }.distinctUntilChanged().flowOn(compute).stateIn(scope, SharingStarted.Eagerly, emptyList())

    private fun transient(server: String, e: Exception) {
        episodeError.value = "剧集目录暂时无法加载"
        if (FanOut.isTransient(e)) recoverable.update { (it + server).distinct() }
    }

    init {
        // The server's own seasons (no catalogue directory) and the episode the page opens on.
        scope.launch {
            combine(tick, resourceWork.map { it?.ref }.distinctUntilChanged(), resourceRetry, canonical.map { it != null }.distinctUntilChanged()) { _, _, _, _ -> }.collectLatest {
                if (server == "catalog") return@collectLatest
                anchor.value = episodeId?.let { source.storedItem(server, it) }
                if (season.value < 0) anchor.value?.parentIndexNumber?.let { season.value = it }
                if (canonical.value == null) try {
                    serverSeasons.value = source.serverSeasons(server, seriesId)
                    if (season.value < 0) season.value = serverSeasons.value.firstOrNull { (it.indexNumber ?: 0) > 0 }?.indexNumber ?: 0
                    if (anchor.value == null) anchor.value = source.nextUp(server, seriesId)
                    if (!anchorApplied.value && currentKey.value == null) anchor.value?.let { season.value = it.parentIndexNumber ?: season.value }
                } catch (e: CancellationException) { throw e } catch (e: Exception) { transient(server, e) }
                // With the catalogue's directory (also when its work came from the cache before anything
                // else) the next to watch is still the server's: it is mapped to its catalogue episode below.
                else if (anchor.value == null && !anchorApplied.value && currentKey.value == null) try {
                    anchor.value = source.nextUp(server, seriesId)
                } catch (e: CancellationException) { throw e } catch (e: Exception) { ZLog.w("Series", "next up unavailable: ${e.javaClass.simpleName}") }
            }
        }
        // A season to start on once the seasons are known (or when the catalogue lacks the chosen one).
        scope.launch {
            seasons.collect { list ->
                // Also after falling back from the catalogue, whose numbering the server may not share.
                if (list.isNotEmpty() && (season.value < 0 || (canonical.value != null || !directoryUsable.value) && list.none { it.number == season.value }))
                    season.value = list.firstOrNull { it.number > 0 }?.number ?: list.first().number
            }
        }
        // The server's episodes of the chosen season.
        scope.launch {
            combine(season, serverSeasons, canonical.map { it?.ref }.distinctUntilChanged(), resourceRetry, tick) { s, ss, _, _, _ -> s to ss }.collectLatest { (number, list) ->
                if (server == "catalog" || canonical.value != null) return@collectLatest
                val chosen = list.firstOrNull { it.indexNumber == number } ?: return@collectLatest
                try {
                    serverEpisodes.value = source.serverEpisodes(server, seriesId, chosen)
                    episodeError.value = null
                    recoverable.update { it - server }
                } catch (e: CancellationException) { throw e } catch (e: Exception) { transient(server, e) }
            }
        }
        // The catalogue directory: the chosen season first, then the rest; kept across a retry only when it
        // was not one. When the chosen season fails or takes longer than DIRECTORY_WAIT_MS (TMDB unreachable
        // or slow), the page falls back to the server's own seasons until a retry.
        scope.launch {
            var directoryFor: CatalogWork? = null
            var directoryRetry = 0
            var lastRetry = resourceRetry.value
            combine(catalogWork, season, resourceRetry, directoryUsable) { c, s, r, u -> DirectoryInputs(c, s, r, u) }.collectLatest { (work, number, retry, usable) ->
                if (work != directoryFor) { directoryFor = work; directory.value = SeriesDirectory(); directoryRetry = retry }
                if (retry != lastRetry) {
                    lastRetry = retry
                    if (!usable) { directoryNote.value = null; directoryUsable.value = true; return@collectLatest }
                }
                if (work == null || number < 0 || !usable) return@collectLatest
                val previous = if (directoryRetry == retry) directory.value.pages else emptyMap()
                directoryRetry = retry
                // A season the catalogue does not have (an anchor from the server) is not judged; any page is.
                val judged = number.takeIf { n -> work.seasons.any { it.number == n } }
                fun giveUp(why: String) {
                    ZLog.w("Series", "catalogue directory $why; using the server's seasons")
                    source.directoryAnswered(false)
                    directoryNote.value = UNREACHABLE
                    directoryUsable.value = false
                }
                coroutineScope {
                    val waiting = launch { delay(DIRECTORY_WAIT_MS); giveUp("too slow") }
                    seriesDirectory(work.seasons.map { it.number }, number, previous) { source.catalogEpisodes(work, it) }.collect { d ->
                        directory.value = d
                        episodeError.value = if (d.failed.isEmpty()) null else "${d.failed.size} 季目录暂时无法加载"
                        val listed = if (judged != null) judged in d.pages else d.pages.isNotEmpty()
                        val failedAll = if (judged != null) judged in d.failed else d.seasons.isNotEmpty() && d.failed.containsAll(d.seasons)
                        if (listed && waiting.isActive) { waiting.cancel(); source.directoryAnswered(true) }
                        else if (failedAll && waiting.isActive) { waiting.cancel(); giveUp("failed") }
                    }
                }
            }
        }
        // The work's resources on every aggregating server, kept per work.
        scope.launch {
            var batchesFor: Any? = null
            combine(resourceWork, targets, tick, resourceRetry, fallback) { w, t, k, r, f -> ResourceInputs(w, t, k, r, f) }
                .distinctUntilChanged { a, b -> a.key == b.key }
                .collectLatest { i ->
                    if (i.work?.ref != batchesFor) { batchesFor = i.work?.ref; batches.value = emptyList() }
                    val work = i.work ?: return@collectLatest
                    batches.update { list -> list.filter { it.server in i.targets } }
                    pending.value = i.targets.size; failed.value = emptyList(); recoverable.value = emptyList()
                    source.resources(i.targets, work, i.fallback).collect { result ->
                        when (result) {
                            is Outcome.Ok -> batches.update { list -> list.filter { it.server != result.server } + result.value }
                            else -> {
                                failed.update { it + result.server }
                                if (result is Outcome.Skipped || result is Outcome.Failed && FanOut.isTransient(result.error)) recoverable.update { it + result.server }
                            }
                        }
                        pending.update { it - 1 }
                    }
                }
        }
        // Servers that did not answer are asked again when their cool-down ends.
        scope.launch {
            combine(recoverable, pending) { r, p -> r to p }.collectLatest { (servers, waiting) ->
                if (waiting > 0 || servers.isEmpty()) return@collectLatest
                val at = now()
                val next = servers.minOf { source.coolingUntil(it)?.takeIf { until -> until > at } ?: (at + FailureTracker.COOL_MS) }
                delay((next - at).coerceAtLeast(0) + 50)
                resourceRetry.value++
            }
        }
        // Each verified resource's episode identity (TMDB episode IDs on servers that number differently).
        scope.launch {
            var identitiesFor: Any? = null
            var inputs: Map<String, Map<String, String>> = emptyMap()
            var epoch = resourceRetry.value to identityRetry.value
            combine(canonical, batches, resourceRetry, identityRetry) { c, b, r, i -> IdentityInputs(c, b, r, i) }.collectLatest { i ->
                if (i.work?.ref != identitiesFor) {
                    identitiesFor = i.work?.ref; identities.value = emptyMap(); inputs = emptyMap(); epoch = i.resourceRetry to i.identityRetry
                }
                val work = i.work ?: return@collectLatest
                val refs = withContext(compute) { i.batches.filter { it.verified }.flatMap { it.episodes }.distinctBy { it.key } }
                val nextInputs = withContext(compute) { refs.associate { it.key to it.item.providerIds } }
                val nextEpoch = i.resourceRetry to i.identityRetry
                val force = epoch != nextEpoch
                val previous = if (epoch == nextEpoch) identities.value.filterKeys { inputs[it] == nextInputs[it] } else emptyMap()
                inputs = nextInputs; epoch = nextEpoch
                episodeIdentities(work, refs, previous) { source.episodeIdentity(work, it, force) }.collect { identities.value = it }
            }
        }
        // The episode the page opens on: the one it was opened for, else the next to watch.
        scope.launch {
            combine(episodes, anchor, batches, mapped, allCatalog) { e, a, b, m, c -> AnchorInputs(e, a, b, m, c) }.collect { i ->
                if (anchorApplied.value || currentKey.value != null) return@collect
                val opened = i.anchor ?: i.batches.flatMap { it.episodes }.firstOrNull { it.server == server && it.item.id == episodeId }?.item ?: return@collect
                if (canonical.value != null) {
                    val target = i.catalog.firstOrNull { ep -> i.mapped.matched[ep.id].orEmpty().any { it.server == server && it.item.id == opened.id } } ?: return@collect
                    anchorApplied.value = true
                    season.value = target.season
                    pick(episodeSlot(target.season, target.number, target.id))
                    return@collect
                }
                anchorApplied.value = true
                if (opened.parentIndexNumber != null && opened.parentIndexNumber != season.value) season.value = opened.parentIndexNumber
                pick(i.episodes.firstOrNull { row -> row.resources.any { it.server == server && it.item.id == opened.id } }?.key
                    ?: episodeSlot(opened.parentIndexNumber, opened.indexNumber, opened.id))
            }
        }
    }

    private data class DirectoryInputs(val work: CatalogWork?, val season: Int, val retry: Int, val usable: Boolean)
    private data class ResourceInputs(val work: CatalogWork?, val targets: List<String>, val tick: Int, val retry: Int, val fallback: ServerItem?) {
        val key get() = listOf(work?.ref, work?.ids, targets, tick, retry, fallback?.key, fallback?.item?.providerIds)
    }
    private data class IdentityInputs(val work: CatalogWork?, val batches: List<ResourceBatch>, val resourceRetry: Int, val identityRetry: Int)
    private data class AnchorInputs(val episodes: List<EpisodeRow>, val anchor: BaseItemDto?, val batches: List<ResourceBatch>, val mapped: EpisodeResources, val catalog: List<CatalogEpisode>)

    companion object {
        /** How long the chosen season may take from the catalogue before the server's seasons are shown. */
        const val DIRECTORY_WAIT_MS = 6_000L
        const val UNREACHABLE = "TMDB 暂时无法连接，显示服务器自己的季集"
    }
}

/**
 * An episode row's name, overview and still by 影片信息来源 (统一数据层 ④): each field from the first
 * source switched on that has it, the directory's catalogue episode ([catalogSource]) or a server's copy
 * of the episode ([server], asked only for servers ahead of the catalogue or when it lacks a field).
 * A server name that only repeats the episode number does not count. With nothing chosen, the
 * catalogue's fields stay.
 */
internal object EpisodeFields {
    data class Fields(val name: String?, val overview: String?, val image: String?)

    fun pick(order: List<SourceRules.MetaSource>, catalogSource: String, catalog: Fields, server: (String) -> Fields?): Fields {
        var name: String? = null
        var overview: String? = null
        var image: String? = null
        fun take(f: Fields) {
            if (name == null) name = f.name?.takeIf { it.isNotBlank() }
            if (overview == null) overview = f.overview?.takeIf { it.isNotBlank() }
            if (image == null) image = f.image
        }
        for (m in order) {
            if (!m.enabled) continue
            if (m.catalog) { if (m.key == catalogSource) take(catalog) } else server(m.key)?.let(::take)
            if (name != null && overview != null && image != null) break
        }
        take(catalog)
        return Fields(name, overview, image)
    }
}
