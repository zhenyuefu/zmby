package app.zmby.danmu

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The overlay's look from 设置 › 播放 › 弹幕, in 1920-wide design pixels (danmu design: 布局).
 * [lineGap] is the space between two rows' text lines, as in the settings preview; the row pitch
 * itself comes from the font's measured line height, so rows never overlap at any size.
 */
data class DanmuStyle(
    val rows: Int = 3,
    val fontSize: Int = 34,
    val lineGap: Int = 4,
    val topMargin: Int = 12,
    /** 20–100. */
    val opacityPercent: Int = 100,
)

/**
 * The 弹幕 media clock (danmu design: 时钟): 9529 samples about once a second, interpolated with a
 * monotonic clock while playing, frozen while paused or when samples stop coming. It decides when
 * comments are admitted; motion follows [DanmuTicker] instead, so a correction never moves text
 * already on screen. Only an explicit seek or a large discontinuity clears the stage.
 */
class DanmuClock(private val staleMs: Long = STALE_MS, private val jumpMs: Long = JUMP_MS) {
    private var position = 0L
    private var at = 0L
    private var playing = false
    private var started = false
    val hasSample: Boolean get() = started

    var lastCorrectionMs = 0L
        private set

    /** Records a sample at monotonic time [nowMs]; true when it is a jump (seek) from where the clock was. */
    fun sample(positionMs: Long, playing: Boolean, nowMs: Long): Boolean {
        lastCorrectionMs = if (started) positionMs - position(nowMs) else 0L
        val jumped = started && abs(lastCorrectionMs) > jumpMs
        position = positionMs.coerceAtLeast(0)
        at = nowMs
        this.playing = playing
        started = true
        return jumped
    }

    /** zmby moved the position itself. */
    fun seek(positionMs: Long, nowMs: Long) {
        position = positionMs.coerceAtLeast(0)
        at = nowMs
        started = true
    }

    fun position(nowMs: Long): Long {
        if (!started) return 0
        return position + if (playing) (nowMs - at).coerceIn(0, staleMs) else 0L
    }

    /** Whether the picture moves: playing and samples are fresh. */
    fun running(nowMs: Long): Boolean = started && playing && nowMs - at <= staleMs

    companion object {
        const val STALE_MS = 2_500L
        const val JUMP_MS = 1_500L
    }
}

/**
 * Animation time counted in display refreshes (after BiliPai's RenderFrameCadence and RenderClock).
 * Each Choreographer frame adds the whole number of refresh periods since the previous one, so text
 * moves by a fixed whole-pixel pattern every refresh, a late frame catches up exactly and a repeated
 * timestamp adds nothing. Sub-pixel steps and per-frame rounding of milliseconds are what made the
 * earlier renderer shimmer and judder on a fixed-rate TV.
 *
 * The period starts from what the display reports, but the display can be wrong for this app: frame
 * rate overrides, variable refresh and TV mode switches can deliver 120 Hz vsyncs while reporting
 * 60 Hz (each frame then sits on half a period and alternates between 0 and 1 tick) or the reverse.
 * So the ticker also measures: the median interval of [WINDOW] consecutive frames replaces the period
 * when it differs by more than [MISMATCH] twice in a row, and [periodChanges] tells the owner to retime.
 */
class DanmuTicker(periodNs: Long = DEFAULT_PERIOD_NS) {
    var periodNs: Long = clamp(periodNs)
        private set
    val hz: Float get() = (1e9 / periodNs).toFloat()
    var ticks = 0L
        private set
    /** Refreshes that passed without a frame while running (debug statistics). */
    var skipped = 0L
        private set
    /** Counts adopted period changes, measured or reported. */
    var periodChanges = 0
        private set
    private var lastNs = 0L
    private var previousFrameNs = 0L
    private val intervals = LongArray(WINDOW)
    private val sorted = LongArray(WINDOW)
    private var count = 0
    private var candidateNs = 0L

    /** The display's own figure (on attach, or a display change): it wins until measurement disagrees. */
    fun reported(periodNs: Long) {
        val p = clamp(periodNs)
        count = 0
        candidateNs = 0L
        if (p != this.periodNs) { this.periodNs = p; periodChanges++ }
    }

    /** A vsync while text runs; the ticks it advanced. */
    fun frame(frameTimeNs: Long): Long {
        measure(frameTimeNs)
        if (lastNs == 0L) { lastNs = frameTimeNs; return 0 }
        val n = (frameTimeNs - lastNs + periodNs / 2) / periodNs
        // Less than half a refresh: keep counting from the last counted frame, lose no time.
        if (n <= 0) return 0
        ticks += n
        if (n > 1) skipped += n - 1
        lastNs = frameTimeNs
        return n
    }

    /** Paused or stale: the next running frame starts counting afresh. */
    fun stop() {
        lastNs = 0L
        previousFrameNs = 0L
    }

    private fun measure(frameTimeNs: Long) {
        val previous = previousFrameNs
        previousFrameNs = frameTimeNs
        if (previous == 0L) return
        val interval = frameTimeNs - previous
        // Pauses and idle wakes are not refreshes.
        if (interval !in MIN_PERIOD_NS..MAX_INTERVAL_NS) return
        intervals[count++] = interval
        if (count < WINDOW) return
        count = 0
        intervals.copyInto(sorted)
        sorted.sort()
        val median = (sorted[WINDOW / 2 - 1] + sorted[WINDOW / 2]) / 2
        if (kotlin.math.abs(median - periodNs) <= periodNs * MISMATCH) { candidateNs = 0L; return }
        // Two windows that agree: a real change, not one stutter.
        if (candidateNs != 0L && kotlin.math.abs(median - candidateNs) <= candidateNs * AGREE) {
            periodNs = clamp((median + candidateNs) / 2)
            periodChanges++
            candidateNs = 0L
        } else candidateNs = median
    }

    companion object {
        const val DEFAULT_PERIOD_NS = 16_666_667L
        const val WINDOW = 24
        private const val MISMATCH = 0.06
        private const val AGREE = 0.03
        /** 500 Hz. */
        private const val MIN_PERIOD_NS = 2_000_000L
        /** About 8 Hz: anything slower is a gap, not a refresh. */
        private const val MAX_INTERVAL_NS = 120_000_000L

        private fun clamp(periodNs: Long) = periodNs.coerceIn(MIN_PERIOD_NS, MAX_INTERVAL_NS)

        fun periodOf(refreshHz: Float): Long =
            if (refreshHz in 10f..500f) (1_000_000_000.0 / refreshHz).roundToLong() else DEFAULT_PERIOD_NS
    }
}

/**
 * Scrolling speed as [px] whole pixels every [ticks] refreshes. Steps are anchored to the global tick
 * count, not to each comment's start, so every comment on screen moves on the same refreshes by the
 * same amount: a pattern like 2, 1, 2, 1 at 144 Hz never makes neighbouring comments jitter apart.
 */
data class DanmuSpeed(val px: Int, val ticks: Int) {
    init { require(px > 0 && ticks > 0) }

    /** Pixels moved between two ticks. */
    fun travelled(fromTick: Long, toTick: Long): Long =
        Math.floorDiv(toTick * px, ticks.toLong()) - Math.floorDiv(fromTick * px, ticks.toLong())

    /** Pixels per millisecond at refresh period [periodNs]. */
    fun perMs(periodNs: Long): Double = px.toDouble() / ticks / (periodNs / 1e6)
}

data class DanmuTextKey(val text: String, val color: Int)

/**
 * A comment on screen, in whole pixels. [startTick] belongs to [DanmuTicker]. [glyph] is whatever the
 * renderer prepared for it when it was admitted; it stays with the comment for its whole life, so a
 * moving comment never switches between a cached texture and direct text.
 */
class Placed<G>(val danmaku: Danmaku, val row: Int, var startTick: Long, val widthPx: Int, val glyph: G) {
    val scrolls: Boolean get() = danmaku.position == DanmuPosition.Scroll
}

/**
 * Lays comments into [rows] lanes per band (danmu design: 布局). Scrolling comments share one speed of
 * [speed] (a fixed whole-pixel pattern per refresh), so a later one never catches up with the one before it in its
 * lane; one enters the first lane whose last comment has fully entered with [gapPx] to spare. Mode 5
 * shares the top lanes, mode 4 has its own lanes from the bottom; both stay [FIXED_MS] centred.
 *
 * A comment with no free lane, or whose glyph is still being prepared, waits in a bounded queue for
 * at most [LATE_MS] (after DanmakuRenderEngine's LayerBuffer) instead of being dropped at once; it then
 * enters from the right edge, never popping in part-way across. Pure: [update] admits by media time
 * and moves by ticks. Backward clock corrections do not rewind the cursor; callers [reset] for seeks.
 */
class DanmuStage<G : Any>(
    private val comments: List<Danmaku>,
    private val glyphOf: (Danmaku, ageMs: Long) -> G?,
    private val widthOf: (G) -> Int,
    private val rows: Int,
) {
    var screenPx = 1920
        private set
    var gapPx = 48
        private set
    var speed = DanmuSpeed(4, 1)
        private set
    private var tickMs = DanmuTicker.DEFAULT_PERIOD_NS / 1e6
    private var fixedTicks = ticksFor(FIXED_MS)

    private var next = 0
    private var initialized = false
    private val placed = ArrayList<Placed<G>>()
    private val waiting = ArrayList<Danmaku>()
    private val topTail = arrayOfNulls<Placed<G>>(rows)
    private val bottomTail = arrayOfNulls<Placed<G>>(rows)

    val size: Int get() = comments.size
    val active: List<Placed<G>> get() = placed
    val nextTimeMs: Long? get() = comments.getOrNull(next)?.timeMs
    val hasWaiting: Boolean get() = waiting.isNotEmpty()
    /** Any scrolling comment on screen: the top band needs every refresh. */
    var moving = false
        private set
    /** Changes whenever something in that band appears or leaves. */
    var topVersion = 0
        private set
    var bottomVersion = 0
        private set

    /** The comments the renderer should have ready now. */
    fun current(): List<Danmaku> = ArrayList<Danmaku>(placed.size + waiting.size).apply {
        for (p in placed) add(p.danmaku)
        addAll(waiting)
    }

    /** A new screen width or text size: everything prepared for the old one is gone. */
    fun layout(screenPx: Int, gapPx: Int, positionMs: Long) {
        this.screenPx = screenPx
        this.gapPx = gapPx
        reset(positionMs)
    }

    /** A new refresh rate: visible comments keep where they are and how long they have shown. */
    fun retime(speed: DanmuSpeed, tickMs: Double, tick: Long) {
        val oldSpeed = this.speed
        val oldTickMs = this.tickMs
        this.speed = speed
        this.tickMs = tickMs
        fixedTicks = ticksFor(FIXED_MS)
        for (p in placed) {
            p.startTick = tick - if (p.scrolls) {
                (oldSpeed.travelled(p.startTick, tick) * speed.ticks + speed.px / 2) / speed.px
            } else ((tick - p.startTick) * oldTickMs / tickMs).roundToLong()
        }
    }

    /** Updates in place: no per-frame lists, pairs or boxed coordinates. */
    fun update(positionMs: Long, tick: Long) {
        if (!initialized) reset(positionMs)
        retire(tick)
        while (next < comments.size && comments[next].timeMs <= positionMs) {
            val d = comments[next++]
            if (positionMs - d.timeMs > LATE_MS) continue
            waiting += d
            // Bounded like a layer buffer: the oldest has the least time left to be useful.
            if (waiting.size > MAX_WAITING) waiting.removeAt(0)
        }
        var kept = 0
        for (i in waiting.indices) {
            val d = waiting[i]
            val age = positionMs - d.timeMs
            if (age > LATE_MS) continue
            // A lane first: a full screen must not ask for (and allocate) glyphs every refresh.
            val row = freeRow(d.position, tick)
            val glyph = if (row < 0) null else glyphOf(d, age)
            if (glyph == null) waiting[kept++] = d else place(d, row, glyph, tick)
        }
        while (waiting.size > kept) waiting.removeAt(waiting.lastIndex)
        var anyScroll = false
        for (i in placed.indices) if (placed[i].scrolls) { anyScroll = true; break }
        moving = anyScroll
    }

    /** Clears the screen and continues from [positionMs] (a seek, a new layout or the first frame). */
    fun reset(positionMs: Long) {
        topVersion++
        bottomVersion++
        placed.clear()
        waiting.clear()
        topTail.fill(null)
        bottomTail.fill(null)
        moving = false
        next = lowerBound(positionMs)
        initialized = true
    }

    fun x(p: Placed<G>, tick: Long): Int = if (p.scrolls) (screenPx - speed.travelled(p.startTick, tick)).toInt()
    else (screenPx - p.widthPx) / 2

    /** The tick at which the earliest centred comment leaves, if any. */
    fun nextFixedEnd(): Long? {
        var end = Long.MAX_VALUE
        for (i in placed.indices) {
            val p = placed[i]
            if (!p.scrolls) end = minOf(end, p.startTick + fixedTicks)
        }
        return end.takeIf { it != Long.MAX_VALUE }
    }

    fun ticksFor(ms: Long): Long = kotlin.math.ceil(ms / tickMs).toLong()

    private fun visible(p: Placed<G>, tick: Long) =
        if (p.scrolls) x(p, tick) + p.widthPx > 0 else tick - p.startTick < fixedTicks

    private fun retire(tick: Long) {
        var kept = 0
        for (i in placed.indices) {
            val p = placed[i]
            if (visible(p, tick)) placed[kept++] = p else changed(p)
        }
        while (placed.size > kept) placed.removeAt(placed.lastIndex)
    }

    private fun freeRow(position: DanmuPosition, tick: Long): Int {
        val tails = if (position == DanmuPosition.Bottom) bottomTail else topTail
        for (row in 0 until rows) if (free(tails[row], position, tick)) return row
        return -1
    }

    private fun place(d: Danmaku, row: Int, glyph: G, tick: Long) {
        val p = Placed(d, row, tick, widthOf(glyph), glyph)
        if (d.position == DanmuPosition.Bottom) bottomTail[row] = p else topTail[row] = p
        placed += p
        changed(p)
    }

    private fun changed(p: Placed<G>) {
        if (p.danmaku.position == DanmuPosition.Bottom) bottomVersion++ else topVersion++
    }

    private fun free(tail: Placed<G>?, position: DanmuPosition, tick: Long): Boolean = when {
        tail == null -> true
        position != DanmuPosition.Scroll || !tail.scrolls -> !visible(tail, tick)
        else -> x(tail, tick) + tail.widthPx + gapPx <= screenPx
    }

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
        /** Mode 4 and 5 comments stay this long. */
        const val FIXED_MS = 4_000L
        /** A scrolling comment travels one screen width in this long. */
        const val CROSS_MS = 8_000f
        /** One still not placed this long after its time is dropped. */
        const val LATE_MS = 1_000L
        const val MAX_WAITING = 48

        /**
         * The step pattern closest to one screen width per [CROSS_MS] (danmu design: 刷新率). Low and
         * 60 Hz class rates get whole pixels every refresh. Above that a step of one or two pixels
         * rounds badly (144 Hz would run 20 % fast, 165 Hz 31 % slow), so [px] every [DanmuSpeed.ticks]
         * refreshes is allowed as long as the pattern still repeats at least 60 times a second, too fast
         * to read as judder: 3 every 2 at 144 and 165 Hz, 2 every 3 at 360 Hz.
         */
        fun speedFor(screenPx: Int, periodNs: Long): DanmuSpeed {
            val target = screenPx / CROSS_MS * periodNs / 1e6 // Pixels per refresh.
            val maxTicks = (1e9 / periodNs / 60 + 0.01).toInt().coerceIn(1, 8)
            var best = DanmuSpeed(kotlin.math.round(target).toInt().coerceAtLeast(1), 1)
            var bestError = kotlin.math.abs(best.px - target)
            for (q in 2..maxTicks) {
                val px = kotlin.math.round(target * q).toInt().coerceAtLeast(1)
                val error = kotlin.math.abs(px.toDouble() / q - target)
                if (error < bestError - 1e-9) { best = DanmuSpeed(px, q); bestError = error }
            }
            val g = gcd(best.px, best.ticks)
            return DanmuSpeed(best.px / g, best.ticks / g)
        }

        private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    }
}
