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

/** Bounds active operations. Queue time and cancelled consumers never count as server failures. */
class FanOut(
    private val health: HealthBook,
    maxConcurrent: Int = MAX_CONCURRENT,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val cacheContext: (String) -> Any? = { null },
) {
    private val permits = Semaphore(maxConcurrent)
    private data class RequestKey(val query: Any, val server: String, val timeoutMs: Long, val context: Any?)
    private class Shared(val task: Deferred<Outcome<Any?>>, var users: Int = 0, var valid: Boolean = true)
    private data class Cached(val result: Outcome.Ok<Any?>, val at: Long)
    private val inFlight = HashMap<RequestKey, Shared>()
    // Only opted-in resource lookups are cached; playback preparation always requests fresh URLs.
    private val cache = LinkedHashMap<RequestKey, Cached>(16, 0.75f, true)

    fun <T> run(servers: List<String>, timeoutMs: Long, share: Any? = null, cacheMs: Long = 0,
        context: (String) -> Any? = { null }, call: suspend (String) -> T): Flow<Outcome<T>> = channelFlow {
        for (server in servers.distinct()) launch { send(one(server, timeoutMs, share, cacheMs, context(server), call)) }
    }

    suspend fun <T> all(servers: List<String>, timeoutMs: Long, share: String? = null, call: suspend (String) -> T): List<Outcome<T>> =
        run(servers, timeoutMs, share, call = call).toList()

    /** A manual refresh also prevents an older in-flight answer from repopulating this cache. */
    fun invalidate(share: Any, servers: List<String>) = synchronized(inFlight) {
        cache.keys.removeAll { it.query == share && it.server in servers }
        val keys = inFlight.keys.filter { it.query == share && it.server in servers }
        keys.forEach { inFlight.remove(it)?.valid = false }
    }

    private suspend fun <T> one(server: String, timeoutMs: Long, share: Any?, cacheMs: Long, context: Any?, call: suspend (String) -> T): Outcome<T> {
        if (share == null) return if (health.isCooling(server)) Outcome.Skipped(server) else execute(server, timeoutMs, call)
        val key = RequestKey(share, server, timeoutMs, listOf(cacheContext(server), context))
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
                Shared(scope.async(start = CoroutineStart.LAZY) { execute(server, timeoutMs, call) })
            }.also { it.users++ }
        }
        try {
            shared.task.start()
            @Suppress("UNCHECKED_CAST")
            val result = shared.task.await() as Outcome<T>
            if (cacheMs > 0 && result is Outcome.Ok) synchronized(inFlight) {
                if (shared.valid) {
                    @Suppress("UNCHECKED_CAST")
                    cache[key] = Cached(result as Outcome.Ok<Any?>, clock())
                    while (cache.size > MAX_CACHED_REQUESTS) cache.remove(cache.keys.first())
                }
            }
            return result
        } finally {
            synchronized(inFlight) {
                if (--shared.users == 0) {
                    if (inFlight[key] === shared) inFlight.remove(key)
                    // A changed query/closed screen must release the slot and cancel its HTTP call.
                    shared.task.cancel()
                }
            }
        }
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
        private const val MAX_CACHED_REQUESTS = 64
        private const val RETRY_DELAY_MS = 350L

        fun isTransient(error: Throwable): Boolean = when (error) {
            is EmbyException -> error.code == null || error.code in setOf(408, 500, 502, 503, 504)
            is app.zmby.emby.NotEmbyException -> false
            is IOException, is TimeoutException -> true
            else -> false
        }
    }
}
