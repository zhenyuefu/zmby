package app.zmby.data

import app.zmby.log.ZLog
import java.util.concurrent.Executors

/**
 * One background thread for preference and file writes, in call order (architecture: 线程与性能约定).
 * Callers update their in-memory state first, so what the screen shows never waits for the disk.
 */
object Disk {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "zmby-disk").apply { isDaemon = true } }

    fun write(block: () -> Unit) {
        executor.execute { runCatching(block).onFailure { ZLog.w("Disk", "write failed", it) } }
    }
}
