package app.zmby.playback

import app.zmby.log.ZLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * One playback's ordered side effects, owned by the application scope rather than the polling job.
 * Slow network/Room writes never hold up status samples. Adjacent ordinary progress reports are
 * coalesced; pause transitions and seeks form ordering boundaries. The worker exits when idle.
 */
internal class PlaybackReportQueue(
    private val scope: CoroutineScope,
    private val reporter: PlaybackReporter,
    private val session: PlaySessionSink?,
) {
    private sealed interface Event {
        data class Start(val at: Long, val positionMs: Long, val recordSession: Boolean) : Event
        data class Progress(val positionMs: Long, val paused: Boolean, val snapshot: SessionProgress?, val boundary: Boolean) : Event
        data class Finish(val positionMs: Long?, val runtimeMs: Long?, val watched: Boolean, val snapshot: SessionProgress?) : Event
    }

    private val pending = ArrayDeque<Event>()
    private var worker: Job? = null
    private var closed = false
    private val done = CompletableDeferred<Unit>()

    fun start(at: Long, positionMs: Long, recordSession: Boolean) = enqueue(Event.Start(at, positionMs, recordSession))

    fun progress(positionMs: Long, paused: Boolean, snapshot: SessionProgress?, boundary: Boolean = false) =
        enqueue(Event.Progress(positionMs, paused, snapshot, boundary))

    suspend fun finish(positionMs: Long?, runtimeMs: Long?, watched: Boolean, snapshot: SessionProgress?) {
        enqueue(Event.Finish(positionMs, runtimeMs, watched, snapshot))
        done.await()
    }

    private fun enqueue(event: Event) {
        val job = synchronized(pending) {
            if (closed) return
            if (event is Event.Finish) closed = true
            val previous = pending.lastOrNull()
            if (event is Event.Progress && !event.boundary && previous is Event.Progress &&
                !previous.boundary && previous.paused == event.paused) pending.removeLast()
            pending.addLast(event)
            worker ?: scope.launch(start = CoroutineStart.LAZY) { drain() }.also {
                worker = it
                // Also completes a finish waiter if the scope was already cancelled before launch.
                it.invokeOnCompletion { cause -> if (cause != null) done.completeExceptionally(cause) }
            }
        }
        job.start()
    }

    private suspend fun drain() {
        while (true) {
            val event = synchronized(pending) {
                if (pending.isEmpty()) { worker = null; return }
                pending.removeFirst()
            }
            try {
                when (event) {
                    is Event.Start -> {
                        if (event.recordSession) session?.started(event.at, event.positionMs)
                        reporter.start(event.positionMs)
                    }
                    is Event.Progress -> {
                        event.snapshot?.let { session?.checkpoint(it) }
                        reporter.progress(event.positionMs, event.paused)
                    }
                    is Event.Finish -> {
                        event.snapshot?.let { session?.finished(it, event.positionMs, event.runtimeMs, event.watched) }
                        event.positionMs?.let { reporter.stopped(it) }
                        if (event.watched) reporter.markPlayed()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ZLog.w("Monitor", "playback side effect failed", e)
            }
            if (event is Event.Finish) done.complete(Unit)
        }
    }
}
