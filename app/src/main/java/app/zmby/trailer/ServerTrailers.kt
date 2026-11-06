package app.zmby.trailer

import app.zmby.aggregate.ResultStore
import app.zmby.data.ServerItem
import app.zmby.emby.BaseItemDto
import app.zmby.log.ZLog
import app.zmby.net.RequestCache
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Trailers stored with an item on its media server (trailer design: the last fallback). Asked when
 * the item says it has some (`LocalTrailerCount` > 0), never when it says none; when the answer
 * that brought the item does not say (list answers), only for the copy the caller allows. Which
 * trailer it is (its item ID, not the URL: that carries the login token) is kept in `repo_cache`
 * (`server_trailer`, per server login) for [FRESH_MS]; the playable URL is built each time.
 * Failures are not stored and not retried for [RETRY_MS].
 */
class ServerTrailers(
    private val list: suspend (server: String, itemId: String) -> List<BaseItemDto>,
    /** The trailer's stream URL and the User-Agent its server wants. */
    private val stream: (server: String, trailerId: String) -> Pair<String, String?>,
    /** The server's current login (user @ address); null for a deleted server. */
    private val login: (server: String) -> String?,
    private val store: ResultStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    @Serializable
    internal data class Stored(val trailerId: String? = null, val runtimeSec: Int? = null, val at: Long)

    private val memory = RequestCache<String, Stored>(64, { MEMORY_MS }, clock = clock)
    private val failedAt = ConcurrentHashMap<String, Long>()

    /**
     * [copy]'s first stored trailer, ready to play; null without one or when its server cannot answer.
     * [askUnknown]: ask even though the item does not say how many trailers it has.
     */
    suspend fun trailer(copy: ServerItem, askUnknown: Boolean = false): TrailerInfo? {
        val count = copy.item.localTrailerCount
        if (copy.server == "catalog" || count == 0 || count == null && !askUnknown) return null
        val who = login(copy.server) ?: return null
        val key = digest(who) + ":" + copy.item.id
        val found = try { memory.get("${copy.server}|$key") { withContext(Dispatchers.IO) { load(copy, key) } } }
            catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        val id = found?.trailerId ?: return null
        val (url, agent) = try { stream(copy.server, id) } catch (e: Exception) { return null }
        return TrailerInfo(videoId = id, url = url, height = 0, runtimeSec = found.runtimeSec, expiresAt = clock() + FRESH_MS,
            format = TrailerFormat.Server, source = copy.server, userAgent = agent)
    }

    private suspend fun load(copy: ServerItem, key: String): Stored {
        val failKey = "${copy.server}|$key"
        if (clock() - (failedAt[failKey] ?: Long.MIN_VALUE / 2) in 0 until RETRY_MS) throw IOException("retry later")
        try {
            store.read(KIND, key, copy.server, FRESH_MS)?.let { return json.decodeFromString(Stored.serializer(), it) }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { /* unreadable: ask again */ }
        return try {
            val trailer = list(copy.server, copy.item.id).firstOrNull { it.id.isNotBlank() }
            val stored = Stored(trailer?.id, trailer?.runTimeTicks?.let { (it / 10_000_000L).toInt() }, clock())
            try { store.write(KIND, key, copy.server, json.encodeToString(Stored.serializer(), stored)) }
            catch (e: CancellationException) { throw e } catch (e: Exception) { ZLog.w(TAG, "server trailer not stored: ${e.javaClass.simpleName}") }
            ZLog.d(TAG, "${copy.key}: ${if (stored.trailerId != null) "server trailer" else "no server trailer"}")
            stored
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failedAt[failKey] = clock()
            ZLog.i(TAG, "${copy.key}: server trailers unavailable (${e.javaClass.simpleName})")
            throw e
        }
    }

    private fun digest(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    companion object {
        const val KIND = "server_trailer"
        private const val TAG = "Trailer"
        /** The server's trailers change rarely; six hours, like its other resource answers. */
        const val FRESH_MS = 6 * 60 * 60_000L
        const val RETRY_MS = 10 * 60_000L
        private const val MEMORY_MS = 60 * 60_000L
    }
}

/** What a screen knows of a title for its trailer: its IMDb ID, and its copies on the servers (page's own first). */
data class TrailerQuery(val imdbId: String?, val copies: List<ServerItem> = emptyList())
