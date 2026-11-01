package app.zmby.stats

import app.zmby.emby.BaseItemDto
import app.zmby.emby.ticksToMs
import app.zmby.log.ZLog
import app.zmby.playback.PlaySessionSink
import app.zmby.playback.SessionProgress
import app.zmby.store.LocalStore
import app.zmby.store.PlaySessionRow
import app.zmby.store.SessionSpans
import app.zmby.store.StoreKeys
import kotlinx.coroutines.CancellationException

/**
 * Writes 我的's playback sessions (local-data spec, 本机播放会话) to `play_session`: a row when the
 * player confirms the file, checkpoints while it plays, the final position when it returns. A failed
 * write is logged and never reaches playback.
 */
class PlaySessionRecorder(private val store: LocalStore) {
    private val dao = store.db.playSessions()

    /** Sessions a crash or kill left open end at their last checkpoint; called once at start. */
    suspend fun closeAbandoned() {
        val n = runCatching { dao.closeOpen() }.onFailure { ZLog.w(TAG, "could not close open sessions", it) }.getOrDefault(0)
        if (n > 0) ZLog.i(TAG, "closed $n session(s) left open by the last run")
    }

    fun sink(server: String, item: BaseItemDto): PlaySessionSink = Sink(server, item)

    private inner class Sink(private val server: String, private val item: BaseItemDto) : PlaySessionSink {
        private var id: Long? = null

        override suspend fun started(at: Long, positionMs: Long) = guard("start") {
            val seriesId = item.seriesId?.takeIf { item.type == "Episode" }
            val seriesKey = seriesId?.let { store.workKeyOf(server, it) ?: StoreKeys.local(server, it) }
            val genres = item.genres.ifEmpty { seriesId?.let { store.detail(server, it)?.item?.genres }.orEmpty() }
            id = dao.insert(
                PlaySessionRow(
                    serverId = server,
                    itemId = item.id,
                    type = if (item.type == "Episode") "Episode" else "Movie",
                    workKey = store.workKeyOf(server, item.id) ?: StoreKeys.work(server, item, seriesKey),
                    seriesKey = seriesKey,
                    seriesId = seriesId,
                    seriesName = item.seriesName,
                    title = item.name,
                    season = item.parentIndexNumber,
                    episode = item.indexNumber,
                    genres = genres.filter { it.isNotBlank() }.distinct(),
                    startedAt = at,
                    endedAt = at,
                    startPositionMs = positionMs,
                    endPositionMs = positionMs,
                    runtimeMs = item.runTimeTicks?.let(::ticksToMs),
                ),
            )
            ZLog.i(TAG, "session $id started: $server/${item.id} at ${positionMs}ms")
        }

        override suspend fun checkpoint(progress: SessionProgress) = guard("checkpoint") { write(progress, progress.positionMs, null, false, open = true) }

        override suspend fun finished(progress: SessionProgress, positionMs: Long?, runtimeMs: Long?, completed: Boolean) = guard("finish") {
            write(progress, positionMs ?: progress.positionMs, runtimeMs, completed, open = false)
            ZLog.i(TAG, "session $id finished: watched ${progress.watchedMs}ms in ${progress.spans.size} span(s), completed=$completed")
        }

        private suspend fun write(progress: SessionProgress, position: Long, runtimeMs: Long?, completed: Boolean, open: Boolean) {
            val row = id ?: return
            dao.update(row, progress.lastSampleAt, progress.watchedMs, SessionSpans.encode(progress.startedAt, progress.spans), position, runtimeMs, completed, open)
        }

        private suspend fun guard(what: String, block: suspend () -> Unit) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ZLog.w(TAG, "session $what not recorded", e)
            }
        }
    }

    private companion object { const val TAG = "Session" }
}
