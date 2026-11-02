package app.zmby.net

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Bounded process cache. Equal requests share work; cancellation and failures are never cached. */
class RequestCache<K, V>(
    private val capacity: Int,
    private val ttlMs: (V) -> Long,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val maxWeight: Long = capacity.toLong(),
    private val weight: (V) -> Long = { 1L },
) {
    private data class Cached<V>(val value: V, val at: Long, val ttl: Long, val weight: Long)
    private class Entry<V> {
        val mutex = Mutex()
        var cached: Cached<V>? = null
        var users = 0
        var generation = 0L
    }
    private val guard = Any()
    private val entries = LinkedHashMap<K, Entry<V>>(16, .75f, true)

    suspend fun get(key: K, load: suspend () -> V): V {
        val entry = synchronized(guard) { entries.getOrPut(key) { Entry() }.also { it.users++ } }
        try {
            return entry.mutex.withLock {
                synchronized(guard) { fresh(entry) }?.let { return@withLock it.value }
                val generation = synchronized(guard) { entry.generation }
                val value = load()
                currentCoroutineContext().ensureActive()
                val ttl = ttlMs(value)
                val cost = weight(value).coerceAtLeast(0)
                synchronized(guard) {
                    if (generation == entry.generation && ttl > 0 && cost <= maxWeight) {
                        entry.cached = Cached(value, clock(), ttl, cost)
                    }
                }
                value
            }
        } finally {
            synchronized(guard) { entry.users--; trim() }
        }
    }

    fun invalidate(key: K) = synchronized(guard) {
        entries[key]?.let { it.generation++; it.cached = null }
    }

    fun clear() = synchronized(guard) {
        entries.values.forEach { it.generation++; it.cached = null }
        entries.entries.removeAll { it.value.users == 0 }
    }

    private fun fresh(entry: Entry<V>): Cached<V>? = entry.cached?.takeIf { clock() - it.at in 0 until it.ttl }

    private fun trim() {
        var total = entries.values.sumOf { it.cached?.weight ?: 0 }
        val iterator = entries.iterator()
        while (iterator.hasNext() && (entries.size > capacity || total > maxWeight)) {
            val entry = iterator.next().value
            if (entry.users > 0) continue
            total -= entry.cached?.weight ?: 0
            iterator.remove()
        }
    }
}
