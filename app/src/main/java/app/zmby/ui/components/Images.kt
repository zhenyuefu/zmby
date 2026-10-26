package app.zmby.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import coil.ImageLoader
import coil.memory.MemoryCache
import coil.request.Disposable
import coil.request.ImageRequest
import coil.size.Size as CoilSize
import coil.transform.Transformation

/*
 * Image requests shared by what is drawn and what is fetched ahead (home hero, P4): Coil finds a
 * prefetched image in its memory cache only when the request is the same — data, size and
 * transformations — so both sides build it here.
 */

/** A title Logo fitted into [width]×[height] px, dark ones lightened ([LogoContrast]). */
fun logoRequest(context: Context, url: String, width: Int, height: Int): ImageRequest =
    ImageRequest.Builder(context)
        .data(url)
        .size(width, height)
        .transformations(LogoContrast)
        // Explicit, so a composable can look the decoded Logo up before its first frame (TitleLogo).
        .memoryCacheKey(logoCacheKey(url, width, height))
        .build()

fun logoCacheKey(url: String, width: Int, height: Int) = MemoryCache.Key("logo:${width}x$height:$url")

/** A full-screen backdrop: the size the backdrop layer lays it out at, so the cached copy fits. */
fun backdropRequest(context: Context, url: String, width: Int, height: Int): ImageRequest =
    ImageRequest.Builder(context).data(url).size(width, height).build()

/**
 * Starts loading [request] unless its result is already in the memory cache or the same key is
 * still loading ([inFlight], keyed by the memory cache key). [then] runs once it is there (also when
 * it already was): a second request for the same file then reads Coil's disk cache instead of
 * downloading it again.
 */
fun prefetch(
    loader: ImageLoader,
    request: ImageRequest,
    key: MemoryCache.Key,
    inFlight: MutableMap<MemoryCache.Key, Disposable>,
    then: (() -> Unit)? = null,
) {
    if (loader.memoryCache?.get(key) != null) {
        then?.invoke()
        return
    }
    if (inFlight[key]?.isDisposed == false) return
    val done = then ?: return run { inFlight[key] = loader.enqueue(request) }
    inFlight[key] = loader.enqueue(request.newBuilder().listener(onSuccess = { _, _ -> done() }, onError = { _, _ -> done() }).build())
}

/**
 * Dark Logos are lightened (ui-spec §1.3). Some servers carry clearlogos drawn in black on a
 * transparent ground (2026-10-25: 血谜拼图, pure black strokes): on the dark page they load fine and
 * show nothing, so the title seemed to be missing. Such a Logo is turned into its grey negative
 * (black → white, dark red → light grey), alpha kept; a Logo with nothing visible at all fails,
 * and the text title stays.
 */
object LogoContrast : Transformation {
    override val cacheKey = "zmby-logo-contrast-v1"

    override suspend fun transform(input: Bitmap, size: CoilSize): Bitmap {
        val pixels = IntArray(input.width * input.height)
        input.getPixels(pixels, 0, input.width, 0, 0, input.width, input.height)
        return when (LogoTone.of(pixels)) {
            LogoTone.Blank -> throw IllegalStateException("the Logo has no visible pixels")
            LogoTone.Light -> input
            LogoTone.Dark -> {
                val out = Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(GREY_NEGATIVE) }
                Canvas(out).drawBitmap(input, 0f, 0f, paint)
                out
            }
        }
    }

    /** RGB → 255 − luma (Rec. 709 weights) in every channel; alpha unchanged. */
    private val GREY_NEGATIVE = ColorMatrix(
        floatArrayOf(
            -0.2126f, -0.7152f, -0.0722f, 0f, 255f,
            -0.2126f, -0.7152f, -0.0722f, 0f, 255f,
            -0.2126f, -0.7152f, -0.0722f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
}

/** How a Logo reads on the dark page: [Light] as it is, [Dark] needs lightening, [Blank] shows nothing. */
enum class LogoTone {
    Light, Dark, Blank;

    companion object {
        /** Mean linear luminance below this (about 2:1 contrast on the page colour) is too dark to read. */
        const val DARK = 0.06

        /** Fewer covered pixels than this share is a Logo with nothing in it. */
        const val BLANK = 0.002

        /** [pixels] are ARGB (Android's `getPixels`, not premultiplied); luminance is weighted by alpha. */
        fun of(pixels: IntArray): LogoTone {
            if (pixels.isEmpty()) return Blank
            var coverage = 0.0
            var light = 0.0
            for (p in pixels) {
                val a = (p ushr 24) / 255.0
                if (a == 0.0) continue
                coverage += a
                light += a * luminance(p)
            }
            if (coverage / pixels.size < BLANK) return Blank
            return if (light / coverage < DARK) Dark else Light
        }

        private fun luminance(p: Int): Double =
            0.2126 * linear((p shr 16) and 0xFF) + 0.7152 * linear((p shr 8) and 0xFF) + 0.0722 * linear(p and 0xFF)

        private fun linear(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
    }
}
