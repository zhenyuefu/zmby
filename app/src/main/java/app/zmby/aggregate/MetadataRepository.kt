package app.zmby.aggregate

import app.zmby.emby.BaseItemDto
import app.zmby.data.ServerItem
import app.zmby.emby.ImageInfo
import app.zmby.log.ZLog
import app.zmby.store.LocalStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Read-only Emby operations used to enrich the work or person currently on screen. */
interface MetadataSource {
    suspend fun item(server: String, id: String): BaseItemDto
    suspend fun byProviderIds(server: String, type: String, ids: List<String>): List<BaseItemDto>
    suspend fun persons(server: String, ids: List<String>): List<BaseItemDto>
    suspend fun person(server: String, name: String): BaseItemDto
    suspend fun peopleByIds(server: String, ids: List<String>): List<BaseItemDto>
    suspend fun worksOf(server: String, personId: String): List<BaseItemDto>
    suspend fun images(server: String, itemId: String): List<ImageInfo>
}

data class PersonUpdate(val key: String, val server: String, val id: String)

/**
 * A name retrieves candidates; shared, non-conflicting external IDs establish identity. FanOut
 * bounds network work, while each response is committed serially so completion order cannot
 * replace the page's identity or drop another server's cast.
 */
class MetadataRepository(
    private val source: MetadataSource,
    private val store: LocalStore,
    private val fanOut: FanOut,
    private val servers: () -> List<String>,
) {
    private data class Copy(val item: BaseItemDto, val people: List<BaseItemDto>, val images: List<ImageInfo>?)

    suspend fun refreshWork(server: String, item: BaseItemDto) = withContext(Dispatchers.IO) {
        val key = store.workKeyOf(server, item.id) ?: return@withContext
        val selected = servers()
        store.remerge(key, selected, ServerItem(server, item))
        val known = store.copies(key)
        fanOut.run(selected, FanOut.SEARCH_TIMEOUT_MS) { s ->
            val candidate = if (s == server) item else {
                val query = MetadataIdentity.query(item)
                if (query.isEmpty() || item.type !in setOf("Movie", "Series")) return@run null
                val cached = known.firstOrNull { it.server == s && MetadataIdentity.same(item, it.item) }?.item
                val match = cached ?: source.byProviderIds(s, item.type!!, query)
                    .filter { MetadataIdentity.same(item, it) }.distinctBy { it.id }.singleOrNull() ?: return@run null
                val full = source.item(s, match.id)
                if (full.id != match.id || full.type != match.type || MetadataIdentity.conflicts(item, full) || MetadataIdentity.conflicts(match, full)) return@run null
                full.copy(providerIds = MetadataIdentity.ids(match) + MetadataIdentity.ids(full))
            }
            prepareCopy(s, candidate)
        }.collect { o ->
            when (o) {
                is Outcome.Ok -> o.value?.let { c ->
                    for (p in c.people) {
                        try { store.mergePerson(o.server, p) }
                        catch (e: IllegalArgumentException) { ZLog.w(TAG, "rejected person ${o.server}:${p.id}: ${e.message}") }
                    }
                    if (o.server == server) store.putDetail(server, c.item)
                    else store.putMatchedDetail(o.server, c.item, key, item)
                    c.images?.let { store.putImages(o.server, c.item.id, it) }
                    store.remerge(key, selected, ServerItem(server, item))
                    ZLog.d(TAG, "work $key: verified ${o.server}:${c.item.id}, cast=${c.item.people.size}")
                }
                is Outcome.Failed -> ZLog.d(TAG, "work $key on ${o.server}: ${o.error.message}")
                is Outcome.Skipped -> Unit
            }
        }
    }

    private suspend fun prepareCopy(server: String, item: BaseItemDto): Copy {
        val ids = item.people.filter { it.type in LocalStore.CAST_TYPES }.mapNotNull { it.id }.distinct().take(MAX_CAST)
        val people = if (ids.isEmpty()) emptyList() else optional { source.peopleByIds(server, ids) }.orEmpty()
            .filter { it.id in ids && MetadataIdentity.validPerson(it) }.distinctBy { it.id }
        val byId = people.associateBy { it.id }
        val enriched = item.copy(people = item.people.map { p ->
            byId[p.id]?.let { p.copy(providerIds = it.providerIds) } ?: p
        })
        val needsImages = store.needsImages(server, item.id)
        return Copy(enriched, people, if (needsImages) optional { source.images(server, item.id) } else null)
    }

    suspend fun refreshPerson(server: String, id: String, onUpdate: suspend (PersonUpdate) -> Unit) = withContext(Dispatchers.IO) {
        val cachedKey = store.alias(server, id)?.personKey
        val loaded = optional { source.item(server, id) }?.takeIf { it.id == id && MetadataIdentity.validPerson(it) }
        val key = if (loaded != null) store.mergePerson(server, loaded) else cachedKey ?: return@withContext
        onUpdate(PersonUpdate(key, server, id))
        val primary = store.person(key) ?: return@withContext
        // Read the stored result directly. A StateFlow collector may still hold the previous key.
        val reference = store.personItem(primary)
        if (loaded != null) optional { source.worksOf(server, id) }?.let { saveWorks(server, id, it) }
        val aliases = store.aliases(key)
        val names = (listOf(primary.name) + aliases.map { it.name }).filter { it.isNotBlank() }.distinct().take(3)
        if (MetadataIdentity.query(reference).isEmpty()) return@withContext
        fanOut.run(servers().filter { it != server }, FanOut.SEARCH_TIMEOUT_MS) { s ->
            val known = aliases.firstOrNull { it.serverId == s }?.personId
            val person = findPerson(s, reference, known, names) ?: return@run null
            val images = optional { source.images(s, person.id) }
            Triple(person, images, optional { source.worksOf(s, person.id) })
        }.collect { o ->
            when (o) {
                is Outcome.Ok -> o.value?.let { (p, images, works) ->
                    store.mergePerson(o.server, p, key)
                    images?.let {
                        store.putImages(o.server, p.id, it)
                        store.mergePerson(o.server, p, key)
                    }
                    // The page remains on [key]; a different server's local key never replaces it.
                    works?.let { saveWorks(o.server, p.id, it) }
                    onUpdate(PersonUpdate(key, o.server, p.id))
                }
                is Outcome.Failed -> ZLog.d(TAG, "person $key on ${o.server}: ${o.error.message}")
                is Outcome.Skipped -> Unit
            }
        }
        if (loaded != null) optional { source.images(server, id) }?.let {
            store.putImages(server, id, it)
            store.mergePerson(server, loaded, key)
        }
    }

    private suspend fun findPerson(server: String, reference: BaseItemDto, known: String?, names: List<String>): BaseItemDto? {
        suspend fun full(candidate: BaseItemDto?, alreadyFull: Boolean = false): BaseItemDto? {
            if (candidate == null || !MetadataIdentity.validPerson(candidate) || !MetadataIdentity.same(reference, candidate)) return null
            if (alreadyFull) return candidate
            val detail = optional { source.item(server, candidate.id) }
            // A list can carry the biography even when a server forbids the single-item endpoint.
            if (detail == null) return candidate
            if (detail.id != candidate.id || !MetadataIdentity.validPerson(detail) || MetadataIdentity.conflicts(reference, detail) || MetadataIdentity.conflicts(candidate, detail)) return null
            return detail.copy(overview = detail.overview?.takeIf(String::isNotBlank) ?: candidate.overview,
                providerIds = MetadataIdentity.ids(candidate) + MetadataIdentity.ids(detail))
        }
        if (known != null) full(optional { source.item(server, known) }, alreadyFull = true)?.let { return it }
        val matches = optional { source.persons(server, MetadataIdentity.query(reference)) }.orEmpty()
            .filter { MetadataIdentity.validPerson(it) && MetadataIdentity.same(reference, it) }.distinctBy { it.id }
        if (matches.size > 1) return null
        full(matches.singleOrNull())?.let { return it }
        for (name in names) full(optional { source.person(server, name) })?.let { return it }
        ZLog.d(TAG, "person on $server: no verified external-ID match")
        return null
    }

    private suspend fun saveWorks(server: String, id: String, all: List<BaseItemDto>) {
        val valid = all.filter { MetadataIdentity.member(it, id) }
        store.putList("person:$server:$id", server, valid)
        ZLog.i(TAG, "person works $server:$id: accepted=${valid.size}, rejected=${all.size - valid.size}")
    }

    private suspend fun <T> optional(call: suspend () -> T): T? = try { call() }
    catch (e: CancellationException) { throw e }
    catch (e: Exception) { ZLog.d(TAG, "optional metadata: ${e.message}"); null }

    private companion object {
        const val TAG = "Metadata"
        const val MAX_CAST = 40
    }
}
