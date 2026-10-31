package app.zmby.ui.components

import androidx.core.graphics.createBitmap
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.zmby.ui.theme.Z
import coil.imageLoader
import coil.memory.MemoryCache
import android.content.Context
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Size as CoilSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import coil.transform.Transformation

/*
 * Liquid glass on API 30 (ui-spec §1.2). The Zidoo has no RenderEffect, so there is no live
 * backdrop blur and no shader refraction (the AGSL lens of Liquid-Glass-Android / Kyant0's backdrop
 * needs API 33). What is left, and cheap: the screen's backdrop is decoded once more as a small,
 * blurred, over-saturated copy; every glass shape draws its own slice of that copy, slightly
 * magnified like a lens, then the glass gradient, a bright rim at the top, a faint inner stroke
 * and a soft shadow. Shapes over the page colour (no backdrop) get the same gradient and rim.
 */

/** The two scrims laid over a backdrop (ui-spec §1.2); the same ones are drawn under each glass slice. */
data class Scrims(
    /** Left→right stops of the page colour's alpha (0.97 → 0.15 by default). */
    val horizontal: List<Pair<Float, Float>> = listOf(0f to 0.97f, 0.4f to 0.8f, 0.75f to 0.15f),
    /** Where the bottom→top scrim starts, as a fraction of the height from the top. */
    val bottomFrom: Float = 1f / 3f,
    /** Fraction of the bottom scrim (from its bottom) that is solid page colour. */
    val bottomSolid: Float = 0.5f,
    val bottomStops: List<Pair<Float, Float>>? = null,
    /** Detail pages fade their directional scrims into uniform dimming as they scroll. */
    val strength: () -> Float = { 1f },
    val dim: () -> Float = { 0f },
) {
    fun DrawScope.draw(area: Size, alpha: Float = 1f) {
        drawRect(Color.Black, size = area, alpha = dim() * alpha)
        val directional = strength() * alpha
        drawRect(
            Brush.horizontalGradient(*horizontal.map { (stop, a) -> stop to Z.bg.copy(alpha = a) }.toTypedArray(), endX = area.width),
            size = area,
            alpha = directional,
        )
        val top = area.height * bottomFrom
        val h = area.height - top
        drawRect(
            Brush.verticalGradient(*(bottomStops ?: listOf(0f to 0f, (1f - bottomSolid) to 1f, 1f to 1f)).map { (stop, a) -> stop to Z.bg.copy(alpha = a) }.toTypedArray(), startY = top, endY = area.height),
            topLeft = Offset(0f, top),
            size = Size(area.width, h),
            alpha = directional,
        )
    }
}

/**
 * The blurred backdrop copy of the current screen, and where the sharp one sits. [visible] and
 * [shift] follow a backdrop that fades and moves up as the page scrolls (home hero): read while
 * drawing, so the glass slices follow without recomposing.
 */
class Backdrop(
    val url: String?,
    val blurred: ImageBitmap?,
    val scrims: Scrims,
    val visible: () -> Float = { 1f },
    val shift: () -> Float = { 0f },
    val slices: List<BackdropSlice>? = null,
    val luminance: BackdropLuminance? = null,
) {
    internal var coordinates: LayoutCoordinates? = null
}

/** A blurred page at the same horizontal position (in screen widths) as its sharp backdrop. */
class BackdropSlice(val image: ImageBitmap?, val offset: () -> Float)

/**
 * Dynamic, not static: the backdrop changes whenever the home hero turns to another title, and a
 * static local recomposes the whole page under it (a likely part of the HomeFeed recompositions the
 * box profile showed while scrolling rows); only the glass shapes read it.
 */
val LocalBackdrop = androidx.compose.runtime.compositionLocalOf<Backdrop?> { null }

/** Makes [url]'s blurred copy available to the glass shapes in [content]; place the sharp one with [BackdropLayer]. */
@Composable
fun BackdropProvider(
    url: String?,
    scrims: Scrims,
    visible: () -> Float = { 1f },
    shift: () -> Float = { 0f },
    slices: List<BackdropSlice>? = null,
    content: @Composable () -> Unit,
) {
    val blurred = if (slices == null) rememberBlurredBackdrop(url) else null
    val luminance by produceState<BackdropLuminance?>(null, blurred) {
        value = withContext(Dispatchers.Default) {
            blurred?.asAndroidBitmap()?.let { bitmap ->
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                BackdropLuminance.fromPixels(pixels, bitmap.width, bitmap.height)
            }
        }
    }
    val backdrop = remember(url, blurred, scrims, visible, shift, slices, luminance) { Backdrop(url, blurred, scrims, visible, shift, slices, luminance) }
    CompositionLocalProvider(LocalBackdrop provides backdrop, content = content)
}

/**
 * The sharp backdrop with its scrims; glass shapes read their slice relative to where this is laid out.
 * [layer] replaces the single image (the home hero's sliding pages); it draws its own scrims.
 */
@Composable
fun BackdropLayer(modifier: Modifier = Modifier, layer: (@Composable BoxScope.() -> Unit)? = null, blurProgress: (() -> Float)? = null) {
    val backdrop = LocalBackdrop.current ?: return
    if (layer != null) {
        Box(modifier.onGloballyPositioned { backdrop.coordinates = it }, content = layer)
        return
    }
    Box(
        modifier
            .onGloballyPositioned { backdrop.coordinates = it }
            // The backdrop and its two full-screen scrims change only with the image: rendered once
            // into an offscreen layer, each frame then draws one textured quad. The Z9X 8K redraws the
            // whole screen every frame (it reports full-screen damage), and these three passes cost
            // about 5 ms of its GPU time per frame while rows scrolled.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
    ) {
        if (backdrop.url != null) {
            Artwork(backdrop.url, Modifier.fillMaxSize())
            if (blurProgress != null && backdrop.blurred != null) {
                Box(Modifier.fillMaxSize().drawWithCache {
                    onDrawBehind { drawCover(backdrop.blurred, size, blurProgress()) }
                })
            }
            Box(Modifier.fillMaxSize().drawWithCache { onDrawBehind { with(backdrop.scrims) { draw(size) } } })
        }
    }
}

/** A screen over a fixed full-screen backdrop; [content]'s glass shapes see the blurred copy. */
@Composable
fun BackdropScreen(
    url: String?,
    scrims: Scrims,
    modifier: Modifier = Modifier,
    visible: () -> Float = { 1f },
    shift: () -> Float = { 0f },
    layer: (@Composable BoxScope.() -> Unit)? = null,
    slices: List<BackdropSlice>? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    BackdropProvider(url, scrims, visible, shift, slices) {
        Box(modifier.fillMaxSize()) {
            BackdropLayer(Modifier.fillMaxSize(), layer)
            content()
        }
    }
}

/**
 * The small blurred copy of a backdrop. The request is shared with prefetching (home hero), and the
 * explicit memory cache key lets a copy decoded before show at once. Changing [url] keeps the old
 * copy until the new one is ready, so the glass never flashes without its slice.
 */
@Composable
fun rememberBlurredBackdrop(url: String?): ImageBitmap? {
    val context = LocalContext.current
    val cached = remember(url) { url?.let { blurredFromMemory(context, it) } }
    val image by produceState(cached, url) {
        if (url == null) {
            value = null
            return@produceState
        }
        if (cached != null) {
            value = cached
            return@produceState
        }
        val result = context.imageLoader.execute(blurredBackdropRequest(context, url))
        value = ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap?.asImageBitmap()
    }
    return image
}

private fun blurredFromMemory(context: Context, url: String): ImageBitmap? =
    context.imageLoader.memoryCache?.get(blurredBackdropKey(url))?.bitmap?.asImageBitmap()

/** The blurred backdrop copy (glass slices); also fetched ahead by the home hero. */
fun blurredBackdropRequest(context: Context, url: String): ImageRequest = ImageRequest.Builder(context)
    .data(url)
    .size(BLUR_WIDTH, BLUR_WIDTH * 9 / 16)
    .allowHardware(false)
    .transformations(BackdropBlur)
    .memoryCacheKey(blurredBackdropKey(url))
    .build()

fun blurredBackdropKey(url: String) = MemoryCache.Key("blur:$url")

private const val BLUR_WIDTH = 192

/**
 * Blur and saturation done once, at decode time, on a ~192-px copy: box blur radius 6, three passes (about 60
 * design px once scaled up, close to the design's blur(30px)), saturation 180%.
 */
private object BackdropBlur : Transformation {
    override val cacheKey = "zmby-backdrop-blur-v1"

    override suspend fun transform(input: Bitmap, size: CoilSize): Bitmap {
        val out = createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.8f) }) }
        Canvas(out).drawBitmap(input, 0f, 0f, paint)
        BoxBlur.blur(out, 6)
        return out
    }
}

/** Glass treatments: resting glass, the focused / selected white glass, or white glass without the lens. */
enum class GlassTone { Glass, On }

/**
 * Paints a glass surface in [shape]: the backdrop slice (when the screen has one), the gradient,
 * the rim and the shadow. [tone] On is the focused or selected state (`glassOn`).
 */
fun Modifier.glass(shape: Shape, tone: GlassTone = GlassTone.Glass, shadow: Boolean = true): Modifier = composed {
    val backdrop = LocalBackdrop.current
    val image = backdrop?.blurred
    val slices = backdrop?.slices
    var offset by remember { mutableStateOf(Offset.Zero) }
    val on = tone == GlassTone.On
    this
        .then(
            if (!shadow) Modifier
            else if (on) Modifier.shadow(24.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.5f), spotColor = Color.Black.copy(alpha = 0.6f))
            else Modifier.shadow(10.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.3f)),
        )
        .then(
            if ((image != null || slices != null) && !on) {
                Modifier.onGloballyPositioned { c ->
                    backdrop.coordinates?.takeIf { it.isAttached && c.isAttached }?.let { offset = it.localPositionOf(c, Offset.Zero) }
                }
            } else Modifier,
        )
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val path = Path().apply { addOutline(outline) }
            val fill = if (on) Brush.verticalGradient(listOf(Z.onTop, Z.onBottom)) else Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.08f)))
            // inset 0 1px 0: the rim is brightest along the top edge and fades within a few pixels.
            val edge = (6.dp.toPx() / size.height).coerceIn(0.02f, 0.5f)
            val rim = Brush.verticalGradient(0f to Color.White.copy(alpha = if (on) 1f else 0.42f), edge to Color.White.copy(alpha = 0f), 1f to Color.White.copy(alpha = if (on) 0f else 0.08f))
            val stroke = Stroke(2.dp.toPx())
            val innerShade = Brush.verticalGradient(0.7f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.10f))
            onDrawBehind {
                clipPath(path) {
                    val seen = if (backdrop != null && !on) backdrop.visible() else 0f
                    if (backdrop != null && seen > 0f && (image != null || slices != null)) {
                        val area = backdrop.coordinates?.size?.let { Size(it.width.toFloat(), it.height.toFloat()) } ?: size
                        // A lens: the slice behind the glass, magnified a little around the glass centre.
                        scale(LENS, pivot = Offset(size.width / 2, size.height / 2)) {
                            translate(-offset.x, -offset.y + backdrop.shift()) {
                                if (slices != null) {
                                    for (slice in slices) {
                                        translate(slice.offset() * area.width, 0f) {
                                            clipRect(0f, 0f, area.width, area.height) {
                                                drawRect(Z.bg, size = area, alpha = seen)
                                                slice.image?.let { drawCover(it, area, seen) }
                                                with(backdrop.scrims) { draw(area, seen) }
                                            }
                                        }
                                    }
                                } else if (image != null) {
                                    drawCover(image, area, seen)
                                    with(backdrop.scrims) { draw(area, seen) }
                                }
                            }
                        }
                    }
                    drawRect(fill)
                    if (on) drawRect(innerShade)
                    // Strokes centred on the edge; the clip keeps their inner half (an inset line).
                    drawOutline(outline, Color.White.copy(alpha = if (on) 0f else 0.10f), style = stroke)
                    drawOutline(outline, rim, style = stroke)
                }
            }
        }
}

private const val LENS = 1.06f

/** Draws [image] scaled to cover [area] (ContentScale.Crop, centred), like the sharp backdrop. */
internal fun DrawScope.drawCover(image: ImageBitmap, area: Size, alpha: Float = 1f) {
    val s = maxOf(area.width / image.width, area.height / image.height)
    val w = image.width * s
    val h = image.height * s
    drawImage(
        image,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(image.width, image.height),
        dstOffset = IntOffset(((area.width - w) / 2).toInt(), ((area.height - h) / 2).toInt()),
        dstSize = IntSize(w.toInt(), h.toInt()),
        alpha = alpha,
        filterQuality = FilterQuality.Low,
    )
}

/** Sunken track (`.well`): segmented controls, toggles, the search field. */
fun Modifier.well(shape: Shape, alpha: Float = 0.30f): Modifier = this.drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val path = Path().apply { addOutline(outline) }
    val stroke = Stroke(2.dp.toPx())
    val edge = (8.dp.toPx() / size.height).coerceIn(0.02f, 0.5f)
    // inset 0 1px 3px rgba(0,0,0,.45): a shadow just inside the top edge.
    val inner = Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.45f), edge to Color.Transparent)
    onDrawBehind {
        clipPath(path) {
            drawRect(Color.Black.copy(alpha = alpha))
            drawRect(inner)
            drawOutline(outline, Color.White.copy(alpha = 0.08f), style = stroke)
        }
    }
}

/** `.card`: grouped panels, with a faint top highlight and inner stroke. */
fun Modifier.groupCard(shape: Shape, fill: Color = Z.card): Modifier = this.drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val path = Path().apply { addOutline(outline) }
    val stroke = Stroke(2.dp.toPx())
    val edge = (4.dp.toPx() / size.height).coerceIn(0.005f, 0.5f)
    val rim = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.10f), edge to Color.Transparent)
    onDrawBehind {
        clipPath(path) {
            drawRect(fill)
            drawOutline(outline, Color.White.copy(alpha = 0.05f), style = stroke)
            drawOutline(outline, rim, style = stroke)
        }
    }
}

/** The white focused / selected fill used by rows, segments and keys (`glassOn` without its shadow). */
fun Modifier.onFill(shape: Shape, alpha: () -> Float = { 1f }): Modifier = this.drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val brush = Brush.verticalGradient(listOf(Z.onTop, Z.onBottom))
    onDrawBehind { if (alpha() > 0f) drawOutline(outline, brush, alpha = alpha()) }
}

/** Shadow for the focused white surfaces that are not glass (rows, segments, keys). */
fun Modifier.liftShadow(shape: Shape, elevation: Dp = 18.dp): Modifier =
    shadow(elevation, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.45f), spotColor = Color.Black.copy(alpha = 0.5f))

/**
 * `.panel`: the frosted pop-up surfaces (side bar, 首页来源, filters, 用手机输入): a dark vertical
 * gradient with a faint top highlight and inner stroke, and a deep drop shadow.
 */
fun Modifier.panelSurface(shape: Shape): Modifier = this
    .shadow(60.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.6f), spotColor = Color.Black.copy(alpha = 0.6f))
    .drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val path = Path().apply { addOutline(outline) }
        val stroke = Stroke(2.dp.toPx())
        val fill = Brush.verticalGradient(listOf(Color(0xDB2E3038), Color(0xEB14151A)))
        val edge = (4.dp.toPx() / size.height).coerceIn(0.005f, 0.5f)
        val rim = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.18f), edge to Color.Transparent)
        onDrawBehind {
            clipPath(path) {
                drawRect(fill)
                drawOutline(outline, Color.White.copy(alpha = 0.08f), style = stroke)
                drawOutline(outline, rim, style = stroke)
            }
        }
    }
