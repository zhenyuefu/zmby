package app.zmby.ui.screens

import androidx.lifecycle.viewModelScope
import app.zmby.ZmbyApp
import app.zmby.aggregate.FanOut
import app.zmby.aggregate.Outcome
import app.zmby.aggregate.VersionFinder
import app.zmby.catalog.CatalogEpisode
import app.zmby.catalog.CatalogId
import app.zmby.catalog.CatalogResources
import app.zmby.catalog.CatalogWork
import app.zmby.catalog.EpisodeIdentity
import app.zmby.catalog.ResourceBatch
import app.zmby.data.ServerItem
import app.zmby.data.SourceFeature
import app.zmby.emby.BaseItemDto
import app.zmby.store.EpisodeMappingRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformLatest
import app.zmby.data.newestProgress
import app.zmby.data.withPlaybackProgress
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private suspend fun serverDirectory(app: ZmbyApp, server: String, seriesId: String, seasonId: String? = null,
    load: suspend () -> List<BaseItemDto>): List<BaseItemDto> =
    when (val result = app.fanOut.run(listOf(server), FanOut.SEARCH_TIMEOUT_MS,
        share = listOf("server-directory", seriesId, seasonId), cacheMs = FanOut.RESOURCE_CACHE_MS) { load() }.first()) {
        is Outcome.Ok -> result.value
        is Outcome.Failed -> throw result.error
        is Outcome.Skipped -> throw java.io.IOException("服务器暂时未响应")
    }

/** All episodes of a season on [server], page by page through the shared directory steps (RN ⑥). */
private suspend fun directoryEpisodes(app: ZmbyApp, server: String, seriesId: String, seasonId: String): List<BaseItemDto> {
    val all = mutableListOf<BaseItemDto>()
    while (true) {
        val start = all.size
        val page = app.fanOut.episodePage(server, seriesId, seasonId, start, VersionFinder.EPISODE_PAGE) {
            app.library.episodePage(server, seriesId, seasonId, start, VersionFinder.EPISODE_PAGE)
        }
        all += page.items
        if (page.items.isEmpty() || all.size >= page.totalRecordCount) return all
    }
}

/**
 * The catalogue work whose episode directory the series page can list: a TMDB work only while a TMDB
 * token is set (its season pages need it), a Bangumi work always. A TMDB work kept in the metadata
 * cache from before the token was removed still names and illustrates the page, but its episodes come
 * from the server's own seasons instead of a directory that cannot load.
 */
internal fun catalogDirectory(work: CatalogWork?, tmdbConfigured: Boolean): CatalogWork? =
    work?.takeIf { it.ref.source != "tmdb" || tmdbConfigured }

/** The episode the series page is on, its versions and their merged progress. */
internal class EpisodeView(val shown: EpisodeRow?, val versions: VersionsState, val merged: VersionProgress)

/** Waited after the episode settles before its versions are looked up (FOCUS_SETTLE_MS came before). */
private const val VERSION_SETTLE_MS = 350L

/** [SeriesSource] on the app's servers, catalogue and store. */
private class AppSeriesSource(private val app: ZmbyApp, private val resources: CatalogResources) : SeriesSource {
    override fun directoryReachable() = app.catalog.reachability.reachable()
    override fun directoryAnswered(ok: Boolean) = if (ok) app.catalog.reachability.answered() else app.catalog.reachability.failed()
    override suspend fun storedItem(server: String, id: String) = app.store.detail(server, id)?.item
    override suspend fun serverSeasons(server: String, seriesId: String) =
        serverDirectory(app, server, seriesId) { app.fanOut.seasons(server, seriesId) { app.library.seasons(server, seriesId) } }
    override suspend fun serverEpisodes(server: String, seriesId: String, season: BaseItemDto) =
        serverDirectory(app, server, seriesId, season.id) {
            directoryEpisodes(app, server, seriesId, season.id).map { it.copy(parentIndexNumber = it.parentIndexNumber ?: season.indexNumber, seriesId = it.seriesId ?: seriesId) }
        }
    override suspend fun nextUp(server: String, seriesId: String) = app.library.nextUp(server, seriesId)
    override suspend fun catalogEpisodes(work: CatalogWork, season: Int): List<CatalogEpisode> = app.catalog.episodes(work, season)
    override fun resources(targets: List<String>, work: CatalogWork, fallback: ServerItem?): Flow<Outcome<List<ResourceBatch>>> = resources.series(targets, work, fallback)
    override suspend fun episodeIdentity(work: CatalogWork, item: BaseItemDto, refresh: Boolean): EpisodeIdentity = app.catalog.episodeIdentity(work, item, refresh = refresh)
    override fun mappings(workKey: String): Flow<List<EpisodeMappingRule>> = app.store.episodeMappings.observe(workKey)
    override fun landscapeImage(ref: ServerItem) = app.library.landscapeImage(ref)
    override val metadataOrder: Flow<List<app.zmby.data.SourceRules.MetaSource>> =
        combine(app.sources.rows, app.registry.lineup) { _, _ -> app.sources.metadata() }
    override fun coolingUntil(server: String) = app.registry.get(server)?.coolingUntil
}

/**
 * The series page's state holder (performance plan R2): the metadata of [DetailViewModel] plus the
 * episode [pipeline], so season directories, resources, identities and episode rows are worked out
 * off the main thread and survive the page's recompositions.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class SeriesViewModel(private val app: ZmbyApp, server: String, seriesId: String, episodeId: String?, provider: CatalogId?) :
    DetailViewModel(app, server, seriesId, provider) {
    private val resourceLookup = CatalogResources(app.library, app.fanOut)

    /** [canonical] when its episode directory can be listed ([catalogDirectory]); drives the episodes and versions. */
    val directoryWork: StateFlow<CatalogWork?> = combine(canonical, app.settings.tmdbToken.flow) { work, _ -> catalogDirectory(work, app.catalog.configured) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 资源聚合 servers plus the page's own. */
    val targets: StateFlow<List<String>> = combine(app.registry.lineup, app.sources.rows) { _, _ ->
        (app.sources.servers(SourceFeature.Aggregate) + listOfNotNull(server.takeUnless { it == "catalog" })).distinct()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val fallback = detail.map { it?.ref }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val pipeline = SeriesPipeline(viewModelScope, server, seriesId, episodeId, AppSeriesSource(app, resourceLookup),
        directoryWork, lookup, fallback, targets, app.recentProgress)

    /** The episode the page is on: the one focus rested on, else the season's first. */
    private val shown: Flow<EpisodeRow?> = combine(pipeline.state, pipeline.currentKey) { d, key -> d.episodes.firstOrNull { it.key == key } ?: d.episodes.firstOrNull() }
        .distinctUntilChanged()

    /** The version lookup waits until the episode has stayed for a moment (600 ms with the focus settle). */
    private val settled: Flow<Boolean> = shown.map { it?.key }.distinctUntilChanged()
        .transformLatest { emit(false); delay(VERSION_SETTLE_MS); emit(true) }

    private class LookupInputs(val data: SeriesData, val shown: EpisodeRow?, val settled: Boolean, val fallback: ServerItem?, val canonical: CatalogWork?, val tick: Int, val retry: Int)

    /** What the version lookup is asked: the shown episode in catalogue or server terms. */
    private val queries: Flow<Pair<EpisodeRow?, VersionQuery>> = combine(
        listOf<Flow<Any?>>(pipeline.state, shown, settled, fallback, canonical, pipeline.tick, pipeline.resourceRetry),
    ) { a ->
        @Suppress("UNCHECKED_CAST")
        LookupInputs(a[0] as SeriesData, a[1] as EpisodeRow?, a[2] as Boolean, a[3] as ServerItem?, a[4] as CatalogWork?, a[5] as Int, a[6] as Int)
    }.map { i ->
        val work = i.data.directoryWork
        val series = i.fallback?.item ?: i.canonical?.item()
        i.shown to when {
            work != null -> VersionQuery.Catalog(CatalogVersionLookup.Input(work, i.shown?.canonical, i.shown?.resources.orEmpty(), i.tick + i.retry, i.fallback,
                manual = null, lookUp = i.settled,
                directoryPending = i.data.pending > 0 || i.data.mapped.awaitingIdentity.isNotEmpty() || i.data.mapped.awaitingCatalog.isNotEmpty()))
            i.shown != null && series != null -> VersionQuery.Server(ServerVersionLookup.Input(server, i.shown.item, series, i.settled, emptyMap(), null))
            else -> VersionQuery.None
        }
    }.distinctUntilChanged()

    private val presenter = VersionPresenter(app, viewModelScope, queries, pipeline.tick, onServerRefresh = ::refreshResources, onCatalogRefresh = ::refreshResources)

    /**
     * The episode the page is on, its versions and their merged progress (performance plan R3): only the
     * sections that show them read it, so neither a D-pad move nor a server's answer recomposes the page.
     */
    val episodeView: StateFlow<EpisodeView> get() = presenter.view

    /** Whether the shown episode has a version to play: the one thing about versions the page layout needs. */
    val hasPlayable: StateFlow<Boolean> get() = presenter.hasPlayable

    /** 刷新 on the versions or a failed directory: lift cool-downs and drop cached answers for this series. */
    fun refreshResources() {
        val servers = targets.value
        servers.forEach(app.registry::retry)
        (directoryWork.value ?: lookup.value)?.let { resourceLookup.invalidate(servers, it, fallback.value) }
        app.fanOut.invalidate(listOf("server-directory", itemId, null), listOf(server))
        app.fanOut.invalidateSteps(servers)
        pipeline.state.value.serverSeasons.forEach { app.fanOut.invalidate(listOf("server-directory", itemId, it.id), listOf(server)) }
        pipeline.resourceRetry.value++
    }
}
