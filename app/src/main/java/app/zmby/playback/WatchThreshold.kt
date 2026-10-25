package app.zmby.playback

/**
 * The one "watched" threshold (plan §3.2): stopping past it marks the item played, and a resume
 * point past it starts from the beginning. Servers and forks disagree on their own thresholds, so
 * the app applies it itself instead of relying on the server.
 */
object WatchThreshold {
    const val DEFAULT_PERCENT = 90
    const val MIN_PERCENT = 50
    const val MAX_PERCENT = 100
    const val STEP = 5

    fun clamp(percent: Int): Int = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)

    /** False when the runtime is unknown (an item the server has not finished scraping). */
    fun isWatched(positionMs: Long, runtimeMs: Long?, percent: Int): Boolean {
        if (runtimeMs == null || runtimeMs <= 0 || positionMs <= 0) return false
        return positionMs * 100 >= runtimeMs * clamp(percent)
    }

    /** Where to start: the resume point, or 0 when it is already past the threshold. */
    fun startPosition(resumeMs: Long, runtimeMs: Long?, percent: Int): Long =
        if (resumeMs > 0 && isWatched(resumeMs, runtimeMs, percent)) 0 else resumeMs.coerceAtLeast(0)
}
