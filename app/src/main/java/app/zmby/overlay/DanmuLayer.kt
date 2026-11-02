package app.zmby.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import android.view.Choreographer
import app.zmby.danmu.DanmuClock
import app.zmby.danmu.DanmuStage
import app.zmby.danmu.nextDanmuFrameDelay
import app.zmby.danmu.Danmaku
import app.zmby.log.ZLog
import app.zmby.playback.MonitorOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 弹幕 on the overlay's passive layer (ui-spec §2.16, danmu design): a full-screen window that never
 * takes focus or touches, added once the comments are loaded. Samples come from the playback
 * monitor through [PlaybackOverlay].
 */
class DanmuLayer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val comments: Deferred<List<Danmaku>?>,
    private val rows: Int,
    /** 20–100. */
    private val opacity: Int,
) : MonitorOverlay {
    private val window = OverlayWindow(context, overlayParams(focusable = false))
    private var view: DanmuView? = null
    private var job: Job? = null
    private var closed = false
    /** The last sample, for a view added after it. */
    private var lastSample: Triple<Long, Boolean, Long>? = null

    fun start() {
        job = scope.launch {
            val list = try { comments.await() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (closed || list.isNullOrEmpty()) return@launch
            val v = DanmuView(context, scope, list, rows, opacity / 100f)
            lastSample?.let { (pos, playing, at) -> v.sample(pos, playing, at) }
            if (window.show { v }) view = v
            else ZLog.w(TAG, "danmu window not shown")
        }
    }

    override fun onSample(positionMs: Long, durationMs: Long, playing: Boolean) {
        val now = SystemClock.uptimeMillis()
        lastSample = Triple(positionMs, playing, now)
        view?.sample(positionMs, playing, now)
    }

    override fun onSeeked(positionMs: Long) {
        val now = SystemClock.uptimeMillis()
        lastSample = Triple(positionMs, lastSample?.second ?: false, now)
        view?.seeked(positionMs, now)
    }

    override fun onLost() {
        closed = true
        job?.cancel()
        comments.cancel()
        view?.release()
        window.hide()
        view = null
    }

    private companion object { const val TAG = "Danmu" }
}

/** One passive View, with a VSync loop only while text moves and background-prepared text. */
@SuppressLint("ViewConstructor")
class DanmuView(
    context: Context,
    scope: CoroutineScope,
    private val comments: List<Danmaku>,
    private val rows: Int,
    private val opacity: Float,
    workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : View(context) {
    val clock = DanmuClock()
    private var scale = 1f
    private var viewportWidth = 0
    private var stage: DanmuStage? = null
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val stroke = Paint(fill).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = (opacity * 255).toInt() }
    private val chipText = "弹幕 · ${comments.size} 条"
    private val chipPaint = Paint(fill).apply { color = Color.WHITE }
    private val chipBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(184, 0, 0, 0) }
    private val chipRect = RectF()
    private var chipBaseline = 0f
    private var chipUntil = 0L
    private var framePending = false
    private var wakeAtMs = 0L
    private var released = false
    private val stats = DanmuPerformance(scope)
    internal val hasPendingFrame: Boolean get() = framePending
    internal val nextWakeMs: Long get() = wakeAtMs
    private val renderer = DanmuTextRenderer(scope, workerDispatcher) {
        stage?.refreshWidths()
        updateChipGeometry()
        requestFrame()
    }
    private val choreographer = Choreographer.getInstance()
    private var frameTimeMs: Long? = null
    private val frame = Choreographer.FrameCallback { timeNs ->
        framePending = false
        frameTimeMs = timeNs / 1_000_000
        if (canRender()) invalidate()
    }
    private val wake = Runnable {
        wakeAtMs = 0L
        requestFrame()
    }

    fun sample(positionMs: Long, playing: Boolean, nowMs: Long) {
        if (released) return
        val wasRunning = clock.running(nowMs)
        val jumped = clock.sample(positionMs, playing, nowMs)
        stats.sample(nowMs, clock.lastCorrectionMs)
        if (jumped) {
            stage?.reset(positionMs)
            renderer.clear()
        }
        prepare()
        val current = stage
        if (jumped || wasRunning != clock.running(nowMs) || clock.running(nowMs) && current?.active?.isNotEmpty() == true ||
            current?.nextTimeMs?.let { it <= positionMs } == true) requestFrame()
        else scheduleNext(nowMs)
    }

    fun seeked(positionMs: Long, nowMs: Long) {
        if (released) return
        clock.seek(positionMs, nowMs)
        stage?.reset(positionMs)
        renderer.clear()
        prepare()
        requestFrame()
    }

    fun release() {
        released = true
        cancelFrames()
        renderer.clear()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (released) return
        chipUntil = SystemClock.uptimeMillis() + CHIP_MS
        prepare()
        requestFrame()
    }

    override fun onDetachedFromWindow() {
        cancelFrames()
        renderer.clear()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // View's constructor can call this before this class's fields are initialized.
        if (ready) updateVisibility()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (ready) updateVisibility()
    }

    private var ready = false
    init { ready = true }

    private fun updateVisibility() {
        if (canRender()) { prepare(); requestFrame() } else { cancelFrames(); renderer.clear() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0 || released) return
        viewportWidth = w
        scale = w / 1920f
        fill.textSize = 34 * scale
        fill.alpha = (opacity * 255).toInt()
        stroke.textSize = fill.textSize
        stroke.strokeWidth = 3 * scale
        stroke.color = Color.argb((opacity * 0.85f * 255).toInt(), 0, 0, 0)
        chipPaint.textSize = 20 * scale
        renderer.configure(scale, chipText)
        val current = stage
        if (current == null) {
            stage = DanmuStage(comments, renderer::width, w.toFloat(), rows, 48 * scale)
        } else current.resize(w.toFloat(), 48 * scale)
        prepare()
        requestFrame()
    }

    private fun canRender(): Boolean = !released && isAttachedToWindow && isShown && windowVisibility == VISIBLE

    private fun prepare() {
        if (!canRender() || viewportWidth <= 0) return
        val current = stage ?: return
        renderer.prepare(comments, current.active, clock.position(SystemClock.uptimeMillis()))
    }

    private fun requestFrame() {
        removeCallbacks(wake)
        wakeAtMs = 0L
        if (!canRender() || framePending) return
        framePending = true
        choreographer.postFrameCallback(frame)
    }

    private fun cancelFrames() {
        choreographer.removeFrameCallback(frame)
        frameTimeMs = null
        removeCallbacks(wake)
        framePending = false
        wakeAtMs = 0L
        stats.idle()
    }

    private fun scheduleNext(nowMs: Long) {
        if (!canRender()) return
        val current = stage ?: return
        val running = clock.running(nowMs)
        if (running && current.active.isNotEmpty()) { requestFrame(); return }
        val position = clock.position(nowMs)
        val next = current.nextTimeMs
        val delay = nextDanmuFrameDelay(
            running, current.active.isNotEmpty(), position, next, chipUntil - nowMs,
            waitingForMeasurement = next != null && next <= position && current.active.isEmpty(),
        )
        if (delay == 0L) { requestFrame(); return }
        choreographer.removeFrameCallback(frame)
        framePending = false
        stats.idle()
        if (delay == null) {
            removeCallbacks(wake)
            wakeAtMs = 0L
        } else {
            val deadline = nowMs + delay
            if (wakeAtMs != deadline) {
                removeCallbacks(wake)
                wakeAtMs = deadline
                postDelayed(wake, delay)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        choreographer.removeFrameCallback(frame)
        framePending = false
        if (released) return
        val startedNs = System.nanoTime()
        // Use the display's frame timestamp; time spent waiting in this frame must not change x.
        val now = frameTimeMs ?: SystemClock.uptimeMillis()
        frameTimeMs = null
        val current = stage ?: return
        val renderTime = clock.renderTime(now)
        if (clock.hasSample) current.update(clock.position(now), renderTime)
        val font = renderer.font
        if (font != null) {
            var i = 0
            while (i < current.active.size) {
                val placed = current.active[i++]
                val x = current.x(placed, renderTime)
                val baseline = (ROW_TOP + placed.row * ROW_STEP) * scale + font.ascent
                val cached = renderer.texture(placed.textKey)
                if (cached != null) {
                    canvas.drawBitmap(cached.bitmap, x - cached.insetX, baseline - cached.baseline, bitmapPaint)
                } else {
                    canvas.drawText(placed.danmaku.text, x, baseline, stroke)
                    fill.color = Color.argb((opacity * 255).toInt(), (placed.danmaku.color shr 16) and 0xFF,
                        (placed.danmaku.color shr 8) and 0xFF, placed.danmaku.color and 0xFF)
                    canvas.drawText(placed.danmaku.text, x, baseline, fill)
                }
            }
            if (now < chipUntil) {
                val radius = 22 * scale
                canvas.drawRoundRect(chipRect, radius, radius, chipBg)
                canvas.drawText(chipText, chipRect.left + 18 * scale, chipBaseline, chipPaint)
            }
        }
        stats.frame(startedNs, System.nanoTime(), current.active.isNotEmpty() && clock.running(now),
            display?.refreshRate ?: 60f, renderer.bytes, canvas.isHardwareAccelerated)
        scheduleNext(now)
    }

    private fun updateChipGeometry() {
        val font = renderer.font ?: return
        val top = height - (110 + 44) * scale
        chipRect.set(96 * scale, top, 96 * scale + font.chipWidth, top + 44 * scale)
        chipBaseline = top + 22 * scale + font.chipBaseline
    }

    private companion object {
        const val ROW_TOP = 70f
        const val ROW_STEP = 62f
        const val CHIP_MS = 4_000L
    }
}
