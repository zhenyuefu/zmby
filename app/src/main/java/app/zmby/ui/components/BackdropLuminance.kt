package app.zmby.ui.components

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow

/** Integral luminance of the tiny decoded backdrop, built off the UI thread. */
class BackdropLuminance private constructor(
    private val width: Int, private val height: Int, private val sums: FloatArray,
) {
    /** Average in normalized image coordinates; each card samples only its own background. */
    fun average(left: Float, top: Float, right: Float, bottom: Float): Float {
        val x0 = floor(left * width).toInt().coerceIn(0, width - 1)
        val y0 = floor(top * height).toInt().coerceIn(0, height - 1)
        val x1 = ceil(right * width).toInt().coerceIn(x0 + 1, width)
        val y1 = ceil(bottom * height).toInt().coerceIn(y0 + 1, height)
        fun at(x: Int, y: Int) = sums[y * (width + 1) + x]
        return (at(x1, y1) - at(x0, y1) - at(x1, y0) + at(x0, y0)) / ((x1 - x0) * (y1 - y0))
    }

    companion object {
        fun fromPixels(pixels: IntArray, width: Int, height: Int): BackdropLuminance {
            require(width > 0 && height > 0 && pixels.size == width * height)
            val linear = FloatArray(256) { value ->
                val s = value / 255f
                if (s <= 0.04045f) s / 12.92f else ((s + 0.055f) / 1.055f).pow(2.4f)
            }
            val sums = FloatArray((width + 1) * (height + 1))
            for (y in 0 until height) {
                var row = 0f
                for (x in 0 until width) {
                    val pixel = pixels[y * width + x]
                    row += 0.2126f * linear[pixel ushr 16 and 255] + 0.7152f * linear[pixel ushr 8 and 255] + 0.0722f * linear[pixel and 255]
                    sums[(y + 1) * (width + 1) + x + 1] = row + sums[y * (width + 1) + x + 1]
                }
            }
            return BackdropLuminance(width, height, sums)
        }
    }
}
