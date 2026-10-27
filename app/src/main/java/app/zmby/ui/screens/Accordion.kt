package app.zmby.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Detail navigation (ui-spec §2.14). The page is the header and a column of blocks. 版本 and
 * 音轨与字幕 collapse to a summary bar while focus is elsewhere; 剧集, 演职人员 and 为你推荐 always
 * stay as they are. Moving between blocks is one transition: focus parks on the destination's
 * anchor, then in the same frame the destination expands and the source collapses while the page
 * aligns once to a row above every changing block; focus then returns to the block's last element.
 */

/** Where the alignment row rests, below the 140-high compact header. */
private val SECTION_TOP = 170.dp

/** Height change, content crossfade and page alignment share one duration and easing. */
private const val TRANSITION_MS = 200

/** Blocks that collapse to a summary bar; the others always keep their content. */
internal val COLLAPSIBLE = setOf(SECTION_VERSIONS, SECTION_TRACKS)

@Stable
class Accordion(first: String) {
    /** The block focus is in (or was in last): the only collapsible block shown expanded. */
    var active by mutableStateOf(first)
        internal set
    /** The block whose anchor may take focus while a transition parks focus there. */
    internal var handoff by mutableStateOf<String?>(null)
        private set
    /** The blocks on the page in order (only those that have something to show). */
    var order: List<String> = emptyList()
    /** Where focus goes Up from the first block (the header's play button). */
    var header: FocusRequester? = null

    /** Each block's default element: selected version, current audio, current episode, first person. */
    private val entries = HashMap<String, FocusRequester>()
    private val anchors = HashMap<String, FocusRequester>()
    /** The element focused last in each block, by focus key, to return to when it is entered again. */
    private val lastKeys = HashMap<String, String>()
    private val coordinates = HashMap<String, LayoutCoordinates>()
    private var navigation: Job? = null
    private var generation = 0
    private var heldJump = false

    // Bound by rememberAccordion on every composition.
    internal var scope: CoroutineScope? = null
    internal var scroll: ScrollState? = null
    internal var memory: FocusMemory? = null
    internal var focusManager: FocusManager? = null
    internal var density = 1f

    fun entry(key: String): FocusRequester = entries.getOrPut(key) { FocusRequester() }
    internal fun anchor(key: String): FocusRequester = anchors.getOrPut(key) { FocusRequester() }

    /** A single block never collapses; otherwise only the active collapsible block is expanded. */
    fun isExpanded(key: String): Boolean = key !in COLLAPSIBLE || active == key || order.size <= 1

    /** A held direction jumps once out of versions or tracks; repeats are consumed until the remote is released. */
    internal fun sectionShortcut(from: String, down: Boolean, up: Boolean, released: Boolean, heldMs: Long, jump: (Int) -> Unit): Boolean {
        if (!down && !up) return false
        if (released) {
            val consumed = heldJump
            heldJump = false
            return consumed
        }
        if (heldJump) return true
        if (from !in COLLAPSIBLE || heldMs < 450) return false
        heldJump = true
        jump(if (down) 1 else -1)
        return true
    }

    /** Records the block's current element before a key can move focus away from it. */
    internal fun noteFocus(key: String) {
        // While a transition runs, lastKey still names the source's element (focus is on an anchor).
        val memory = memory ?: return
        if (handoff != null || memory.restorePending) return
        memory.lastKey?.let { lastKeys[key] = it }
    }

    internal fun remembered(key: String): String? = lastKeys[key]

    internal fun gainedFocus(key: String) {
        active = key
    }

    /**
     * The row kept in place when [key] is entered: it lies above every block that changes height,
     * so the alignment is computed once and the resize happens beneath it. Tracks and cast keep the
     * versions bar above them; recommendations keep the cast row.
     */
    internal fun alignmentRow(key: String): String {
        val i = order.indexOf(key)
        if (i < 0) return key
        if (key == SECTION_RECS) return order.getOrNull(i - 1) ?: key
        if (key in COLLAPSIBLE || key == SECTION_CAST) return order.take(i + 1).firstOrNull { it in COLLAPSIBLE } ?: key
        return key
    }

    internal fun noteLayout(key: String, c: LayoutCoordinates) { coordinates[key] = c }

    private fun topOf(key: String): Float? = coordinates[key]?.takeIf { it.isAttached }?.positionInRoot()?.y

    internal fun cancelNavigation() {
        navigation?.cancel()
        navigation = null
        handoff = null
        heldJump = false
    }

    /** Focus came back to the header: the whole page scrolls back up so the title shows again. */
    fun showHeader() {
        cancelNavigation()
        val scroll = scroll ?: return
        if (scroll.value > 0) navigation = scope?.launch { scroll.animateScrollTo(0, tween(TRANSITION_MS, easing = FastOutSlowInEasing)) }
    }

    /**
     * Moves to the block [delta] away from [from] (Up from the first goes to the header); false when
     * there is none.
     */
    fun step(from: String, delta: Int): Boolean {
        val to = order.getOrNull(order.indexOf(from) + delta)
        if (to == null) {
            val h = header ?: return false
            if (delta > 0) return false
            cancelNavigation()
            navigation = scope?.launch { h.requestWhenReady() }
            return true
        }
        if (from in COLLAPSIBLE) heldJump = true
        open(to, delta)
        return true
    }

    /**
     * Enters [to]: park on its anchor, switch blocks in one frame, align once, restore its element.
     * A block without its default element (版本 with only 「n 台未响应」) takes its first control;
     * one with no control at all is passed over in the direction of travel.
     */
    fun open(to: String, delta: Int = 1) {
        val scope = scope ?: return
        navigation?.cancel()
        val token = ++generation
        handoff = to
        val remembered = lastKeys[to]
        val source = memory?.lastKey
        navigation = scope.launch {
            try {
                // The anchor is always placed, so focus can leave the source before it collapses.
                if (!anchor(to).requestWhenReady(600)) return@launch
                active = to
                align(to)
                if (restore(remembered) || entry(to).requestWhenReady(300)) return@launch
                // From the anchor at the block's top, Down lands on its first control (the block's
                // focus group keeps the search inside it).
                if (focusManager?.moveFocus(FocusDirection.Down) == true) return@launch
                val next = order.getOrNull(order.indexOf(to) + delta)
                if (next != null) open(next, delta)
                // Nothing further that way: back to where focus came from.
                else if (!restore(source)) header?.requestWhenReady()
            } finally {
                if (generation == token) handoff = null
            }
        }
    }

    private fun CoroutineScope.align(key: String) {
        val scroll = scroll ?: return
        val top = topOf(alignmentRow(key)) ?: return
        val target = (scroll.value + top - SECTION_TOP.value * density).toInt().coerceAtLeast(0)
        if (kotlin.math.abs(scroll.value - target) > 1) launch { scroll.animateScrollTo(target, tween(TRANSITION_MS, easing = FastOutSlowInEasing)) }
    }

    /** Returns focus to [key] once its element is composed in the expanded block. */
    private suspend fun restore(key: String?): Boolean {
        val memory = memory ?: return false
        if (key == null) return false
        memory.lastKey = key
        memory.beginRestore()
        withTimeoutOrNull(300) { snapshotFlow { memory.restorePending }.first { !it } }
        val ok = memory.restored
        memory.restorePending = false
        return ok
    }
}

/** Keeps the accordion of one detail page across recompositions and binds it to the page's scroll. */
@Composable
fun rememberAccordion(first: String, scroll: ScrollState): Accordion {
    val accordion = rememberRetained("detailAccordion", first) { Accordion(first) }
    accordion.scope = rememberCoroutineScope()
    accordion.scroll = scroll
    accordion.memory = LocalNavEntry.current?.focus
    accordion.focusManager = LocalFocusManager.current
    accordion.density = LocalDensity.current.density
    DisposableEffect(accordion) { onDispose { accordion.cancelNavigation() } }
    return accordion
}

/** A block that never collapses (剧集, 演职人员, 为你推荐). */
@Composable
fun DetailBlock(accordion: Accordion, key: String, content: @Composable () -> Unit) {
    BlockFrame(accordion, key, animateHeight = false) { content() }
}

/**
 * A collapsible block: [content] when expanded, else the `mini` bar (88 high, radius 24, 4 % white):
 * the block name (22/700, 150 wide), a one-line [summary] and the [count]. Both are composed and
 * measured all the time, so switching only changes which is placed, the wrapper's height and the
 * layers' alpha; nothing is laid out for the first time in the middle of a transition.
 */
@Composable
fun CollapsibleSection(
    accordion: Accordion,
    key: String,
    title: String,
    count: String?,
    barHeight: Dp = 88.dp,
    summary: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    val expanded = accordion.isExpanded(key)
    val reveal by animateFloatAsState(if (expanded) 1f else 0f, tween(TRANSITION_MS, easing = FastOutSlowInEasing), label = "section-reveal")
    BlockFrame(accordion, key) {
        Layout(content = {
            Box(Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen; alpha = 1f - reveal }) { MiniBar(title, count, barHeight, summary) }
            Box(Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen; alpha = reveal; translationY = (1f - reveal) * 12.dp.toPx() }) { content() }
        }) { children, constraints ->
            val mini = children[0].measure(constraints)
            val full = children[1].measure(constraints)
            val visible = if (expanded) full else mini
            layout(visible.width, visible.height) {
                if (!expanded || reveal < 1f) mini.placeRelative(0, 0)
                if (expanded || reveal > 0f) full.placeRelative(0, 0)
            }
        }
    }
}

/** Focus and key handling shared by all blocks. */
@Composable
private fun BlockFrame(accordion: Accordion, key: String, animateHeight: Boolean = true, content: @Composable () -> Unit) {
    val memory = LocalNavEntry.current?.focus
    val focusManager = LocalFocusManager.current
    Column(
        Modifier
            .fillMaxWidth()
            // Only the wrapper's height animates; the children keep their final constraints. The
            // animation clips to the wrapper, so blocks that never collapse go without it and their
            // focused cards keep the full scaled outline and shadow.
            .then(if (animateHeight) Modifier.animateContentSize(tween(TRANSITION_MS, easing = FastOutSlowInEasing), alignment = Alignment.TopStart) else Modifier)
            .onGloballyPositioned { accordion.noteLayout(key, it) }
            .onFocusChanged { if (it.hasFocus) accordion.gainedFocus(key) }
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown) accordion.noteFocus(key)
                accordion.sectionShortcut(
                    key, e.key == Key.DirectionDown, e.key == Key.DirectionUp,
                    e.type == KeyEventType.KeyUp,
                    if (e.nativeKeyEvent.isLongPress || e.nativeKeyEvent.repeatCount > 0) 450L else e.nativeKeyEvent.eventTime - e.nativeKeyEvent.downTime,
                ) { delta -> accordion.step(key, delta) }
            }
            .onKeyEvent { e ->
                val delta = when (e.key) {
                    Key.DirectionDown -> 1
                    Key.DirectionUp -> -1
                    else -> 0
                }
                if (delta == 0 || e.type != KeyEventType.KeyDown) return@onKeyEvent false
                // Still parked on the anchor: the press goes on to the next block right away.
                if (accordion.handoff == key) { accordion.step(key, delta); return@onKeyEvent true }
                // Inside the block first; at its edge, the next block (the search cannot leave the block).
                val before = memory?.lastKey
                val moved = focusManager.moveFocus(if (delta > 0) FocusDirection.Down else FocusDirection.Up)
                // A nested onEnter can redirect focus while moveFocus itself reports cancellation.
                if (moved || memory?.lastKey != before) return@onKeyEvent true
                accordion.step(key, delta)
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
        content()
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
