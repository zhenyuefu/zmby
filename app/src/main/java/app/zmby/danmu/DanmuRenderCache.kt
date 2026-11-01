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

/** A static chip needs only its expiry redraw; a blank video sleeps until the next admission. */
fun nextDanmuFrameDelay(
    running: Boolean,
    active: Boolean,
    positionMs: Long,
    nextTimeMs: Long?,
    chipRemainingMs: Long,
    waitingForMeasurement: Boolean = false,
): Long? {
    if (running && active) return 0L
    val commentDelay = if (running && nextTimeMs != null && !waitingForMeasurement) {
        (nextTimeMs - positionMs).coerceAtLeast(0)
    } else null
    val chipDelay = chipRemainingMs.takeIf { it > 0 }
    return when {
        commentDelay == null -> chipDelay
        chipDelay == null -> commentDelay
        else -> minOf(commentDelay, chipDelay)
    }
}
