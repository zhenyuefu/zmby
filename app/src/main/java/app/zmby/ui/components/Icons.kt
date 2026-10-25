package app.zmby.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.LocalContentColor

/**
 * The glyphs from the design, as their SVG paths on a 24-unit grid. [filled] glyphs are solid,
 * the others are stroked with round caps like the originals.
 */
enum class Glyph(val path: String, val filled: Boolean = false) {
    Play("M7 4.5v15l12-7.5z", filled = true),
    Plus("M12 5v14M5 12h14"),
    Check("M5 12.5l4.5 4.5L19 7.5"),
    Search("M18 11a7 7 0 1 1-14 0a7 7 0 1 1 14 0M20 20l-3.5-3.5"),
    Back("M15 5l-7 7 7 7"),
    Chevron("M9 5l7 7-7 7"),
    Gear(
        "M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0" +
            "M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3h0a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8v0a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z",
    ),
    Qr("M4.5 3h4A1.5 1.5 0 0 1 10 4.5v4A1.5 1.5 0 0 1 8.5 10h-4A1.5 1.5 0 0 1 3 8.5v-4A1.5 1.5 0 0 1 4.5 3zM15.5 3h4A1.5 1.5 0 0 1 21 4.5v4a1.5 1.5 0 0 1-1.5 1.5h-4A1.5 1.5 0 0 1 14 8.5v-4A1.5 1.5 0 0 1 15.5 3zM4.5 14h4a1.5 1.5 0 0 1 1.5 1.5v4A1.5 1.5 0 0 1 8.5 21h-4A1.5 1.5 0 0 1 3 19.5v-4A1.5 1.5 0 0 1 4.5 14zM14 14h3v3h-3zM18 18h3v3h-3z"),
    Up("M6 15l6-6 6 6"),
    Down("M6 9l6 6 6-6"),
    Trash("M4 7h16M10 11v6M14 11v6M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12M9 7V4h6v3"),
    Bolt("M13 3L5 14h6l-1 7 8-11h-6z"),
    // 数据来源 rows, the provider drag handle and 待看 (single-colour line icons in the same style).
    Stack("M12 3l9 5-9 5-9-5zM3 13l9 5 9-5"),
    Clock("M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0M12 7v5l3 2"),
    Info("M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0M12 11v6M12 7.5v.5"),
    Skip("M5 5l9 7-9 7zM18 5v14"),
    Heart("M12 20s-7-4.5-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.5-7 10-7 10z"),
    Chart("M5 20V11M12 20V5M19 20v-6"),
    Grip("M5 9h14M5 15h14"),
    Bookmark("M7 4h10v16l-5-4-5 4z"),
    Sort("M7 4v16M4 17l3 3 3-3M17 20V4M14 7l3-3 3 3"),
    Filter("M4 5h16l-6 8v6l-4-2v-4z"),
}

private val parsed = HashMap<Glyph, Path>()

private fun pathOf(glyph: Glyph): Path = parsed.getOrPut(glyph) { PathParser().parsePathString(glyph.path).toPath() }

@Composable
fun Icon(glyph: Glyph, size: Dp = 26.dp, color: Color = LocalContentColor.current, strokeWidth: Float = 2.2f) {
    Canvas(Modifier.size(size)) {
        val u = this.size.width / 24f
        scale(u, u, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            if (glyph.filled) drawPath(pathOf(glyph), color)
            else drawPath(pathOf(glyph), color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
