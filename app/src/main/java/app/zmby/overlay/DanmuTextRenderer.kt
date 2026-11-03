package app.zmby.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import app.zmby.danmu.DanmuRenderCache
import app.zmby.danmu.DanmuStage
import app.zmby.danmu.DanmuTextKey
import app.zmby.danmu.Danmaku
import app.zmby.danmu.Placed
import app.zmby.log.ZLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil

internal data class DanmuFontLayout(val ascent: Float, val chipWidth: Float, val chipBaseline: Float)
internal data class DanmuTexture(val bitmap: Bitmap, val insetX: Float, val baseline: Float)

/**
 * UI-thread cache with a cancellable background preparer. Only the active comments and the next
 * three seconds are considered. A byte budget admits visible text first; the rest still gets a
 * width measurement and falls back to drawText. No measurement or bitmap allocation happens in draw.
 */
internal class DanmuTextRenderer(
    private val scope: CoroutineScope,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val fontSize: Int = 34,
    private val onReady: () -> Unit,
) {
    private data class Request(val comments: List<Danmaku>, val active: List<Danmaku>, val positionMs: Long)
    private data class Plan(val widths: Map<String, Float>, val admitted: Set<DanmuTextKey>, val font: DanmuFontLayout)
    private val widths = DanmuRenderCache<String, Float>(0, MAX_WIDTHS)
    private val textures = DanmuRenderCache<DanmuTextKey, DanmuTexture>(MAX_BYTES, MAX_TEXTURES)
    private var job: Job? = null
    private var pending: Request? = null
    private var lastRequest: Request? = null
    private var generation = 0
    private var scale = 1f
    private var chipText = ""
    var font: DanmuFontLayout? = null
        private set
    val bytes: Long get() = textures.bytes

    fun width(d: Danmaku): Float? = widths[d.text]
    fun texture(key: DanmuTextKey): DanmuTexture? = textures[key]

    fun configure(scale: Float, chipText: String) {
        clear()
        this.scale = scale
        this.chipText = chipText
    }

    fun clear() {
        generation++
        job?.cancel()
        job = null
        pending = null
        lastRequest = null
        widths.clear()
        textures.clear()
        font = null
    }

    fun prepare(comments: List<Danmaku>, active: List<Placed>, positionMs: Long) {
        val request = Request(comments, active.map { it.danmaku }, positionMs)
        if (request == lastRequest && pending == null) return
        if (job?.isActive == true) { pending = request; return }
        launch(request)
    }

    private fun launch(request: Request) {
        lastRequest = request
        val version = generation
        val knownWidths = widths.snapshot()
        val painterScale = scale
        val chip = chipText
        job = scope.launch {
            try {
                val painter = withContext(workerDispatcher) { TextPainter(painterScale, chip, fontSize) }
                val plan = withContext(workerDispatcher) {
                    val needed = LinkedHashSet<DanmuTextKey>()
                    for (d in request.active) needed += DanmuTextKey(d.text, d.color)
                    var i = lowerBound(request.comments, request.positionMs - DanmuStage.LATE_MS)
                    while (i < request.comments.size) {
                        val d = request.comments[i++]
                        if (d.timeMs > request.positionMs + LOOK_AHEAD_MS) break
                        needed += DanmuTextKey(d.text, d.color)
                    }
                    val measured = LinkedHashMap<String, Float>()
                    val admitted = LinkedHashSet<DanmuTextKey>()
                    var bytes = 0L
                    for (key in needed) {
                        currentCoroutineContext().ensureActive()
                        val width = measured.getOrPut(key.text) { knownWidths[key.text] ?: painter.measure(key.text) }
                        val cost = painter.byteCount(width)
                        if (admitted.size < MAX_TEXTURES && cost > 0 && bytes + cost <= MAX_BYTES) {
                            admitted += key
                            bytes += cost
                        }
                    }
                    Plan(measured, admitted, painter.font)
                }
                if (version != generation) return@launch
                textures.retain(plan.admitted)
                var changed = font != plan.font
                for ((text, width) in plan.widths) {
                    if (widths[text] != width) changed = true
                    widths.put(text, width, 0)
                }
                font = plan.font
                if (changed) onReady()
                for (key in plan.admitted) {
                    if (textures[key] != null) continue
                    val texture = withContext(workerDispatcher) {
                        currentCoroutineContext().ensureActive()
                        painter.render(key, plan.widths.getValue(key.text))
                    } ?: continue
                    if (version != generation) return@launch
                    textures.put(key, texture, texture.bitmap.allocationByteCount.toLong())
                    onReady()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ZLog.w("Danmu", "text preparation failed", e)
            } finally {
                if (version == generation) {
                    job = null
                    pending?.also { pending = null }?.let { launch(it) }
                }
            }
        }
    }

    private class TextPainter(scale: Float, chipText: String, fontSize: Int) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = fontSize * scale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        private val stroke = Paint(fill).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3 * scale
            strokeJoin = Paint.Join.ROUND
            color = Color.argb(217, 0, 0, 0)
        }
        private val metrics = fill.fontMetrics
        private val padding = ceil(3 * scale + 2).toInt()
        private val height = ceil(metrics.bottom - metrics.top).toInt() + padding * 2
        private val baseline = padding - metrics.top
        val font: DanmuFontLayout

        init {
            val chipPaint = Paint(fill).apply { textSize = 20 * scale }
            val chipMetrics = chipPaint.fontMetrics
            font = DanmuFontLayout(-metrics.ascent, chipPaint.measureText(chipText) + 36 * scale,
                -(chipMetrics.ascent + chipMetrics.descent) / 2)
        }

        fun measure(text: String): Float = fill.measureText(text)
        private fun width(advance: Float): Int = ceil(advance).toInt() + padding * 2
        fun byteCount(advance: Float): Long = if (width(advance) in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION) {
            width(advance).toLong() * height * 4
        } else 0

        fun render(key: DanmuTextKey, advance: Float): DanmuTexture? {
            // Keep enough headroom for the current frame and the background allocation.
            return try {
                val bitmap = createBitmap(width(advance), height)
                val canvas = Canvas(bitmap)
                fill.color = key.color or (0xFF shl 24)
                canvas.drawText(key.text, padding.toFloat(), baseline, stroke)
                canvas.drawText(key.text, padding.toFloat(), baseline, fill)
                DanmuTexture(bitmap, padding.toFloat(), baseline)
            } catch (_: OutOfMemoryError) {
                null // Measured text can still be drawn directly; a cache is optional.
            }
        }
    }

    companion object {
        const val MAX_BYTES = 8L * 1024 * 1024
        const val LOOK_AHEAD_MS = 3_000L
        private const val MAX_TEXTURES = 128
        private const val MAX_WIDTHS = 512
        private const val MAX_DIMENSION = 8192

        private fun lowerBound(comments: List<Danmaku>, timeMs: Long): Int {
            var lo = 0
            var hi = comments.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (comments[mid].timeMs < timeMs) lo = mid + 1 else hi = mid
            }
            return lo
        }
    }
}
