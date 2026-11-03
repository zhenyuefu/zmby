package app.zmby.danmu

/**
 * The 弹幕 clock (danmu design: 时钟): 9529 samples about once a second, interpolated with a
 * monotonic clock while playing, frozen while paused or when samples stop coming. Media samples
 * decide when to admit comments; the separate render clock moves already visible comments without
 * jumping on small corrections. Only an explicit seek or a large discontinuity clears the stage.
 */
class DanmuClock(private val staleMs: Long = STALE_MS, private val jumpMs: Long = JUMP_MS) {
    private var position = 0L
    private var at = 0L
    private var playing = false
    private var started = false
    private var rendered = 0L
    val hasSample: Boolean get() = started

    var lastCorrectionMs = 0L
        private set

    /** Records a sample at monotonic time [nowMs]; true when it is a jump (seek) from where the clock was. */
    fun sample(positionMs: Long, playing: Boolean, nowMs: Long): Boolean {
        lastCorrectionMs = if (started) positionMs - position(nowMs) else 0L
        val jumped = started && kotlin.math.abs(lastCorrectionMs) > jumpMs
        rendered = renderTime(nowMs)
        position = positionMs.coerceAtLeast(0)
        at = nowMs
        this.playing = playing
        started = true
        return jumped
    }

    /** zmby moved the position itself. */
    fun seek(positionMs: Long, nowMs: Long) {
        rendered = renderTime(nowMs)
        position = positionMs.coerceAtLeast(0)
        at = nowMs
        started = true
    }

    fun position(nowMs: Long): Long {
        if (!started) return 0
        return position + elapsed(nowMs)
    }

    /** Elapsed animation time never follows a media-clock correction backwards or forwards. */
    fun renderTime(nowMs: Long): Long = rendered + if (started) elapsed(nowMs) else 0L

    private fun elapsed(nowMs: Long): Long = if (playing) (nowMs - at).coerceIn(0, staleMs) else 0L

    /** Whether the picture moves: playing and samples are fresh. */
    fun running(nowMs: Long): Boolean = started && playing && nowMs - at <= staleMs

    companion object {
        const val STALE_MS = 2_500L
        const val JUMP_MS = 1_500L
    }
}

data class DanmuTextKey(val text: String, val color: Int)

/** A comment on screen. [startMs] belongs to the render clock, not the sampled media clock. */
class Placed(val danmaku: Danmaku, val row: Int, val startMs: Long, var widthPx: Float) {
    val textKey = DanmuTextKey(danmaku.text, danmaku.color)
}

/**
 * Lays comments into [rows] rows that scroll right to left at one speed (danmu design: 布局). With
 * one speed a later comment never catches up with the one before it in its row, so a comment
 * takes the first row whose last comment has fully entered with [gapPx] to spare, or is dropped.
 * Pure: [update] admits by media time and moves by render time. Backward sample corrections do not
 * rewind the admission cursor; callers explicitly [reset] for seeks.
 */
class DanmuStage(
    private val comments: List<Danmaku>,
    private val widthOf: (Danmaku) -> Float?,
    private var screenPx: Float,
    private val rows: Int,
    private var gapPx: Float,
) {
    private var next = 0
    private var initialized = false
    private val placed = ArrayList<Placed>()
    private val rowTail = arrayOfNulls<Placed>(rows)

    private val bottomTail = arrayOfNulls<Placed>(rows)

    val size: Int get() = comments.size
    val active: List<Placed> get() = placed
    val nextTimeMs: Long? get() = comments.getOrNull(next)?.timeMs

    /** Updates in place. Missing background measurements wait until ready, at most [LATE_MS]. */
    fun update(positionMs: Long, renderTimeMs: Long = positionMs) {
        if (!initialized) reset(positionMs)
        while (next < comments.size && comments[next].timeMs <= positionMs) {
            val d = comments[next]
            val age = positionMs - d.timeMs
            if (age > LATE_MS) { next++; continue }
            val start = renderTimeMs - age
            var row = 0
            val tails = if (d.position == DanmuPosition.Bottom) bottomTail else rowTail
            while (row < rows && !free(tails[row], d.position, start)) row++
            if (row == rows) { next++; continue }
            val width = widthOf(d) ?: break
            val p = Placed(d, row, start, width)
            tails[row] = p
            placed += p
            next++
        }
        var kept = 0
        var i = 0
        while (i < placed.size) {
            val p = placed[i++]
            if (visible(p, renderTimeMs)) placed[kept++] = p
        }
        while (placed.size > kept) placed.removeAt(placed.lastIndex)
    }

    /** Keeps progress and row assignments when the window changes size. */
    fun resize(widthPx: Float, gapPx: Float) {
        val ratio = widthPx / screenPx
        for (p in placed) p.widthPx *= ratio
        for (tail in rowTail) if (tail != null && tail !in placed) tail.widthPx *= ratio
        screenPx = widthPx
        this.gapPx = gapPx
    }

    fun refreshWidths() {
        for (p in placed) widthOf(p.danmaku)?.let { p.widthPx = it }
    }

    /** Clears the screen and continues from [positionMs] (a seek, or the first frame). */
    fun reset(positionMs: Long) {
        placed.clear()
        rowTail.fill(null)
        bottomTail.fill(null)
        next = lowerBound(positionMs)
        initialized = true
    }

    private fun visible(p: Placed, time: Long) = if (p.danmaku.position == DanmuPosition.Scroll)
        x(p, time) + p.widthPx >= 0 else time - p.startMs < FIXED_MS

    private fun free(tail: Placed?, position: DanmuPosition, atMs: Long): Boolean = when {
        tail == null -> true
        position != DanmuPosition.Scroll || tail.danmaku.position != DanmuPosition.Scroll -> !visible(tail, atMs)
        else -> x(tail, atMs) + tail.widthPx + gapPx <= screenPx
    }

    fun x(p: Placed, renderTimeMs: Long): Float = if (p.danmaku.position != DanmuPosition.Scroll)
        (screenPx - p.widthPx) / 2 else screenPx - (renderTimeMs - p.startMs) * screenPx / CROSS_MS

    private fun lowerBound(t: Long): Int {
        var lo = 0
        var hi = comments.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (comments[mid].timeMs < t) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        /** A comment crosses the screen in this long. */
        const val FIXED_MS = 4_000L
        const val CROSS_MS = 8_000f
        /** One still not placed this long after its time is dropped. */
        const val LATE_MS = 1_000L
    }
}
