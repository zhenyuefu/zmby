package app.zmby.log

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

enum class LogLevel(val letter: Char, val priority: Int) {
    V('V', 2), D('D', 3), I('I', 4), W('W', 5), E('E', 6);

    companion object {
        /** "w", "W", "warn" and "5" all mean [W]; unknown input means no filter. */
        fun parse(raw: String?): LogLevel? {
            val s = raw?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
            s.toIntOrNull()?.let { p -> return entries.firstOrNull { it.priority == p } }
            return entries.firstOrNull { it.letter == s[0] }
        }
    }
}

data class LogEntry(val seq: Long, val timeMs: Long, val level: LogLevel, val tag: String, val message: String) {
    /** `seq time L tag: message`, continuation lines (stack traces) indented. */
    fun format(): String {
        val time = synchronized(TIME) { TIME.format(Date(timeMs)) }
        val head = "%06d %s %c %s: ".format(Locale.ROOT, seq, time, level.letter, tag)
        return head + message.replace("\n", "\n    ")
    }

    private companion object {
        val TIME = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT)
    }
}

/**
 * The last [capacity] log entries, numbered from 1 so readers can resume with `since=<seq>`.
 * Thread-safe; [await] lets a streaming reader block until something new arrives.
 */
class LogBuffer(private val capacity: Int = 5000) {
    private val lock = Object()
    private val ring = ArrayDeque<LogEntry>(capacity)
    private var next = 1L

    val lastSeq: Long get() = synchronized(lock) { next - 1 }

    fun append(level: LogLevel, tag: String, message: String, timeMs: Long = System.currentTimeMillis()): LogEntry =
        synchronized(lock) {
            val entry = LogEntry(next++, timeMs, level, tag, message)
            if (ring.size == capacity) ring.removeFirst()
            ring.addLast(entry)
            lock.notifyAll()
            entry
        }

    /** Entries after [seq] at [min] level or above, oldest first, at most the newest [limit]. */
    fun since(seq: Long, min: LogLevel = LogLevel.V, limit: Int = capacity): List<LogEntry> = synchronized(lock) {
        ring.filter { it.seq > seq && it.level >= min }.takeLast(limit)
    }

    /** Like [since], but waits up to [timeoutMs] for an entry after [seq] to arrive. */
    fun await(seq: Long, min: LogLevel, timeoutMs: Long): List<LogEntry> = synchronized(lock) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (next - 1 <= seq) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return emptyList()
            lock.wait(left)
        }
        ring.filter { it.seq > seq && it.level >= min }
    }
}

/**
 * The app's one logging entry point. Every line is redacted, kept in [buffer] for the LAN log
 * server (`GET /log` on the box) and mirrored to logcat under `zmby.<tag>`.
 */
object ZLog {
    val buffer = LogBuffer(5000)
    val redactor = Redactor()

    /** Replaced in JVM tests; on the box it writes to logcat. */
    @Volatile var sink: (LogLevel, String, String) -> Unit = ::logcat

    /**
     * Once started, formatting, redaction (regexes) and the logcat write happen on one low-priority
     * thread in call order, never on the caller's (architecture: 线程与性能约定). Until then (JVM
     * tests) a line is written before [log] returns.
     */
    @Volatile private var writer: ExecutorService? = null

    fun startBackgroundWriter() {
        if (writer != null) return
        writer = Executors.newSingleThreadExecutor { r -> Thread(r, "zmby-log").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    }

    /** Masks this exact value wherever it later appears (tokens learned at login). */
    fun secret(value: String?) = redactor.addSecret(value)

    fun v(tag: String, message: String) = log(LogLevel.V, tag, message)
    fun d(tag: String, message: String) = log(LogLevel.D, tag, message)
    fun i(tag: String, message: String) = log(LogLevel.I, tag, message)
    fun w(tag: String, message: String, error: Throwable? = null) = log(LogLevel.W, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = log(LogLevel.E, tag, message, error)

    fun log(level: LogLevel, tag: String, message: String, error: Throwable? = null) {
        val time = System.currentTimeMillis()
        val w = writer
        if (w == null) write(level, tag, message, error, time) else runCatching { w.execute { write(level, tag, message, error, time) } }
    }

    /** Writes on the calling thread, after what is queued: for the crash handler, before the process dies. */
    fun logNow(level: LogLevel, tag: String, message: String, error: Throwable? = null) {
        val time = System.currentTimeMillis()
        writer?.let { w -> runCatching { w.submit {}.get(500, java.util.concurrent.TimeUnit.MILLISECONDS) } }
        write(level, tag, message, error, time)
    }

    private fun write(level: LogLevel, tag: String, message: String, error: Throwable?, time: Long) {
        val full = if (error == null) message else "$message\n${error.stackTraceToString().trimEnd()}"
        val clean = redactor.redact(full)
        buffer.append(level, tag, clean, time)
        runCatching { sink(level, tag, clean) }
    }

    private fun logcat(level: LogLevel, tag: String, message: String) {
        android.util.Log.println(level.priority, "zmby.$tag", message)
    }
}
