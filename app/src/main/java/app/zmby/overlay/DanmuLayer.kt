package app.zmby.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import app.zmby.danmu.DanmuClock
import app.zmby.danmu.DanmuPosition
import app.zmby.danmu.DanmuStage
import app.zmby.danmu.DanmuStyle
import app.zmby.danmu.DanmuTicker
import app.zmby.danmu.Danmaku
import app.zmby.danmu.nextDanmuFrameDelay
import app.zmby.log.ZLog
import app.zmby.playback.MonitorOverlay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 弹幕 on the overlay's passive layer (ui-spec §2.16, danmu design): two windows that never take
 * focus or touches, added once the comments are loaded. The top band is only as tall as its rows, so
 * the window redrawn every refresh is a strip, not a full-screen translucent buffer over the video;
 * the bottom band (mode 4 rows and the count chip) is redrawn only when its content changes and is
 * removed once it has nothing to show. Samples come from the playback monitor through [PlaybackOverlay].
 */
class DanmuLayer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val comments: Deferred<List<Danmaku>?>,
    private val style: DanmuStyle,
) : MonitorOverlay {
    private val topWindow = OverlayWindow(context, danmuBandParams(Gravity.TOP, "zmby danmu top"))
    private val bottomWindow = OverlayWindow(context, danmuBandParams(Gravity.BOTTOM, "zmby danmu bottom"))
    private var engine: DanmuEngine? = null
    private var job: Job? = null
    private var closed = false
    /** The last sample, for an engine created after it. */
    private var lastSample: Triple<Long, Boolean, Long>? = null

    fun start() {
        job = scope.launch {
            val list = try { comments.await() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (closed || list.isNullOrEmpty()) return@launch
            val e = DanmuEngine(context, scope, list, style, onBottomIdle = { bottomWindow.hide() })
            lastSample?.let { (pos, playing, at) -> e.sample(pos, playing, at) }
            if (!topWindow.show { e.top }) {
                ZLog.w(TAG, "danmu window not shown")
                e.release()
                return@launch
            }
            if (!bottomWindow.show { e.bottom }) ZLog.w(TAG, "danmu bottom window not shown")
            engine = e
        }
    }

    override fun onSample(positionMs: Long, durationMs: Long, playing: Boolean) {
        val now = SystemClock.uptimeMillis()
        lastSample = Triple(positionMs, playing, now)
        engine?.sample(positionMs, playing, now)
    }

    override fun onSeeked(positionMs: Long) {
        val now = SystemClock.uptimeMillis()
        lastSample = Triple(positionMs, lastSample?.second ?: false, now)
        engine?.seeked(positionMs, now)
    }

    override fun onLost() {
        closed = true
        job?.cancel()
        comments.cancel()
        engine?.release()
        topWindow.hide()
        bottomWindow.hide()
        engine = null
    }

    private companion object { const val TAG = "Danmu" }
}

/** Full width, as tall as its content: the band's view measures its own height. */
internal fun danmuBandParams(gravity: Int, title: String): WindowManager.LayoutParams =
    overlayParams(focusable = false, gravity = gravity).apply {
        height = WindowManager.LayoutParams.WRAP_CONTENT
        this.title = title
    }

internal enum class DanmuBand { Top, Bottom }

/**
 * The frame loop shared by both bands (after BiliPai's DanmakuView/Controller and AkDanmaku's split of
 * logic from drawing). A Choreographer callback advances [DanmuTicker], admits and retires comments
 * and invalidates only the band that changed; onDraw only draws what the frame laid out, so an extra
 * system redraw never moves text. Moving text gets one frame per refresh; a blank screen sleeps until
 * the next admission, a centred comment leaving or the chip expiring.
 */
internal class DanmuEngine(
    context: Context,
    scope: CoroutineScope,
    private val comments: List<Danmaku>,
    private val style: DanmuStyle,
    workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val onBottomIdle: () -> Unit = {},
    /** What the display says; tests stand in for it. */
    private val displayRefreshHz: (View) -> Float? = { it.display?.refreshRate },
) {
    val clock = DanmuClock()
    private val rows = style.rows.coerceIn(1, 10)
    private val opacity = style.opacityPercent.coerceIn(0, 100) / 100f
    private val ticker = DanmuTicker()
    private val renderer = DanmuTextRenderer(scope, workerDispatcher, style.fontSize) { requestFrame() }
    private val stage = DanmuStage(comments, renderer::glyph, DanmuGlyph::widthPx, rows)
    private val hasBottom = comments.any { it.position == DanmuPosition.Bottom }
    private val stats = DanmuPerformance(scope)
    private val chipText = "弹幕 · ${comments.size} 条"
    private val bitmapPaint = Paint().apply { alpha = (opacity * 255).roundToInt() }
    private val chipBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(184, 0, 0, 0) }
    private val chipRect = RectF()
    private var geometry: Geometry? = null
    private var laidOutWidth = 0
    private var chipUntil = 0L
    private var bottomIdle = false
    private var seenTop = -1
    private var seenBottom = -1
    private var chipWasVisible = false
    private var framePending = false
    private var wakeAtMs = 0L
    private var released = false
    private val handler = Handler(Looper.getMainLooper())
    private val choreographer = Choreographer.getInstance()
    private val frame = Choreographer.FrameCallback { doFrame(it) }
    private val wake = Runnable {
        wakeAtMs = 0L
        requestFrame()
    }
    private val displays = context.getSystemService(DisplayManager::class.java)
    private var listening = false
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == top.display?.displayId) updateRefreshRate()
        }
    }
    internal val hasPendingFrame: Boolean get() = framePending
    internal val nextWakeMs: Long get() = wakeAtMs
    internal val ticks: Long get() = ticker.ticks
    internal val periodNs: Long get() = ticker.periodNs
    internal val activeCount: Int get() = stage.active.size
    internal fun activeX(index: Int): Int = stage.x(stage.active[index], ticker.ticks)

    // Last: the views call back into the fields above.
    val top = DanmuBandView(context, this, DanmuBand.Top)
    val bottom = DanmuBandView(context, this, DanmuBand.Bottom)

    fun sample(positionMs: Long, playing: Boolean, nowMs: Long) {
        if (released) return
        val jumped = clock.sample(positionMs, playing, nowMs)
        stats.sample(nowMs, clock.lastCorrectionMs)
        if (jumped) {
            stage.reset(positionMs)
            renderer.clear()
        }
        prepare(nowMs)
        requestFrame()
    }

    fun seeked(positionMs: Long, nowMs: Long) {
        if (released) return
        clock.seek(positionMs, nowMs)
        stage.reset(positionMs)
        renderer.clear()
        prepare(nowMs)
        requestFrame()
    }

    fun release() {
        released = true
        cancelFrames()
        renderer.clear()
        stopListening()
    }

    internal fun bandHeight(band: DanmuBand, width: Int): Int {
        val g = geometryFor(width) ?: return 0
        return if (band == DanmuBand.Top) g.topHeight else g.bottomHeight
    }

    internal fun attached() {
        if (released) return
        if (chipUntil == 0L) chipUntil = SystemClock.uptimeMillis() + CHIP_MS
        startListening()
        updateRefreshRate()
        prepare(SystemClock.uptimeMillis())
        requestFrame()
    }

    internal fun detached() {
        cancelFrames()
        renderer.clear()
        stopListening()
    }

    internal fun visibilityChanged() {
        if (canRender()) { prepare(SystemClock.uptimeMillis()); requestFrame() } else { cancelFrames(); renderer.clear() }
    }

    /** The top band's width: everything prepared for another width is discarded. */
    internal fun layout(width: Int) {
        if (width <= 0 || released || width == laidOutWidth) return
        val g = geometryFor(width) ?: return
        laidOutWidth = width
        val now = SystemClock.uptimeMillis()
        stage.layout(width, g.gapPx, clock.position(now))
        renderer.configure(g.scale)
        retime()
        prepare(now)
        requestFrame()
    }

    /** One refresh: advance, admit, retire, then invalidate only what changed. */
    internal fun doFrame(frameTimeNs: Long) {
        choreographer.removeFrameCallback(frame)
        framePending = false
        if (!canRender()) return
        val nowMs = frameTimeNs / 1_000_000
        val running = clock.running(nowMs)
        val changes = ticker.periodChanges
        val advanced = if (running) ticker.frame(frameTimeNs) else { ticker.stop(); 0L }
        // The measured vsync period disagreed with the display's figure: new steps from here on.
        if (ticker.periodChanges != changes) retime()
        if (clock.hasSample && laidOutWidth > 0) stage.update(clock.position(nowMs), ticker.ticks)
        if ((advanced > 0 && stage.moving) || stage.topVersion != seenTop) top.invalidate()
        seenTop = stage.topVersion
        val chipVisible = nowMs < chipUntil
        if (stage.bottomVersion != seenBottom || chipVisible != chipWasVisible) bottom.invalidate()
        seenBottom = stage.bottomVersion
        chipWasVisible = chipVisible
        if (!chipVisible && !hasBottom && !bottomIdle) {
            bottomIdle = true
            onBottomIdle()
        }
        schedule(nowMs, running)
    }

    internal fun draw(band: DanmuBand, canvas: Canvas, bandHeight: Int) {
        if (released) return
        val g = geometry ?: return
        if (laidOutWidth <= 0) return
        val startedNs = System.nanoTime()
        val tick = ticker.ticks
        val bottomBand = band == DanmuBand.Bottom
        val list = stage.active
        for (i in list.indices) {
            val p = list[i]
            if ((p.danmaku.position == DanmuPosition.Bottom) != bottomBand) continue
            val x = stage.x(p, tick)
            if (x >= laidOutWidth) continue // Entered this refresh, still beyond the right edge.
            val baseline = g.rowTop(band, p.row, bandHeight) + g.metrics.baseline
            val texture = p.glyph.texture
            if (texture != null) {
                // Whole pixels, no filtering: the prepared tiles reach the screen unresampled.
                var left = x - texture.insetX
                val top = (baseline - texture.baseline).toFloat()
                val tiles = texture.tiles
                for (t in tiles.indices) {
                    val tile = tiles[t]
                    if (left < laidOutWidth && left + tile.width > 0) canvas.drawBitmap(tile, left.toFloat(), top, bitmapPaint)
                    left += tile.width
                }
            } else {
                canvas.drawText(p.danmaku.text, x.toFloat(), baseline.toFloat(), g.stroke)
                g.fill.color = Color.argb((opacity * 255).roundToInt(), (p.danmaku.color shr 16) and 0xFF,
                    (p.danmaku.color shr 8) and 0xFF, p.danmaku.color and 0xFF)
                canvas.drawText(p.danmaku.text, x.toFloat(), baseline.toFloat(), g.fill)
            }
        }
        if (bottomBand && SystemClock.uptimeMillis() < chipUntil) {
            val chipTop = bandHeight - g.chipInset - g.chipHeight
            chipRect.set(g.chipLeft.toFloat(), chipTop.toFloat(), (g.chipLeft + g.chipWidth).toFloat(), (chipTop + g.chipHeight).toFloat())
            canvas.drawRoundRect(chipRect, g.chipHeight / 2f, g.chipHeight / 2f, chipBg)
            canvas.drawText(chipText, g.chipLeft + g.chipPadding, chipTop + g.chipBaseline, g.chipPaint)
        }
        if (!bottomBand) stats.frame(startedNs, System.nanoTime(), stage.moving && clock.running(SystemClock.uptimeMillis()),
            ticker.hz, renderer.bytes, canvas.isHardwareAccelerated, ticker.skipped)
    }

    private fun canRender(): Boolean = !released && top.isAttachedToWindow && top.isShown && top.windowVisibility == View.VISIBLE

    private fun prepare(nowMs: Long) {
        if (!canRender() || laidOutWidth <= 0) return
        renderer.prepare(comments, stage.current(), clock.position(nowMs))
    }

    private fun requestFrame() {
        handler.removeCallbacks(wake)
        wakeAtMs = 0L
        if (!canRender() || framePending) return
        framePending = true
        choreographer.postFrameCallback(frame)
    }

    private fun cancelFrames() {
        choreographer.removeFrameCallback(frame)
        handler.removeCallbacks(wake)
        framePending = false
        wakeAtMs = 0L
        ticker.stop()
        stats.idle()
    }

    private fun schedule(nowMs: Long, running: Boolean) {
        val fixedEnd = stage.nextFixedEnd()
        val fixedRemaining = fixedEnd?.let { (it - ticker.ticks) * ticker.periodNs / 1_000_000 }
        val delay = nextDanmuFrameDelay(running, stage.moving, stage.hasWaiting, clock.position(nowMs),
            stage.nextTimeMs, fixedRemaining, chipUntil - nowMs)
        if (delay == 0L) { requestFrame(); return }
        stats.idle()
        handler.removeCallbacks(wake)
        wakeAtMs = 0L
        if (delay != null) {
            wakeAtMs = SystemClock.uptimeMillis() + delay
            handler.postDelayed(wake, delay)
        }
    }

    /** The display's figure; [DanmuTicker] corrects it from measured vsyncs when they disagree. */
    private fun updateRefreshRate() {
        val hz = displayRefreshHz(top) ?: return
        val changes = ticker.periodChanges
        ticker.reported(DanmuTicker.periodOf(hz))
        if (ticker.periodChanges != changes) retime()
    }

    /** The step pattern for this width and rate; visible comments keep their place. */
    private fun retime() {
        if (laidOutWidth <= 0) return
        stage.retime(DanmuStage.speedFor(laidOutWidth, ticker.periodNs), ticker.periodNs / 1e6, ticker.ticks)
    }

    private fun startListening() {
        if (listening || displays == null) return
        displays.registerDisplayListener(displayListener, handler)
        listening = true
    }

    private fun stopListening() {
        if (!listening) return
        displays?.unregisterDisplayListener(displayListener)
        listening = false
    }

    private fun geometryFor(width: Int): Geometry? {
        if (width <= 0) return null
        geometry?.takeIf { it.width == width }?.let { return it }
        return Geometry(width, style, rows, hasBottom, chipText, opacity).also { geometry = it }
    }

    /**
     * Every size in whole screen pixels for one width (1920 design pixels across). Measured once per
     * width on the UI thread: a handful of font metrics and the chip label, never per frame.
     */
    private class Geometry(val width: Int, style: DanmuStyle, rows: Int, hasBottom: Boolean, chipText: String, opacity: Float) {
        val scale = width / DESIGN_WIDTH
        val fill = danmuTextPaint(style.fontSize * scale)
        val stroke = Paint(fill).apply {
            this.style = Paint.Style.STROKE
            strokeWidth = danmuStrokeWidth(scale)
            strokeJoin = Paint.Join.ROUND
            color = Color.argb((DANMU_OUTLINE_ALPHA * opacity).roundToInt(), 0, 0, 0)
        }
        val metrics = DanmuMetrics.of(fill, stroke.strokeWidth)
        private val gap = (style.lineGap * scale).roundToInt()
        private val pitch = metrics.lineHeight + gap
        private val topY = (style.topMargin * scale).roundToInt()
        private val bottomMargin = (BOTTOM_MARGIN * scale).roundToInt()
        val gapPx = (SCROLL_GAP * scale).roundToInt()
        val topHeight = topY + rows * pitch - gap
        val chipPaint = danmuTextPaint(20 * scale).apply { color = Color.WHITE }
        val chipHeight = (44 * scale).roundToInt()
        val chipInset = (110 * scale).roundToInt()
        val chipLeft = (96 * scale).roundToInt()
        val chipPadding = 18 * scale
        val chipWidth = ceil(chipPaint.measureText(chipText) + 36 * scale).toInt()
        val chipBaseline = chipPaint.fontMetrics.let { chipHeight / 2f - (it.ascent + it.descent) / 2 }
        val bottomHeight = maxOf(chipInset + chipHeight, if (hasBottom) bottomMargin + rows * pitch - gap else 0)

        fun rowTop(band: DanmuBand, row: Int, bandHeight: Int): Int =
            if (band == DanmuBand.Top) topY + row * pitch else bandHeight - bottomMargin - metrics.lineHeight - row * pitch
    }

    private companion object {
        const val CHIP_MS = 4_000L
        const val DESIGN_WIDTH = 1920f
        const val BOTTOM_MARGIN = 4
        const val SCROLL_GAP = 48
    }
}

/** One band's passive View: it measures its own height and draws what the engine laid out. */
@SuppressLint("ViewConstructor")
internal class DanmuBandView(context: Context, private val engine: DanmuEngine, val band: DanmuBand) : View(context) {
    // View's constructor can call the visibility hooks before this class's fields are initialized.
    private var ready = false
    init { ready = true }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, engine.bandHeight(band, width))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (band == DanmuBand.Top) engine.layout(w)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (band == DanmuBand.Top) engine.attached()
    }

    override fun onDetachedFromWindow() {
        if (band == DanmuBand.Top) engine.detached()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (ready && band == DanmuBand.Top) engine.visibilityChanged()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (ready && band == DanmuBand.Top) engine.visibilityChanged()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        engine.draw(band, canvas, height)
    }
}
