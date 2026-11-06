package app.zmby.trailer

import app.zmby.aggregate.ResultStore
import app.zmby.log.ZLog
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What one IMDb request tells about a title: its trailer and its user rating (trailer design; ratings design). */
@Serializable
data class ImdbTitle(
    val trailer: TrailerInfo? = null,
    /** IMDb's weighted user rating, 1–10. */
    val rating: Double? = null,
    val votes: Int? = null,
)

/**
 * A title's IMDb answer, local first (统一数据层): memory, then the `repo_cache` row
 * (`kind = imdb_title`), then one GraphQL request for trailer and rating together; concurrent asks
 * for the same title share it. An answer is kept for [TITLE_FRESH_MS], and a trailer within that
 * only while its signed link works or the whole video is in the playback cache (the link is then
 * never opened). Failures are not stored: the title is not asked again for [RETRY_MS] in this process.
 */
class ImdbTitleRepository(
    private val query: suspend (imdbId: String) -> String,
    private val store: ResultStore,
    private val scope: CoroutineScope,
    /** Whether the playback cache holds every byte of [TrailerInfo.cacheKey]. */
    private val complete: (cacheKey: String) -> Boolean = { false },
    /** Tallest picture worth fetching (the screen's height). */
    private val maxHeight: () -> Int = { 1080 },
    private val clock: () -> Long = System::currentTimeMillis,
    private val json: Json = Json { ignoreUnknownKeys = true },
    /** The debug mock server's links are http. */
    private val allowHttp: Boolean = false,
) {
    @Serializable
    internal data class Stored(val title: ImdbTitle = ImdbTitle(), val at: Long)

    private val memory = object : LinkedHashMap<String, Stored>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Stored>?) = size > MEMORY_ENTRIES
    }
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val inFlight = ConcurrentHashMap<String, Deferred<ImdbTitle?>>()

    /** The trailer to play for [imdbId], or null when IMDb has none or cannot be reached. */
    suspend fun trailer(imdbId: String): TrailerInfo? = title(imdbId)?.trailer

    /** Trailer and rating of [imdbId] (any form [ImdbTrailers.normalize] reads); null when IMDb cannot be reached. */
    suspend fun title(imdbId: String): ImdbTitle? {
        val id = ImdbTrailers.normalize(imdbId) ?: return null
        synchronized(memory) { memory[id] }?.let { usable(it) }?.let { return it.title }
        // Lazy, so only the ask that wins the map entry starts a request; the others wait for it.
        val candidate = scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) { load(id) }
        val shared = inFlight.putIfAbsent(id, candidate) ?: candidate.also { started ->
            started.invokeOnCompletion { inFlight.remove(id, started) }
            started.start()
        }
        if (shared !== candidate) candidate.cancel()
        return shared.await()
    }

    /** A stored answer still good to act on: recent, and its trailer (if any) still playable. */
    private fun usable(s: Stored): Stored? {
        val now = clock()
        if (now - s.at !in 0 until TITLE_FRESH_MS) return null
        val info = s.title.trailer ?: return s
        return s.takeIf { info.expiresAt - LINK_MARGIN_MS > now || complete(info.cacheKey) }
    }

    private suspend fun load(id: String): ImdbTitle? {
        val stored = try {
            store.read(KIND, id, SOURCE, TITLE_FRESH_MS)?.let { json.decodeFromString(Stored.serializer(), it) }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            ZLog.w(TAG, "stored IMDb title unreadable: ${e.javaClass.simpleName}"); null
        }
        stored?.let(::usable)?.let { remember(id, it); return it.title }
        if (clock() - (failedAt[id] ?: Long.MIN_VALUE / 2) in 0 until RETRY_MS) return null
        return try {
            val body = query(id)
            val title = ImdbTitle(ImdbTrailers.parse(json, body, maxHeight(), clock(), allowHttp)).let { t ->
                ImdbTrailers.rating(json, body)?.let { (rating, votes) -> t.copy(rating = rating, votes = votes) } ?: t
            }
            val fresh = Stored(title, clock())
            remember(id, fresh)
            failedAt.remove(id)
            try { store.write(KIND, id, SOURCE, json.encodeToString(Stored.serializer(), fresh)) }
            catch (e: CancellationException) { throw e } catch (e: Exception) { ZLog.w(TAG, "IMDb title not stored: ${e.javaClass.simpleName}") }
            ZLog.d(TAG, "$id: ${title.trailer?.let { "trailer ${it.videoId} ${it.height}p" } ?: "no trailer"}, rating ${title.rating ?: "none"}")
            title
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failedAt[id] = clock()
            // Never the link or the answer: only what went wrong.
            ZLog.i(TAG, "$id: IMDb unavailable (${e.javaClass.simpleName}${e.message?.takeIf { it.startsWith("IMDb") }?.let { ": $it" }.orEmpty()})")
            null
        }
    }

    private fun remember(id: String, s: Stored) = synchronized(memory) { memory[id] = s }

    companion object {
        const val KIND = "imdb_title"
        /** The `server_id` column of the rows: not a media server. */
        const val SOURCE = "imdb"
        private const val TAG = "Imdb"
        /**
         * An answer is asked again after three days: ratings move slowly, and a title without a trailer
         * may get one before release.
         */
        const val TITLE_FRESH_MS = 3 * 24 * 60 * 60_000L
        /** A link this close to its expiry is not handed to the player (a trailer runs a few minutes). */
        const val LINK_MARGIN_MS = 15 * 60_000L
        const val RETRY_MS = 10 * 60_000L
        private const val MEMORY_ENTRIES = 64
    }
}
