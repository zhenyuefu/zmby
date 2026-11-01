package app.zmby.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.zmby.media.formatClock
import app.zmby.segments.SegmentKind
import app.zmby.ui.components.Focusable
import app.zmby.ui.components.GlassButton
import app.zmby.ui.components.Glyph
import app.zmby.ui.components.Icon
import app.zmby.ui.theme.Z
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

private val Directions = setOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)
private val Confirm = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

/** The focusable window's content: a skip button or the next-episode card (ui-spec §2.16). */
@Composable
fun OverlayPrompts(overlay: PlaybackOverlay) {
    when (val p = overlay.prompt.value) {
        is OverlayPrompt.Skip -> SkipPrompt(overlay, p)
        OverlayPrompt.Next -> overlay.card?.let { NextCard(overlay, it) }
        null -> Unit
    }
}

private fun skipLabel(kind: SegmentKind) = when (kind) {
    SegmentKind.Intro -> "跳过片头"
    SegmentKind.Recap -> "跳过回顾"
    SegmentKind.Credits -> "跳过片尾"
    SegmentKind.Preview -> "跳过预告"
}

private fun keepLabel(kind: SegmentKind) = when (kind) {
    SegmentKind.Intro -> "继续看片头"
    SegmentKind.Recap -> "继续看回顾"
    SegmentKind.Credits -> "继续看片尾"
    SegmentKind.Preview -> "继续看预告"
}

/**
 * `PlayerSkip`: a white pill with an 8-second ring; OK seeks, 返回 or any direction closes it and
 * gives the keys back (that key is not passed to the player, hence the hint bar).
 */
@Composable
private fun SkipPrompt(overlay: PlaybackOverlay, skip: OverlayPrompt.Skip) {
    val focus = remember { FocusRequester() }
    var left by remember(skip) { mutableIntStateOf(OverlayPlan.SKIP_SECONDS) }
    LaunchedEffect(skip) {
        runCatching { focus.requestFocus() }
        while (left > 0) {
            delay(1_000)
            left--
        }
        overlay.dismiss()
    }
    Column(
        Modifier.onPreviewKeyEvent { e ->
            // Every key but OK closes it (play/pause too: this window has the keys, the player does
            // not see this press). Closed on key up, so the player never sees half of a press.
            if (e.key in Confirm) return@onPreviewKeyEvent false
            if (e.type == KeyEventType.KeyUp) overlay.dismiss()
            true
        },
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Focusable({ overlay.confirmSkip(skip) }, Modifier.focusRequester(focus), shape = RoundedCornerShape(percent = 50), focusedScale = 1f, outline = 0.dp, shadow = 24.dp, label = skipLabel(skip.kind)) {
            Row(
                Modifier.height(80.dp).background(Color.White, RoundedCornerShape(percent = 50)).padding(start = 14.dp, end = 34.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                CountdownRing(left, OverlayPlan.SKIP_SECONDS, Z.bg)
                Icon(Glyph.Skip, size = 28.dp, color = Z.bg)
                Text(skipLabel(skip.kind), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Z.bg)
            }
        }
        HintBar(listOf("OK" to "跳到 ${formatClock(skip.targetMs)}", "返回" to keepLabel(skip.kind)))
    }
}

@Composable
private fun CountdownRing(left: Int, total: Int, color: Color) {
    Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(52.dp)) {
            val stroke = 4.dp.toPx()
            val inset = stroke / 2
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(color.copy(alpha = 0.18f), 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
            drawArc(color, -90f, 360f * left / total, false, Offset(inset, inset), arc, style = Stroke(stroke))
        }
        Text("$left", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun HintBar(keys: List<Pair<String, String>>) {
    Row(
        Modifier.background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(14.dp)).padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((key, what) in keys) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                KeyCap(key)
                Text(what, fontSize = 18.sp, color = Z.textSecondary)
            }
        }
    }
}

@Composable
private fun KeyCap(text: String) {
    Box(
        Modifier.border(1.5.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
}

/**
 * `PlayerNext`: 760 wide, 72% dark. 立即播放 ends this episode so the next one starts when the player
 * returns; 看完片尾 closes the card (the usual prompt follows at the end); 返回 cancels.
 */
@Composable
private fun NextCard(overlay: PlaybackOverlay, card: UpNextCard) {
    val focus = remember { FocusRequester() }
    var left by remember(card.episode.id) { mutableIntStateOf(if (overlay.countdown) OverlayPlan.NEXT_SECONDS else -1) }
    LaunchedEffect(card.episode.id) {
        runCatching { focus.requestFocus() }
        while (left > 0) {
            delay(1_000)
            if (left > 0) left--
        }
        if (left == 0) overlay.playNextNow()
    }
    Column(
        Modifier
            .width(760.dp)
            .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(32.dp))
            .padding(28.dp)
            .onPreviewKeyEvent { e ->
                when {
                    e.key in Directions -> { if (e.type == KeyEventType.KeyDown) left = -1; false }
                    e.key in Confirm -> false
                    e.key == Key.Back -> { if (e.type == KeyEventType.KeyUp) overlay.cancelNext(); true }
                    // Any other key (play/pause): the card gets out of the way, as 看完片尾.
                    else -> { if (e.type == KeyEventType.KeyUp) overlay.watchCredits(); true }
                }
            },
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(Modifier.size(300.dp, 169.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.08f))) {
                card.imageUrl?.let { AsyncImage(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(300.dp, 169.dp)) }
                val duration = overlay.durationMs.longValue
                val watched = if (duration > 0) (overlay.positionMs.longValue.toFloat() / duration).coerceIn(0f, 1f) else 0f
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(6.dp).background(Color.White.copy(alpha = 0.25f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(watched).background(Color.White))
                }
            }
            Column(Modifier.height(169.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("下一集", fontSize = 18.sp, color = Z.textSecondary)
                Text(card.title, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (card.meta.isNotBlank()) Text(card.meta, fontSize = 18.sp, color = Z.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (left > 0) Text("$left 秒后播放", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassButton("立即播放", { overlay.playNextNow() }, Modifier.focusRequester(focus), glyph = Glyph.Play, height = 64.dp, horizontalPadding = 30.dp, fontSize = 22)
            GlassButton("看完片尾", { overlay.watchCredits() }, height = 64.dp, horizontalPadding = 30.dp, fontSize = 22)
            Box(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                KeyCap("返回")
                Text("取消", fontSize = 18.sp, color = Z.textSecondary)
            }
        }
    }
}
