package app.zmby.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import app.zmby.ui.theme.Z

/**
 * How a header icon button is drawn (ui-spec §2.15, `DetailActions` second row): focus is the white
 * `glassOn` at 1.08; 已开启 is glass with a solid glyph and a 2 px inner ring (never the white
 * fill, so it cannot be mistaken for focus); 已开启 and focused is white with the solid glyph; busy
 * swaps the glyph for a turning arc; unavailable is 40 % opaque and still focusable.
 */
internal data class ActionLook(
    val white: Boolean,
    val ring: Boolean,
    val solidGlyph: Boolean,
    val spinner: Boolean,
    val alpha: Float,
    val scale: Float,
)

internal fun actionLook(focused: Boolean, on: Boolean, busy: Boolean, enabled: Boolean): ActionLook = ActionLook(
    white = focused,
    ring = on && !focused,
    solidGlyph = on && !busy,
    spinner = busy,
    alpha = if (enabled) 1f else 0.4f,
    scale = if (focused) 1.08f else 1f,
)

/** OK does something only when the button is available and not already working on a press. */
internal fun actionAccepts(busy: Boolean, enabled: Boolean, allowWhileBusy: Boolean = false): Boolean = enabled && (!busy || allowWhileBusy)

/**
 * `.ibtn` with the 2.15 states and the focus name tag: a dark 72 % capsule (17/600) 14 below the
 * button, shown only while it has focus. The tag lies outside the layout, so the row does not move.
 * [tip] is the state's name (「加入收藏」「已收藏」…); [focusKey] stays the same across states, so
 * focus returns to the button whatever it is called now.
 */
@Composable
fun ActionIconButton(
    glyph: Glyph,
    tip: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    on: Boolean = false,
    busy: Boolean = false,
    enabled: Boolean = true,
    /** Line glyphs that have no solid form (the check, the range marks) keep their stroke when on. */
    solidWhenOn: Boolean = true,
    focusKey: String,
    /** A 12 px amber dot at the top right: something needs attention (弹幕 未匹配, ui-spec §2.19). */
    dot: Boolean = false,
    /** Read-only status queries must not prevent opening their search/editor panel. */
    allowWhileBusy: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    Box(modifier.onFocusChanged { focused = it.hasFocus }) {
        val look = actionLook(focused, on, busy, enabled)
        Focusable(
            { if (actionAccepts(busy, enabled, allowWhileBusy)) onClick() },
            Modifier.semantics { contentDescription = tip },
            shape = CircleShape,
            focusedScale = 1.08f,
            outline = 0.dp,
            shadow = 0.dp,
            label = "$focusKey $tip",
            focusKey = focusKey,
        ) { _ ->
            CompositionLocalProvider(LocalContentColor provides if (look.white) Z.onFocus else Z.textPrimary) {
                Box(
                    Modifier
                        .graphicsLayer { alpha = look.alpha }
                        .size(72.dp)
                        .glass(CircleShape, if (look.white) GlassTone.On else GlassTone.Glass)
                        .then(if (look.ring) Modifier.border(2.dp, Color.White.copy(alpha = 0.85f), CircleShape) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    if (look.spinner) Spinner(30.dp)
                    else Icon(glyph, size = 28.dp, strokeWidth = 2.4f, fill = glyph.filled || look.solidGlyph && solidWhenOn)
                    if (dot && !look.spinner) {
                        // `.dot`: 12 px at top 8 · right 8, with a 3 px dark ring around it.
                        Box(
                            Modifier.align(Alignment.TopEnd).offset(x = (-5).dp, y = 5.dp).size(18.dp)
                                .background(Color(0xE614151A), CircleShape).padding(3.dp)
                                .background(Z.warn, CircleShape),
                        )
                    }
                }
            }
        }
        if (focused) FocusTip(tip, Modifier.align(Alignment.TopCenter).offset(y = 86.dp))
    }
}

/** The name tag under a focused icon button, centred on it and free to be wider than it. */
@Composable
private fun FocusTip(text: String, modifier: Modifier) {
    Text(
        text,
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
        color = Z.textPrimary,
        maxLines = 1,
        modifier = modifier
            .layout { measurable, _ ->
                val p = measurable.measure(Constraints())
                layout(0, 0) { p.place(-p.width / 2, 0) }
            }
            .background(Color(0xB80A0B0D), Pill)
            .border(1.dp, Color.White.copy(alpha = 0.12f), Pill)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

private val Pill = RoundedCornerShape(percent = 50)

/** The turning arc that stands for 处理中 / 刷新中 (a 3⁄4 circle, round caps). */
@Composable
fun Spinner(size: Dp, color: Color = LocalContentColor.current, strokeWidth: Dp = 2.6.dp) {
    val turn by rememberInfiniteTransition(label = "spinner").animateFloat(
        0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "spinner-turn",
    )
    Canvas(Modifier.size(size)) {
        val w = strokeWidth.toPx()
        rotate(turn) {
            drawArc(color, startAngle = -90f, sweepAngle = 270f, useCenter = false,
                topLeft = Offset(w / 2 + size.toPx() * 0.08f, w / 2 + size.toPx() * 0.08f),
                size = Size(this.size.width - w - size.toPx() * 0.16f, this.size.height - w - size.toPx() * 0.16f),
                style = Stroke(w, cap = StrokeCap.Round))
        }
    }
}

/**
 * The 1 px divider between the text buttons and the icon buttons of a detail header. It is a
 * plain box (no focus), 40 high, with 4 extra on either side of the row gap.
 */
@Composable
fun ActionDivider() {
    Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(40.dp).background(Color.White.copy(alpha = 0.2f)))
}

/**
 * A glass pill (52 high, 19/600) for a section's status action, e.g. 版本's 刷新: [busy] shows the
 * arc at 70 % and ignores OK, [warn] puts the yellow dot before the text.
 */
@Composable
fun StatusPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: Glyph? = null,
    busy: Boolean = false,
    warn: Boolean = false,
    focusKey: String,
) {
    GlassSurface({ if (!busy) onClick() }, modifier.graphicsLayer { alpha = if (busy) 0.7f else 1f }, label = focusKey, focusKey = focusKey) {
        Row(
            Modifier.height(52.dp).padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                busy -> Spinner(20.dp, strokeWidth = 2.4.dp)
                warn -> HealthDot(Health.Warn, size = 10.dp)
                glyph != null -> Icon(glyph, size = 20.dp, strokeWidth = 2.4f)
            }
            Text(text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** A key cap (`.key`): 30 high, 16/700 on 16 % white, for the hold hints and dialog footers. */
@Composable
fun KeyCap(text: String) {
    Box(
        Modifier
            .height(30.dp)
            .widthIn(min = 30.dp)
            .background(Color.White.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Z.textPrimary, maxLines = 1)
    }
}
