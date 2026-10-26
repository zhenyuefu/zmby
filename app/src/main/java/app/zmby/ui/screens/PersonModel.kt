package app.zmby.ui.screens

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zmby.ZmbyApp
import app.zmby.aggregate.MetadataIdentity
import app.zmby.aggregate.providerId
import app.zmby.data.ServerItem
import app.zmby.store.CastRules
import app.zmby.store.ImageRef
import app.zmby.store.Person
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@Immutable
data class PersonWork(val lead: ServerItem, val versions: Int, val year: Int?, val part: String?, val kind: String?)
@Immutable
data class PersonWorks(val works: List<PersonWork>, val kinds: List<String>, val loaded: Boolean)

/** Server-only fallback is transient and never searches other servers for a person's identity. */
class PersonViewModel(private val app: ZmbyApp, private val initialKey: String, private val server: String, private val personId: String) : ViewModel() {
    private val _person = MutableStateFlow<Person?>(null)
    val person: StateFlow<Person?> = _person.asStateFlow()
    private val _works = MutableStateFlow(PersonWorks(emptyList(), emptyList(), false))
    val works: StateFlow<PersonWorks> = _works.asStateFlow()
    private var refreshing: Job? = null
    init { refresh() }
    fun refresh() {
        if (refreshing?.isActive == true) return
        refreshing = viewModelScope.launch(Dispatchers.IO) {
            try {
                val p = app.library.item(server, personId)
                if (!MetadataIdentity.validPerson(p) || p.id != personId) return@launch
                _person.value = Person(initialKey, p.name.orEmpty(), overview = p.overview, birthDate = p.premiereDate,
                    birthPlace = p.productionLocations.firstOrNull(), image = p.imageTags["Primary"]?.let { ImageRef(server, personId, it) }, tmdb = p.providerId("tmdb"), imdb = p.providerId("imdb"))
                // With a provider ID the screen switches directly to the provider's biography and credits.
                if (p.providerId("tmdb") != null && app.catalog.configured) return@launch
                val items = app.library.worksOf(server, personId).filter { MetadataIdentity.member(it, personId) }
                val cards = items.distinctBy { it.id }.map { item ->
                    val rows = item.people.filter { it.id == personId }
                    val actor = rows.firstOrNull { it.type in setOf("Actor", "GuestStar") }
                    PersonWork(ServerItem(server, item), 1, item.productionYear,
                        actor?.role ?: rows.mapNotNull { CastRules.KIND_LABEL[it.type] }.distinct().joinToString(" · "), actor?.type ?: rows.firstOrNull()?.type)
                }.sortedByDescending { it.year ?: Int.MIN_VALUE }
                _works.value = PersonWorks(cards, cards.mapNotNull { it.kind }.distinct(), true)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _works.value = _works.value.copy(loaded = true) }
        }
    }
}
