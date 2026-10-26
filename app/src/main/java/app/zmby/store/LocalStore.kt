package app.zmby.store

import app.zmby.data.ServerItem
import app.zmby.emby.BaseItemDto
import app.zmby.emby.ImageInfo
import app.zmby.emby.MediaSourceInfo
import app.zmby.favorites.Favorite
import app.zmby.favorites.FavoriteInstance
import app.zmby.favorites.FavoritesState
import app.zmby.favorites.PendingWrite
import app.zmby.log.ZLog
import app.zmby.aggregate.MetadataIdentity
import app.zmby.aggregate.providerId
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A cached list: its items in order (as last fetched), the server's total and when it was fetched. */
data class CachedList(val key: String, val items: List<ServerItem>, val total: Int, val fetchedAt: Long) {
    companion object { fun empty(key: String) = CachedList(key, emptyList(), 0, 0) }
}

/** What the detail page shows from the store: the item (full when it was opened before) and the merged work. */
data class CachedDetail(val ref: ServerItem, val full: Boolean, val workKey: String, val meta: WorkMeta?)

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
    /** Set by the app after source preferences load. Tests without preferences use every source. */
    var canonicalMetadataOnly = false
    var metadataServers: (() -> List<String>)? = null
    private val metadataLock = Mutex()

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

    /** The cached item (full if opened before) and its merged work, as it changes. */
    fun detailFlow(server: String, itemId: String): Flow<CachedDetail?> =
        works.instanceFlow(server, itemId).distinctUntilChanged().map { row ->
            row ?: return@map null
            val ref = decode(row, full = true) ?: return@map null
            CachedDetail(ref, row.detailJson != null, row.workKey, works.meta(row.workKey))
        }.flowOn(Dispatchers.Default)

    fun metaFlow(key: String): Flow<WorkMeta?> = works.metaFlow(key).distinctUntilChanged()

    suspend fun detail(server: String, itemId: String): ServerItem? = works.instance(server, itemId)?.let { decode(it, full = true) }

    /** Stores a full item (detail page) and its cast; returns its work key. */
    suspend fun putDetail(server: String, item: BaseItemDto): String {
        val row = putItems(server, listOf(item)).single()
        works.upsertInstances(listOf(row.copy(detailJson = json.encodeToString(BaseItemDto.serializer(), item), detailAt = clock())))
        if (!canonicalMetadataOnly) remerge(row.workKey, metadataServers?.invoke() ?: works.instancesOfWork(row.workKey).map { it.serverId }.distinct(), ServerItem(server, item))
        return row.workKey
    }

    /** Only a verified external-ID match may attach another server's full copy to this work. */
    suspend fun putMatchedDetail(server: String, item: BaseItemDto, key: String, reference: BaseItemDto) {
        require(MetadataIdentity.same(reference, item)) { "Metadata identity mismatch" }
        val row = putItems(server, listOf(item)).single()
        works.upsertInstances(listOf(row.copy(workKey = key, detailJson = json.encodeToString(BaseItemDto.serializer(), item), detailAt = clock())))
    }

    suspend fun copies(key: String): List<ServerItem> = works.instancesOfWork(key).mapNotNull { decode(it, full = true) }

    suspend fun putImages(server: String, itemId: String, images: List<ImageInfo>) {
        val row = works.instance(server, itemId) ?: return
        works.upsertInstances(listOf(row.copy(imagesJson = json.encodeToString(ListSerializer(ImageInfo.serializer()), images))))
    }

    /** Instances of [key] on [servers] whose image sizes have not been asked for yet. */
    suspend fun missingImages(key: String, servers: Set<String>): List<Pair<String, String>> =
        works.instancesOfWork(key).filter { it.serverId in servers && it.imagesJson == null }.map { it.serverId to it.itemId }

    suspend fun needsImages(server: String, id: String): Boolean = works.instance(server, id)?.imagesJson == null

    /**
     * Merges the work [key] again from its instances on the metadata-source [servers] (priority =
     * list order). A work keyed by one item only uses that item.
     */
    suspend fun remerge(key: String, servers: List<String>, reference: ServerItem? = null): WorkMeta? = metadataLock.withLock { db.withTransaction {
        val order = servers.withIndex().associate { it.value to it.index }
        val rows = works.instancesOfWork(key).filter { r ->
            r.serverId in order && (StoreKeys.isExternal(key) || StoreKeys.localRef(key) == (r.serverId to r.itemId))
        }
        val allCandidates = rows.mapNotNull { r ->
            val item = decode(r, full = true)?.item ?: return@mapNotNull null
            val images = r.imagesJson?.let { runCatching { json.decodeFromString(ListSerializer(ImageInfo.serializer()), it) }.getOrNull() }
            MetaCandidate(r.serverId, order.getValue(r.serverId), item, images)
        }
        val lead = reference ?: allCandidates.minByOrNull { it.order }?.let { ServerItem(it.server, it.item) }
        val candidates = allCandidates.filter { c -> lead != null &&
            (lead.item.type !in setOf("Movie", "Series") ||
                (c.server == lead.server && c.item.id == lead.item.id) || MetadataIdentity.same(lead.item, c.item)) }
        val meta = MetaMerge.merge(key, candidates, clock())
        if (meta == null) {
            works.deleteMeta(key)
            people.replaceWorkPeople(key, emptyList())
            return@withTransaction null
        }
        works.upsertMeta(meta)
        rebuildCast(key, candidates.sortedBy { it.order })
        meta
    } }

    private suspend fun rebuildCast(key: String, candidates: List<MetaCandidate>) {
        val rows = mutableListOf<WorkPerson>()
        for (c in candidates) {
            val cast = c.item.people.filter { it.id != null && !it.name.isNullOrBlank() && it.type in CAST_TYPES }
            for (p in cast) {
                val alias = people.alias(c.server, p.id!!)
                val personKey = alias?.personKey ?: StoreKeys.person(c.server, p.id, p.providerIds)
                val old = people.person(personKey)
                val identity = BaseItemDto(id = p.id, name = p.name, type = "Person", providerIds = p.providerIds)
                if (old != null && MetadataIdentity.conflicts(personItem(old), identity)) continue
                val name = listOfNotNull(old?.name, p.name).let { names -> names.firstOrNull(MetaMerge::isChinese) ?: names.first() }
                people.upsertAliases(listOf(PersonAlias(c.server, p.id, personKey, p.name!!, p.primaryImageTag ?: alias?.imageTag)))
                people.upsertPerson((old ?: Person(personKey, name, c.server)).copy(name = name,
                    image = old?.image ?: p.primaryImageTag?.let { ImageRef(c.server, p.id, it) }, accessedAt = clock()))
                val i = rows.indexOfFirst { it.personKey == personKey && it.kind == p.type }
                if (i < 0) rows += WorkPerson(key, personKey, p.type!!, p.role, rows.size)
                else if (!MetaMerge.isChinese(rows[i].role.orEmpty()) && MetaMerge.isChinese(p.role.orEmpty())) rows[i] = rows[i].copy(role = p.role)
            }
        }
        people.replaceWorkPeople(key, rows)
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

    // ---- people

    fun castFlow(workKey: String): Flow<List<CastEntry>> = people.castFlow(workKey).distinctUntilChanged()

    suspend fun person(key: String): Person? = people.person(key)
    fun personFlow(key: String): Flow<Person?> = people.personFlow(key).distinctUntilChanged()
    suspend fun aliases(key: String): List<PersonAlias> = people.aliases(key)
    suspend fun alias(server: String, personId: String): PersonAlias? = people.alias(server, personId)

    /**
     * The person page fetched [item] (a person on [server]): with a TMDB (or IMDb) id the person row
     * moves to that key, so the same person from any server is one row; name in Chinese first.
     */
    suspend fun mergePerson(server: String, item: BaseItemDto, expectedKey: String? = null): String = metadataLock.withLock { db.withTransaction {
        require(MetadataIdentity.validPerson(item)) { "Invalid person response" }
        val now = clock()
        val oldKey = people.alias(server, item.id)?.personKey ?: StoreKeys.person(server, item.id)
        val old = people.person(oldKey)
        val expected = expectedKey?.let { people.person(it) }
        if (expected != null && expectedKey != oldKey) require(MetadataIdentity.same(personItem(expected), item)) { "Person identity mismatch" }
        val known = people.byExternalIds(item.providerId("tmdb"), item.providerId("imdb"))
            .filter { MetadataIdentity.same(personItem(it), item) }.singleOrNull()
        val key = when {
            expected != null -> expected.personKey
            known != null -> known.personKey
            old != null && (old.tmdb != null || old.imdb != null) && !MetadataIdentity.conflicts(personItem(old), item) -> oldKey
            else -> StoreKeys.person(server, item.id, item.providerIds)
        }
        val target = if (key != oldKey) people.person(key) else old
        if (target != null) require(!MetadataIdentity.conflicts(personItem(target), item)) { "Conflicting person external IDs" }
        if (old != null && (old.tmdb != null || old.imdb != null)) require(!MetadataIdentity.conflicts(personItem(old), item)) { "Person alias changed identity" }
        if (key != oldKey) {
            people.moveAliases(oldKey, key)
            people.moveWorkPeople(oldKey, key)
            people.deletePerson(oldKey)
        }
        people.upsertAliases(listOf(PersonAlias(server, item.id, key, item.name.orEmpty(), item.imageTags["Primary"])))
        // Preserve each server's full response: later sources cannot blank a biography or make
        // merging depend on which request happened to finish first.
        val raw = putItems(server, listOf(item)).single()
        works.upsertInstances(listOf(raw.copy(detailJson = json.encodeToString(BaseItemDto.serializer(), item), detailAt = now)))
        val order = metadataServers?.invoke()?.withIndex()?.associate { it.value to it.index }.orEmpty()
        val candidates = people.aliases(key).mapNotNull { a ->
            val r = works.instance(a.serverId, a.personId) ?: return@mapNotNull null
            val p = decode(r, full = true)?.item ?: return@mapNotNull null
            if (!MetadataIdentity.validPerson(p) || (a.serverId != server && !MetadataIdentity.same(item, p))) return@mapNotNull null
            if (metadataServers != null && a.serverId !in order && a.serverId != server) return@mapNotNull null
            val images = r.imagesJson?.let { json.decodeFromString(ListSerializer(ImageInfo.serializer()), it) }
            MetaCandidate(a.serverId, order[a.serverId] ?: Int.MAX_VALUE, p, images)
        }.sortedWith(compareBy<MetaCandidate> { it.order }.thenBy { it.server })
        fun text(field: (MetaCandidate) -> String?) = MetaMerge.pickText(candidates, field)?.second
        val name = text { it.item.name } ?: item.name.orEmpty()
        val ids = MetadataIdentity.ids(item)
        val base = target ?: old ?: Person(key, name)
        people.upsertPerson(base.copy(
            personKey = key,
            name = name,
            overview = text { it.item.overview } ?: base.overview,
            birthDate = candidates.firstNotNullOfOrNull { it.item.premiereDate?.takeIf(String::isNotBlank) } ?: base.birthDate,
            birthPlace = text { it.item.productionLocations.firstOrNull() } ?: base.birthPlace,
            image = MetaMerge.pickImage(candidates, "Primary") ?: base.image,
            tmdb = ids["tmdb"] ?: base.tmdb,
            imdb = ids["imdb"] ?: base.imdb,
            fetchedAt = now,
            detailAt = now,
            accessedAt = now,
        ))
        key
    } }

    fun personItem(p: Person): BaseItemDto = BaseItemDto(id = p.personKey, type = "Person", name = p.name,
        providerIds = listOfNotNull(p.tmdb?.let { "Tmdb" to it }, p.imdb?.let { "Imdb" to it }).toMap())

    /** Old person lists trusted server-side filters. Rebuild just those lists on upgrade. */
    suspend fun repairPersonCache() = db.withTransaction {
        if (!marked("migration:verified-person-works-v1")) {
            lists.deleteItemsLike("person:")
            lists.deleteListsLike("person:")
            people.deleteCompatCast()
            people.deleteCompatAliases()
            people.deleteCompatPeople()
            mark("migration:verified-person-works-v1")
        }
    }

    suspend fun touchPerson(key: String) = people.touch(key, clock())

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
