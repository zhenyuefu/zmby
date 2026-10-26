package app.zmby.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import app.zmby.ui.theme.Z

enum class Health { Ok, Warn, Off }

fun Health.color(): Color = when (this) {
    Health.Ok -> Z.ok
    Health.Warn -> Z.warn
    Health.Off -> Z.off
}

@Composable
fun HealthDot(health: Health, size: Dp = 12.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(health.color()))
}

/** True inside a focused (white) row or segment: secondary text and badges switch to dark. */
val LocalInverted = compositionLocalOf { false }

@Composable
fun secondaryText(): Color = if (LocalInverted.current) Z.onFocusSecondary else Z.textSecondary

private val Pill = RoundedCornerShape(percent = 50)

/**
 * `.lg` glass surface that takes focus (`.btn`, `.ibtn`, the server pill): glass at rest, `glassOn`
 * and scale 1.06 when focused (ui-spec §1.2).
 */
@Composable
fun GlassSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = Pill,
    focusedScale: Float = 1.06f,
    onFocus: (() -> Unit)? = null,
    label: String? = null,
    focusKey: String? = label,
    /** A toggled-on state (待看 added): `glassOn` at rest too. */
    on: Boolean = false,
    content: @Composable (focused: Boolean) -> Unit,
) {
    Focusable(onClick, modifier, shape = shape, focusedScale = focusedScale, outline = if (on) 3.dp else 0.dp, shadow = 0.dp, onFocus = onFocus, label = label, focusKey = focusKey) { focused ->
        val lit = focused || on
        CompositionLocalProvider(LocalContentColor provides if (lit) Z.onFocus else Z.textPrimary, LocalInverted provides lit) {
            Box(Modifier.glass(shape, if (lit) GlassTone.On else GlassTone.Glass)) { content(focused) }
        }
    }
}

/** `.lg.btn`: pill button, 72 high, 38 side padding, 24/600. */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: Glyph? = null,
    height: Dp = 72.dp,
    horizontalPadding: Dp = 38.dp,
    fontSize: Int = 24,
    onFocus: (() -> Unit)? = null,
    label: String = text,
) {
    GlassSurface(onClick, modifier, onFocus = onFocus, label = label) {
        Row(
            Modifier.height(height).padding(horizontal = horizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (glyph != null) Icon(glyph, size = 24.dp)
            Text(text, fontSize = fontSize.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** `.lg.ibtn`: round icon button (72); the back button is the 64 variant. */
@Composable
fun GlassIconButton(glyph: Glyph, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 72.dp, glyphSize: Dp = 28.dp, label: String = glyph.name, on: Boolean = false) {
    GlassSurface(onClick, modifier, shape = CircleShape, label = label, on = on) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) { Icon(glyph, size = glyphSize, strokeWidth = 2.4f) }
    }
}

/** The 64 round glass back button at the top left of every pushed screen. */
@Composable
fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) =
    GlassIconButton(Glyph.Back, onClick, modifier, size = 64.dp, glyphSize = 26.dp, label = "返回")

/** A glass label that does not take focus: server name on artwork, version count, status chips. */
@Composable
fun GlassChip(text: String, modifier: Modifier = Modifier, health: Health? = null, fontSize: Int = 16, height: Dp? = null, horizontalPadding: Dp = 14.dp) {
    Row(
        modifier
            .glass(Pill, shadow = false)
            .then(if (height != null) Modifier.height(height) else Modifier)
            .padding(horizontal = horizontalPadding, vertical = if (height == null) 6.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (health != null) HealthDot(health, size = 10.dp)
        Text(text, fontSize = fontSize.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

data class Segment(
    val label: String,
    val count: String? = null,
    val glyph: Glyph? = null,
    /** Unavailable (timed-out line): 45 % text, still focusable. */
    val dim: Boolean = false,
    val selectOnFocus: Boolean = true,
    /** A health dot before the label (servers in the browse toolbar). */
    val health: Health? = null,
)

/**
 * `.well > .seg`: segments in a sunken track (quality, seasons, lines, choices in settings), or in a
 * glass capsule ([glassTrack], the top navigation). The selected segment is white (`seg.on`).
 *
 * Focus: entering the group from above or below lands on the selected segment (with select on
 * focus the nearest one would switch the page). A focused selected segment grows and lifts; a
 * focused unselected one (choices that need OK) gets a white ring. [focusShowsOn] makes the focused
 * segment white even when nothing is selected (candidate lists).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SegmentedWell(
    items: List<Segment>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    segHeight: Dp = 50.dp,
    fontSize: Int = 20,
    horizontalPadding: Dp = 26.dp,
    selectOnFocus: Boolean = true,
    glassTrack: Boolean = false,
    /** Segments share the width equally (line pickers in a card). */
    equalWidths: Boolean = false,
    /** Wrap onto more lines (pinyin candidates). */
    wrap: Boolean = false,
    focusShowsOn: Boolean = false,
    labelPrefix: String = "seg",
    focusKeyPrefix: String? = null,
) {
    val requesters = remember(items.size) { List(items.size) { FocusRequester() } }
    val shape = if (wrap) RoundedCornerShape(32.dp) else Pill
    val track = modifier
        .focusProperties {
            onEnter = {
                if (selected in requesters.indices && (requestedFocusDirection == FocusDirection.Up || requestedFocusDirection == FocusDirection.Down)) {
                    requesters[selected].requestFocus()
                }
            }
        }
        .focusGroup()
        .then(if (glassTrack) Modifier.glass(shape) else Modifier.well(shape))
        .padding(if (glassTrack) 6.dp else 5.dp)
    val segment: @Composable (Int, Segment, Modifier) -> Unit = { i, item, m ->
        val isSelected = i == selected
        Focusable(
            onClick = { onSelect(i) },
            modifier = m.focusRequester(requesters[i]),
            shape = Pill,
            focusedScale = if (isSelected || focusShowsOn) 1.08f else 1.06f,
            outline = 0.dp,
            shadow = 0.dp,
            onFocus = if (selectOnFocus && item.selectOnFocus) ({ onSelect(i) }) else null,
            label = "$labelPrefix " + item.label.ifEmpty { item.glyph?.name.orEmpty() },
            focusKey = focusKeyPrefix?.let { "$it:${item.label}" } ?: "$labelPrefix ${item.label}",
        ) { focused ->
            val on = isSelected || (focusShowsOn && focused)
            val fg = when {
                on -> Z.onFocus
                item.dim -> Z.textDisabled
                else -> Z.textPrimary
            }
            Row(
                Modifier
                    .then(if (equalWidths) Modifier.fillMaxWidth() else Modifier)
                    .height(segHeight)
                    .then(if (on && focused) Modifier.liftShadow(Pill) else Modifier)
                    .then(if (on) Modifier.onFill(Pill) else if (focused) Modifier.background(Color.White.copy(alpha = 0.14f), Pill) else Modifier)
                    .then(if (focused && !on) Modifier.border(3.dp, Z.focusFill, Pill) else Modifier)
                    .padding(horizontal = if (item.glyph != null && item.label.isEmpty()) 18.dp else horizontalPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            ) {
                if (item.health != null) HealthDot(item.health, size = 10.dp)
                if (item.glyph != null) Icon(item.glyph, size = 26.dp, color = fg)
                if (item.label.isNotEmpty()) Text(item.label, fontSize = fontSize.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (item.count != null) Text(item.count, fontSize = fontSize.sp, fontWeight = FontWeight.SemiBold, color = fg.copy(alpha = fg.alpha * 0.6f))
            }
        }
    }
    if (wrap) {
        FlowRow(track, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items.forEachIndexed { i, item -> segment(i, item, Modifier) }
        }
    } else {
        Row(track, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            items.forEachIndexed { i, item -> segment(i, item, if (equalWidths) Modifier.weight(1f) else Modifier) }
        }
    }
}

/**
 * `.tg`: 84×46 sunken track. Off = 60 % white knob on the left; on = white glass track with a dark
 * knob on the right (told apart by brightness, not colour). Inside a focused white row the on state
 * inverts (dark track, white knob) so it stays visible.
 */
@Composable
fun GlassToggle(checked: Boolean, modifier: Modifier = Modifier) {
    val inverted = LocalInverted.current
    Box(
        modifier
            .width(84.dp)
            .height(46.dp)
            .then(
                when {
                    checked && inverted -> Modifier.background(Z.onFocus, Pill)
                    checked -> Modifier.liftShadow(Pill, 8.dp).background(Brush.verticalGradient(listOf(Color.White, Color(0xFFDFE0E4))), Pill)
                    inverted -> Modifier.well(Pill, alpha = 0.22f)
                    else -> Modifier.well(Pill, alpha = 0.32f)
                },
            ),
    ) {
        val knob = when {
            checked && inverted -> Color.White
            checked -> Z.onFocus
            inverted -> Z.onFocus.copy(alpha = 0.5f)
            else -> Color.White.copy(alpha = 0.6f)
        }
        Box(Modifier.offset(x = if (checked) 43.dp else 5.dp, y = 5.dp).size(36.dp).clip(CircleShape).background(knob))
    }
}

/** A toggle that takes focus on its own (media library switches): grows and gets a white ring. */
@Composable
fun ToggleButton(checked: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, label: String? = null) {
    Focusable(onToggle, modifier, shape = Pill, focusedScale = 1.12f, outline = 3.dp, shadow = 0.dp, label = label?.let { "$it ${if (checked) "开" else "关"}" }) {
        GlassToggle(checked)
    }
}

/**
 * `.row`: list rows for versions, servers, tracks, lines and settings. [tile] gives the light resting
 * fill, [selected] the slightly brighter fill and the ✓; focus is `glassOn` with scale 1.02, and the
 * content sees [LocalInverted] to darken secondary text.
 */
@Composable
fun SelectableRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    tile: Boolean = false,
    dimmed: Boolean = false,
    showCheck: Boolean = true,
    radius: Dp = 18.dp,
    padding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    onFocus: (() -> Unit)? = null,
    label: String? = null,
    focusKey: String? = label,
    animateFocus: Boolean = false,
    content: @Composable RowScope.(focused: Boolean) -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Focusable(onClick, modifier, shape = shape, focusedScale = 1.02f, outline = 0.dp, shadow = 0.dp, dimmed = dimmed, onFocus = onFocus, label = label, focusKey = focusKey, propagateMinConstraints = true) { focused ->
        val focusAlpha by animateFloatAsState(if (focused) 1f else 0f, tween(if (animateFocus) 150 else 0), label = "row-focus")
        CompositionLocalProvider(LocalContentColor provides lerp(Z.textPrimary, Z.onFocus, focusAlpha), LocalInverted provides (focusAlpha > 0.5f)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (selected) Z.sel else if (tile) Z.tile else Color.Transparent, shape)
                    .then(if (focused) Modifier.liftShadow(shape) else Modifier)
                    .onFill(shape) { focusAlpha }
                    .padding(padding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (showCheck) {
                    Box(Modifier.size(22.dp)) { if (selected) Icon(Glyph.Check, size = 22.dp, strokeWidth = 2.8f) }
                }
                content(focused)
            }
        }
    }
}

/** `.bdg`: 1.5 px outlined tag (HDR / audio formats); [fill] = 16 % white (默认 / 原盘 / 评分). */
@Composable
fun Badge(text: String, fill: Boolean = false, fontSize: Int = 15) {
    val inverted = LocalInverted.current
    val shape = RoundedCornerShape(8.dp)
    Text(
        text,
        fontSize = fontSize.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        color = if (fill && inverted) Color.White else LocalContentColor.current,
        modifier = Modifier
            .then(
                when {
                    fill && inverted -> Modifier.background(Z.onFocus, shape)
                    fill -> Modifier.background(Color.White.copy(alpha = 0.16f), shape)
                    else -> Modifier.border(1.5.dp, if (inverted) Z.onFocus.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.5f), shape)
                },
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, height: Dp = 6.dp, track: Color = Color.White.copy(alpha = 0.28f)) {
    Box(modifier.height(height).clip(CircleShape).background(track)) {
        if (fraction > 0f) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).clip(CircleShape).background(Z.focusFill))
        }
    }
}
