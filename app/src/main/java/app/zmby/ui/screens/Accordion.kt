package app.zmby.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.zmby.ui.FocusMemory
import app.zmby.ui.LocalNavEntry
import app.zmby.ui.rememberRetained
import app.zmby.ui.theme.Z
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.launch

/*
 * Detail navigation (ui-spec §2.14): a stable section anchor accepts focus before expansion;
 * the remembered row takes focus next. Artwork rows keep their dimensions and fade their captions.
 * A held direction leaves the tracks once; the page aligns the new section below its compact header.
 */

/** Where the preceding context row rests, below the 140-high compact header. */
private val SECTION_TOP = 170.dp
private const val SECTION_RESIZE_MS = 180

@Stable
class Accordion(first: String) {
    /** The expanded block. */
    var active by mutableStateOf(first)
        internal set
    internal var leaving by mutableStateOf<String?>(null)
        private set
    private var focusedSection: String? = null
    private var headerActive = false
    /** The blocks on the page in order (only those that have something to show). */
    var order: List<String> = emptyList()
    /** Each block's default element: selected version, current audio, first person, current episode. */
    private val entries = HashMap<String, FocusRequester>()
    private val anchors = HashMap<String, FocusRequester>()
    internal var handoff by mutableStateOf<String?>(null)
        private set
    /** The element focused last in each block, by focus key, to return to when it expands again. */
    private val lastKeys = HashMap<String, String>()
    private val coordinates = HashMap<String, androidx.compose.ui.layout.LayoutCoordinates>()
    /** Where focus goes Up from the first block (the header's play button). */
    var header: FocusRequester? = null
    private var navigation: Job? = null
    private var heldJump = false
    var moving by mutableStateOf(false)
        private set

    /** A held direction jumps once out of tracks; consume repeats until the remote is released. */
    internal fun sectionShortcut(from: String, down: Boolean, up: Boolean, released: Boolean, heldMs: Long, jump: (Int) -> Unit): Boolean {
        if (!down && !up) return false
        if (released) {
            val consumed = heldJump
            heldJump = false
            return consumed
        }
        if (heldJump) return true
        if ((from != SECTION_TRACKS && from != SECTION_VERSIONS) || heldMs < 450) return false
        heldJump = true
        jump(if (down) 1 else -1)
        return true
    }

    fun entry(key: String): FocusRequester = entries.getOrPut(key) { FocusRequester() }
    internal fun anchor(key: String): FocusRequester = anchors.getOrPut(key) { FocusRequester() }

    internal fun noteFocus(key: String, memory: FocusMemory?) {
        // While focus is being restored into another block, lastKey already names that block's element.
        if (memory == null || memory.restorePending) return
        memory.lastKey?.let { lastKeys[key] = it }
    }

    internal fun gainedFocus(key: String) {
        headerActive = false
        focusedSection = key
        // The outgoing block can report focus again while the incoming block is being laid out.
        if (key != leaving) active = key
    }

    internal fun prepareHandoff(key: String, memory: FocusMemory?): String? {
        val from = focusedSection ?: active
        if (!headerActive && key != from) memory?.lastKey?.let { lastKeys[from] = it }
        leaving = from.takeIf { it != key }
        handoff = key
        moving = true
        return lastKeys[key]
    }

    /** Keep compact source information above the focused row instead of pinning every row high. */
    internal fun contextFor(key: String): String {
        val before = order.takeWhile { it != key }
        return if (key == SECTION_CAST && SECTION_VERSIONS in before) SECTION_VERSIONS
        else before.lastOrNull() ?: key
    }

    internal fun cancelNavigation() {
        navigation?.cancel()
        leaving = null
        handoff = null
        heldJump = false
        moving = false
    }

    /** Header and section navigation share one scroll job; rapid Up/Down cancels the older one. */
    fun showHeader(scope: CoroutineScope, scroll: ScrollState) {
        cancelNavigation()
        headerActive = true
        if (scroll.value > 0) navigation = scope.launch { scroll.animateScrollTo(0, tween(220)) }
    }

    internal fun noteLayout(key: String, c: androidx.compose.ui.layout.LayoutCoordinates) { coordinates[key] = c }

    private fun topOf(key: String): Float? = coordinates[key]?.takeIf { it.isAttached }?.positionInRoot()?.y

    /**
     * Moves to the block [delta] away from [from] (Up from the first goes to the header); false when
     * there is none. Focus returns to where it was in that block, else its default element.
     */
    fun step(from: String, delta: Int, scope: CoroutineScope, memory: FocusMemory?, scroll: ScrollState?, density: Float): Boolean {
        val i = order.indexOf(from)
        val to = order.getOrNull(i + delta)
        if (to == null) {
            if (delta < 0) header?.let { h ->
                navigation?.cancel()
                moving = false
                leaving = null
                handoff = null
                navigation = scope.launch { h.requestWhenReady() }
                return true
            }
            return false
        }
        if (from == SECTION_TRACKS || from == SECTION_VERSIONS) heldJump = true
        open(to, scope, memory, scroll, density)
        return true
    }

    /** Focuses the persistent anchor first, expands its section, then restores the row. */
    fun open(key: String, scope: CoroutineScope, memory: FocusMemory?, scroll: ScrollState?, density: Float) {
        navigation?.cancel()
        val from = focusedSection ?: active
        val remembered = prepareHandoff(key, memory)
        navigation = scope.launch {
            // This anchor is always placed, even while its content is collapsed. Focus crosses to
            // the section first; its focus callback then expands it and we restore the leaf below.
            if (!anchor(key).requestWhenReady()) { leaving = null; handoff = null; moving = false; return@launch }
            awaitFrame()
            // Align the context row once, before removing the old expanded card. Every changing
            // block is at or below this row, so expansion can adapt beneath a fixed page anchor.
            // Card-internal focus never asks the outer page to bring an individual row into view.
            if (scroll != null) {
                topOf(contextFor(key))?.let { top ->
                    val target = (scroll.value + top - SECTION_TOP.value * density).toInt().coerceIn(0, scroll.maxValue)
                    if (kotlin.math.abs(scroll.value - target) > 1) scroll.animateScrollTo(target, tween(180, easing = FastOutSlowInEasing))
                }
            }
            var done = false
            if (remembered != null && memory != null) {
                memory.lastKey = remembered
                memory.beginRestore()
                // The block's content is composed on the next frames; its element takes focus itself.
                repeat(30) { if (!done) { delay(16); if (memory.restored && !memory.restorePending) done = true } }
                memory.restorePending = false
            }
            if (!done) done = entry(key).requestWhenReady()
            if (!done) {
                active = from
                entry(from).requestWhenReady()
                leaving = null
                handoff = null
                moving = false
                return@launch
            }
            // Keep the source placed until the destination has accepted focus. Removing the old
            // focus target first lets the root choose the header's play button in between frames.
            awaitFrame()
            leaving = null
            handoff = null
            delay(SECTION_RESIZE_MS.toLong())
            // A busy device can start the resize a frame late. Wait for positions to settle
            // before considering the handoff complete; never retarget the page during the resize.
            var previous: List<Int?>? = null
            var stable = 0
            for (frame in 0 until 12) {
                awaitFrame()
                val positions = order.map { topOf(it)?.toInt() }
                stable = if (positions == previous) stable + 1 else 0
                if (stable >= 2) break
                previous = positions
            }
            moving = false
        }
    }
}

/**
 * One accordion block: [content] when expanded, else the `mini` bar (88 high, radius 24, 4 % white
 * with a 1 px inner line): the block name (22/700, 150 wide), a one-line [summary] and the [count].
 */
@Composable
fun CollapsibleSection(
    accordion: Accordion,
    key: String,
    title: String,
    count: String?,
    scope: CoroutineScope,
    scroll: ScrollState?,
    barHeight: Dp = 88.dp,
    retainContent: Boolean = false,
    summary: @Composable RowScope.() -> Unit = {},
    content: @Composable (expanded: Boolean) -> Unit,
) {
    val expanded = accordion.active == key || accordion.leaving == key || accordion.order.size <= 1
    val reveal by animateFloatAsState(if (expanded) 1f else 0f, tween(150), label = "section-reveal")
    val memory = LocalNavEntry.current?.focus
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current.density
    Column(
        Modifier
            .fillMaxWidth()
            // Only the wrapper changes height. The child keeps its final constraints and drawing
            // layer, so its lazy lists and text are not remeasured at intermediate heights.
            .animateContentSize(tween(SECTION_RESIZE_MS), alignment = Alignment.TopStart)
            .onGloballyPositioned { accordion.noteLayout(key, it) }
            .onFocusChanged {
                if (it.hasFocus) accordion.gainedFocus(key)
            }
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown) accordion.noteFocus(key, memory)
                accordion.sectionShortcut(
                    key, e.key == Key.DirectionDown, e.key == Key.DirectionUp,
                    e.type == KeyEventType.KeyUp,
                    if (e.nativeKeyEvent.isLongPress || e.nativeKeyEvent.repeatCount > 0) 450L else e.nativeKeyEvent.eventTime - e.nativeKeyEvent.downTime,
                ) { delta -> accordion.step(key, delta, scope, memory, scroll, density) }
            }
            .onKeyEvent { e ->
                val delta = when (e.key) {
                    Key.DirectionDown -> 1
                    Key.DirectionUp -> -1
                    else -> 0
                }
                if (delta == 0 || e.type != KeyEventType.KeyDown) return@onKeyEvent false
                // Inside the block first; at its edge, the next block (the search cannot leave the block).
                val before = memory?.lastKey
                val moved = focusManager.moveFocus(if (delta > 0) FocusDirection.Down else FocusDirection.Up)
                // A nested onEnter can redirect focus while moveFocus itself reports cancellation.
                // Treat the synchronous key change as success rather than also leaving this block.
                if (moved || memory?.lastKey != before) return@onKeyEvent true
                accordion.step(key, delta, scope, memory, scroll, density)
                true
            }
            .focusProperties {
                onExit = {
                    if (requestedFocusDirection == FocusDirection.Up || requestedFocusDirection == FocusDirection.Down) cancelFocusChange()
                }
            }
            .focusGroup(),
    ) {
        // Programmatic transfer target only: no extra stop during normal directional navigation.
        Box(Modifier.width(1.dp).height(0.dp)
            .focusRequester(accordion.anchor(key))
            .focusProperties { canFocus = accordion.handoff == key }
            .focusable())
        // Each tree keeps its final constraints and cached layer. Crossfade both during the resize
        // so the outgoing card does not vanish into an empty gap before the next row moves up.
        if (retainContent) Box(Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) { content(expanded) }
        else Layout(content = {
            Box(Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen; alpha = 1f - reveal }) { MiniBar(title, count, barHeight, summary) }
            Box(Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen; alpha = reveal; translationY = (1f - reveal) * 12.dp.toPx() }) { content(true) }
        }) { children, constraints ->
            val mini = if (!expanded || reveal < 1f) children[0].measure(constraints) else null
            val full = if (expanded || reveal > 0f) children[1].measure(constraints) else null
            val visible = (if (expanded) full else mini)!!
            layout(visible.width, visible.height) {
                mini?.placeRelative(0, 0)
                full?.placeRelative(0, 0)
            }
        }
    }
}

private val MiniShape = RoundedCornerShape(24.dp)

@Composable
private fun MiniBar(title: String, count: String?, height: Dp, summary: @Composable RowScope.() -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 80.dp, vertical = 10.dp)
            .fillMaxWidth()
            .height(height)
            .background(Color.White.copy(alpha = 0.04f), MiniShape)
            .padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.width(150.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { summary() }
        if (count != null) Text(count, fontSize = 18.sp, color = Z.textTertiary, maxLines = 1)
    }
}

/** The one-line summary text of a bar (20, 75 % white, ellipsized). */
@Composable
fun RowScope.SummaryText(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 20.sp, color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier.weight(1f, fill = false))
}

/** Keeps the accordion of one detail page across recompositions. */
@Composable
fun rememberAccordion(first: String): Accordion {
    val accordion = rememberRetained("detailAccordion", first) { Accordion(first) }
    DisposableEffect(accordion) { onDispose { accordion.cancelNavigation() } }
    return accordion
}
