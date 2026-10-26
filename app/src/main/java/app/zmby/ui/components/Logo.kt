package app.zmby.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.zmby.ui.theme.Z
import coil.compose.AsyncImage
import coil.imageLoader
import app.zmby.media.UNTITLED
import app.zmby.media.visibleText
import androidx.compose.foundation.Image
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The zmby mark (ui-spec §2.7): three rounded tiles stacked diagonally (many servers) ending in the
 * white front tile with a play triangle (one-press play). viewBox 64: tiles 38×38, radius 12,
 * offset 9; opacity 28 % / 55 % / 100 %; triangle M36 33v16l13-8z. [light] is the variant for light
 * backgrounds. Shares the launcher mark geometry.
 */
@Composable
fun ZmbyMark(size: Dp, light: Boolean = false) {
    Canvas(Modifier.size(size)) {
        val u = this.size.width / 64f
        val tile = if (light) Z.onFocus else Color.White
        val alphas = if (light) floatArrayOf(0.18f, 0.42f, 1f) else floatArrayOf(0.28f, 0.55f, 1f)
        for (i in 0..2) {
            val o = (4 + 9 * i) * u
            drawRoundRect(tile.copy(alpha = alphas[i]), Offset(o, o), Size(38 * u, 38 * u), CornerRadius(12 * u))
        }
        val tri = Path().apply { moveTo(36 * u, 33 * u); lineTo(36 * u, 49 * u); lineTo(49 * u, 41 * u); close() }
        drawPath(tri, if (light) Color.White else Z.onFocus)
    }
}

/** Mark + `zmby` wordmark (Inter 800, letter spacing −1): 46 + 32 in the top bar. */
@Composable
fun ZmbyLogo(modifier: Modifier = Modifier, markSize: Dp = 46.dp, wordSize: Int = 32) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(markSize * 14 / 46)) {
        ZmbyMark(markSize)
        Text("zmby", fontSize = wordSize.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp, color = Z.textPrimary)
    }
}

/**
 * Title, Logo first (ui-spec §1.3): the text title at 60/900 (at most two lines) shows while the
 * server's Logo is loading, when it fails and when there is none; once the Logo has loaded it takes
 * the text's place, fitted into [maxWidth]×[maxHeight], left-aligned and sitting on the bottom edge.
 * While loading, the image is laid out (invisibly) at its full box so the page does not jump when it
 * arrives.
 *
 * A Logo decoded before (an earlier visit, the home hero's prefetch) is drawn in the very first
 * frame, with no text before it. One that is only in the disk cache shows nothing for the moment its
 * decode takes (at most [DECODE_GRACE_MS]) instead of flashing the text; only a Logo that has to
 * come from the server shows the text first.
 */
@Composable
fun TitleLogo(logoUrl: String?, title: String, maxWidth: Dp, maxHeight: Dp, modifier: Modifier = Modifier, compact: Boolean = false) {
    val context = LocalContext.current
    val loader = context.imageLoader
    val density = LocalDensity.current
    val w = with(density) { maxWidth.roundToPx() }
    val h = with(density) { maxHeight.roundToPx() }
    val request = remember(logoUrl, w, h) { logoUrl?.let { logoRequest(context, it, w, h) } }
    val cached = remember(request) {
        if (logoUrl == null) null else loader.memoryCache?.get(logoCacheKey(logoUrl, w, h))?.bitmap?.asImageBitmap()
    }
    var state by remember(request) {
        mutableStateOf(
            when {
                request == null -> LogoState.None
                cached != null -> LogoState.Loaded
                else -> LogoState.Checking
            },
        )
    }
    LaunchedEffect(request) {
        if (state != LogoState.Checking || logoUrl == null) return@LaunchedEffect
        val onDisk = withContext(Dispatchers.IO) {
            runCatching { loader.diskCache?.openSnapshot(logoUrl)?.use { true } ?: false }.getOrDefault(false)
        }
        if (state != LogoState.Checking) return@LaunchedEffect
        if (!onDisk) {
            state = LogoState.Loading
            return@LaunchedEffect
        }
        state = LogoState.Decoding
        delay(DECODE_GRACE_MS)
        if (state == LogoState.Decoding) state = LogoState.Loading
    }
    Box(modifier, contentAlignment = Alignment.BottomStart) {
        if (state == LogoState.None || state == LogoState.Loading) TextTitle(title, compact)
        val box = Modifier.size(maxWidth, maxHeight)
        if (cached != null) {
            Image(remember(cached) { BitmapPainter(cached) }, title, box, Alignment.BottomStart, ContentScale.Fit)
        } else if (request != null && state != LogoState.None) {
            AsyncImage(
                model = request,
                contentDescription = title,
                contentScale = ContentScale.Fit,
                alignment = Alignment.BottomStart,
                onSuccess = { state = LogoState.Loaded },
                onError = { state = LogoState.None },
                modifier = box.alpha(if (state == LogoState.Loaded) 1f else 0f),
            )
        }
    }
}

/** How long a Logo found in the disk cache may take to decode before the text title shows instead. */
private const val DECODE_GRACE_MS = 300L

/** Checking: looking in the disk cache; Decoding: found there, being decoded (nothing shown yet). */
private enum class LogoState { None, Checking, Decoding, Loading, Loaded }

@Composable
private fun TextTitle(title: String, compact: Boolean = false) {
    Text(
        // Never blank (ui-spec §1.3): callers pass titleOf(…); this guards the rest.
        visibleText(title) ?: UNTITLED,
        // The compact detail header (ui-spec §2.14) uses 30/800 on one line.
        fontSize = if (compact) 30.sp else 60.sp,
        lineHeight = if (compact) 36.sp else 67.sp,
        fontWeight = if (compact) FontWeight.ExtraBold else FontWeight.Black,
        letterSpacing = (-1).sp,
        color = Z.textPrimary,
        maxLines = if (compact) 1 else 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 860.dp),
    )
}
