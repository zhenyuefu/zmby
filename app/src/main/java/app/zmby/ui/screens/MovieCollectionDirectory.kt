package app.zmby.ui.screens

import app.zmby.ZmbyApp
import app.zmby.aggregate.AggregateSearch
import app.zmby.aggregate.FanOut
import app.zmby.aggregate.Outcome
import app.zmby.aggregate.MetadataIdentity
import app.zmby.aggregate.Persist
import app.zmby.aggregate.tmdb
import app.zmby.catalog.CatalogWork
import app.zmby.data.ServerItem
import app.zmby.data.SourceFeature
import app.zmby.data.withPlaybackProgress
import app.zmby.emby.EmbyClient
import app.zmby.emby.ItemsResult
import app.zmby.store.CachedList
import app.zmby.log.ZLog
import app.zmby.store.Freshness
import app.zmby.store.StoreKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*

/** A collection entry keeps its catalogue identity even after a server answers with a playable copy. */
data class MovieCollection(
    val box: ServerItem,
    val films: List<ServerItem>,
    val sources: List<String> = listOf(box.server),
    val matches: Map<String, List<ServerItem>> = emptyMap(),
    val pending: Set<String> = emptySet(),
    val currentKey: String? = null,
    val completeList: Boolean = true,
    val alternateKeys: Set<String> = emptySet(),
) {
    val catalog get() = box.server == "catalog"
    val key get() = if (catalog) box.key else box.item.tmdb()?.toLongOrNull()?.takeIf { it > 0 }?.let { "box:tmdb:$it" } ?: box.key
    fun represents(selected: String?): Boolean = selected == key || selected in alternateKeys
    fun available(film: ServerItem) = matches[film.key].orEmpty()
    fun status(film: ServerItem): String? = when {
        !catalog -> null
        available(film).isNotEmpty() -> "有片源"
        film.key in pending -> "待确认"
        else -> "暂无片源"
    }
    val currentIndex = films.indexOfFirst { it.key == currentKey }
    val meta = collectionMeta(films)
    val foundCount = matches.count { it.value.isNotEmpty() }
}

internal fun serverCollection(box: ServerItem, members: CachedList): MovieCollection {
    val count = members.items.size
    // Older cached pages stored the returned size as their total, even at the API's page limit.
    val complete = count >= members.total && count >= (box.item.childCount ?: 0) &&
        (count < EmbyClient.COLLECTION_LIMIT || count == box.item.childCount)
    return MovieCollection(box, members.items, completeList = complete)
}

private fun externalMemberKeys(ref: ServerItem): List<String> {
    val kind = when (ref.item.type) {
        "Movie" -> "movie"
        "Series" -> "series"
        else -> return emptyList()
    }
    return MetadataIdentity.ids(ref.item).mapNotNull { (provider, id) ->
        val number = if (provider == "imdb") id.takeIf { it.startsWith("tt") }?.drop(2) else id
        number?.toLongOrNull()?.takeIf { it > 0 }?.let { "$kind:$provider:$id" }
    }
}

private fun memberKey(ref: ServerItem) = externalMemberKeys(ref).firstOrNull() ?: StoreKeys.local(ref.server, ref.item.id)

private fun conflictingMemberIds(copies: List<ServerItem>): Boolean = copies.size > 1 &&
    copies.flatMap { MetadataIdentity.ids(it.item).entries }.groupBy({ it.key }, { it.value }).values.any { it.distinct().size > 1 }

internal fun containingCollections(id: String, list: List<MovieCollection>) = list.filter { c -> c.films.any { it.item.id == id } }

internal fun mergeServerCollections(groups: List<MovieCollection>, current: Set<String>): List<MovieCollection> =
    groups.groupBy { it.key }.values.map { group ->
        if (group.size == 1) {
            val box = group.single()
            return@map box.copy(currentKey = box.films.firstOrNull { it.key in current }?.key)
        }
        val films = group.flatMap { it.films }.distinctBy { it.key }.groupBy(::memberKey)
        val representatives = films.values.flatMap { copies ->
            if (conflictingMemberIds(copies)) copies
            else listOf(copies.firstOrNull { it.key in current } ?: copies.first())
        }
        group.first().copy(
            films = representatives.sortedBy { it.item.productionYear ?: Int.MAX_VALUE },
            sources = group.map { it.box.server }.distinct(),
            completeList = group.all { it.completeList },
            currentKey = representatives.firstOrNull { it.key in current }?.key,
        )
    }

/** Compare rosters by work identity after merging boxes with a shared external collection ID. */
internal fun distinctCollections(catalog: MovieCollection?, local: List<MovieCollection>): List<MovieCollection> {
    if (local.isEmpty()) return listOfNotNull(catalog)
    // An already confirmed server address also identifies copies that carry no TMDB ID.
    val aliases = mutableMapOf<String, String?>()
    fun alias(key: String, work: String) {
        if (key !in aliases) aliases[key] = work else if (aliases[key] != work) aliases[key] = null
    }
    val series = catalog?.films.orEmpty().mapTo(mutableSetOf()) { film ->
        val work = memberKey(film)
        for (copy in catalog?.available(film).orEmpty()) {
            alias(StoreKeys.local(copy.server, copy.item.id), work)
            externalMemberKeys(copy).forEach { alias(it, work) }
        }
        work
    }
    fun identity(film: ServerItem): String {
        val address = StoreKeys.local(film.server, film.item.id)
        val keys = externalMemberKeys(film)
        val mapped = (keys + address).filter { it in aliases }.map { aliases[it] }.distinct()
        if (mapped.isEmpty()) return keys.firstOrNull() ?: address
        val target = mapped.singleOrNull() ?: return address
        val explicit = keys.firstOrNull { it.startsWith("movie:tmdb:") }
        return if (film.item.type == "Movie" && (explicit == null || explicit == target)) target else address
    }

    val members = local.flatMap { it.films } + catalog?.matches.orEmpty().values.flatten()
    val conflicting = members.groupBy(::identity).filterValues(::conflictingMemberIds).keys
    val visible = mutableListOf<MovieCollection>()
    val sameMembers = mutableMapOf<Set<String>, Int>()
    val covered = mutableSetOf<String>()
    for (box in local) {
        if (!box.completeList || box.films.isEmpty()) {
            visible += box
            continue
        }
        val roster = box.films.mapTo(mutableSetOf()) { film ->
            val work = identity(film)
            if (work in conflicting) StoreKeys.local(film.server, film.item.id) else work
        }
        if (series.containsAll(roster)) {
            covered += box.key
            continue
        }
        val index = sameMembers.putIfAbsent(roster, visible.size)
        if (index == null) visible += box
        else {
            val first = visible[index]
            visible[index] = first.copy(sources = (first.sources + box.sources).distinct(), alternateKeys = first.alternateKeys + box.key)
        }
    }
    return listOfNotNull(catalog?.copy(alternateKeys = covered)) + visible
}

@kotlinx.serialization.Serializable
internal data class CollectionMatches(val items: List<ServerItem>, val complete: Boolean)

internal fun collectionMatches(server: String, ids: Set<String>, page: ItemsResult): CollectionMatches {
    val hits = page.items.filter { it.type == "Movie" && it.tmdb() in ids }
    // If the server ignores the filter or truncates its answer, absence cannot be established.
    return CollectionMatches(hits.filterNot { it.locationType.equals("Virtual", true) }.distinctBy { it.id }.map { ServerItem(server, it) },
        hits.size == page.items.size && (page.totalRecordCount == page.items.size || page.totalRecordCount == 0 && page.items.size < 50))
}

private val MATCHES = Persist("collection-matches", CollectionMatches.serializer())

/** A slow server holds only its own next batch. Every positive result can be displayed immediately. */
internal fun collectionResources(box: MovieCollection, servers: List<String>, fanOut: FanOut,
    lookup: suspend (String, List<String>) -> ItemsResult): Flow<MovieCollection> {
    val filmKeys = box.films.associate { requireNotNull(it.item.tmdb()) to it.key }
    val batches = filmKeys.keys.sorted().chunked(AggregateSearch.BATCH)
    data class ServerAnswer(val copies: List<ServerItem> = emptyList(), val checked: Set<String> = emptySet())
    val branches = servers.map { server -> flow {
        var answer = ServerAnswer()
        emit(answer)
        delay(350) // Give playback versions and navigation priority on a cold page.
        for (batch in batches) {
            val result = fanOut.run(listOf(server), FanOut.LIST_TIMEOUT_MS, share = listOf("collection-movies", batch),
                cacheMs = FanOut.RESOURCE_CACHE_MS, persist = MATCHES) {
                collectionMatches(server, batch.toSet(), lookup(server, batch))
            }.single()
            if (result !is Outcome.Ok) break
            answer = ServerAnswer(answer.copies + result.value.items, if (result.value.complete) answer.checked + batch else answer.checked)
            emit(answer)
            if (!result.value.complete) break // An ignored/truncated filter cannot establish further misses.
        }
    } }
    if (branches.isEmpty()) return flowOf(box)
    return combine(branches) { answers ->
        val matches = answers.flatMap { it.copies }.groupBy { filmKeys.getValue(requireNotNull(it.item.tmdb())) }
        val pending = filmKeys.filterKeys { id -> answers.any { id !in it.checked } }.values.toSet()
        box.copy(matches = matches, pending = pending)
    }
}

/** Network branches emit independently. Both the detail strip and its grid collect the same state. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class MovieCollectionDirectory(private val app: ZmbyApp) {
    fun collections(ownServer: String, copies: Flow<List<ServerItem>>, tmdb: Flow<CatalogWork?>): Flow<List<MovieCollection>> {
        val servers = combine(app.registry.lineup, app.sources.rows) { lineup, _ ->
            (lineup.filter { it.id == ownServer && it.enabled }.map { it.id } + app.sources.servers(SourceFeature.Aggregate)).distinct()
        }.distinctUntilChanged()
        val activeCopies = combine(copies, servers) { list, allowed -> list.filter { it.server in allowed } }
        val serverBoxes = servers.flatMapLatest { allowed ->
            val branches = allowed.map { server ->
                copies.map { list -> list.firstOrNull { it.server == server }?.item?.id }.distinctUntilChanged().flatMapLatest { id ->
                    if (id == null) flowOf(emptyList()) else localCollections(server, id)
                }
            }
            if (branches.isEmpty()) flowOf(emptyList()) else combine(branches) { it.toList().flatten() }
        }
        val directory = combine(tmdb, app.settings.metadataLanguage.flow, app.settings.tmdbToken.flow) { work, lang, token ->
            work?.collectionId?.takeIf { token.isNotBlank() }?.let { Triple(it, work.ref.id, lang) }
        }.distinctUntilChanged().flatMapLatest { query ->
            flow {
                emit(null)
                if (query == null) return@flow
                val (box, films) = app.catalog.movieCollection(query.first)
                val current = films.firstOrNull { it.item.tmdb() == query.second } ?: return@flow
                emit(MovieCollection(box, films, currentKey = current.key))
            }.catch { ZLog.d("Collection", "TMDB collection unavailable: ${it.message}") }
        }
        val canonical = directory.flatMapLatest { box ->
            if (box == null) flowOf(null) else servers.flatMapLatest { allowed ->
                collectionResources(box, allowed, app.fanOut, app.library::movieCollectionMatches)
            }
        }
        return combine(canonical, serverBoxes, activeCopies) { catalog, groups, known ->
            val currentKeys = known.mapTo(mutableSetOf()) { it.key }
            val local = mergeServerCollections(groups, currentKeys)
            val preferred = catalog?.let { box ->
                val real = known.filterNot { it.item.locationType.equals("Virtual", true) }
                val indexed = (groups.flatMap { it.films } + real).filter { it.item.type == "Movie" && !it.item.locationType.equals("Virtual", true) }.groupBy { it.item.tmdb() }
                val matches = box.films.associate { film ->
                    val current = if (film.key == box.currentKey) real.filter { it.item.tmdb() == null || it.item.tmdb() == film.item.tmdb() } else emptyList()
                    film.key to (current + indexed[film.item.tmdb()].orEmpty() + box.available(film)).distinctBy { it.key }
                }
                box.copy(matches = matches)
            }
            distinctCollections(preferred, local)
        }.combine(app.recentProgress) { collections, progress ->
            if (progress.isEmpty()) collections else collections.map { collection ->
                if (collection.catalog) collection.copy(matches = collection.matches.mapValues { (_, copies) ->
                    copies.map { it.withPlaybackProgress(progress) }
                }) else collection.copy(films = collection.films.map { it.withPlaybackProgress(progress) })
            }
        }.flowOn(Dispatchers.IO)
    }

    private fun localCollections(server: String, itemId: String): Flow<List<MovieCollection>> = flow {
        emit(emptyList())
        val key = "in-collections:$server:$itemId"
        val cached = app.store.list(key)
        val stored = cached?.items.orEmpty().mapNotNull { box ->
            app.store.list("collection:$server:${box.item.id}")?.let { serverCollection(box, it) }
        }
        val valid = containingCollections(itemId, stored)
        if (valid.isNotEmpty()) emit(valid)
        if (cached != null && valid.size == cached.total && !Freshness.due(Freshness.Kind.Browse, cached.fetchedAt, System.currentTimeMillis())) return@flow
        delay(350)
        app.fanOut.run(listOf(server), FanOut.LIST_TIMEOUT_MS, share = listOf("in-collections", itemId), cacheMs = Freshness.BROWSE_MS) {
            app.library.collectionsOf(server, itemId).map { (box, films) -> serverCollection(box, films) }.also { found ->
                app.store.putList(key, server, found.map { it.box.item })
            }
        }.collect { if (it is Outcome.Ok) emit(it.value) }
    }.catch { ZLog.d("Collection", "Server collections unavailable: ${it.message}") }
}
