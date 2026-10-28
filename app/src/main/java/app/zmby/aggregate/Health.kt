package app.zmby.aggregate

/**
 * What fan-out requests report about each server, and what they ask before calling it
 * (aggregation spec §3.1): consecutive failures put a server in a cool-down during which it is
 * skipped and shown greyed out.
 */
interface HealthBook {
    fun isCooling(serverId: String): Boolean
    fun onSuccess(serverId: String)
    fun onFailure(serverId: String, error: Throwable, timedOut: Boolean)
}

/** In-memory failure counting and cool-down; [ServerRegistry][app.zmby.data.ServerRegistry] wraps it. */
class FailureTracker(
    private val clock: () -> Long = System::currentTimeMillis,
    private val failuresToCool: Int = FAILURES_TO_COOL,
    private val coolMs: Long = COOL_MS,
) {
    data class State(val failures: Int = 0, val coolingUntil: Long = 0, val lastError: String? = null, val timedOut: Boolean = false)

    private val states = HashMap<String, State>()

    @Synchronized fun state(id: String): State = states[id] ?: State()

    @Synchronized fun isCooling(id: String): Boolean = state(id).coolingUntil > clock()

    @Synchronized fun success(id: String) {
        states.remove(id)
    }

    /** Returns true when this failure starts a cool-down. */
    @Synchronized fun failure(id: String, message: String, timedOut: Boolean): Boolean {
        val previous = state(id)
        val old = if (previous.coolingUntil != 0L && previous.coolingUntil <= clock()) State() else previous
        val failures = old.failures + 1
        val cool = failures >= failuresToCool
        states[id] = State(failures, if (cool) clock() + coolMs else old.coolingUntil, message, timedOut)
        return cool
    }

    @Synchronized fun reset(id: String) {
        states.remove(id)
    }

    companion object {
        const val FAILURES_TO_COOL = 2
        const val COOL_MS = 30_000L
    }
}
