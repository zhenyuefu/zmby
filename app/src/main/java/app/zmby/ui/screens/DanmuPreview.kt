package app.zmby.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.tv.material3.Text

/** How 弹幕 are drawn, as set in 设置 › 播放 › 弹幕. Sizes are in design pixels (1920 × 1080). */
internal data class DanmuLook(val rows: Int, val fontSize: Int, val lineGap: Int, val topMargin: Int, val opacity: Int)

/** The small tag in the preview's corner: 「3 行 · 30 px · 75%」. */
internal fun danmuLookLabel(look: DanmuLook): String = "${look.rows} 行 · ${look.fontSize} px · ${look.opacity}%"

/** Sample lines of the preview: each row is a start offset and its comments. */
private val SAMPLES = listOf(
    260 to listOf("前方高能", "这段配乐太好了", "演技炸裂"),
    60 to listOf("终于更新了", "秋天的颜色", "原来是他"),
    420 to listOf("二刷打卡", "这镜头绝了"),
    160 to listOf("名场面", "又哭了"),
    340 to listOf("细节拉满", "下一集呢"),
)

/**
 * 实时预览条 (ui-spec §2.20): 210 high above the 弹幕 rows; example comments over a blurred frame
 * with the current rows, size, line spacing, top margin and opacity. Dimmed while 弹幕 is off.
 */
@Composable
internal fun DanmuPreview(look: DanmuLook, dimmed: Boolean) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 8.dp)
            .height(210.dp)
            .alpha(if (dimmed) 0.45f else 1f)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF1D2A2A), Color(0xFF11181A))))
            .background(Brush.radialGradient(listOf(Color(0xFF8A6A3E), Color.Transparent), center = Offset(1050f, 150f), radius = 520f))
            .background(Brush.radialGradient(listOf(Color(0xFF2F4A52), Color.Transparent), center = Offset(280f, 170f), radius = 700f))
            .clearAndSetSemantics { },
    ) {
        val style = TextStyle(
            fontSize = look.fontSize.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            shadow = Shadow(Color.Black.copy(alpha = 0.9f), Offset(0f, 1f), 3f),
        )
        Column(
            Modifier.fillMaxSize().padding(top = look.topMargin.dp).alpha(look.opacity / 100f),
            verticalArrangement = Arrangement.spacedBy(look.lineGap.dp),
        ) {
            SAMPLES.take(look.rows.coerceIn(1, SAMPLES.size)).forEachIndexed { row, (start, lines) ->
                // Wider than the strip on purpose: the frame clips it like the screen edge.
                Row(Modifier.wrapContentWidth(Alignment.Start, unbounded = true).padding(start = start.dp), horizontalArrangement = Arrangement.spacedBy((120 + row * 20).dp)) {
                    lines.forEachIndexed { i, line ->
                        Text(line, style = style, color = if (row == 1 && i == 1) Color(0xFFFDE68A) else Color.White, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
        Text(
            danmuLookLabel(look),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 14.dp)
                .background(Color(0x990A0B0D), RoundedCornerShape(percent = 50)).padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}
