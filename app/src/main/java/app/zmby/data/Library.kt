package app.zmby.data

import android.content.Context
import app.zmby.SessionStore
import app.zmby.aggregate.SearchSource
import app.zmby.aggregate.VersionSource
import app.zmby.emby.BaseItemDto
import app.zmby.emby.MediaSourceInfo
import app.zmby.emby.EmbyClient
import app.zmby.emby.ItemsResult
import app.zmby.emby.ServerSession
import app.zmby.emby.UaMode
import app.zmby.emby.UaSetting
import app.zmby.log.ZLog
import app.zmby.search.CandidateServer
import app.zmby.search.LocalTitle
import app.zmby.search.Pinyin
import app.zmby.search.PinyinProbe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.StateFlow

/**
 * An item and the server it came from. Every cross-server operation passes the pair: the same item
 * id on another server can be a different film (aggregation spec, 归属与标识).
 */
data class ServerItem(val server: String, val item: BaseItemDto) {
    val key: String get() = "$server:${item.id}"
}

data class LibraryView(val server: String, val id: String, val name: String, val collectionType: String?, val onHome: Boolean, val inSearch: Boolean)

/**
 * The data the screens read, over every server in the [registry]. Per-server calls name their
 * server; nothing here pages through a whole library.
 */
class Library(
    context: Context,
    val emby: EmbyClient,
    val registry: ServerRegistry,
    legacy: SessionStore,
    private val caps: ServerCapsStore,
    private val store: app.zmby.store.LocalStore? = null,
) : VersionSource, SearchSource, app.zmby.aggregate.MetadataSource {
    private val prefs = context.getSharedPreferences("libraries", Context.MODE_PRIVATE)
    private val historyPrefs = context.getSharedPreferences("search_history", Context.MODE_PRIVATE)
    private val versionPrefs = context.getSharedPreferences("picked_versions", Context.MODE_PRIVATE)

    init {
        migrate(legacy)
        // Auto found another identity for a server (a login, a ping or any request): keep it for the server.
        emby.onIdentityChange = { base, serverId, mode ->
            val id = serverId.ifEmpty { registry.ownerOf(base)?.id.orEmpty() }
            if (id.isNotEmpty()) {
                registry.recordIdentity(id, mode)
                ZLog.i(TAG, "${registry.get(id)?.name ?: id}: now spoken to as ${mode.label}")
            }
        }
    }

    /** The User-Agent for a URL on one of the servers (images, subtitles); null for other hosts. */
    fun agentFor(url: String): String? = registry.ownerOf(url)?.let { emby.clientInfo.identity(it.config.ua).userAgent }

    /** 服务器页 › User-Agent: a new choice starts detection afresh. */
    fun setUa(server: String, mode: UaMode, custom: String? = null) {
        registry.update(server) { it.copy(ua = UaSetting(mode = mode, detected = null, custom = custom ?: it.ua.custom)) }
    }

    /**
     * P0/P1 kept one login in `session.xml`. The first start of the multi-server build turns it into
     * the first registry entry and moves its per-server facts and library switches over; the old
     * file stays untouched, so an older build still finds its login.
     */
    private fun migrate(legacy: SessionStore) {
        if (registry.all.isNotEmpty() || prefs.getBoolean(KEY_MIGRATED, false)) return
        val old = legacy.load()
        if (old != null) {
            val config = registry.add(ServerRegistry.fromLegacy(old))
            caps.update(config.id) { caps.get(old.baseUrl) }
            val edit = prefs.edit()
            for (key in listOf(KEY_HOME_OFF, KEY_SEARCH_OFF)) {
                val ids = prefs.getStringSet(key, emptySet())!!
                edit.putStringSet("${key}_v2", ids.map { "${config.id}|$it" }.toSet())
            }
            edit.apply()
            ZLog.i(TAG, "moved the single login (${config.name}) into the server registry as ${config.id}")
        }
        prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
    }

    val servers: StateFlow<List<ServerStatus>> get() = registry.servers

    fun status(server: String): ServerStatus? = registry.get(server)
    fun session(server: String): ServerSession = registry.session(server) ?: error("这台服务器已删除")

    /** Logs in and adds the server; the same Emby server and user only gains a line. */
    suspend fun addServer(url: String, user: String, password: String, name: String? = null, lineName: String? = null): ServerConfig {
        val s = emby.authenticate(url, user, password)
        if (s.ua.detected == UaMode.Official) ZLog.i(TAG, "${s.serverName ?: s.baseUrl} only let the official client in; it plays through the local relay")
        val line = ServerLine(ServerRegistry.newId(), lineName?.takeIf { it.isNotBlank() } ?: "主线路", s.baseUrl)
        val config = registry.add(
            ServerConfig(
                id = ServerRegistry.newId(),
                name = name?.takeIf { it.isNotBlank() } ?: s.serverName ?: ServerRegistry.hostOf(s.baseUrl),
                lines = listOf(line),
                lineId = line.id,
                userId = s.userId,
                token = s.accessToken,
                userName = user,
                embyServerId = s.serverId,
                ua = s.ua,
            ),
        )
        runCatching { measure(config.id) }
        return config
    }

    /** Called after a server is deleted (its cached rows go too). */
    var onRemoved: ((String) -> Unit)? = null

    fun removeServer(id: String) {
        registry.remove(id)
        onRemoved?.invoke(id)
        ZLog.i(TAG, "removed server $id")
    }

    /** 测速: pings every line of [only] (or of every enabled server) and records health. */
    suspend fun measure(only: String? = null) = coroutineScope {
        val targets = registry.enabled.filter { only == null || it.id == only }
        targets.flatMap { s -> s.config.lines.map { line -> s to line } }
            .map { (s, line) ->
                async {
                    val (ms, version) = emby.ping(line.url, s.config.ua, s.id)
                    registry.recordLatency(s.id, line.id, ms, version)
                }
            }.awaitAll()
        Unit
    }

    private val viewsCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<BaseItemDto>>>()

    /**
     * The user's top-level libraries on [server]: kept in memory for ten minutes and in the store
     * for offline starts, so home rows and browsing do not ask for them every time.
     */
    suspend fun views(server: String): List<BaseItemDto> {
        val now = System.currentTimeMillis()
        viewsCache[server]?.takeIf { now - it.first < VIEWS_TTL_MS }?.let { return it.second }
        return try {
            emby.views(session(server)).items.also { items ->
                viewsCache[server] = now to items
                store?.putList("views:$server", server, items)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            store?.list("views:$server")?.items?.map { it.item }?.takeIf { it.isNotEmpty() } ?: throw e
        }
    }

    suspend fun libraries(server: String): List<LibraryView> {
        val homeOff = offSet("${KEY_HOME_OFF}_v2")
        val searchOff = offSet("${KEY_SEARCH_OFF}_v2")
        return views(server)
            .filter { it.collectionType == null || it.collectionType in VIDEO_COLLECTIONS }
            .map { LibraryView(server, it.id, it.name.orEmpty(), it.collectionType, "$server|${it.id}" !in homeOff, "$server|${it.id}" !in searchOff) }
    }

    /** Of [views] (a server's top-level folders), the video libraries whose home switch is on. */
    fun homeLibraries(server: String, views: List<BaseItemDto>): List<BaseItemDto> {
        val homeOff = offSet("${KEY_HOME_OFF}_v2")
        return views.filter { (it.collectionType == null || it.collectionType in VIDEO_COLLECTIONS) && "$server|${it.id}" !in homeOff }
    }

    fun setOnHome(server: String, libraryId: String, on: Boolean) = toggle("${KEY_HOME_OFF}_v2", "$server|$libraryId", !on)
    fun setInSearch(server: String, libraryId: String, on: Boolean) = toggle("${KEY_SEARCH_OFF}_v2", "$server|$libraryId", !on)

    fun backupSwitches(server: String): Pair<List<String>, List<String>> {
        fun read(key: String) = offSet("${key}_v2").filter { it.startsWith("$server|") }.map { it.substringAfter('|') }
        return read(KEY_HOME_OFF) to read(KEY_SEARCH_OFF)
    }

    fun restoreSwitches(server: String, homeOff: List<String>, searchOff: List<String>) {
        for ((key, ids) in listOf(KEY_HOME_OFF to homeOff, KEY_SEARCH_OFF to searchOff)) {
            val all = offSet("${key}_v2").filter { it.startsWith("$server|") }.map { it.substringAfter('|') }
            (all + ids).distinct().forEach { id -> toggle("${key}_v2", "$server|$id", id in ids) }
        }
    }

    /** Library switches in memory; the file is written behind on [Disk]. */
    private val switchSets = HashMap<String, Set<String>>()

    private fun offSet(key: String): Set<String> = synchronized(switchSets) {
        switchSets.getOrPut(key) { prefs.getStringSet(key, emptySet())!!.toSet() }
    }

    private fun toggle(key: String, id: String, add: Boolean) {
        val next = synchronized(switchSets) {
            offSet(key).toMutableSet().apply { if (add) add(id) else remove(id) }.toSet().also { switchSets[key] = it }
        }
        Disk.write { prefs.edit().putStringSet(key, next).apply() }
    }

    // ---- one server

    suspend fun resume(server: String): List<ServerItem> =
        emby.resume(session(server)).items.map { ServerItem(server, it) }.also(::note)

    /** 最新添加 from the server's libraries whose home switch is on. */
    suspend fun latest(server: String, perLibrary: Int = 24): List<ServerItem> = coroutineScope {
        val s = session(server)
        val libs = libraries(server)
        val enabled = libs.filter { it.onHome }
        val items = if (enabled.isEmpty()) emptyList() else if (enabled.size == libs.size) {
            // One bounded request for the whole server, instead of a burst for every library.
            emby.latest(s, null, perLibrary)
        } else {
            val slots = Semaphore(2)
            enabled.map { lib -> async {
                slots.withPermit {
                    try { emby.latest(s, lib.id, perLibrary) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { ZLog.w(TAG, "latest library ${lib.name} on ${registry.get(server)?.name}: ${e.message}"); emptyList() }
                }
            } }.flatMap { it.await() }
        }
        items
            .distinctBy { it.id }
            .map { ServerItem(server, it) }
            .also(::note)
    }

    /** 最新 of one library (a server's own home). */
    suspend fun latestIn(server: String, libraryId: String, limit: Int = 24): List<ServerItem> =
        emby.latest(session(server), libraryId, limit).distinctBy { it.id }.map { ServerItem(server, it) }.also(::note)

    suspend fun nextUp(server: String, limit: Int = 24): List<ServerItem> =
        emby.nextUp(session(server), null, limit = limit).items.map { ServerItem(server, it) }.also(::note)

    /** One page of a browse grid on one server; [parentId] narrows it to one library, [query] sorts and filters. */
    suspend fun browse(server: String, types: String, favorites: Boolean, parentId: String? = null, start: Int = 0, limit: Int = PAGE, query: Map<String, String> = emptyMap()): ItemsResult =
        emby.browse(session(server), types, favorites, start = start, limit = limit, parentId = parentId, query = query).also { r -> note(r.items.map { ServerItem(server, it) }) }

    /**
     * Where titles starting with [letter] begin in a browse grid sorted by name: the number of items
     * before it (`NameLessThan`, Limit=0), one request. "#" is the top.
     */
    suspend fun indexOfLetter(server: String, types: String, parentId: String?, query: Map<String, String>, letter: String): Int {
        if (letter == "#") return 0
        return emby.browse(session(server), types, false, start = 0, limit = 0, parentId = parentId, query = query + ("NameLessThan" to letter)).totalRecordCount
    }

    /** A person on [server] by name (人物页: person ids never cross servers). */
    override suspend fun person(server: String, name: String): BaseItemDto = emby.person(session(server), name)

    override suspend fun persons(server: String, ids: List<String>): List<BaseItemDto> = emby.persons(session(server), ids).items

    override suspend fun peopleByIds(server: String, ids: List<String>): List<BaseItemDto> = emby.peopleByIds(session(server), ids).items

    /** Films and series with [server]'s person [personId], with People for the person's part. */
    override suspend fun worksOf(server: String, personId: String): List<BaseItemDto> =
        emby.itemsWithPerson(session(server), personId).items.filter { it.type == "Movie" || it.type == "Series" }

    suspend fun genres(server: String, parentId: String?, types: String): List<String> =
        emby.genres(session(server), parentId, types).items.mapNotNull { it.name?.takeIf(String::isNotBlank) }

    /** Searches the server's libraries whose search switch is on; one request when all of them are. */
    override suspend fun search(server: String, term: String): List<BaseItemDto> = coroutineScope {
        val s = session(server)
        val libs = libraries(server)
        val enabled = libs.filter { it.inSearch }
        val items = if (enabled.size == libs.size) {
            emby.search(s, term).items
        } else {
            val slots = Semaphore(2)
            enabled.map { lib -> async { slots.withPermit { emby.search(s, term, parentId = lib.id).items } } }.flatMap { it.await() }.distinctBy { it.id }
        }
        items.also { note(it.map { i -> ServerItem(server, i) }) }
    }

    suspend fun resourceItem(server: String, id: String): BaseItemDto = emby.resourceItem(session(server),id)

    override suspend fun item(server: String, id: String): BaseItemDto = emby.item(session(server), id)

    suspend fun playedItems(server: String, start: Int, limit: Int) = emby.playedItems(session(server), start, limit)

    /** [id]'s base fields and ProviderIds only (a series' IDs for the segment databases). */
    suspend fun providerIdsOf(server: String, id: String): BaseItemDto? = emby.providerIdsOf(session(server), listOf(id)).items.firstOrNull()
    override suspend fun seasons(server: String, seriesId: String) = emby.seasons(session(server), seriesId).items

    // ---- aggregation (VersionSource, SearchSource)

    override suspend fun byProviderIds(server: String, type: String, ids: List<String>): List<BaseItemDto> =
        emby.byProviderIds(session(server), type, ids).items

    override suspend fun searchType(server: String, term: String, type: String, limit: Int): List<BaseItemDto> =
        emby.searchType(session(server), term, type, limit).items

    override suspend fun episodePage(server: String, seriesId: String, seasonId: String, start: Int, limit: Int): ItemsResult =
        emby.episodes(session(server), seriesId, seasonId, start = start, limit = limit)

    override suspend fun sources(server: String, itemId: String): List<MediaSourceInfo> =
        emby.playbackInfo(session(server), itemId, 0, isPlayback = false).mediaSources

    /** The version picked by hand for a work last time: (server, media source id). */
    private val picked = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun pickedVersion(workKey: String): Pair<String, String>? =
        (picked[workKey] ?: versionPrefs.getString(workKey, null))?.split('|')?.takeIf { it.size == 2 }?.let { it[0] to it[1] }

    fun rememberVersion(workKey: String, server: String, sourceId: String) {
        picked[workKey] = "$server|$sourceId"
        Disk.write { versionPrefs.edit().putString(workKey, "$server|$sourceId").apply() }
    }

    /** Every episode of a season, page by page: a long anime season has hundreds. */
    suspend fun episodes(server: String, seriesId: String, seasonId: String): List<BaseItemDto> {
        val s = session(server)
        val all = mutableListOf<BaseItemDto>()
        while (true) {
            val page = emby.episodes(s, seriesId, seasonId, start = all.size, limit = EPISODE_PAGE)
            all += page.items
            if (page.items.isEmpty() || all.size >= page.totalRecordCount) return all
        }
    }

    suspend fun nextUp(server: String, seriesId: String) = emby.nextUp(session(server), seriesId).items.firstOrNull()

    /** The episode after [episode] in series order (across seasons), on the same server, or null at the end. */
    suspend fun nextEpisode(episode: ServerItem): BaseItemDto? {
        val seriesId = episode.item.seriesId ?: return null
        val page = emby.episodes(session(episode.server), seriesId, startItemId = episode.item.id, limit = 2)
        val i = page.items.indexOfFirst { it.id == episode.item.id }
        // Servers that ignore StartItemId return the series from the top; then search the list.
        return if (i >= 0) page.items.getOrNull(i + 1) else null
    }

    suspend fun setPlayed(server: String, id: String, played: Boolean) = emby.setPlayed(session(server), id, played)
    suspend fun setFavorite(server: String, id: String, favorite: Boolean) = emby.setFavorite(session(server), id, favorite)

    // ---- images

    fun primaryImage(ref: ServerItem, maxHeight: Int = 440): String? {
        if (ref.server == "catalog") return ref.item.imageTags["Primary"]
        val s = registry.session(ref.server) ?: return null
        val item = ref.item
        item.imageTags["Primary"]?.let { return emby.imageUrl(s, item.id, "Primary", it, maxHeight) }
        if (item.seriesId != null && item.seriesPrimaryImageTag != null) {
            return emby.imageUrl(s, item.seriesId, "Primary", item.seriesPrimaryImageTag, maxHeight)
        }
        return null
    }

    /** Landscape artwork for 16:9 cards: episode still, else thumb, else backdrop. */
    fun landscapeImage(ref: ServerItem, maxHeight: Int = 340): String? {
        val s = registry.session(ref.server) ?: return null
        val item = ref.item
        if (item.type == "Episode") item.imageTags["Primary"]?.let { return emby.imageUrl(s, item.id, "Primary", it, maxHeight) }
        item.imageTags["Thumb"]?.let { return emby.imageUrl(s, item.id, "Thumb", it, maxHeight) }
        if (item.parentThumbItemId != null && item.parentThumbImageTag != null) {
            return emby.imageUrl(s, item.parentThumbItemId, "Thumb", item.parentThumbImageTag, maxHeight)
        }
        return backdrop(ref, maxHeight)
    }

    fun backdrop(ref: ServerItem, maxHeight: Int = 1080): String? {
        if (ref.server == "catalog") return ref.item.backdropImageTags.firstOrNull()
        val s = registry.session(ref.server) ?: return null
        val item = ref.item
        item.backdropImageTags.firstOrNull()?.let { return emby.imageUrl(s, item.id, "Backdrop", it, maxHeight) }
        if (item.parentBackdropItemId != null) {
            item.parentBackdropImageTags.firstOrNull()?.let { return emby.imageUrl(s, item.parentBackdropItemId, "Backdrop", it, maxHeight) }
        }
        return null
    }

    /**
     * The title Logo (ui-spec §1.3): the item's own, else its parent's (an episode shows the series
     * logo); null when the server has none and the title is drawn as text.
     */
    fun logo(ref: ServerItem, maxHeight: Int = 340): String? {
        if (ref.server == "catalog") return ref.item.imageTags["Logo"]
        val s = registry.session(ref.server) ?: return null
        val item = ref.item
        item.imageTags["Logo"]?.let { return emby.imageUrl(s, item.id, "Logo", it, maxHeight) }
        if (item.parentLogoItemId != null && item.parentLogoImageTag != null) {
            return emby.imageUrl(s, item.parentLogoItemId, "Logo", item.parentLogoImageTag, maxHeight)
        }
        return null
    }

    /** An image picked by the store's metadata merge ([app.zmby.store.ImageRef]); null once its server is gone. */
    fun imageUrl(ref: app.zmby.store.ImageRef, type: String, maxHeight: Int): String? {
        val s = registry.session(ref.server) ?: return null
        return emby.imageUrl(s, ref.item, type, ref.tag, maxHeight)
    }

    override suspend fun images(server: String, itemId: String) = emby.images(session(server), itemId)

    fun itemJson(item: BaseItemDto): String = emby.json.encodeToString(BaseItemDto.serializer(), item)

    // ---- pinyin search (aggregation spec): candidates from local titles and pinyin-capable servers only

    private val seen = LinkedHashMap<String, LocalTitle>()

    /** Remembers titles the app has already fetched, as local pinyin candidates; no extra requests. */
    fun note(items: List<ServerItem>) = synchronized(seen) {
        for (ref in items) {
            val it = ref.item
            val title = (if (it.type == "Episode") it.seriesName else it.name)?.takeIf { t -> t.isNotBlank() } ?: continue
            seen.remove(title)
            seen[title] = LocalTitle(title, if (it.type == "Episode") null else it.productionYear)
        }
        while (seen.size > MAX_LOCAL_TITLES) seen.remove(seen.keys.first())
    }

    /** Titles seen this session plus the search history, newest first. */
    fun localTitles(): List<LocalTitle> {
        val recent = synchronized(seen) { seen.values.reversed() }
        return searchHistory().map { LocalTitle(it) } + recent
    }

    @Volatile private var history: List<String>? = null

    fun searchHistory(): List<String> = history
        ?: historyPrefs.getString(KEY_HISTORY, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty().also { history = it }

    /** Keeps the last 50 searches that contain Chinese (pinyin candidates come from them). */
    fun rememberSearch(term: String) {
        val t = term.trim()
        if (t.isEmpty() || !Pinyin.hasHan(t)) return
        val list = (listOf(t) + searchHistory().filter { it != t }).take(MAX_HISTORY)
        history = list
        Disk.write { historyPrefs.edit().putString(KEY_HISTORY, list.joinToString("\n")).apply() }
    }

    fun capsOf(server: String): ServerCaps = caps.get(session(server).capsKey)
    fun setRelayOverride(server: String, o: Override) { caps.update(session(server).capsKey) { it.copy(relay = o) } }
    fun setPinyinOverride(server: String, o: Override) { caps.update(session(server).capsKey) { it.copy(pinyinOverride = o) } }

    /**
     * The servers to ask for candidates, in priority order: in the search source, not cooling down, and
     * whose search understands pinyin. [PinyinCandidates][app.zmby.search.PinyinCandidates] asks the first three.
     */
    /** The servers search asks, in priority order (设置 › 数据来源 › 搜索来源); set by the app. */
    var searchScope: (() -> List<String>)? = null

    fun candidateServers(): List<CandidateServer> = (searchScope?.invoke()?.mapNotNull { registry.get(it) } ?: registry.aggregating)
        .filter { !registry.isCooling(it.id) && caps.get(it.session.capsKey).supportsPinyin }
        .map { status ->
            object : CandidateServer {
                override val key = status.id
                override suspend fun search(term: String, limit: Int) = emby.searchTitles(status.session, term, limit).items
            }
        }

    /**
     * Probes one server's pinyin support once: after login, when its version changes and weekly.
     * Uses a Chinese title from that server's [latest] (already fetched), so it costs one search with Limit=5.
     */
    suspend fun maybeProbePinyin(server: String, latest: List<BaseItemDto>, now: Long = System.currentTimeMillis()) {
        val status = status(server) ?: return
        val s = status.session
        val version = status.version
        val known = caps.get(s.capsKey)
        if (known.pinyinOverride != Override.Auto) return
        val fresh = known.pinyinCheckedAt > 0 && now - known.pinyinCheckedAt < ServerCaps.RECHECK_MS
        if (fresh && (version == null || version == known.version)) return
        // Picking the sample converts titles to pinyin (pinyin4j reads its tables): never on the main thread.
        val result = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { PinyinProbe.probe(latest) { term -> emby.searchTitles(s, term, 5).items } } }
            .onFailure { ZLog.w("Pinyin", "probe of ${status.name} failed: $it") }
            .getOrElse { return }
        caps.update(s.capsKey) { it.copy(pinyin = result, pinyinCheckedAt = now, version = version ?: it.version) }
    }

    companion object {
        const val PAGE = 60
        private const val VIEWS_TTL_MS = 10 * 60_000L
        private const val TAG = "Library"
        private const val MAX_LOCAL_TITLES = 2000
        private const val MAX_HISTORY = 50
        private const val KEY_HISTORY = "terms"
        private const val EPISODE_PAGE = 100
        private const val KEY_HOME_OFF = "home_off"
        private const val KEY_SEARCH_OFF = "search_off"
        private const val KEY_MIGRATED = "migrated_to_registry"
        private val VIDEO_COLLECTIONS = setOf("movies", "tvshows", "homevideos", "mixed", "boxsets")
    }
}
