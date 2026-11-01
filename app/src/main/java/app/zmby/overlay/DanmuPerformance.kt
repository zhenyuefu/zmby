package app.zmby.overlay

import app.zmby.BuildConfig
import app.zmby.log.ZLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Debug aggregates only: CPU draw time and moving-frame gaps, not GPU/presentation deadlines. */
internal class DanmuPerformance(private val scope: CoroutineScope) {
    private val draws = LongArray(if (BuildConfig.DEBUG) 600 else 0)
    private val gaps = LongArray(draws.size)
    private var count = 0
    private var index = 0
    private var previousFrameNs = 0L
    private var lastLogNs = 0L
    private var lastSampleMs = 0L
    private var sampleGapMs = 0L
    private var correctionMs = 0L

    fun idle() { previousFrameNs = 0L }

    fun sample(atMs: Long, correction: Long) {
        if (!BuildConfig.DEBUG) return
        if (lastSampleMs > 0) sampleGapMs = maxOf(sampleGapMs, atMs - lastSampleMs)
        lastSampleMs = atMs
        correctionMs = maxOf(correctionMs, abs(correction))
    }

    fun frame(startNs: Long, endNs: Long, moving: Boolean, hz: Float, bytes: Long, accelerated: Boolean) {
        if (!BuildConfig.DEBUG) return
        draws[index] = endNs - startNs
        gaps[index] = if (moving && previousFrameNs > 0) startNs - previousFrameNs else 0L
        previousFrameNs = if (moving) startNs else 0L
        index = (index + 1) % draws.size
        count = (count + 1).coerceAtMost(draws.size)
        if (lastLogNs == 0L) lastLogNs = endNs
        if (endNs - lastLogNs < 10_000_000_000L) return
        val drawBatch = draws.copyOf(count)
        val gapBatch = gaps.copyOf(count)
        val sampleGap = sampleGapMs
        val correction = correctionMs
        lastLogNs = endNs
        sampleGapMs = 0
        correctionMs = 0
        scope.launch(Dispatchers.Default) {
            drawBatch.sort()
            gapBatch.sort()
            fun percentile(values: LongArray, p: Double): Double = values[((values.size - 1) * p).toInt()] / 1_000_000.0
            ZLog.d("DanmuPerf", "drawMs p95=${percentile(drawBatch, .95)} p99=${percentile(drawBatch, .99)} " +
                "movingGapMs p99=${percentile(gapBatch, .99)} hz=$hz sampleGapMaxMs=$sampleGap " +
                "correctionMaxMs=$correction bitmapBytes=$bytes accelerated=$accelerated")
        }
    }
}
