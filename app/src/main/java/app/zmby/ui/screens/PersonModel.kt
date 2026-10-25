package app.zmby.ui.screens

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zmby.ZmbyApp
import app.zmby.aggregate.FanOut
import app.zmby.aggregate.Hit
import app.zmby.aggregate.Merger
import app.zmby.aggregate.Outcome
import app.zmby.data.ServerItem
import app.zmby.data.SourceFeature
import app.zmby.log.ZLog
import app.zmby.store.CastRules
import app.zmby.store.Person
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One work on the person page: merged across servers like search, with the person's part in it. */
@Immutable
data class PersonWork(val lead: ServerItem, val versions: Int, val year: Int?, val part: String?, val kind: String?)

@Immutable
data class PersonWorks(val works: List<PersonWork>, val kinds: List<String>, val loaded: Boolean)

/**
 * 人物页 (ui-spec §2.9), local first: the person and the works already in the store show at once;
 * [refresh] fetches the person (TMDB id → one person across servers) and, on each metadata-source
 * server, finds the person by name and lists their works with that server's own person id. Servers
 * that fail stay silent; results join without clearing what is shown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PersonViewModel(private val app: ZmbyApp, initialKey: String, private val server: String, private val personId: String) : ViewModel() {
    private val key = MutableStateFlow(initialKey)
    /** Server → that server's own id of this person. */
    private val ids = MutableStateFlow(mapOf(server to personId))

    val person: StateFlow<Person?> = key.flatMapLatest { app.store.personFlow(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val works: StateFlow<PersonWorks> = ids.flatMapLatest { map ->
        val order = app.registry.all.associate { it.id to it.order }
        app.store.listsFlow(map.map { (s, id) -> listKey(s, id) }).map { lists ->
            val hits = map.flatMap { (s, id) ->
                lists[listKey(s, id)]?.items.orEmpty().mapIndexed { i, r -> Hit(r, order[s] ?: Int.MAX_VALUE, i) }
            }
            val merged = Merger.merge(hits)
            val cards = merged.works.map { w -> card(w.lead.ref, w.servers, map) } + merged.unmerged.map { card(it.ref, 1, map) }
            // Newest first; works without a year last.
            val sorted = cards.sortedWith(compareByDescending<PersonWork> { it.year ?: Int.MIN_VALUE })
            PersonWorks(sorted, cards.mapNotNull { it.kind }, lists.values.any { it.fetchedAt > 0 })
        }.flowOn(Dispatchers.Default)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PersonWorks(emptyList(), emptyList(), false))

    private var refreshing: Job? = null

    init {
        viewModelScope.launch {
            app.store.touchPerson(initialKey)
            // Servers already known to have this person: their works show from the store at once.
            val known = app.store.aliases(initialKey).associate { it.serverId to it.personId }
            if (known.isNotEmpty()) ids.update { known + it }
        }
        refresh()
    }

    fun refresh() {
        if (refreshing?.isActive == true) return
        refreshing = viewModelScope.launch {
            try {
                val item = app.library.item(server, personId)
                key.value = app.store.mergePerson(server, item)
                loadWorks(server, personId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ZLog.w(TAG, "person $personId on ${app.registry.get(server)?.name}: ${e.message}")
            }
            val name = person.value?.name ?: return@launch
            val tmdb = person.value?.tmdb
            // Every name the store knows for this person (servers name people in their own language).
            val names = (listOf(name) + app.store.aliases(key.value).map { it.name }).filter { it.isNotBlank() }.distinct()
            val others = app.sources.servers(SourceFeature.Metadata).filter { it != server }
            app.fanOut.run(others, FanOut.LIST_TIMEOUT_MS) { s -> findAndLoad(s, names, tmdb) }.collect { o ->
                if (o is Outcome.Failed) ZLog.d(TAG, "works on ${app.registry.get(o.server)?.name}: ${o.error.message}")
            }
        }
    }

    /** On another server: its own id of the person (by name; a different TMDB id is someone else). */
    private suspend fun findAndLoad(s: String, names: List<String>, tmdb: String?) {
        val known = app.store.aliases(key.value).firstOrNull { it.serverId == s }?.personId
        val id = known ?: run {
            val found = names.firstNotNullOfOrNull { n -> runCatching { app.library.person(s, n) }.getOrNull() } ?: return
            val theirs = found.providerIds.entries.firstOrNull { it.key.equals("Tmdb", true) }?.value?.trim()?.takeIf(String::isNotEmpty)
            if (tmdb != null && theirs != null && theirs != tmdb) return
            key.value = app.store.mergePerson(s, found)
            found.id
        }
        loadWorks(s, id)
    }

    private suspend fun loadWorks(s: String, id: String) {
        val items = app.library.worksOf(s, id)
        app.store.putList(listKey(s, id), s, items)
        ids.update { it + (s to id) }
    }

    /** The person's part in [ref]: 「角色」 for actors, 「导演」「编剧」 for crew. */
    private fun card(ref: ServerItem, versions: Int, ids: Map<String, String>): PersonWork {
        val pid = ids[ref.server]
        val rows = ref.item.people.filter { it.id == pid }
        val actor = rows.firstOrNull { it.type == "Actor" || it.type == "GuestStar" }
        val kind = actor?.type ?: rows.firstOrNull()?.type
        val part = actor?.role?.takeIf(String::isNotBlank)
            ?: rows.mapNotNull { CastRules.KIND_LABEL[it.type] }.distinct().joinToString(" · ").ifEmpty { null }
        return PersonWork(ref, versions, ref.item.productionYear, part, kind)
    }

    private fun listKey(s: String, id: String) = "person:$s:$id"

    private companion object { const val TAG = "Person" }
}
