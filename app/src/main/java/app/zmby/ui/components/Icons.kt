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
    Heart("M12 20.5s-7.5-4.6-7.5-10.4A4.4 4.4 0 0 1 12 7.3a4.4 4.4 0 0 1 7.5 2.8c0 5.8-7.5 10.4-7.5 10.4z"),
    Chart("M5 20V11M12 20V5M19 20v-6"),
    Grip("M5 9h14M5 15h14"),
    Bookmark("M6.5 3.5h11v17l-5.5-3.8-5.5 3.8z"),
    Sort("M7 4v16M4 17l3 3 3-3M17 20V4M14 7l3-3 3 3"),
    Filter("M4 5h16l-6 8v6l-4-2v-4z"),
    // Refresh (我的, retry), and the manual segment marks on the detail page.
    Refresh("M20 11a8 8 0 1 0-2.3 5.7M20 4v7h-7"),
    Marks("M3 12h18M7 7v10M17 7v10"),
    // 侧边栏 (MainSidebar.dc.html).
    Home("M4 11l8-7 8 7v9a1 1 0 0 1-1 1h-5v-6h-4v6H5a1 1 0 0 1-1-1z"),
    // 在看: a play sign in a circle.
    PlayCircle("M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0M10 8.5v7l5.5-3.5z"),
    Film("M6 4h12a3 3 0 0 1 3 3v10a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3V7a3 3 0 0 1 3-3zM3 9h18M8 4v5M16 4v5"),
    Tv("M6 6h12a3 3 0 0 1 3 3v7a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3V9a3 3 0 0 1 3-3zM8 2l4 4 4-4"),
    Episodes("M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h6v6h-6z"),
    Servers("M5 4h14a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zM5 13h14a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2z"),
    Close("M6 6l12 12M18 6L6 18"),
    // 我的 (MainSidebar.dc.html): a head and shoulders.
    Person("M16 8a4 4 0 1 1-8 0a4 4 0 1 1 8 0M4 21c1.5-4 4.5-6 8-6s6.5 2 8 6"),
    // 设置 › 网络 (代理服务器): a globe with its meridian and equator.
    Globe("M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0M3 12h18M12 3c2.4 2.5 3.6 5.5 3.6 9s-1.2 6.5-3.6 9M12 3c-2.4 2.5-3.6 5.5-3.6 9s1.2 6.5 3.6 9"),
    // 季集映射 (Series.dc.html): two opposed arrows.
    Swap("M4 7h11l-3-3M20 17H9l3 3"),
    // 弹幕 (DanmuPanel.dc.html): a speech bubble with two lines.
    Danmu("M4 5h16v11H9l-5 4zM8 9h8M8 12.5h5"),
    // 弹幕 · 已关闭 (DanmuPanel.dc.html): the bubble crossed out.
    DanmuOff("M4 5h16v11H9l-5 4zM3 3l18 18"),
    // 输入 · 手机输入 (Settings.dc.html, text rows).
    Keyboard("M5 6h14a2.5 2.5 0 0 1 2.5 2.5v7a2.5 2.5 0 0 1-2.5 2.5H5a2.5 2.5 0 0 1-2.5-2.5v-7A2.5 2.5 0 0 1 5 6zM6.5 10h.01M10 10h.01M13.5 10h.01M17 10h.01M8 14h8"),
    Phone("M9.5 2.5h5A2.5 2.5 0 0 1 17 5v14a2.5 2.5 0 0 1-2.5 2.5h-5A2.5 2.5 0 0 1 7 19V5a2.5 2.5 0 0 1 2.5-2.5zM11 18.5h2"),
    // A picked-up row in a ranked list (SettingsQuality.dc.html).
    Reorder("M8 9l4-4 4 4M8 15l4 4 4-4"),
    // 预告片 (TrailerDetail.dc.html): a screen with a play sign; the chip's muted and sound speakers;
    // the full-screen trailer's 暂停.
    Trailer("M6 5h12a3 3 0 0 1 3 3v8a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3V8a3 3 0 0 1 3-3zM10.5 9.5v5l4-2.5z"),
    Muted("M4 9v6h4l5 4V5L8 9zM17 9l4 6M21 9l-4 6"),
    Sound("M4 9v6h4l5 4V5L8 9zM16.5 9a4 4 0 0 1 0 6M19 6.5a7.5 7.5 0 0 1 0 11"),
    Pause("M7 5h2a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1zM15 5h2a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1h-2a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z", filled = true),
    // Rating badges (ratings design): single-colour silhouettes drawn for zmby, not the services' logos.
    // Tomatometer fresh: a tomato under its calyx; rotten: a splat.
    Tomato("M12 8c4.7 0 8.5 3 8.5 6.8S16.7 21.5 12 21.5 3.5 18.6 3.5 14.8 7.3 8 12 8zM12 2.2l.9 2.6 2.8-.8-1.6 2.2 2.4 1.2-3.4.1L12 7.6l-1.1-1.1-3.4-.1 2.4-1.2L8.3 4l2.8.8z", filled = true),
    Splat("M12 3l1.8 4.2 4-2.4-.9 4.5 4.6.5-3.6 2.9 3.4 3.2-4.6.2.9 4.6-4-2.4-1.6 4.3-1.7-4.3-4 2.4.9-4.6-4.6-.2 3.4-3.2L2.5 9.8l4.6-.5-.9-4.5 4 2.4z", filled = true),
    // Popcornmeter positive: a full bucket of three slats; negative: the bucket tipped over.
    Popcorn("M5.5 11h3.6l.7 10.5H7.2zM10.3 11h3.4l-.3 10.5h-2.8zM14.9 11h3.6l-1.7 10.5h-2.6zM5 10.3a2.6 2.6 0 0 1 2.3-4 3 3 0 0 1 4.7-2.3 3 3 0 0 1 4.7 2.3 2.6 2.6 0 0 1 2.3 4z", filled = true),
    PopcornSpilled("M2 10.5L14 7v4.27L2 12.43zM2 13.03l12-1.16v4.26L2 14.97zM2 15.57l12 1.16V21L2 17.5zM15.8 9.5a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0-3.4 0zM18.9 13.5a1.6 1.6 0 1 0 3.2 0a1.6 1.6 0 1 0-3.2 0zM16 17a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0zM19.7 19.6a1.3 1.3 0 1 0 2.6 0a1.3 1.3 0 1 0-2.6 0z", filled = true),
}

private val parsed = HashMap<Glyph, Path>()

private fun pathOf(glyph: Glyph): Path = parsed.getOrPut(glyph) { PathParser().parsePathString(glyph.path).toPath() }

@Composable
fun Icon(glyph: Glyph, size: Dp = 26.dp, color: Color = LocalContentColor.current, strokeWidth: Float = 2.2f, fill: Boolean = glyph.filled) {
    Canvas(Modifier.size(size)) {
        val u = this.size.width / 24f
        scale(u, u, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            if (fill) drawPath(pathOf(glyph), color)
            // A line glyph drawn solid (收藏, 待看 turned on) keeps its outline so the shape stays the same size.
            if (!glyph.filled) drawPath(pathOf(glyph), color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
