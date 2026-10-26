package app.zmby.ui.components

import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.ScrollState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import app.zmby.ui.LocalNavEntry
import app.zmby.ui.NavEntry
import kotlinx.coroutines.android.awaitFrame
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.zmby.log.ZLog
import app.zmby.ui.theme.Z

/**
 * The global focus treatment (ui-spec §1): the focused element scales up, gets a white outline and
 * a deep shadow; nothing else in the row changes. [content] receives the focus state so it can
 * also switch its own fill (buttons turn white).
 */
@Composable
fun Focusable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(14.dp),
    focusedScale: Float = 1.08f,
    outline: Dp = 4.dp,
    shadow: Dp = 40.dp,
    origin: TransformOrigin = TransformOrigin.Center,
    dimmed: Boolean = false,
    onFocus: (() -> Unit)? = null,
    /** Names the element in the debug log when it gains focus. */
    label: String? = null,
    /**
     * Identifies the element within its screen so focus returns to it when the user comes back
     * (see [NavEntry]); defaults to [label]. Cards pass their item id.
     */
    focusKey: String? = label,
    /** Pass a fixed or minimum size from [modifier] on to [content] (rows that fill their height). */
    propagateMinConstraints: Boolean = false,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val memory = LocalNavEntry.current?.focus
    val restore = remember { FocusRequester() }
    if (memory != null && focusKey != null) {
        LaunchedEffect(memory.restorePending) {
            if (memory.restorePending && memory.lastKey == focusKey) {
                awaitFrame()
                if (runCatching { restore.requestFocus() }.getOrDefault(false)) {
                    memory.restored = true
                    memory.restorePending = false
                    ZLog.d("Focus", "restored $focusKey")
                }
            }
        }
    }
    val interaction = remember { MutableInteractionSource() }
    // Read focus from the focus system itself: a FocusInteraction emitted before the interaction
    // collector subscribes is lost, which left an initially focused toggle without its outline.
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) focusedScale else 1f, tween(150), label = "focus-scale")
    LaunchedEffect(focused) {
        if (focused) {
            ZLog.d("Focus", label ?: focusKey ?: "(unnamed)")
            if (memory != null && focusKey != null) memory.lastKey = focusKey
            onFocus?.invoke()
        }
    }
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = origin
                alpha = if (dimmed) 0.58f else 1f
            }
            .then(if (focused && shadow > 0.dp) Modifier.shadow(shadow, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black) else Modifier)
            .then(if (focused && outline > 0.dp) Modifier.border(outline, Z.focusFill, shape) else Modifier)
            .focusRequester(restore)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        propagateMinConstraints = propagateMinConstraints,
    ) {
        content(focused)
    }
}

/**
 * For horizontal rows: Left/Right at either end stay put instead of jumping to whatever lies in
 * that direction elsewhere on screen (Right on the last card used to land on the top tabs). With
 * [up], Up leaves the row to that element instead of the geometrically nearest one; with [enter],
 * entering the row from above or below lands on that card.
 */
fun Modifier.horizontalRow(up: FocusRequester? = null, enter: FocusRequester? = null): Modifier = this
    .focusProperties {
        if (enter != null) {
            onEnter = {
                if (requestedFocusDirection == FocusDirection.Up || requestedFocusDirection == FocusDirection.Down) {
                    runCatching { enter.requestFocus() }
                }
            }
        }
        onExit = {
            when (requestedFocusDirection) {
                FocusDirection.Left, FocusDirection.Right -> cancelFocusChange()
                FocusDirection.Up -> if (up != null && runCatching { up.requestFocus() }.getOrDefault(false)) cancelFocusChange()
                else -> {}
            }
        }
    }
    .focusGroup()

/**
 * Scrolls back to the top when focus returns to a screen's top area. Compose's bring-into-view of
 * the newly focused button starts at the same moment and would cancel a plain animateScrollTo(0),
 * leaving the title cut off; this scroll runs at a priority bring-into-view cannot interrupt.
 */
suspend fun ScrollState.scrollToTopFirmly() {
    awaitFrame()
    val start = value.toFloat()
    if (start <= 0f) return
    scroll(MutatePriority.PreventUserInput) {
        var done = 0f
        animate(0f, start, animationSpec = tween(220)) { v, _ ->
            scrollBy(-(v - done))
            done = v
        }
    }
}

/**
 * The same for a lazy list (home): from inside its first item the remaining offset is animated at
 * a priority bring-into-view cannot interrupt; from further down it jumps to the top.
 */
suspend fun androidx.compose.foundation.lazy.LazyListState.scrollToTopFirmly() {
    awaitFrame()
    if (!canScrollBackward) return
    if (firstVisibleItemIndex > 0) {
        scrollToItem(0)
        return
    }
    val start = firstVisibleItemScrollOffset.toFloat()
    scroll(MutatePriority.PreventUserInput) {
        var done = 0f
        animate(0f, start, animationSpec = tween(220)) { v, _ ->
            scrollBy(-(v - done))
            done = v
        }
    }
}

/** The remote's menu key on this element (a poster card adds or removes the work from 待看). */
fun Modifier.onMenuKey(action: () -> Unit): Modifier = this.onPreviewKeyEvent { e ->
    if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && e.key == androidx.compose.ui.input.key.Key.Menu) { action(); true } else false
}
