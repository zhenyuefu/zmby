package app.zmby.store

import app.zmby.data.ServerItem
import app.zmby.emby.BaseItemDto
import app.zmby.emby.MediaSourceInfo
import app.zmby.favorites.Favorite
import app.zmby.favorites.FavoriteInstance
import app.zmby.favorites.FavoritesState
import app.zmby.favorites.PendingWrite
import app.zmby.log.ZLog
import app.zmby.aggregate.MetadataIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A cached list: its items in order (as last fetched), the server's total and when it was fetched. */
data class CachedList(val key: String, val items: List<ServerItem>, val total: Int, val fetchedAt: Long) {
    companion object { fun empty(key: String) = CachedList(key, emptyList(), 0, 0) }
}

/** What the detail page shows from the store: the item (full when it was opened before) and its work key. */
data class CachedDetail(val ref: ServerItem, val full: Boolean, val workKey: String)

/**
 * The local store's operations (local-data spec): screens read its flows and show them at once;
 * refreshes write what servers return through it. Only data fetched in normal use is kept — nothing
 * here pages through a library to fill the cache. All calls suspend and run on Room's executors;
 * decoding runs on [Dispatchers.Default].
 */
class LocalStore(val db: ZmbyDatabase, private val json: Json, private val clock: () -> Long = System::currentTimeMillis) {
    private val works = db.works()
    private val lists = db.lists()
    private val people = db.people()
    private val versionDao = db.versions()
    private val favoriteDao = db.favorites()
    val watchlist = db.watchlist()
    val sources = db.sources()

    // ---- items

    /**
     * Records items one server returned (a list, a search, a lookup): each gets its work key
     * ([StoreKeys]; an episode uses its series' key when the series is known) and keeps what an
     * earlier detail fetch stored. An item first keyed by itself that now has an external ID moves
     * to the work key (the old local metadata row is dropped and merged again on the next open).
     */
    suspend fun putItems(server: String, items: List<BaseItemDto>): List<WorkInstance> {
        if (items.isEmpty()) return emptyList()
        val now = clock()
        val unique = items.distinctBy { it.id }
        val known = works.instances(server, unique.map { it.id }).associateBy { it.itemId }
        val seriesIds = unique.mapNotNull { it.seriesId }.distinct()
        val seriesKeys = if (seriesIds.isEmpty()) emptyMap() else works.instances(server, seriesIds).associate { it.itemId to it.workKey }
        val rows = unique.map { item ->
            val old = known[item.id]
            val previous = old?.let { decode(it, full = true)?.item }
            val key = if (old != null && StoreKeys.isExternal(old.workKey) && previous != null && MetadataIdentity.same(previous, item)) old.workKey
                else StoreKeys.work(server, item, item.seriesId?.let { seriesKeys[it] })
            if (old != null && old.workKey != key && !StoreKeys.isExternal(old.workKey) && StoreKeys.isExternal(key)) {
                works.deleteMeta(old.workKey)
                ZLog.d(TAG, "$server:${item.id} now keyed ${key} (was ${old.workKey})")
            }
            WorkInstance(
                serverId = server,
                itemId = item.id,
                workKey = key,
                type = item.type ?: "Video",
                itemJson = json.encodeToString(BaseItemDto.serializer(), listForm(item)),
                detailJson = old?.detailJson,
                imagesJson = old?.imagesJson?.takeIf { previous?.imageTags == item.imageTags && previous?.backdropImageTags == item.backdropImageTags },
                played = item.userData?.played == true,
                positionTicks = item.userData?.playbackPositionTicks ?: 0,
                serverFavorite = item.userData?.isFavorite == true,
                dateCreated = item.dateCreated,
                fetchedAt = now,
                detailAt = old?.detailAt ?: 0,
                accessedAt = now,
            )
        }
        works.upsertInstances(rows)
        return rows
    }

    /** Replaces the list [key] with [items] of [server] (a home row, a page, a resume snapshot). */
    suspend fun putList(key: String, server: String, items: List<BaseItemDto>, total: Int = items.size) {
        putItems(server, items)
        lists.replace(ListCache(key, total, clock()), items.distinctBy { it.id }.mapIndexed { i, it -> ListItem(key, i, server, it.id) })
    }

    /** Lists by key, decoded; missing keys come back empty with fetchedAt 0. */
    fun listsFlow(keys: List<String>): Flow<Map<String, CachedList>> =
        kotlinx.coroutines.flow.combine(lists.listsFlow(keys), lists.entriesFlow(keys)) { metas, entries -> metas to entries }
            .distinctUntilChanged()
            .map { (metas, entries) -> assemble(keys, metas, entries) }
            .flowOn(Dispatchers.Default)

    suspend fun list(key: String): CachedList? {
        val meta = lists.list(key) ?: return null
        return assemble(listOf(key), listOf(meta), lists.entries(listOf(key)))[key]
    }

    private fun assemble(keys: List<String>, metas: List<ListCache>, entries: List<ListEntry>): Map<String, CachedList> {
        val byKey = entries.groupBy { it.listKey }
        val metaByKey = metas.associateBy { it.listKey }
        return keys.associateWith { k ->
            val m = metaByKey[k]
            CachedList(k, byKey[k].orEmpty().mapNotNull { decode(it.instance) }, m?.total ?: 0, m?.fetchedAt ?: 0)
        }
    }

    /** Decoded items by row version; a refresh of one row does not re-decode the others. */
    private val decoded = object : LinkedHashMap<String, ServerItem>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ServerItem>?) = size > DECODE_CACHE
    }

    fun decode(row: WorkInstance, full: Boolean = false): ServerItem? {
        val text = (if (full) row.detailJson else null) ?: row.itemJson
        val cacheKey = "${row.serverId}|${row.itemId}|${text.hashCode()}|${text.length}"
        synchronized(decoded) { decoded[cacheKey]?.let { return it } }
        val item = runCatching { json.decodeFromString(BaseItemDto.serializer(), text) }
            .onFailure { ZLog.w(TAG, "cached ${row.serverId}:${row.itemId} unreadable", it) }
            .getOrNull() ?: return null
        val ref = ServerItem(row.serverId, item)
        synchronized(decoded) { decoded[cacheKey] = ref }
        return ref
    }

    /** What a list keeps of an item: no sources or chapters; People only when the list carried them (a person's works). */
    private fun listForm(item: BaseItemDto) = item.copy(mediaSources = emptyList(), chapters = emptyList())

    suspend fun workKeyOf(server: String, itemId: String): String? = works.instance(server, itemId)?.workKey

    // ---- detail

    /** The cached item (full if opened before), as it changes. */
    fun detailFlow(server: String, itemId: String): Flow<CachedDetail?> =
        works.instanceFlow(server, itemId).distinctUntilChanged().map { row ->
            row ?: return@map null
            val ref = decode(row, full = true) ?: return@map null
            CachedDetail(ref, row.detailJson != null, row.workKey)
        }.flowOn(Dispatchers.Default)

    suspend fun detail(server: String, itemId: String): ServerItem? = works.instance(server, itemId)?.let { decode(it, full = true) }

    /**
     * Stores a full item (detail page); returns its work key. Canonical metadata comes from
     * TMDB / Bangumi (catalog); server copies are never merged across servers.
     */
    suspend fun putDetail(server: String, item: BaseItemDto): String {
        val row = putItems(server, listOf(item)).single()
        works.upsertInstances(listOf(row.copy(detailJson = json.encodeToString(BaseItemDto.serializer(), item), detailAt = clock())))
        return row.workKey
    }

    suspend fun touch(server: String, itemId: String, key: String?) {
        val now = clock()
        works.touchInstance(server, itemId, now)
        if (key != null) works.touchMeta(key, now)
    }

    // ---- versions

    suspend fun versions(forKey: String): List<VersionSummary> = versionDao.versions(forKey)

    suspend fun putVersion(forKey: String, server: String, item: BaseItemDto, sources: List<MediaSourceInfo>, reason: String, possible: Boolean) {
        versionDao.upsert(listOf(VersionSummary(
            forKey, server, item.id,
            json.encodeToString(BaseItemDto.serializer(), listForm(item)),
            json.encodeToString(ListSerializer(MediaSourceInfo.serializer()), sources),
            reason, possible, clock(),
        )))
    }

    suspend fun dropVersion(forKey: String, server: String) = versionDao.delete(forKey, server)

    fun decodeSources(text: String): List<MediaSourceInfo> =
        runCatching { json.decodeFromString(ListSerializer(MediaSourceInfo.serializer()), text) }.getOrDefault(emptyList())

    fun decodeItem(text: String): BaseItemDto? = runCatching { json.decodeFromString(BaseItemDto.serializer(), text) }.getOrNull()

    /** This work's full copies on servers other than [except] (cached details only; no request). */
    suspend fun copiesOfWork(key: String, except: String): List<ServerItem> =
        works.instancesOfWork(key).filter { it.serverId != except && it.detailJson != null }.mapNotNull { decode(it, full = true) }

    // ---- after playback

    /** The player's stop report, written at once so the rows change before the server is asked again. */
    suspend fun recordProgress(server: String, itemId: String, positionTicks: Long, played: Boolean) {
        works.setUserData(server, itemId, if (played) 0 else positionTicks, played)
        val row = works.instance(server, itemId) ?: return
        val item = decode(row)?.item ?: return
        val data = (item.userData ?: app.zmby.emby.UserItemData()).copy(
            playbackPositionTicks = if (played) 0 else positionTicks,
            played = played,
            lastPlayedDate = java.time.Instant.ofEpochMilli(clock()).toString(),
        )
        works.upsertInstances(listOf(row.copy(itemJson = json.encodeToString(BaseItemDto.serializer(), item.copy(userData = data)))))
    }

    // ---- favourites (favorites spec; FavoriteLogic decides, this only persists)

    suspend fun loadFavorites(): FavoritesState? {
        val rows = favoriteDao.favorites()
        val instances = favoriteDao.instances().groupBy { it.workKey }
        val pulls = sources.syncs(FEATURE_FAVORITES).associate { it.serverId to it.lastPullAt }
        if (rows.isEmpty() && pulls.isEmpty()) return null
        return FavoritesState(
            favorites = rows.map { r ->
                Favorite(r.workKey, r.title, r.year, r.type, r.addedAt, r.updatedAt, r.removedAt,
                    instances[r.workKey].orEmpty().map { FavoriteInstance(it.serverId, it.itemId, it.synced) }, r.imageTag)
            },
            queue = favoriteDao.writes().map { PendingWrite(it.serverId, it.itemId, it.favorite, it.attempts) },
            lastPull = pulls,
        )
    }

    suspend fun saveFavorites(state: FavoritesState) {
        favoriteDao.replaceAll(
            state.favorites.map { FavoriteRow(it.workKey, it.title, it.year, it.type, it.addedAt, it.updatedAt, it.removedAt, it.imageTag) },
            state.favorites.flatMap { f -> f.instances.mapIndexed { i, x -> FavoriteInstanceRow(f.workKey, x.server, x.itemId, x.synced, i) } },
            state.queue.mapIndexed { i, w -> FavoriteWriteRow(w.server, w.itemId, w.favorite, w.attempts, i) },
        )
        state.lastPull.forEach { (server, at) -> sources.upsertSync(SyncState(FEATURE_FAVORITES, server, at)) }
    }

    /** One-off markers (a migration done) live in sync_state under [feature] with no server. */
    suspend fun marked(feature: String): Boolean = sources.sync(feature, "") != null
    suspend fun mark(feature: String) = sources.upsertSync(SyncState(feature, "", clock()))

    // ---- maintenance

    /** LRU (local-data spec, 容量): works and instances over 50 000 rows, people unused for a week. */
    suspend fun trim(max: Int = Freshness.MAX_ROWS) {
        val dropInstances = Freshness.excess(works.instanceCount(), max)
        val dropMeta = Freshness.excess(works.metaCount(), max)
        val a = if (dropInstances > 0) works.trimInstances(dropInstances) else 0
        val b = if (dropMeta > 0) works.trimMeta(dropMeta) else 0
        val c = people.prune(clock() - Freshness.PERSON_PRUNE_MS)
        if (c > 0) people.pruneAliases()
        if (a + b + c > 0) ZLog.i(TAG, "trimmed $a instances, $b works, $c people")
    }

    /** A server was deleted (or its account replaced): every row of it goes. */
    suspend fun deleteServer(server: String) {
        works.deleteServerInstances(server)
        lists.deleteServerItems(server)
        for (prefix in listOf("resume:$server", "nextup:$server", "latest:$server", "browse:$server", "views:$server", "seasons:$server", "episodes:$server", "person:$server")) {
            lists.deleteListsLike(prefix)
            lists.deleteItemsLike(prefix)
        }
        versionDao.deleteServer(server)
        db.playHistory().deleteServer(server)
        people.deleteServerAliases(server)
        sources.deleteServerSync(server)
        sources.deleteSource(server)
        ZLog.i(TAG, "deleted the cached data of server $server")
    }

    companion object {
        private const val TAG = "Store"
        private const val DECODE_CACHE = 3000
        const val FEATURE_FAVORITES = "favorites"
        /** Shown in the cast row (ui-spec §2.2 4): actors, directors, writers. */
        val CAST_TYPES = setOf("Actor", "Director", "Writer")
    }
}
