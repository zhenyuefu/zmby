package app.zmby.danmu

/** Thread-confined LRU. Published graphics are dropped, never recycled under an in-flight draw. */
class DanmuRenderCache<K, V>(val maxBytes: Long, private val maxEntries: Int) {
    private data class Entry<V>(val value: V, val bytes: Long)
    private val entries = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)
    var bytes = 0L
        private set
    val size: Int get() = entries.size

    init { require(maxBytes >= 0 && maxEntries > 0) }

    operator fun get(key: K): V? = entries[key]?.value

    fun put(key: K, value: V, byteCount: Long): Boolean {
        require(byteCount >= 0)
        if (byteCount > maxBytes) return false
        entries.remove(key)?.let { bytes -= it.bytes }
        while (entries.isNotEmpty() && (bytes + byteCount > maxBytes || entries.size >= maxEntries)) {
            val oldest = entries.entries.iterator()
            bytes -= oldest.next().value.bytes
            oldest.remove()
        }
        entries[key] = Entry(value, byteCount)
        bytes += byteCount
        return true
    }

    fun retain(keys: Set<K>) {
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in keys) { bytes -= entry.value.bytes; iterator.remove() }
        }
    }

    fun keys(): Set<K> = entries.keys.toSet()
    fun snapshot(): Map<K, V> = entries.mapValues { it.value.value }
    fun clear() { entries.clear(); bytes = 0 }
}

/**
 * When nothing scrolls, the next frame is needed only for the next admission, a centred comment
 * leaving or the chip expiring; null sleeps until a sample, seek or prepared glyph wakes it.
 */
fun nextDanmuFrameDelay(
    running: Boolean,
    moving: Boolean,
    waiting: Boolean,
    positionMs: Long,
    nextTimeMs: Long?,
    fixedRemainingMs: Long?,
    chipRemainingMs: Long,
): Long? {
    if (running && (moving || waiting)) return 0L
    var delay = if (chipRemainingMs > 0) chipRemainingMs else Long.MAX_VALUE
    if (running && nextTimeMs != null) delay = minOf(delay, (nextTimeMs - positionMs).coerceAtLeast(0))
    if (running && fixedRemainingMs != null) delay = minOf(delay, fixedRemainingMs.coerceAtLeast(0))
    return delay.takeIf { it != Long.MAX_VALUE }
}
