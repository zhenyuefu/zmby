package app.zmby.ui.theme

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/** Tokens from the TV UI spec §1.1 (.agents/notes/topics/tv-ui/specification.md, second edition). */
object Z {
    val bg = Color(0xFF0A0B0D)
    val aside = Color(0xFF111215)
    /** `.card`: grouped panels. */
    val card = Color.White.copy(alpha = 0.06f)
    /** `.row.tile`: version and server rows at rest. */
    val tile = Color.White.copy(alpha = 0.06f)
    /** `.row.sel`: the selected row. */
    val sel = Color.White.copy(alpha = 0.08f)
    /** `.well`: the sunken track under segments, toggles and the search field. */
    val well = Color.Black.copy(alpha = 0.30f)
    /** `glassOn` gradient, top to bottom. */
    val onTop = Color.White
    val onBottom = Color(0xFFE4E5E9)
    val onFocus = bg
    /** Secondary text inside a focused (white) row. */
    val onFocusSecondary = Color(0xFF3F3F46)
    val textPrimary = Color.White
    val textSecondary = Color.White.copy(alpha = 0.68f)
    val textTertiary = Color.White.copy(alpha = 0.60f)
    /** Unavailable segment text (timed-out line). */
    val textDisabled = Color.White.copy(alpha = 0.45f)
    val ok = Color(0xFF4ADE80)
    val warn = Color(0xFFFBBF24)
    val off = Color(0xFF71717A)
    val focusFill = Color.White

    const val DESIGN_WIDTH = 1920f
}

fun zText(size: Int, weight: FontWeight = FontWeight.Normal, color: Color = Color.Unspecified, lineHeight: TextUnit = TextUnit.Unspecified) =
    TextStyle(fontSize = size.sp, fontWeight = weight, color = color, lineHeight = lineHeight)

val Mono = FontFamily.Monospace

/**
 * Lays the UI out on the 1920-px design grid: one density factor for the whole tree, so every
 * number from the design (px) is written as dp/sp unchanged, whatever the box's real density.
 */
/**
 * Scroll only as far as needed to show the focused item (plus a margin for the focus scale and
 * shadow). Compose's TV default pivots the focused item to a third of the screen, which pushes the
 * home hero away as soon as the first row is focused; the design keeps it in view.
 */
@OptIn(ExperimentalFoundationApi::class)
private val MinimalScroll = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val margin = containerSize * 0.06f
        val end = offset + size
        return when {
            size + 2 * margin > containerSize -> offset - margin
            offset < margin -> offset - margin
            end > containerSize - margin -> end - (containerSize - margin)
            else -> 0f
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ZmbyTheme(content: @Composable () -> Unit) {
    val config = LocalConfiguration.current
    val real = LocalDensity.current
    val widthPx = config.screenWidthDp * real.density
    val design = Density(density = widthPx / Z.DESIGN_WIDTH, fontScale = 1f)
    MaterialTheme(colorScheme = darkColorScheme(background = Z.bg, surface = Z.bg, onSurface = Z.textPrimary, primary = Z.focusFill)) {
        CompositionLocalProvider(
            LocalDensity provides design,
            LocalBringIntoViewSpec provides MinimalScroll,
            LocalContentColor provides Z.textPrimary,
            // No colour here: Text falls back to LocalContentColor, which buttons flip when focused.
            LocalTextStyle provides TextStyle(fontSize = 22.sp),
        ) {
            Box(Modifier.fillMaxSize().background(Z.bg)) { content() }
        }
    }
}
