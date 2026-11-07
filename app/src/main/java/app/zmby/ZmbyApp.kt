package app.zmby

import androidx.core.content.edit
import android.app.Application
import android.content.Context
import android.os.Build
import app.zmby.aggregate.AggregateSearch
import app.zmby.aggregate.FanOut
import app.zmby.aggregate.HomeFeed
import app.zmby.aggregate.VersionFinder
import app.zmby.data.Library
import app.zmby.favorites.Favorites
import app.zmby.favorites.Watchlist
import app.zmby.data.PrefsRegistryStore
import app.zmby.data.ServerRegistry
import app.zmby.data.PrefsCapsStore
import app.zmby.data.ServerCapsStore
import app.zmby.data.enforceStartupLimit
import app.zmby.data.Settings
import app.zmby.data.Disk
import app.zmby.store.LocalStore
import app.zmby.data.DataSources
import app.zmby.data.SegmentProvider
import app.zmby.data.SourceFeature
import app.zmby.store.ZmbyDatabase
import coil.disk.DiskCache
import kotlinx.coroutines.flow.first
import app.zmby.playback.StreamProbe
import app.zmby.playback.StreamRelay
import app.zmby.log.LanServer
import app.zmby.log.ZLog
import app.zmby.emby.ClientInfo
import app.zmby.emby.OfficialEmby
import app.zmby.emby.UserAgentInterceptor
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.imageLoader
import app.zmby.emby.EmbyClient
import app.zmby.emby.ServerSession
import app.zmby.playback.PlaybackMonitor
import app.zmby.playback.PlaybackOutcome
import app.zmby.data.ServerItem
import app.zmby.playback.LocalStrmPreparer
import app.zmby.playback.LocalStrmSession
import app.zmby.playback.MediaStorePlaybackStore
import app.zmby.playback.PlaybackPreparer
import app.zmby.playback.SubtitleDownloader
import app.zmby.zidoo.ZidooStatusApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.UUID
import java.util.concurrent.TimeUnit

/** How many of this box's newest progress reports stay in memory (and are read back at startup). */
private const val RECENT_PROGRESS = 128

/** Stored server answers older than this are deleted at startup (they are only used for six hours). */
private const val STORED_ANSWERS_MS = 7 * 24 * 60 * 60_000L

/** IMDb's GraphQL endpoint (trailers); a debug build may point `debug.zmby.imdb_base` at the mock server. */
private const val IMDB_GRAPHQL = "https://caching.graphql.imdb.com/"

/** Wikidata (IMDb ID → Rotten Tomatoes ID) and Rotten Tomatoes title pages (ratings design); `debug.zmby.wikidata_base` / `debug.zmby.rt_base` in debug builds. */
private const val WIKIDATA_SPARQL = "https://query.wikidata.org/sparql"
private const val ROTTEN_TOMATOES = "https://www.rottentomatoes.com/"

class ZmbyApp : Application(), ImageLoaderFactory {

    /** Outlives activities so the final playback report is not cancelled when the screen closes. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var sessionStore: SessionStore
        private set
    /** The one HTTP client; it puts each server's User-Agent on requests that have none. */
    private lateinit var http: OkHttpClient
    /** TMDB and Bangumi have their own client (own timeouts); its pool is evicted with [http]'s. */
    private lateinit var catalogClient: OkHttpClient
    /**
     * 设置 › 网络: which requests go through the proxy. Every client asks it, and so does the platform's
     * HttpURLConnection (trailers) as the process default; the system's own choice stays the fallback.
     */
    val proxyRoutes = app.zmby.net.ProxyRoutes(java.net.ProxySelector.getDefault())
    lateinit var emby: EmbyClient
        private set
    lateinit var preparer: PlaybackPreparer
        private set
    lateinit var playbackMemory: app.zmby.playback.PlaybackMemory
        private set
    lateinit var playbackArtwork: app.zmby.playback.PlaybackArtworkResolver
        private set
    lateinit var capsStore: ServerCapsStore
        private set
    /** Local forwarding for 302 cloud-drive servers; its port opens on first use. */
    lateinit var relay: StreamRelay
        private set
    lateinit var zidooStatus: ZidooStatusApi
        private set
    /** Local-STRM sessions (external subtitles on com.zidoo.player). */
    lateinit var localStrm: LocalStrmPreparer
        private set
    lateinit var registry: ServerRegistry
        private set
    lateinit var library: Library
        private set
    lateinit var fanOut: FanOut
        private set
    lateinit var home: HomeFeed
        private set
    lateinit var versions: VersionFinder
        private set
    lateinit var search: AggregateSearch
        private set
    lateinit var favorites: Favorites
        private set
    lateinit var settings: Settings
        private set
    /** The local store (Room): metadata, lists, favourites, watchlist, data sources. */
    lateinit var store: LocalStore
        private set
    /** 待看: purely local. */
    lateinit var watchlist: Watchlist
        private set
    /** 设置 › 数据来源: which servers each feature asks. */
    lateinit var sources: DataSources
        private set
    lateinit var catalogCache: app.zmby.catalog.LimitedCatalogCache
        private set
    var activeImageCacheMb = app.zmby.data.CacheLimits.IMAGES_DEFAULT_MB
        private set
    lateinit var catalog: app.zmby.catalog.CatalogRepository
        private set
    /** List cards' canonical titles and posters by 影片信息来源 (统一数据层 ④), from works already loaded. */
    lateinit var cardFaces: app.zmby.catalog.CardFaces
        private set
    /** 片头片尾: segment markers from the providers in 片段标记来源 (P4). */
    lateinit var segments: app.zmby.segments.SegmentRepository
        private set
    /** Play history of the 观影统计 servers (我的 › 最近看过), local first. */
    lateinit var stats: app.zmby.stats.WatchStats
        private set
    /** 我的: playback sessions on this box, the time charts' source (local-data spec). */
    lateinit var playSessions: app.zmby.stats.PlaySessionRecorder
        private set
    lateinit var me: app.zmby.stats.MeFeed
        private set
    /** 我的 › 追剧日历: TMDB air dates of followed series, cached. */
    lateinit var airing: app.zmby.stats.AiringCalendar
        private set
    /** 观看进度回写: after a playback, the same film or episode on the other servers chosen for it. */
    lateinit var watchSync: app.zmby.playback.WatchSync
        private set
    /** IMDb trailers behind the home hero and the detail pages (trailer design), with their playback cache. */
    lateinit var trailers: app.zmby.trailer.Trailers
        private set
    /** IMDb, Tomatometer and Popcornmeter on the detail pages (ratings design). */
    lateinit var ratings: app.zmby.ratings.Ratings
        private set

    val backups by lazy { app.zmby.backup.BackupRepository(this) }

    /** 弹幕 (danmu design): matched and cached per playback; the server is the one the user typed. */
    val danmu by lazy { app.zmby.danmu.DanmuRepository(cacheDir.resolve("danmu"), app.zmby.danmu.DandanplayApi(http),
        choicesDir = filesDir.resolve("danmu-selections"), aliasContext = {
            listOf(settings.tmdbApiBase.value, settings.tmdbToken.value, settings.metadataLanguage.value).joinToString("|")
        }) }
    val danmuQueries by lazy { app.zmby.danmu.DanmuQueryResolver(
        parent = { server, id -> store.detail(server, id)?.item ?: library.item(server, id) },
        cached = catalog::cached, resolve = catalog::resolve, aliases = catalog::danmuAliases,
    ) }

    /** The 弹幕服务器 from the settings; its secret is kept out of the log. */
    fun danmuEndpoint(): app.zmby.danmu.DanmuEndpoint {
        val e = app.zmby.danmu.DanmuEndpoint(settings.danmuApiBase.value.trim(), settings.danmuAppId.value.trim(), settings.danmuAppSecret.value.trim())
        ZLog.secret(e.appSecret.takeIf { it.isNotEmpty() })
        return e
    }

    /**
     * Per playback request, handed over by token: the versions to fall back on (播放失败自动换源) and
     * the copies of the same film or episode the screen had matched (观看进度回写).
     */
    private val handoffs = java.util.concurrent.ConcurrentHashMap<String, PlayHandoff>()

    fun putHandoff(alternates: List<app.zmby.playback.Alternate>, copies: List<ServerItem> = emptyList()): String? {
        if (alternates.isEmpty() && copies.isEmpty()) return null
        if (handoffs.size > 8) handoffs.clear()
        return UUID.randomUUID().toString().also { handoffs[it] = PlayHandoff(alternates, copies) }
    }

    fun takeHandoff(token: String?): PlayHandoff = token?.let { handoffs.remove(it) } ?: PlayHandoff()

    /** The LAN log server; [lanAddress] is what to type on the Mac, or null while it is off. */
    val lanServer = LanServer()
    private val _lanAddress = MutableStateFlow<String?>(null)
    val lanAddress: StateFlow<String?> = _lanAddress.asStateFlow()

    /** The playback currently handed to the external player, if any. */
    var activePlayback: ActivePlayback? = null

    /**
     * This box's newest progress per copy (stop reports, 标记为已观看), merged by time with the
     * servers' answers on every page. Written through to the store's `progress_report` table and read
     * back at startup, so a restart does not lose what was just watched (统一数据层 · 观看进度).
     */
    private val _recentProgress = MutableStateFlow<Map<String, app.zmby.emby.UserItemData>>(emptyMap())
    val recentProgress: StateFlow<Map<String, app.zmby.emby.UserItemData>> = _recentProgress.asStateFlow()

    private fun noteProgress(ref: ServerItem, data: app.zmby.emby.UserItemData) {
        _recentProgress.update { (it - ref.key + (ref.key to data)).entries.toList().takeLast(RECENT_PROGRESS).associate { row -> row.toPair() } }
        if (ref.server == "catalog") return
        val at = data.lastPlayedDate?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: System.currentTimeMillis()
        appScope.launch(Dispatchers.IO) {
            runCatching { store.putReport(ref.server, ref.item.id, data.playbackPositionTicks, data.played, at) }
                .onFailure { ZLog.w(TAG, "progress report not stored", it) }
        }
    }

    /**
     * False until [init] has read the preferences and built the app's parts on a background thread;
     * the screens show an empty frame until then (architecture: 线程与性能约定).
     */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        ZLog.startBackgroundWriter()
        installCrashLogging()
        Diagnostics.install(this)
        appScope.launch(Dispatchers.IO) {
            val started = System.nanoTime()
            init()
            _ready.value = true
            ZLog.i(TAG, "ready in ${(System.nanoTime() - started) / 1_000_000} ms")
            launch { favorites.drain() }
            launch(Dispatchers.Main) { settings.lanLog.collect(::applyLanLog) }
        }
    }

    /** Suspends until the app's parts exist (a screen restored straight into [PlaybackActivity]). */
    suspend fun awaitReady() { ready.first { it } }

    /** Everything that reads preferences or files: runs on [Dispatchers.IO], never on the main thread. */
    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    private suspend fun init() {
        settings = Settings(this)
        playbackMemory = app.zmby.playback.PlaybackMemory(getSharedPreferences("playback_memory", Context.MODE_PRIVATE))
        activeImageCacheMb = settings.imageCacheMb.value
        ZLog.i(TAG, "start ${BuildConfigCompat.versionName(this)} on ${Build.MODEL} (API ${Build.VERSION.SDK_INT})")
        sessionStore = SessionStore(this)
        val info = ClientInfo(
            device = Build.MODEL.ifBlank { "Android TV" },
            deviceId = sessionStore.deviceId,
            version = BuildConfigCompat.versionName(this),
            // What Emby for Android TV sends: the platform's own agent (OfficialEmby).
            officialAgent = System.getProperty("http.agent")?.takeIf { it.startsWith("Dalvik/") }
                ?: OfficialEmby.dalvikAgent(Build.VERSION.RELEASE, Build.MODEL, Build.ID),
        )
        java.net.ProxySelector.setDefault(proxyRoutes)
        java.net.Authenticator.setDefault(proxyRoutes.platformAuthenticator)
        // Requests that set no User-Agent themselves (images through Coil) get their server's.
        http = OkHttpClient.Builder()
            .proxySelector(proxyRoutes)
            .proxyAuthenticator(proxyRoutes.okHttpAuthenticator)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(UserAgentInterceptor(info.userAgent) { url -> if (::library.isInitialized) library.agentFor(url) else null })
            .build()
        emby = EmbyClient(http, info)
        capsStore = PrefsCapsStore(this)
        relay = StreamRelay(http, emby.clientInfo.userAgent)
        val playbackStore = MediaStorePlaybackStore(contentResolver)
        val subtitleAgent = { url: String -> (if (::library.isInitialized) library.agentFor(url) else null) ?: info.userAgent }
        localStrm = LocalStrmPreparer(playbackStore, SubtitleDownloader(http, subtitleAgent)::download)
        // Whatever a killed process left behind; a playback that old is not running any more.
        runCatching { playbackStore.sweep(STALE_SESSION_MS) }
            .onSuccess { if (it > 0) ZLog.i(TAG, "removed $it stale playback session(s)") }
            .onFailure { ZLog.w(TAG, "playback session sweep failed", it) }
        val local = http.newBuilder()
            .connectTimeout(1500, TimeUnit.MILLISECONDS)
            .readTimeout(1500, TimeUnit.MILLISECONDS)
            .build()
        zidooStatus = ZidooStatusApi(local)
        registry = ServerRegistry(PrefsRegistryStore(this), persist = Disk::write)
        // Before the first request; later changes are applied as they come.
        proxyRoutes.update(proxyPlan(registry.lineup.value))
        ZLog.secret(settings.proxyPassword.value.takeIf { it.isNotEmpty() })
        appScope.launch(Dispatchers.IO) { watchProxy() }
        store = LocalStore(ZmbyDatabase.open(this), emby.json)
        // Reports from before the restart, under any made since (a stop report can arrive first).
        appScope.launch(Dispatchers.IO) {
            runCatching { store.recentReports(RECENT_PROGRESS) }.getOrNull()?.let { stored ->
                _recentProgress.update { now -> app.zmby.data.newestProgress(stored, now) }
            }
        }
        catalogCache = app.zmby.catalog.LimitedCatalogCache(store.db, { settings.metadataCacheMb.value * app.zmby.data.CacheLimits.MIB })
        catalogClient = OkHttpClient.Builder().proxySelector(proxyRoutes).proxyAuthenticator(proxyRoutes.okHttpAuthenticator)
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
        val catalogHttp = app.zmby.catalog.CatalogHttp(catalogClient,
            catalogCache, { settings.tmdbToken.value },
            tmdbBaseProvider = { debugUrl("debug.zmby.tmdb_base") ?: settings.tmdbApiBase.value },
            bgmBase = debugUrl("debug.zmby.bgm_base") ?: "https://api.bgm.tv/v0/",
            animeIndexFile = noBackupFilesDir.resolve("catalog").resolve("bangumi-data-index.txt"),
            animeEndpoints = listOfNotNull(debugUrl("debug.zmby.bangumi_data")) + app.zmby.catalog.CatalogHttp.ANIME_ENDPOINTS,
            userAgent = "zmby/${BuildConfig.VERSION_NAME} (https://github.com/zhenyuefu/zmby)")
        catalog = app.zmby.catalog.CatalogRepository(catalogHttp, catalogCache,
            { settings.metadataLanguage.value }, { settings.posterLanguage.value }, { settings.logoLanguage.value }, { settings.animeAliases.value })
        cardFaces = app.zmby.catalog.CardFaces(catalogCache, catalogHttp.json, appScope, { "${settings.metadataLanguage.value}:${settings.posterLanguage.value}" })
        catalog.onWork = cardFaces::remember
        appScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.flow.combine(settings.metadataLanguage.flow, settings.posterLanguage.flow) { _, _ -> }.collect { cardFaces.load() }
        }
        ZLog.secret(settings.tmdbToken.value)
        catalogCache.prune(System.currentTimeMillis() - 30 * app.zmby.catalog.CatalogHttp.DAY)
        runCatching { catalogCache.dropLegacyRows() }.onSuccess { if (it > 0) ZLog.i(TAG, "dropped $it whole bangumi-data cache row(s)") }
        appScope.launch(Dispatchers.IO) {
            settings.metadataCacheMb.flow.collect {
                try { catalogCache.trimToLimit() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { ZLog.w(TAG, "metadata cache trim failed", e) }
            }
        }
        library = Library(this, emby, registry, sessionStore, capsStore, store)
        val artworkServer = app.zmby.playback.CachedArtworkServer { url ->
            val snapshot = imageLoader.diskCache?.openSnapshot(url)
            if (snapshot == null) null else {
                val file = snapshot.data.toFile()
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(file.path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) { snapshot.close(); null }
                else app.zmby.playback.CachedArtwork(file, snapshot::close)
            }
        }
        playbackArtwork = app.zmby.playback.PlaybackArtworkResolver(catalog::cached,
            { server, id -> store.detail(server, id)?.item }, { library.backdrop(it) }, { library.logo(it) }, artworkServer::cached)
        preparer = PlaybackPreparer(emby, StreamProbe(http, emby.clientInfo.userAgent), capsStore, relay, localStrm,
            artwork = { session, item -> playbackArtwork.resolve(session.id, item) }, proxied = proxyRoutes::proxies)
        // Server answers also kept in Room for their freshness (统一数据层 ①③), per server login.
        val results = app.zmby.store.RoomResultStore(store.db.repoCache())
        fanOut = FanOut(registry, cacheContext = { registry.session(it) }, results = results,
            login = { id -> registry.session(id)?.let { "${it.userId}@${it.baseUrl}" } }, json = emby.json)
        appScope.launch(Dispatchers.IO) { runCatching { results.prune(STORED_ANSWERS_MS) } }
        home = HomeFeed(library, fanOut, store)
        versions = VersionFinder(library, fanOut)
        search = AggregateSearch(library, fanOut)
        library.onRemoved = { id -> appScope.launch(Dispatchers.IO) { store.deleteServer(id) } }
        sources = DataSources(registry, persist = { store.sources.upsertSources(it) }, providerKey = { p ->
            if (p == SegmentProvider.PublicMetaDB) settings.publicMetaDbKey.value else null
        })
        sources.load(store.sources.sources(), store.marked(SOURCES_MIGRATED)) { store.mark(SOURCES_MIGRATED) }
        appScope.launch(Dispatchers.Default) {
            kotlinx.coroutines.flow.combine(sources.rows, registry.lineup) { _, _ -> sources.metadata() }.collect { cardFaces.order = it }
        }
        library.searchScope = { sources.servers(SourceFeature.Search) }
        favorites = Favorites(this, library, fanOut, appScope, store, inSource = { sources.enabled(SourceFeature.Favorites, it) })
        favorites.load()
        watchlist = Watchlist(library, store, appScope)
        val segmentHttp = app.zmby.segments.SegmentHttp(http, "zmby/${BuildConfigCompat.versionName(this)}")
        segments = app.zmby.segments.SegmentRepository(
            markers = store.sources,
            clients = listOf(
                app.zmby.segments.TheIntroDbClient(segmentHttp),
                app.zmby.segments.IntroDbClient(segmentHttp),
                app.zmby.segments.PublicMetaDbClient(segmentHttp),
            ),
            providers = { sources.providers() },
            key = { p -> if (p == SegmentProvider.PublicMetaDB) settings.publicMetaDbKey.value.takeIf { it.isNotBlank() } else null },
        )
        ZLog.secret(settings.publicMetaDbKey.value)
        stats = app.zmby.stats.WatchStats(store, library, { sources.servers(SourceFeature.Stats) })
        playSessions = app.zmby.stats.PlaySessionRecorder(store)
        me = app.zmby.stats.MeFeed(store, { sources.enabled(SourceFeature.Stats, it) }, { sources.servers(SourceFeature.Stats) }, sources.rows)
        airing = app.zmby.stats.AiringCalendar(catalog, app.zmby.catalog.CatalogResources(library, fanOut), { key -> store.episodeMappings.observe(key).first() })
        watchSync = app.zmby.playback.WatchSync(
            allowed = { sources.enabled(SourceFeature.WatchSync, it) && registry.get(it)?.enabled == true },
            fetch = library::resourceItem,
            send = { server, id, w -> library.setUserData(server, id, w.positionTicks, w.played) },
            recorded = { server, id, w -> store.recordSynced(server, id, w.positionTicks, w.played) },
            refused = { server, code ->
                sources.set(SourceFeature.WatchSync, server, false)
                store.sources.upsertSync(app.zmby.store.SyncState(SourceFeature.WatchSync.key, server, System.currentTimeMillis(), "服务器拒绝写入（$code），已关闭"))
            },
            name = { library.status(it)?.name ?: it },
        )
        val agent = "zmby/${BuildConfigCompat.versionName(this)} (https://github.com/zhenyuefu/zmby)"
        val trailerMedia = app.zmby.trailer.TrailerMedia(this, cacheDir.resolve("trailers"), agent)
        val imdbMock = debugUrl("debug.zmby.imdb_base")
        val trailerApi = app.zmby.trailer.ImdbTrailerApi(http, { imdbMock ?: IMDB_GRAPHQL }, agent)
        val imdbTitles = app.zmby.trailer.ImdbTitleRepository(trailerApi::query, results, appScope,
            complete = trailerMedia::complete,
            // The screen's height decides the copy: 1080p on a 1080p or 4K output, less on smaller ones.
            maxHeight = { minOf(1080, maxOf(resources.displayMetrics.heightPixels, 480)) },
            allowHttp = imdbMock != null)
        val rtMock = debugUrl("debug.zmby.rt_base")
        val rtApi = app.zmby.ratings.RottenTomatoesApi(http, { debugUrl("debug.zmby.wikidata_base") ?: WIKIDATA_SPARQL },
            { rtMock ?: ROTTEN_TOMATOES }, agent)
        val rottenTomatoes = app.zmby.ratings.RottenTomatoesRepository(rtApi::path, rtApi::page, results,
            videos = rtApi::videos, smil = rtApi::smil, anyHost = rtMock != null)
        // IMDb, then Rotten Tomatoes, then the trailers stored on the servers (trailer design).
        trailers = app.zmby.trailer.Trailers(imdbTitles, trailerMedia, rottenTomatoes::trailer,
            app.zmby.trailer.ServerTrailers(library::localTrailers, library::trailerStream,
                login = { id -> registry.session(id)?.let { "${it.userId}@${it.baseUrl}" } }, store = results))
        ratings = app.zmby.ratings.Ratings(imdbTitles, rottenTomatoes)
        // No playback survives a new process: what the last run left open ends at its last checkpoint.
        appScope.launch(Dispatchers.IO) { playSessions.closeAbandoned() }
        appScope.launch(Dispatchers.IO) { runCatching { store.trim() }.onFailure { ZLog.w(TAG, "trim failed", it) } }
    }

    private fun proxyPlan(lineup: List<app.zmby.data.ServerStatus>) = app.zmby.net.ProxyPlan.build(
        settings.proxyKind.value, settings.proxyAddress.value, settings.proxyUser.value, settings.proxyPassword.value,
        lines = lineup.flatMap { s -> s.config.lines.filter { it.proxy }.map { it.url } },
        apis = app.zmby.net.ProxyApi.parse(settings.proxyApis.value),
        tmdbBase = settings.tmdbApiBase.value, danmuBase = settings.danmuApiBase.value.trim(),
    )

    /**
     * Rebuilds the proxy plan when the proxy, its APIs or a server line changes. Idle pooled connections
     * are closed so the next request takes the new route; one in use finishes the way it started.
     */
    private suspend fun watchProxy() {
        val inputs = listOf(settings.proxyKind.flow, settings.proxyAddress.flow, settings.proxyUser.flow, settings.proxyPassword.flow,
            settings.proxyApis.flow, settings.tmdbApiBase.flow, settings.danmuApiBase.flow, registry.lineup)
        kotlinx.coroutines.flow.combine(inputs) { proxyPlan(registry.lineup.value) }.collect { plan ->
            ZLog.secret(plan.credentials?.password?.takeIf { it.isNotEmpty() })
            if (proxyRoutes.update(plan)) {
                http.connectionPool.evictAll()
                catalogClient.connectionPool.evictAll()
            }
        }
    }

    /** Coil uses the app's client, so artwork goes out with the server's User-Agent too. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { http }
        // One Coil disk cache per directory. A new budget is applied on the next process start.
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("image_cache")).maxSizeBytes(activeImageCacheMb * app.zmby.data.CacheLimits.MIB).build() }
        // Emby image URLs carry the image's tag, so a cached copy is valid until the URL changes;
        // servers rarely send cache headers, and honouring them would leave posters blank offline.
        .respectCacheHeaders(false)
        .components { add(app.zmby.ui.components.SingleFlightImages()) }
        .build().also { loader ->
            appScope.launch(Dispatchers.IO) {
                runCatching { loader.diskCache?.enforceStartupLimit() }
                    .onFailure { ZLog.w(TAG, "image cache trim failed", it) }
            }
        }

    private fun applyLanLog(on: Boolean) {
        Thread {
            if (on) {
                try {
                    lanServer.start()
                    refreshLanAddress()
                    ZLog.i(TAG, "LAN log at ${_lanAddress.value}")
                } catch (e: Exception) {
                    _lanAddress.value = null
                    ZLog.w(TAG, "LAN log server failed to start", e)
                }
            } else {
                lanServer.stop()
                _lanAddress.value = null
            }
        }.start()
    }

    /** The box's address can change (DHCP, Wi-Fi/Ethernet) or not exist yet at startup; re-read it when shown. */
    fun refreshLanAddress() {
        val port = lanServer.port
        if (port <= 0) return
        val ip = LanServer.lanAddresses().firstOrNull() ?: "127.0.0.1"
        _lanAddress.value = "http://$ip:$port"
    }

    /** Puts the crash's stack into the log buffer (and so on the LAN page) before the process dies. */
    private fun installCrashLogging() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            ZLog.logNow(app.zmby.log.LogLevel.E, TAG, "uncaught on ${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }
    }

    /** The store key of [item] on [server] (an episode under its series' key when known). */
    suspend fun workKeyOf(server: String, item: app.zmby.emby.BaseItemDto): String =
        store.workKeyOf(server, item.id)
            ?: app.zmby.store.StoreKeys.work(server, item, item.seriesId?.let { store.workKeyOf(server, it) })

    /**
     * The segments for playing [item] on [server] in a version of [durationMs] (片头片尾跳过). The
     * episode's series supplies the external IDs; Emby markers need the full item (chapters), from
     * the store or, failing that, one request.
     */
    suspend fun segmentPlan(server: String, item: app.zmby.emby.BaseItemDto, version: app.zmby.emby.MediaSourceInfo? = null): app.zmby.segments.SegmentPlan =
        segmentLookup(server, item, version).plan

    private val segmentLookups = app.zmby.segments.SegmentLookupCache()

    /**
     * Segment markers for [item] played in [version] (one of its sources; null = the item's default):
     * provider times are checked against that version's runtime, and Emby markers come from an item
     * timed on that version.
     */
    suspend fun segmentLookup(server: String, item: app.zmby.emby.BaseItemDto, version: app.zmby.emby.MediaSourceInfo? = null): app.zmby.segments.SegmentLookup = withContext(Dispatchers.IO) {
        val providers = sources.providers()
        if (providers.none { it.enabled }) return@withContext app.zmby.segments.SegmentLookup()
        val durationMs = version?.runTimeTicks?.let { app.zmby.emby.ticksToMs(it) } ?: item.runtimeMs
        val request = app.zmby.segments.SegmentLookupCache.Key(server, item.id, durationMs, providers,
            segments.changes.value, registry.session(server), settings.publicMetaDbKey.value, version?.id)
        segmentLookups.get(request, isCurrent = {
            request.providers == sources.providers() && request.revision == segments.changes.value &&
                request.connection == registry.session(server) && request.providerKey == settings.publicMetaDbKey.value
        }) { fetchSegments(server, item, version, durationMs) }
    }

    private suspend fun fetchSegments(server: String, item: app.zmby.emby.BaseItemDto, version: app.zmby.emby.MediaSourceInfo?, durationMs: Long?): app.zmby.segments.SegmentLookup {
        var embyUnavailable = false
        suspend fun fullItem(): app.zmby.emby.BaseItemDto = try { library.item(server, item.id) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { embyUnavailable = true; item }
        val seriesId = item.seriesId?.takeIf { item.type == "Episode" }
        val series = seriesId?.let { id -> store.detail(server, id)?.item ?: runCatching { library.providerIdsOf(server, id) }.getOrNull() }
        val loaded = if (item.chapters.isNotEmpty() || version?.chapters?.isNotEmpty() == true) item
            else store.detail(server, item.id)?.item?.takeIf { it.chapters.isNotEmpty() }
                ?: if (sources.providers().any { it.enabled && it.provider == SegmentProvider.Emby }) fullItem() else item
        // Emby lists each version's chapters on its media source: those are timed on the version played.
        val full = app.zmby.segments.SegmentRules.asPlayed(loaded,
            version?.let { v -> v.takeIf { it.chapters.isNotEmpty() } ?: loaded.mediaSources.firstOrNull { it.id == v.id } })
        val key = workKeyOf(server, item)
        val result = segments.lookup(full, series, key, durationMs) {
            // Another cut grouped under this item keeps its chapters on its own item (Emby's ItemId).
            val own = (version?.itemId ?: version?.id)?.takeIf { it != item.id && !app.zmby.segments.SegmentRules.sameCut(full, durationMs) }?.let { id ->
                try { library.item(server, id).takeIf { it.id != item.id } }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { null }
            }
            val order = registry.all.associate { it.id to it.order }
            listOfNotNull(own) + store.copiesOfWork(key, server).sortedBy { order[it.server] ?: Int.MAX_VALUE }.map { it.item }
        }
        return result.copy(unavailable = result.unavailable + if (embyUnavailable) setOf(SegmentProvider.Emby) else emptySet())
    }

    private val watchedWrites = kotlinx.coroutines.sync.Mutex()

    /** Persist a manual mark before syncing confirmed copies. An episode keeps its own identity. */
    suspend fun setWatched(ref: ServerItem, played: Boolean, instances: List<ServerItem>): Int =
        appScope.async(Dispatchers.IO) {
            watchedWrites.withLock {
                val data = (ref.item.userData ?: app.zmby.emby.UserItemData()).copy(
                    played = played, playbackPositionTicks = 0, lastPlayedDate = java.time.Instant.now().toString())
                suspend fun record(target: ServerItem) {
                    if (target.server == "catalog") store.putItems(target.server, listOf(target.item.copy(userData = data)))
                    else store.recordProgress(target.server, target.item.id, 0, played)
                    library.invalidateDetail(target.server, target.item.id)
                    noteProgress(target, data)
                }
                record(ref)
                home.watching.invalidate()
                if (played) watchlist.removeStarted(listOf(ref.copy(item = ref.item.copy(userData = data))))
                val known = (instances + ref).filter { it.server != "catalog" && it.item.type == ref.item.type }.groupBy { it.server }
                val ids = ref.item.providerIds.map { (key, id) -> "${key.lowercase()}.$id" }
                val servers = registry.enabled.map { it.id }.filter { it in known || ids.isNotEmpty() }
                val results = fanOut.all(servers, FanOut.SEARCH_TIMEOUT_MS) { server ->
                    val knownCopies = known[server].orEmpty()
                    for (target in knownCopies.distinctBy { it.key }) {
                        library.setPlayed(server, target.item.id, played)
                        record(target)
                    }
                    if (ids.isNotEmpty()) {
                        // Some servers copy the series' IDs onto every episode: an episode found by ID
                        // must also carry the same season and episode number.
                        val extra = library.byProviderIds(server, requireNotNull(ref.item.type), ids)
                            .filter { app.zmby.catalog.ResourceMapping.same(ref.item, it) && knownCopies.none { copy -> copy.item.id == it.id } }
                            .filter { ref.item.type != "Episode" || it.parentIndexNumber == ref.item.parentIndexNumber && it.indexNumber == ref.item.indexNumber }
                        for (item in extra.distinctBy { it.id }) {
                            library.setPlayed(server, item.id, played)
                            record(ServerItem(server, item))
                        }
                    }
                }
                results.count { it !is app.zmby.aggregate.Outcome.Ok }
            }
        }.await()

    /** After a playback ended with [outcome] (the stop report is on its way to the server). */
    suspend fun onPlayed(ref: ServerItem, outcome: PlaybackOutcome) {
        ZLog.d(TAG, "played ${ref.key}: ${outcome.positionMs} ms, watched=${outcome.watched}")
        // Watching started (progress or watched): the work leaves 待看 and shows in 继续观看.
        val data = (ref.item.userData ?: app.zmby.emby.UserItemData()).copy(
            playbackPositionTicks = if (outcome.watched) 0 else app.zmby.emby.msToTicks(outcome.positionMs ?: 0),
            played = outcome.watched,
            lastPlayedDate = java.time.Instant.now().toString(),
        )
        noteProgress(ref, data)
        home.watching.invalidate()
        library.invalidateDetail(ref.server, ref.item.id)
        ref.item.seriesId?.let { library.invalidateDetail(ref.server, it) }
        watchlist.removeStarted(listOf(ServerItem(ref.server, ref.item.copy(userData = data))))
    }

    /** Deletes a finished local-STRM session; the player has already closed the files. */
    fun releaseLocal(local: LocalStrmSession?) {
        local ?: return
        appScope.launch(Dispatchers.IO) {
            runCatching { localStrm.release(local) }.onFailure { ZLog.w(TAG, "could not delete ${local.baseName}", it) }
            ZLog.i(TAG, "released playback session ${local.baseName}")
        }
    }

    private companion object {
        const val TAG = "App"
        const val STALE_SESSION_MS = 12 * 60 * 60 * 1000L
        const val SOURCES_MIGRATED = "migration:data_sources"
    }
}

class PlayHandoff(
    val alternates: List<app.zmby.playback.Alternate> = emptyList(),
    /** Every matched copy, the version started included; 观看进度回写 writes the ones on other servers. */
    val copies: List<ServerItem> = emptyList(),
)

class ActivePlayback(
    val monitor: PlaybackMonitor, val watchJob: Job, val local: LocalStrmSession?,
    val prepared: app.zmby.playback.PreparedPlayback, val server: String,
    val item: app.zmby.emby.BaseItemDto, val launchedAt: Long,
    /** 播放浮层 of this playback, when on. */
    val overlay: app.zmby.overlay.PlaybackOverlay? = null,
)

/**
 * The P0/P1 single login in app-private preferences. Since P2 the servers live in [ServerRegistry];
 * this is read once to migrate the old login, and keeps the device id.
 */
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    val deviceId: String =
        prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit { putString(KEY_DEVICE_ID, it) }
        }

    fun load(): ServerSession? {
        val baseUrl = prefs.getString(KEY_URL, null) ?: return null
        val userId = prefs.getString(KEY_USER, null) ?: return null
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        ZLog.secret(token)
        return ServerSession(baseUrl, userId, token, prefs.getString(KEY_SERVER_ID, null), prefs.getString(KEY_SERVER_NAME, null))
    }

    fun save(s: ServerSession) {
        prefs.edit {
            putString(KEY_URL, s.baseUrl)
            putString(KEY_USER, s.userId)
            putString(KEY_TOKEN, s.accessToken)
            putString(KEY_SERVER_ID, s.serverId)
            putString(KEY_SERVER_NAME, s.serverName)
        }
    }

    fun clear() {
        prefs.edit { remove(KEY_URL).remove(KEY_USER).remove(KEY_TOKEN).remove(KEY_SERVER_ID).remove(KEY_SERVER_NAME) }
    }

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_URL = "base_url"
        const val KEY_USER = "user_id"
        const val KEY_TOKEN = "token"
        const val KEY_SERVER_ID = "server_id"
        const val KEY_SERVER_NAME = "server_name"
    }
}

/**
 * Debug builds only, for emulator tests without the CDN or Bangumi: `adb shell setprop
 * debug.zmby.bangumi_data <url>` tries a local bangumi-data copy first, `debug.zmby.bgm_base <url>`
 * replaces the Bangumi API base and `debug.zmby.tmdb_base <url>` the TMDB one (all served by
 * tools/mock_emby.py, which accepts any token for its TMDB). Release builds ignore them.
 */
private fun debugUrl(property: String): String? = if (!BuildConfig.DEBUG) null else runCatching {
    // getprop is the platform command also used by adb; avoid the hidden SystemProperties API.
    val process = ProcessBuilder("/system/bin/getprop", property).start()
    try {
        process.inputStream.bufferedReader().use { it.readLine().orEmpty() }
    } finally {
        process.destroy()
    }
}.getOrNull()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

private object BuildConfigCompat {
    fun versionName(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"
}

val Context.zmby: ZmbyApp get() = applicationContext as ZmbyApp
