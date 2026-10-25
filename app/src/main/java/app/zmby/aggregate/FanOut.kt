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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeoutException

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
) {
    private val permits = Semaphore(maxConcurrent)
    private class Shared(val task: Deferred<Outcome<Any?>>, var users: Int = 0)
    private val inFlight = HashMap<String, Shared>()

    fun <T> run(servers: List<String>, timeoutMs: Long, share: String? = null, call: suspend (String) -> T): Flow<Outcome<T>> = channelFlow {
        for (server in servers) launch { send(one(server, timeoutMs, share, call)) }
    }

    suspend fun <T> all(servers: List<String>, timeoutMs: Long, share: String? = null, call: suspend (String) -> T): List<Outcome<T>> =
        run(servers, timeoutMs, share, call).toList()

    private suspend fun <T> one(server: String, timeoutMs: Long, share: String?, call: suspend (String) -> T): Outcome<T> {
        if (health.isCooling(server)) return Outcome.Skipped(server)
        if (share == null) return execute(server, timeoutMs, call)
        val key = "$share|$server|$timeoutMs"
        val shared = synchronized(inFlight) {
            inFlight.getOrPut(key) {
                Shared(scope.async(start = CoroutineStart.LAZY) { execute(server, timeoutMs, call) })
            }.also { it.users++ }
        }
        try {
            shared.task.start()
            @Suppress("UNCHECKED_CAST")
            return shared.task.await() as Outcome<T>
        } finally {
            synchronized(inFlight) {
                if (--shared.users == 0) {
                    inFlight.remove(key)
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
            withTimeoutOrNull(timeoutMs) { Outcome.Ok(server, call(server), clock() - started) }
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
    }
}
