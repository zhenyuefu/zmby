package app.zmby.aggregate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeoutException
import java.io.IOException
import app.zmby.emby.EmbyException

/** A failed request is different from an empty answer or a server that was not asked. */
sealed interface Outcome<out T> {
    val server: String
    data class Ok<T>(override val server: String, val value: T, val ms: Long) : Outcome<T>
    data class Failed(override val server: String, val error: Throwable, val timedOut: Boolean) : Outcome<Nothing>
    data class Skipped(override val server: String) : Outcome<Nothing>
}

/**
 * Where [FanOut] keeps answers across restarts (统一数据层 ①: the persistent half of the request
 * cache; [app.zmby.store.RoomResultStore] in the app). Keys already include the request identity and
 * the server login; only successful answers are written.
 */
interface ResultStore {
    suspend fun read(kind: String, key: String, server: String, maxAgeMs: Long): String?
    suspend fun write(kind: String, key: String, server: String, json: String)
    suspend fun drop(kind: String, key: String, servers: List<String>)
    suspend fun dropKinds(kinds: List<String>, servers: List<String>)
}

/** Opts a shared request into the [ResultStore]: its kind (a table partition) and how to encode the answer. */
class Persist<T>(val kind: String, val serializer: kotlinx.serialization.KSerializer<T>)

/** Bounds active operations. Queue time and cancelled consumers never count as server failures. */
class FanOut(
    private val health: HealthBook,
    maxConcurrent: Int = MAX_CONCURRENT,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val cacheContext: (String) -> Any? = { null },
    /** Answers kept across restarts; null keeps everything in memory only (tests). */
    private val results: ResultStore? = null,
    /** The server login a stored answer belongs to (user and address); a changed login asks again. */
    private val login: (String) -> String? = { null },
    private val json: kotlinx.serialization.json.Json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true },
) {
    /** The stored key of a request: its identity, the per-server context and the login, hashed. */
    private fun storedKey(share: Any, context: Any?, server: String): String? {
        val who = login(server) ?: return null
        val text = "$share|$context|$who"
        val digest = java.security.MessageDigest.getInstance("SHA-1").digest(text.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private suspend fun <T> readStored(persist: Persist<T>, share: Any, context: Any?, server: String, maxAgeMs: Long): T? {
        val store = results ?: return null
        val key = storedKey(share, context, server) ?: return null
        val text = try { store.read(persist.kind, key, server, maxAgeMs) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } ?: return null
        return runCatching { json.decodeFromString(persist.serializer, text) }
            .onFailure { app.zmby.log.ZLog.w("FanOut", "stored ${persist.kind} answer unreadable: ${it.message}") }.getOrNull()
    }

    private fun <T> writeStored(persist: Persist<T>, share: Any, context: Any?, server: String, value: T) {
        val store = results ?: return
        val key = storedKey(share, context, server) ?: return
        val text = runCatching { json.encodeToString(persist.serializer, value) }
            .onFailure { app.zmby.log.ZLog.w("FanOut", "${persist.kind} answer not stored: ${it.message}") }.getOrNull() ?: return
        scope.launch { runCatching { store.write(persist.kind, key, server, text) }.onFailure { app.zmby.log.ZLog.w("FanOut", "${persist.kind} store write failed: ${it.message}") } }
    }

    private val permits = Semaphore(maxConcurrent)
    private data class RequestKey(val query: Any, val server: String, val timeoutMs: Long, val context: Any?)
    private class Shared(var users: Int = 0, var valid: Boolean = true) { lateinit var task: Deferred<Outcome<Any?>> }
    private data class Cached(val result: Outcome.Ok<Any?>, val at: Long)
    private val inFlight = HashMap<RequestKey, Shared>()
    // Only opted-in resource lookups are cached; playback preparation always requests fresh URLs.
    private val cache = LinkedHashMap<RequestKey, Cached>(16, 0.75f, true)

    private class Step(val value: Any?, val ttl: Long)
    private val steps = app.zmby.net.RequestCache<List<Any?>, Step>(STEP_CAPACITY, { it.ttl }, clock)

    /**
     * One step inside a server's operation (a series match, its season list, an episode page),
     * shared by every operation that needs it and kept for [cacheMs] per server login (performance
     * plan RN ⑥): moving along an episode row no longer finds the series and its seasons again for
     * each episode, and the series page and the version lookup read the same season directory.
     * Failures are not kept; health is counted by the enclosing [run].
     */
    suspend fun <T> step(server: String, share: Any, cacheMs: Long, persist: Persist<T>? = null, call: suspend () -> T): T {
        @Suppress("UNCHECKED_CAST")
        return steps.get(listOf(share, server, cacheContext(server))) {
            // Memory first; then what an earlier run stored; then the server.
            val stored = persist?.let { readStored(it, share, null, server, cacheMs) }
            Step(stored ?: call().also { value -> persist?.let { writeStored(it, share, null, server, value) } }, cacheMs)
        }.value as T
    }

    /** Drops the [step] answers of [servers] (a manual refresh), also the stored ones. */
    fun invalidateSteps(servers: List<String>) {
        steps.invalidateWhere { it[1] in servers }
        results?.let { store -> scope.launch { runCatching { store.dropKinds(STEP_KINDS, servers) } } }
    }

    /** A series' seasons on [server], as a shared [step]. */
    suspend fun seasons(server: String, seriesId: String, load: suspend () -> List<app.zmby.emby.BaseItemDto>): List<app.zmby.emby.BaseItemDto> =
        step(server, listOf("seasons", seriesId), RESOURCE_CACHE_MS, Persist(KIND_SEASONS, kotlinx.serialization.builtins.ListSerializer(app.zmby.emby.BaseItemDto.serializer())), load)

    /** One page of a season's episodes on [server], as a shared [step]. */
    suspend fun episodePage(server: String, seriesId: String, seasonId: String, start: Int, limit: Int, load: suspend () -> app.zmby.emby.ItemsResult): app.zmby.emby.ItemsResult =
        step(server, listOf("episode-page", seriesId, seasonId, start, limit), RESOURCE_CACHE_MS, Persist(KIND_EPISODES, app.zmby.emby.ItemsResult.serializer()), load)

    fun <T> run(servers: List<String>, timeoutMs: Long, share: Any? = null, cacheMs: Long = 0,
        context: (String) -> Any? = { null }, persist: Persist<T>? = null, call: suspend (String) -> T): Flow<Outcome<T>> = channelFlow {
        for (server in servers.distinct()) launch { send(one(server, timeoutMs, share, cacheMs, context(server), persist, call)) }
    }

    suspend fun <T> all(servers: List<String>, timeoutMs: Long, share: String? = null, call: suspend (String) -> T): List<Outcome<T>> =
        run(servers, timeoutMs, share, call = call).toList()

    /** A manual refresh also prevents an older in-flight answer from repopulating this cache. */
    fun invalidate(share: Any, servers: List<String>, persist: Persist<*>? = null, context: (String) -> Any? = { null }) {
        synchronized(inFlight) {
            cache.keys.removeAll { it.query == share && it.server in servers }
            val keys = inFlight.keys.filter { it.query == share && it.server in servers }
            keys.forEach { inFlight.remove(it)?.valid = false }
        }
        val store = results ?: return
        if (persist == null) return
        scope.launch {
            for (server in servers) storedKey(share, context(server), server)?.let { runCatching { store.drop(persist.kind, it, listOf(server)) } }
        }
    }

    /** Memory-only read for the first UI frame; uses exactly the same scope and expiry as run. */
    fun <T> cached(servers: List<String>, timeoutMs: Long, share: Any, cacheMs: Long,
        context: (String) -> Any? = { null }): Map<String, Outcome.Ok<T>> = synchronized(inFlight) {
        servers.distinct().mapNotNull { server ->
            val key = RequestKey(share, server, timeoutMs, listOf(cacheContext(server), context(server)))
            val saved = cache[key] ?: return@mapNotNull null
            if (clock() - saved.at !in 0 until cacheMs) { cache.remove(key); return@mapNotNull null }
            @Suppress("UNCHECKED_CAST")
            server to (saved.result as Outcome.Ok<T>)
        }.toMap()
    }

    private suspend fun <T> one(server: String, timeoutMs: Long, share: Any?, cacheMs: Long, context: Any?, persist: Persist<T>?, call: suspend (String) -> T): Outcome<T> {
        if (share == null) return if (health.isCooling(server)) Outcome.Skipped(server) else execute(server, timeoutMs, call)
        val key = RequestKey(share, server, timeoutMs, listOf(cacheContext(server), context))
        // Not in memory: an answer an earlier run stored within the same freshness (统一数据层 ③).
        if (persist != null && cacheMs > 0) {
            val inMemory = synchronized(inFlight) { cache[key]?.takeIf { clock() - it.at in 0 until cacheMs } != null || key in inFlight }
            if (!inMemory) readStored(persist, share, context, server, cacheMs)?.let { value ->
                val ok = Outcome.Ok(server, value, 0)
                synchronized(inFlight) {
                    @Suppress("UNCHECKED_CAST")
                    cache[key] = Cached(ok as Outcome.Ok<Any?>, clock())
                    while (cache.size > MAX_CACHED_REQUESTS) cache.remove(cache.keys.first())
                }
                return ok
            }
        }
        val shared = synchronized(inFlight) {
            if (cacheMs > 0) {
                val saved = cache[key]
                if (saved != null && clock() - saved.at in 0 until cacheMs) {
                    @Suppress("UNCHECKED_CAST")
                    return saved.result as Outcome<T>
                }
                cache.remove(key)
            }
            if (health.isCooling(server)) return Outcome.Skipped(server)
            inFlight.getOrPut(key) {
                Shared().also { created ->
                    created.task = scope.async(start = CoroutineStart.LAZY) {
                        val result = execute(server, timeoutMs, call)
                        // Cached by the request itself, so an answer that arrives after its screen
                        // re-asked (see the grace below) is kept as well.
                        if (cacheMs > 0 && result is Outcome.Ok) synchronized(inFlight) {
                            if (created.valid) {
                                @Suppress("UNCHECKED_CAST")
                                cache[key] = Cached(result as Outcome.Ok<Any?>, clock())
                                while (cache.size > MAX_CACHED_REQUESTS) cache.remove(cache.keys.first())
                                if (persist != null) writeStored(persist, share, context, server, result.value)
                            }
                        }
                        result
                    }
                }
            }.also { it.users++ }
        }
        try {
            shared.task.start()
            @Suppress("UNCHECKED_CAST")
            return shared.task.await() as Outcome<T>
        } finally {
            synchronized(inFlight) {
                if (--shared.users == 0) {
                    if (cacheMs > 0 && shared.valid && !shared.task.isCompleted) {
                        // A cached lookup whose screen restarts its query (new keys while answers come
                        // in) is joined again instead of being asked twice (performance plan RN).
                        scope.launch {
                            delay(ORPHAN_GRACE_MS)
                            synchronized(inFlight) { if (shared.users == 0) release(key, shared) }
                        }
                    } else {
                        release(key, shared)
                    }
                }
            }
        }
    }

    /** A changed query or a closed screen releases the slot and cancels the HTTP call. Holds [inFlight]. */
    private fun release(key: RequestKey, shared: Shared) {
        if (inFlight[key] === shared) inFlight.remove(key)
        shared.task.cancel()
    }

    private suspend fun <T> execute(server: String, timeoutMs: Long, call: suspend (String) -> T): Outcome<T> = permits.withPermit {
        if (health.isCooling(server)) return@withPermit Outcome.Skipped(server)
        val started = clock()
        val outcome = try {
            // Only this deadline is converted to failure. Parent cancellation and nested deadlines
            // propagate as cancellation, rather than poisoning the server's shared health state.
            withTimeoutOrNull(timeoutMs) {
                val value = try { call(server) }
                catch (e: CancellationException) { throw e }
                catch (e: IOException) {
                    // Authentication, rate limiting and other permanent HTTP errors need user action.
                    if (!isTransient(e)) throw e
                    delay(RETRY_DELAY_MS)
                    call(server)
                }
                Outcome.Ok(server, value, clock() - started)
            }
                ?: Outcome.Failed(server, TimeoutException("Request timed out after ${timeoutMs}ms"), timedOut = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Failed(server, e, timedOut = false)
        }
        currentCoroutineContext().ensureActive()
        // A shared operation updates health once, regardless of its number of consumers.
        when (outcome) {
            is Outcome.Ok -> health.onSuccess(server)
            is Outcome.Failed -> health.onFailure(server, outcome.error, outcome.timedOut)
            is Outcome.Skipped -> Unit
        }
        outcome
    }

    companion object {
        const val MAX_CONCURRENT = 8
        const val LIST_TIMEOUT_MS = 8_000L
        const val SEARCH_TIMEOUT_MS = 20_000L
        const val RESOURCE_CACHE_MS = 6 * 60 * 60_000L
        /** Step answers kept at once (series matches, season lists, episode pages). */
        const val STEP_CAPACITY = 512
        private const val MAX_CACHED_REQUESTS = 64
        /** How long a cached lookup left by its last consumer keeps running for one that asks again. */
        const val ORPHAN_GRACE_MS = 1_500L
        private const val RETRY_DELAY_MS = 350L

        // Kinds in the stored answers (repo_cache.kind).
        const val KIND_VERSIONS = "versions"
        const val KIND_SERIES_MATCH = "series-match"
        const val KIND_SEASONS = "seasons"
        const val KIND_EPISODES = "episode-page"
        const val KIND_SIMILAR = "similar"
        /** The step kinds a manual refresh drops with [invalidateSteps]. */
        val STEP_KINDS = listOf(KIND_SERIES_MATCH, KIND_SEASONS, KIND_EPISODES)

        fun isTransient(error: Throwable): Boolean = when (error) {
            is EmbyException -> error.code == null || error.code in setOf(408, 500, 502, 503, 504)
            is app.zmby.emby.NotEmbyException -> false
            is IOException, is TimeoutException -> true
            else -> false
        }
    }
}
