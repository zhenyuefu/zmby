package app.zmby.data

/**
 * Wrong import codes on the LAN page: a source (the peer's address) is locked for [lockMs] after
 * [perSource] wrong codes, and the whole session closes after [total] wrong codes from anywhere,
 * so six digits cannot be walked through while the page is open. A right code clears its source.
 */
class ImportGuard(
    private val clock: () -> Long = System::currentTimeMillis,
    val perSource: Int = 5,
    val lockMs: Long = 10 * 60_000L,
    val total: Int = 20,
) {
    sealed interface Verdict {
        /** Code accepted. */
        data object Ok : Verdict
        /** Wrong code; [left] more before this source is locked. */
        data class Wrong(val left: Int) : Verdict
        /** This source may not try again before [untilMs]. */
        data class Locked(val untilMs: Long) : Verdict
        /** Too many wrong codes in total: the session must close. */
        data object Closed : Verdict
    }

    private class Source(var wrong: Int = 0, var lockedUntil: Long = 0)
    private val sources = HashMap<String, Source>()
    private var wrongTotal = 0
    private var closed = false

    /** Whether [source] is locked right now (checked before the code is even read). */
    @Synchronized fun locked(source: String): Verdict.Locked? {
        if (closed) return Verdict.Locked(Long.MAX_VALUE)
        val until = sources[source]?.lockedUntil ?: return null
        return if (until > clock()) Verdict.Locked(until) else null
    }

    @Synchronized fun check(source: String, ok: Boolean): Verdict {
        if (closed) return Verdict.Closed
        locked(source)?.let { return it }
        val s = sources.getOrPut(source) { Source() }
        if (ok) {
            s.wrong = 0
            return Verdict.Ok
        }
        wrongTotal++
        if (wrongTotal >= total) {
            closed = true
            return Verdict.Closed
        }
        s.wrong++
        if (s.wrong >= perSource) {
            s.wrong = 0
            s.lockedUntil = clock() + lockMs
            return Verdict.Locked(s.lockedUntil)
        }
        return Verdict.Wrong(perSource - s.wrong)
    }

    companion object {
        /** Compares codes without an early exit on the first differing digit. */
        fun same(a: String, b: String): Boolean =
            java.security.MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }
}
