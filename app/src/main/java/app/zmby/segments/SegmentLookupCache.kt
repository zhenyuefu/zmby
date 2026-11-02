package app.zmby.segments

import app.zmby.data.ProviderState
import app.zmby.emby.ServerSession
import app.zmby.net.RequestCache

/** Shares full-item hydration and marker queries between the icon, editor, prefetch and playback. */
class SegmentLookupCache(clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    data class Key(
        val server: String,
        val item: String,
        val durationMs: Long?,
        val providers: List<ProviderState>,
        val revision: Long,
        val connection: ServerSession? = null,
        val providerKey: String? = null,
    )
    private val cache = RequestCache<Key, SegmentLookup>(128, {
        when {
            it.unavailable.isNotEmpty() -> 5_000L
            it.plan.isEmpty() -> 5 * 60_000L
            else -> 6 * 60 * 60_000L
        }
    }, clock)

    suspend fun get(key: Key, isCurrent: () -> Boolean = { true }, load: suspend () -> SegmentLookup): SegmentLookup = cache.get(key) {
        load().also { if (!isCurrent()) cache.invalidate(key) }
    }
}
