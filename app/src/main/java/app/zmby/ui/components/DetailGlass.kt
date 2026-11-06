package app.zmby.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** Detail selection panels: a backdrop slice, then 46% dark glass and restrained inset highlights. */
fun Modifier.detailGlass(shape: Shape): Modifier = composed {
    val backdrop = LocalBackdrop.current
    var offset by remember { mutableStateOf(Offset.Zero) }
    val off = app.zmby.Diagnostics.drawOff
    this.then(if ("shadow" in off) Modifier else Modifier.shadow(18.dp, shape, clip = false,
        ambientColor = Color.Black.copy(alpha = 0.30f), spotColor = Color.Black.copy(alpha = 0.30f)))
        .onGloballyPositioned { c ->
            backdrop?.coordinates?.takeIf { it.isAttached && c.isAttached }?.let { offset = it.localPositionOf(c, Offset.Zero) }
        }
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            // The rim is the inner 1 px of a 2 px stroke: a 1 px stroke on the outline inset by half a pixel.
            val hairline = 1.dp.toPx()
            val inner = shape.createOutline(Size(size.width - hairline, size.height - hairline), layoutDirection, this)
            val stroke = Stroke(hairline)
            val edge = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.16f),
                (2.dp.toPx() / size.height).coerceIn(0.001f, 1f) to Color.Transparent)
            onDrawBehind {
                // Every layer is painted straight into the panel's rounded shape (performance plan R6):
                // a rounded clip around full-screen draws cost a full-screen fill per layer and panel.
                if ("slice" !in off) backdrop?.let { drawDetailSliceInto(it, offset, outline) }
                val area = backdrop?.coordinates?.size.takeIf { "tint" !in off }
                val image = backdrop?.blurred
                val bright = if (area != null && image != null) {
                    val scale = maxOf(area.width.toFloat() / image.width, area.height.toFloat() / image.height)
                    val imageWidth = image.width * scale
                    val imageHeight = image.height * scale
                    val cropX = (imageWidth - area.width) / 2f
                    val cropY = (imageHeight - area.height) / 2f
                    (backdrop.luminance?.average((offset.x + cropX) / imageWidth, (offset.y + cropY) / imageHeight,
                        (offset.x + size.width + cropX) / imageWidth, (offset.y + size.height + cropY) / imageHeight) ?: 0f) > 0.55f
                } else false
                drawOutline(outline, Color(0xFF101115).copy(alpha = if (bright) 0.62f else 0.46f))
                translate(hairline / 2, hairline / 2) {
                    drawOutline(inner, Color.White.copy(alpha = 0.08f), style = stroke)
                    drawOutline(inner, edge, style = stroke)
                }
            }
        }
}

/**
 * The backdrop under a panel at [offset] (the blurred copy with the page's scrims), painted into
 * [outline] in one fill from [Backdrop.sliceSource] placed in backdrop coordinates (performance plan
 * R6); under a clip it looked the same as four full-area layers.
 */
private fun DrawScope.drawDetailSliceInto(backdrop: Backdrop, offset: Offset, outline: Outline) {
    val area = backdrop.coordinates?.size?.let { Size(it.width.toFloat(), it.height.toFloat()) } ?: return
    val source = backdrop.sliceSource(area, this) ?: return
    val s = area.width / source.width
    val shader = ImageShader(source, TileMode.Clamp, TileMode.Clamp).apply {
        setLocalMatrix(android.graphics.Matrix().apply { setScale(s, s); postTranslate(-offset.x, -offset.y) })
    }
    drawOutline(outline, ShaderBrush(shader))
}

/** No tinted plate or reveal mask: the same backdrop gradually gains blur, light and a shadow. */
fun Modifier.detailTopGlass(progress: () -> Float): Modifier = composed {
    val backdrop = LocalBackdrop.current
    this.drawWithCache {
        val shadowHeight = 44.dp.toPx()
        val shadow = Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.32f), Color.Transparent),
            startY = size.height, endY = size.height + shadowHeight)
        onDrawBehind {
            val p = progress().coerceIn(0f, 1f)
            if (p > 0f) {
                drawRect(shadow, topLeft = Offset(0f, size.height), size = Size(size.width, shadowHeight), alpha = p)
                clipRect {
                    backdrop?.let { drawDetailSlice(it, Offset.Zero, p) }
                    drawRect(Color.White, alpha = 0.05f * p)
                    drawLine(Color.White.copy(alpha = 0.18f * p), Offset.Zero, Offset(size.width, 0f), 2.dp.toPx())
                    drawLine(Color.White.copy(alpha = 0.10f * p), Offset(0f, size.height), Offset(size.width, size.height), 2.dp.toPx())
                }
            }
        }
    }
}

private fun DrawScope.drawDetailSlice(backdrop: Backdrop, offset: Offset, alpha: Float = 1f) {
    val image = backdrop.blurred ?: return
    val area = backdrop.coordinates?.size?.let { Size(it.width.toFloat(), it.height.toFloat()) } ?: return
    translate(-offset.x, -offset.y) {
        drawCover(image, area, alpha)
        with(backdrop.scrims) { draw(area, alpha) }
    }
}

/** Text on the exposed detail artwork stays readable without a panel behind the row. */
@Composable
fun detailTextShadow(): Shadow {
    val density = LocalDensity.current.density
    return remember(density) { Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 2f * density), 10f * density) }
}
