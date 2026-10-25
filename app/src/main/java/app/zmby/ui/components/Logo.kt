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

/**
 * The zmby mark (ui-spec §2.7): three rounded tiles stacked diagonally (many servers) ending in the
 * white front tile with a play triangle (one-press play). viewBox 64: tiles 38×38, radius 12,
 * offset 9; opacity 28 % / 55 % / 100 %; triangle M36 33v16l13-8z. [light] is the variant for light
 * backgrounds. Same geometry as res/drawable/ic_zmby_mark.xml.
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
fun ZmbyLogo(markSize: Dp = 46.dp, wordSize: Int = 32, modifier: Modifier = Modifier) {
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
 */
@Composable
fun TitleLogo(logoUrl: String?, title: String, maxWidth: Dp, maxHeight: Dp, modifier: Modifier = Modifier) {
    var state by remember(logoUrl) { mutableStateOf(if (logoUrl == null) LogoState.None else LogoState.Loading) }
    Box(modifier, contentAlignment = Alignment.BottomStart) {
        if (state != LogoState.Loaded) TextTitle(title)
        if (logoUrl != null && state != LogoState.None) {
            AsyncImage(
                model = logoUrl,
                contentDescription = title,
                contentScale = ContentScale.Fit,
                alignment = Alignment.BottomStart,
                onSuccess = { state = LogoState.Loaded },
                onError = { state = LogoState.None },
                modifier = Modifier.size(maxWidth, maxHeight).alpha(if (state == LogoState.Loaded) 1f else 0f),
            )
        }
    }
}

private enum class LogoState { None, Loading, Loaded }

@Composable
private fun TextTitle(title: String) {
    Text(
        title,
        fontSize = 60.sp,
        lineHeight = 67.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = (-1).sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 860.dp),
    )
}
