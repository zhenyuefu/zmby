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
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.UUID
import java.util.concurrent.TimeUnit

class ZmbyApp : Application(), ImageLoaderFactory {

    /** Outlives activities so the final playback report is not cancelled when the screen closes. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var sessionStore: SessionStore
        private set
    /** The one HTTP client; it puts each server's User-Agent on requests that have none. */
    private lateinit var http: OkHttpClient
    lateinit var emby: EmbyClient
        private set
    lateinit var preparer: PlaybackPreparer
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
    /** 片头片尾: segment markers from the providers in 片段标记来源 (P4). */
    lateinit var segments: app.zmby.segments.SegmentRepository
        private set
    /** 观影统计 (P4): play history of the 观影统计 servers, local first. */
    lateinit var stats: app.zmby.stats.WatchStats
        private set

    val backups by lazy { app.zmby.backup.BackupRepository(this) }

    /** Versions to fall back on per playback request (播放失败自动换源), handed over by token. */
    private val alternates = java.util.concurrent.ConcurrentHashMap<String, List<app.zmby.playback.Alternate>>()

    fun putAlternates(list: List<app.zmby.playback.Alternate>): String? {
        if (list.isEmpty()) return null
        if (alternates.size > 8) alternates.clear()
        return UUID.randomUUID().toString().also { alternates[it] = list }
    }

    fun takeAlternates(token: String?): List<app.zmby.playback.Alternate> = token?.let { alternates.remove(it) }.orEmpty()

    /** The LAN log server; [lanAddress] is what to type on the Mac, or null while it is off. */
    val lanServer = LanServer()
    private val _lanAddress = MutableStateFlow<String?>(null)
    val lanAddress: StateFlow<String?> = _lanAddress.asStateFlow()

    /** The playback currently handed to the external player, if any. */
    var activePlayback: ActivePlayback? = null

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
    private suspend fun init() {
        settings = Settings(this)
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
        // Requests that set no User-Agent themselves (images through Coil) get their server's.
        http = OkHttpClient.Builder()
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
        preparer = PlaybackPreparer(emby, StreamProbe(http, emby.clientInfo.userAgent), capsStore, relay, localStrm)
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
        store = LocalStore(ZmbyDatabase.open(this), emby.json)
        catalogCache = app.zmby.catalog.LimitedCatalogCache(store.db, { settings.metadataCacheMb.value * app.zmby.data.CacheLimits.MIB })
        val catalogHttp = app.zmby.catalog.CatalogHttp(OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build(),
            catalogCache, { settings.tmdbToken.value },
            tmdbBaseProvider = { debugUrl("debug.zmby.tmdb_base") ?: settings.tmdbApiBase.value },
            bgmBase = debugUrl("debug.zmby.bgm_base") ?: "https://api.bgm.tv/v0/",
            animeIndexFile = noBackupFilesDir.resolve("catalog").resolve("bangumi-data-index.txt"),
            animeEndpoints = listOfNotNull(debugUrl("debug.zmby.bangumi_data")) + app.zmby.catalog.CatalogHttp.ANIME_ENDPOINTS,
            userAgent = "zmby/${BuildConfig.VERSION_NAME} (https://github.com/zhenyuefu/zmby)")
        catalog = app.zmby.catalog.CatalogRepository(catalogHttp, catalogCache,
            { settings.metadataLanguage.value }, { settings.posterLanguage.value }, { settings.logoLanguage.value }, { settings.animeAliases.value })
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
        fanOut = FanOut(registry)
        home = HomeFeed(library, fanOut, store)
        versions = VersionFinder(library, fanOut)
        search = AggregateSearch(library, fanOut)
        library.onRemoved = { id -> appScope.launch(Dispatchers.IO) { store.deleteServer(id) } }
        sources = DataSources(registry, persist = { store.sources.upsertSources(it) }, providerKey = { p ->
            if (p == SegmentProvider.PublicMetaDB) settings.publicMetaDbKey.value else null
        })
        sources.load(store.sources.sources(), store.marked(SOURCES_MIGRATED)) { store.mark(SOURCES_MIGRATED) }
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
        stats = app.zmby.stats.WatchStats(store, library, { sources.servers(SourceFeature.Stats) }, sources.rows)
        appScope.launch(Dispatchers.IO) { runCatching { store.trim() }.onFailure { ZLog.w(TAG, "trim failed", it) } }
    }

    /** Coil uses the app's client, so artwork goes out with the server's User-Agent too. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { http }
        // One Coil disk cache per directory. A new budget is applied on the next process start.
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("image_cache")).maxSizeBytes(activeImageCacheMb * app.zmby.data.CacheLimits.MIB).build() }
        // Emby image URLs carry the image's tag, so a cached copy is valid until the URL changes;
        // servers rarely send cache headers, and honouring them would leave posters blank offline.
        .respectCacheHeaders(false)
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
    suspend fun segmentPlan(server: String, item: app.zmby.emby.BaseItemDto, durationMs: Long?): app.zmby.segments.SegmentPlan {
        val seriesId = item.seriesId?.takeIf { item.type == "Episode" }
        val series = seriesId?.let { id -> store.detail(server, id)?.item ?: runCatching { library.providerIdsOf(server, id) }.getOrNull() }
        val full = if (item.chapters.isNotEmpty()) item else store.detail(server, item.id)?.item?.takeIf { it.chapters.isNotEmpty() }
            ?: if (sources.providers().any { it.enabled && it.provider == SegmentProvider.Emby }) runCatching { library.item(server, item.id) }.getOrNull() ?: item else item
        val key = workKeyOf(server, item)
        return segments.plan(full, series, key, durationMs) {
            val order = registry.all.associate { it.id to it.order }
            store.copiesOfWork(key, server).sortedBy { order[it.server] ?: Int.MAX_VALUE }.map { it.item }
        }
    }

    /** After a playback ended with [outcome] (the stop report is on its way to the server). */
    suspend fun onPlayed(ref: ServerItem, outcome: PlaybackOutcome) {
        ZLog.d(TAG, "played ${ref.key}: ${outcome.positionMs} ms, watched=${outcome.watched}")
        // Watching started (progress or watched): the work leaves 待看 and shows in 继续观看.
        val data = (ref.item.userData ?: app.zmby.emby.UserItemData()).copy(
            playbackPositionTicks = app.zmby.emby.msToTicks(outcome.positionMs ?: 0),
            played = outcome.watched,
        )
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

class ActivePlayback(
    val monitor: PlaybackMonitor, val watchJob: Job, val local: LocalStrmSession?,
    val prepared: app.zmby.playback.PreparedPlayback, val server: String,
    val item: app.zmby.emby.BaseItemDto, val launchedAt: Long,
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
