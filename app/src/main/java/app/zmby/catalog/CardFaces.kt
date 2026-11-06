package app.zmby.catalog

import app.zmby.aggregate.MetadataIdentity
import app.zmby.data.ServerItem
import app.zmby.data.SourceRules
import app.zmby.emby.BaseItemDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** A work's canonical card fields: its title and (TMDB only) its poster. */
@Serializable
data class CardFace(val title: String? = null, val poster: String? = null)

/**
 * 列表卡片按影片信息来源取值 (统一数据层 ④). Remembers the title and poster of each work this device has
 * already loaded from TMDB or Bangumi (a detail or series page, the hero), keyed by catalogue ID, so list
 * cards can follow 影片信息来源 without asking the network. A card whose work was never loaded keeps its
 * server's own fields. Kept in `catalog_cache` as one row per text and poster language (at most
 * [LIMIT] works, least recently used dropped first); read once in the background, written a moment
 * after changes.
 */
class CardFaces(
    private val storage: CatalogStorage,
    private val json: Json,
    private val scope: CoroutineScope,
    private val languageKey: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var loadedKey: String? = null
    private var faces = newMap()
    private var saving: Job? = null

    /** 影片信息来源 in order; set by the app whenever the order, a switch or the server list changes. */
    @Volatile var order: List<SourceRules.MetaSource> = emptyList()
        set(value) { if (field != value) { field = value; bump() } }

    private val _version = MutableStateFlow(0)
    /** Changes whenever a face or [order] changes: card models recompute on it. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private fun bump() = _version.update { it + 1 }

    private fun rowKey(language: String) = "cardfaces:v1:$language"

    /** Reads the faces for the current languages (I/O; call off the main thread). Nothing to do when already loaded. */
    suspend fun load() {
        val key = rowKey(languageKey())
        if (synchronized(lock) { loadedKey == key }) return
        val stored = try {
            storage.get(key)?.let { json.decodeFromString(SERIALIZER, it.json) }.orEmpty()
        } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyMap() }
        synchronized(lock) {
            if (loadedKey == key) return
            loadedKey = key
            faces = newMap().apply { putAll(stored) }
        }
        bump()
    }

    /** A work loaded (or read from the cache) in the current languages. */
    fun remember(work: CatalogWork) {
        val face = CardFace(work.title.takeIf { it.isNotBlank() }, work.poster)
        if (face.title == null && face.poster == null) return
        val changed = synchronized(lock) {
            if (loadedKey != rowKey(languageKey())) return
            faces.put(work.ref.key, face) != face
        }
        if (!changed) return
        bump()
        saveSoon()
    }

    private fun saveSoon() {
        synchronized(lock) {
            saving?.cancel()
            saving = scope.launch(Dispatchers.IO) {
                delay(SAVE_DELAY_MS)
                val (key, snapshot) = synchronized(lock) { (loadedKey ?: return@launch) to LinkedHashMap(faces) }
                try { storage.put(CatalogCache(key, json.encodeToString(SERIALIZER, snapshot), clock())) }
                catch (e: CancellationException) { throw e } catch (_: Exception) { }
            }
        }
    }

    private fun get(key: String): CardFace? = synchronized(lock) { faces[key] }

    /** The card fields of [ref] by 影片信息来源, or null when its own server's fields stay. */
    fun face(ref: ServerItem): CardFace? = face(ref.server, ref.item)

    fun face(server: String, item: BaseItemDto): CardFace? {
        val kind = when (item.type) { "Movie" -> "movie"; "Series" -> "tv"; else -> return null }
        val ids = MetadataIdentity.ids(item)
        if (ids["tmdb"] == null && ids["bangumi"] == null) return null
        return pick(order, server) { source -> ids[source]?.let { id -> get("$source:$kind:$id") } }
    }

    /** By a favourite's or 待看 entry's work key (`movie:tmdb:…`, `series:tmdb:…`); other keys keep their own fields. */
    fun faceOfWork(server: String, workKey: String): CardFace? {
        val parts = workKey.split(':')
        if (parts.size != 3 || parts[1] != "tmdb") return null
        val kind = when (parts[0]) { "movie" -> "movie"; "series" -> "tv"; else -> return null }
        return pick(order, server) { source -> if (source == "tmdb") get("tmdb:$kind:${parts[2]}") else null }
    }

    companion object {
        const val LIMIT = 2000
        const val SAVE_DELAY_MS = 3_000L
        private val SERIALIZER = MapSerializer(String.serializer(), CardFace.serializer())

        private fun newMap() = object : LinkedHashMap<String, CardFace>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CardFace>?) = size > LIMIT
        }

        /**
         * Field by field, the first catalogue switched on before the card's own server that knows the
         * work. Other servers' copies are not known to a list card, so they are passed over; the card's own
         * server ends the search (its fields are the card's defaults). Null when no catalogue answers.
         */
        fun pick(order: List<SourceRules.MetaSource>, server: String, face: (String) -> CardFace?): CardFace? {
            var title: String? = null
            var poster: String? = null
            for (m in order) {
                if (!m.enabled) continue
                if (!m.catalog) { if (m.key == server) break else continue }
                val f = face(m.key) ?: continue
                if (title == null) title = f.title?.takeIf { it.isNotBlank() }
                if (poster == null) poster = f.poster
                if (title != null && poster != null) break
            }
            return if (title == null && poster == null) null else CardFace(title, poster)
        }
    }
}
