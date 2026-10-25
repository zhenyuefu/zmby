package app.zmby.ui.components

import android.graphics.Bitmap

/**
 * A small CPU blur for the decode-time backdrop copy (a few hundred pixels across, done once per
 * image): three horizontal and vertical box-blur passes, which approximate a Gaussian.
 */
object BoxBlur {
    fun blur(bitmap: Bitmap, radius: Int) {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        blur(px, w, h, radius)
        bitmap.setPixels(px, 0, w, 0, 0, w, h)
    }

    /** Blurs ARGB [px] (row-major, [w]×[h]) in place; edges clamp. */
    fun blur(px: IntArray, w: Int, h: Int, radius: Int) {
        if (radius < 1 || w == 0 || h == 0) return
        val tmp = IntArray(px.size)
        repeat(3) {
            pass(px, tmp, w, h, radius, horizontal = true)
            pass(tmp, px, w, h, radius, horizontal = false)
        }
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val window = 2 * r + 1
        for (line in 0 until lines) {
            fun at(i: Int): Int {
                val c = i.coerceIn(0, len - 1)
                return if (horizontal) line * w + c else c * w + line
            }
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (i in -r..r) {
                val p = src[at(i)]
                a += p ushr 24; rr += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
            }
            for (i in 0 until len) {
                dst[at(i)] = ((a / window) shl 24) or ((rr / window) shl 16) or ((g / window) shl 8) or (b / window)
                val out = src[at(i - r)]
                val inn = src[at(i + r + 1)]
                a += (inn ushr 24) - (out ushr 24)
                rr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                g += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                b += (inn and 0xFF) - (out and 0xFF)
            }
        }
    }
}
