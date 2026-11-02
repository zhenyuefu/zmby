package app.zmby.playback

import app.zmby.danmu.DanmuEndpoint
import app.zmby.danmu.Danmaku
import app.zmby.emby.BaseItemDto
import app.zmby.segments.SegmentLookup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/** Starts with playback preparation. The next card never waits for either background download. */
class NextEpisodePrefetch(
    scope: CoroutineScope,
    val server: String,
    val endpoint: DanmuEndpoint,
    val rows: Int,
    lookup: suspend () -> BaseItemDto?,
    comments: (suspend (BaseItemDto) -> List<Danmaku>?)?,
    segments: suspend (BaseItemDto) -> SegmentLookup,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    data class Content(
        val episode: BaseItemDto,
        val comments: Deferred<List<Danmaku>?>?,
        val segments: Deferred<SegmentLookup>,
    )
    private val lifetime = SupervisorJob(scope.coroutineContext[Job])
    private val worker = CoroutineScope(scope.coroutineContext + lifetime + dispatcher)
    val ready: Deferred<Content?> = worker.async {
        try {
            val next = lookup() ?: return@async null
            Content(next, comments?.let { load -> worker.async { load(next) } }, worker.async { segments(next) })
        } finally {
            // Close the parent after its already-started children finish; no idle jobs accumulate.
            lifetime.complete()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun completedFor(server: String, item: String): Content? =
        if (this.server != server || !ready.isCompleted || ready.isCancelled) null
        else runCatching { ready.getCompleted() }.getOrNull()?.takeIf { it.episode.id == item }

    fun cancel() { lifetime.cancel() }
}
