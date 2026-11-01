package app.zmby.playback

import app.zmby.store.WatchSpan

/** Where one playback's session goes (我的, local-data spec 本机播放会话); implementations never throw. */
interface PlaySessionSink {
    /** The player confirmed this file at [at], from [positionMs]. */
    suspend fun started(at: Long, positionMs: Long)
    /** A checkpoint: what a crash or kill leaves behind. */
    suspend fun checkpoint(progress: SessionProgress)
    /** The player returned: the final position and whether it counts as watched. */
    suspend fun finished(progress: SessionProgress, positionMs: Long?, runtimeMs: Long?, completed: Boolean)
}

/** A session so far: the stretches really played and the last sample. */
data class SessionProgress(val startedAt: Long, val lastSampleAt: Long, val spans: List<WatchSpan>, val positionMs: Long) {
    val watchedMs: Long get() = spans.sumOf { it.length }
}

/**
 * Watched time of one playback from the player's progress samples (local-data spec, 怎么产生). Two
 * samples in a row that are both playing count the position's advance, at most the wall-clock time
 * between them, if the advance is plausible for normal playback; pauses, seeks (backwards, or far
 * ahead of the clock) and stalls count nothing. Each counted piece ends at its sample's time and
 * joins the previous span when it starts less than [JOIN_MS] after it.
 */
class SessionTracker(val startedAt: Long, startPositionMs: Long, playing: Boolean) {
    private var lastAt = startedAt
    private var lastPosition = startPositionMs
    private var lastPlaying = playing
    private val spans = ArrayList<WatchSpan>()

    fun sample(at: Long, positionMs: Long, playing: Boolean) {
        // Some players report 0 while they buffer or switch; it is not a position.
        if (positionMs <= 0) return
        val wall = at - lastAt
        val advance = positionMs - lastPosition
        if (playing && lastPlaying && wall > 0 && advance > 0 && advance <= wall * 3 / 2 + SLACK_MS) {
            add(at, minOf(wall, advance))
        }
        lastAt = at
        lastPosition = positionMs
        lastPlaying = playing
    }

    /** zmby itself moved the player (片头片尾跳过): the jump is not watching. */
    fun seeked(at: Long, positionMs: Long) {
        lastAt = at
        lastPosition = positionMs
    }

    fun snapshot(): SessionProgress = SessionProgress(startedAt, lastAt, spans.toList(), lastPosition)

    private fun add(at: Long, length: Long) {
        val start = at - length
        val last = spans.lastOrNull()
        if (last != null && start - last.end < JOIN_MS) {
            spans[spans.lastIndex] = WatchSpan(last.start, maxOf(last.end, at))
        } else if (last != null && spans.size >= MAX_SPANS) {
            // Full: the time still counts, only where it falls gets coarser.
            spans[spans.lastIndex] = WatchSpan(last.start, last.end + length)
        } else {
            spans += WatchSpan(start, at)
        }
    }

    companion object {
        /** Allowance on top of 1.5× the wall-clock time for the player's position granularity. */
        const val SLACK_MS = 2_000L
        /** Poll jitter: a piece starting this soon after the last span continues it. */
        const val JOIN_MS = 250L
        const val MAX_SPANS = 400
    }
}
