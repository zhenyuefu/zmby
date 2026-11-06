package app.zmby.catalog

/**
 * Whether the TMDB catalogue answered lately (2026-11-05 user request): after it failed or was too slow
 * to list a season, series pages use the servers' own season directories for [PAUSE_MS] instead of
 * waiting for it again. Process memory only; an explicit retry on a page tries the catalogue anyway.
 */
class CatalogReachability(private val clock: () -> Long = System::currentTimeMillis) {
    @Volatile private var failedAt = 0L

    fun reachable(): Boolean = failedAt == 0L || clock() - failedAt !in 0 until PAUSE_MS

    fun failed() { failedAt = clock() }

    fun answered() { failedAt = 0L }

    companion object {
        const val PAUSE_MS = 2 * 60_000L
    }
}
