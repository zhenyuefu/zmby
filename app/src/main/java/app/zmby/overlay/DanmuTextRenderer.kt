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

/**
 * A prepared text image in [tiles] side by side, each at most [DanmuTextRenderer.TILE_PX] wide: long
 * comments on a 4K interface are wider than the 2048–4096 px texture limit of TV GPUs. The text's
 * origin sits at whole pixels ([insetX], [baseline]) inside the first tile; tiles are drawn at whole
 * pixels without filtering, so the joins are seamless.
 */
internal class DanmuTexture(val tiles: Array<Bitmap>, val insetX: Int, val baseline: Int) {
    val width: Int get() = tiles.sumOf { it.width }
    val bytes: Long get() = tiles.sumOf { it.allocationByteCount.toLong() }
}

/**
 * What the stage keeps for an admitted comment: its advance in whole pixels and its texture, or none
 * when it is drawn as text for its whole life (over budget or not ready in time).
 */
internal class DanmuGlyph(val widthPx: Int, val texture: DanmuTexture?)

/** Line metrics in whole pixels: a row is [lineHeight] tall and its baseline [baseline] below its top. */
internal data class DanmuMetrics(val lineHeight: Int, val baseline: Int) {
    companion object {
        fun of(paint: Paint, strokeWidth: Float): DanmuMetrics {
            val fm = paint.fontMetrics
            val outline = ceil(strokeWidth / 2).toInt()
            val ascent = ceil(-fm.ascent).toInt()
            return DanmuMetrics(ascent + ceil(fm.descent).toInt() + outline * 2, outline + ascent)
        }
    }
}

/** The one 弹幕 face: system default sans serif, bold, with the glyph fallback the system provides. */
internal fun danmuTextPaint(textSize: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
    this.textSize = textSize
    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
}

internal fun danmuStrokeWidth(scale: Float): Float = 3 * scale
internal const val DANMU_OUTLINE_ALPHA = 217

/**
 * UI-thread cache with a cancellable background preparer (after AkDanmaku's CacheManager): only the
 * comments on screen or waiting, and the next three seconds, are considered. A byte budget admits
 * those first; the rest still get a width and are drawn as text. The budget scales with the pixel
 * area, so a 4K interface caches as many comments as 1080p. Each new bitmap is handed to
 * [Bitmap.prepareToDraw] on the worker, which starts its GPU upload before the comment's first frame
 * instead of inside it. No measurement or bitmap allocation happens on the UI thread.
 */
internal class DanmuTextRenderer(
    private val scope: CoroutineScope,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val fontSize: Int = 34,
    private val onReady: () -> Unit,
) {
    private data class Request(val comments: List<Danmaku>, val current: List<Danmaku>, val positionMs: Long)
    private class Plan(val widths: Map<String, Float>, val admitted: Set<DanmuTextKey>)
    private val widths = DanmuRenderCache<String, Float>(0, MAX_WIDTHS)
    private var textures = DanmuRenderCache<DanmuTextKey, DanmuTexture>(BASE_BYTES, MAX_TEXTURES)
    /** Text the current plan will draw a bitmap for; a comment briefly waits for it. */
    private var planned: Set<DanmuTextKey> = emptySet()
    private var job: Job? = null
    private var pending: Request? = null
    private var lastRequest: Request? = null
    private var generation = 0
    private var scale = 1f
    val bytes: Long get() = textures.bytes
    /** The bitmap budget for the current scale. */
    val maxBytes: Long get() = textures.maxBytes

    fun width(d: Danmaku): Float? = widths[d.text]
    fun texture(key: DanmuTextKey): DanmuTexture? = textures[key]

    /**
     * The glyph for a comment about to enter, or null to let it wait: its width is not known yet, or
     * its planned bitmap is still being drawn and it has not waited [TEXTURE_WAIT_MS].
     */
    fun glyph(d: Danmaku, ageMs: Long): DanmuGlyph? {
        val width = widths[d.text] ?: return null
        val key = DanmuTextKey(d.text, d.color)
        val texture = textures[key]
        if (texture == null && ageMs < TEXTURE_WAIT_MS && key in planned) return null
        return DanmuGlyph(ceil(width).toInt(), texture)
    }

    /** A new text scale: everything is dropped, and the budget follows the screen's pixel area. */
    fun configure(scale: Float) {
        clear()
        this.scale = scale
        textures = DanmuRenderCache(budgetFor(scale), MAX_TEXTURES)
    }

    fun clear() {
        generation++
        job?.cancel()
        job = null
        pending = null
        lastRequest = null
        planned = emptySet()
        widths.clear()
        textures.clear()
    }

    fun prepare(comments: List<Danmaku>, current: List<Danmaku>, positionMs: Long) {
        val request = Request(comments, current, positionMs)
        if (request == lastRequest && pending == null) return
        if (job?.isActive == true) { pending = request; return }
        launch(request)
    }

    private fun launch(request: Request) {
        lastRequest = request
        val version = generation
        val knownWidths = widths.snapshot()
        val painterScale = scale
        val budget = textures.maxBytes
        job = scope.launch {
            try {
                val painter = withContext(workerDispatcher) { TextPainter(painterScale, fontSize) }
                val plan = withContext(workerDispatcher) {
                    val needed = LinkedHashSet<DanmuTextKey>()
                    for (d in request.current) needed += DanmuTextKey(d.text, d.color)
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
                        if (admitted.size < MAX_TEXTURES && cost > 0 && bytes + cost <= budget) {
                            admitted += key
                            bytes += cost
                        }
                    }
                    Plan(measured, admitted)
                }
                if (version != generation) return@launch
                textures.retain(plan.admitted)
                planned = plan.admitted
                var changed = false
                for ((text, width) in plan.widths) {
                    if (widths[text] != width) changed = true
                    widths.put(text, width, 0)
                }
                if (changed) onReady()
                for (key in plan.admitted) {
                    if (textures[key] != null) continue
                    val texture = withContext(workerDispatcher) {
                        currentCoroutineContext().ensureActive()
                        painter.render(key, plan.widths.getValue(key.text))
                    } ?: continue
                    if (version != generation) return@launch
                    textures.put(key, texture, texture.bytes)
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

    private class TextPainter(scale: Float, fontSize: Int) {
        private val fill = danmuTextPaint(fontSize * scale)
        private val stroke = Paint(fill).apply {
            style = Paint.Style.STROKE
            strokeWidth = danmuStrokeWidth(scale)
            strokeJoin = Paint.Join.ROUND
            color = Color.argb(DANMU_OUTLINE_ALPHA, 0, 0, 0)
        }
        private val metrics = fill.fontMetrics
        private val padding = ceil(stroke.strokeWidth / 2).toInt() + 2
        // Whole-pixel origin: the bitmap is drawn at whole pixels, so the text is never resampled.
        private val baseline = padding + ceil(-metrics.top).toInt()
        private val height = baseline + ceil(metrics.bottom).toInt() + padding

        fun measure(text: String): Float = fill.measureText(text)
        private fun width(advance: Float): Int = ceil(advance).toInt() + padding * 2
        fun byteCount(advance: Float): Long = if (width(advance) in 1..MAX_WIDTH && height in 1..TILE_PX) {
            width(advance).toLong() * height * 4
        } else 0

        fun render(key: DanmuTextKey, advance: Float): DanmuTexture? {
            val width = width(advance)
            return try {
                fill.color = key.color or (0xFF shl 24)
                val tiles = Array((width + TILE_PX - 1) / TILE_PX) { i ->
                    val left = i * TILE_PX
                    createBitmap(minOf(TILE_PX, width - left), height).also { bitmap ->
                        // The whole line, shifted by a whole number of pixels: each tile holds exactly
                        // its columns of the same rendering.
                        val canvas = Canvas(bitmap)
                        canvas.translate(-left.toFloat(), 0f)
                        canvas.drawText(key.text, padding.toFloat(), baseline.toFloat(), stroke)
                        canvas.drawText(key.text, padding.toFloat(), baseline.toFloat(), fill)
                        // Starts the texture upload on the render thread now, ahead of the first frame.
                        bitmap.prepareToDraw()
                    }
                }
                DanmuTexture(tiles, padding, baseline)
            } catch (_: OutOfMemoryError) {
                null // Measured text can still be drawn directly; a cache is optional.
            }
        }
    }

    companion object {
        /** The budget at the 1920-wide design size; it grows with the pixel area up to [MAX_BUDGET]. */
        const val BASE_BYTES = 8L * 1024 * 1024
        /** 4K holds as many comments as 1080p does in [BASE_BYTES]; 8K is capped here. */
        const val MAX_BUDGET = 32L * 1024 * 1024
        /** No wider than the texture size every GLES 3 GPU supports. */
        const val TILE_PX = 2048
        const val LOOK_AHEAD_MS = 3_000L
        /** How long a due comment may wait for its planned bitmap before it is drawn as text. */
        const val TEXTURE_WAIT_MS = 300L
        private const val MAX_TEXTURES = 128
        private const val MAX_WIDTHS = 512
        private const val MAX_WIDTH = 16 * TILE_PX

        fun budgetFor(scale: Float): Long =
            (BASE_BYTES * scale.toDouble() * scale).toLong().coerceIn(BASE_BYTES, MAX_BUDGET)

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
