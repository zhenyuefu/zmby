package app.zmby.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.zmby.ui.theme.Z
import kotlinx.coroutines.delay

/** One result shown in the bottom capsule; [retry] is what OK does while a failure is on screen. */
data class ActionNotice(val text: String, val failed: Boolean, val retry: (() -> Unit)?, val id: Long)

/**
 * 操作结果提示 (ui-spec §2.15): the outcome of an action (标记已观看, 收藏, 待看, 季集映射 …) as a
 * capsule at the bottom centre that fades after [durationMs]. It never takes focus; while a failure
 * with a retry is shown, OK retries instead of reaching the focused element. A newer result replaces
 * the one on screen. Screens get it from [LocalActionFeedback] inside an [ActionFeedbackHost].
 */
@Stable
class ActionFeedback(val durationMs: Long = 2_500) {
    var notice by mutableStateOf<ActionNotice?>(null)
        private set
    private var next = 0L

    fun success(text: String) = show(ActionNotice(text, failed = false, retry = null, id = ++next))

    fun failure(text: String, retry: (() -> Unit)? = null) = show(ActionNotice(text, failed = true, retry = retry, id = ++next))

    private fun show(n: ActionNotice) { notice = n }

    /** Removes [shown] once its time is up, unless a newer notice has replaced it meanwhile. */
    fun expire(shown: ActionNotice) { if (notice?.id == shown.id) notice = null }

    /** OK while a failure with a retry is on screen: hides it and runs the retry. False otherwise. */
    fun retry(): Boolean {
        val n = notice?.takeIf { it.failed } ?: return false
        val action = n.retry ?: return false
        notice = null
        action()
        return true
    }

    val retryPending: Boolean get() = notice?.let { it.failed && it.retry != null } == true
}

val LocalActionFeedback = staticCompositionLocalOf<ActionFeedback?> { null }

/**
 * Provides [feedback] to [content] and draws its capsule over it, at the bottom centre. OK presses
 * are taken (both key down and up, so the focused button does not also fire) only while a failure
 * with a retry is visible.
 */
@Composable
fun ActionFeedbackHost(
    modifier: Modifier = Modifier,
    feedback: ActionFeedback = remember { ActionFeedback() },
    /** Distance of the capsule from the bottom edge (a dialog keeps it clear of its panel). */
    toastBottom: androidx.compose.ui.unit.Dp = 56.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    var retryPress by remember { mutableStateOf(false) }
    CompositionLocalProvider(LocalActionFeedback provides feedback) {
        Box(modifier.onPreviewKeyEvent { e ->
            val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
            when {
                !ok -> false
                e.type == KeyEventType.KeyDown && (retryPress || feedback.retryPending) -> { retryPress = true; true }
                e.type == KeyEventType.KeyUp && retryPress -> { retryPress = false; feedback.retry(); true }
                else -> false
            }
        }) {
            content()
            ActionToast(feedback, Modifier.align(Alignment.BottomCenter).padding(bottom = toastBottom))
        }
    }
}

@Composable
private fun ActionToast(feedback: ActionFeedback, modifier: Modifier) {
    val current = feedback.notice
    // The last notice stays composed while it fades out.
    var last by remember { mutableStateOf<ActionNotice?>(null) }
    if (current != null) last = current
    LaunchedEffect(current?.id) {
        val shown = current ?: return@LaunchedEffect
        delay(feedback.durationMs)
        feedback.expire(shown)
    }
    AnimatedVisibility(current != null, modifier, enter = fadeIn(tween(150)), exit = fadeOut(tween(300))) {
        last?.let { ToastCapsule(it) }
    }
}

private val ToastShape = RoundedCornerShape(percent = 50)

@Composable
private fun ToastCapsule(n: ActionNotice) {
    Row(
        Modifier
            .shadow(14.dp, ToastShape, clip = false, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.35f))
            .toastSurface()
            .height(64.dp)
            .widthIn(max = 1400.dp)
            .padding(horizontal = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (n.failed) HealthDot(Health.Warn, size = 10.dp) else Icon(Glyph.Check, size = 22.dp, color = Z.ok, strokeWidth = 2.8f)
        Text(n.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Z.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (n.failed && n.retry != null) Text("按确认重试", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.65f), maxLines = 1)
    }
}

/** `.toast`: dark frosted capsule (16,17,21 at 62 %) with a faint top highlight and inner stroke. */
private fun Modifier.toastSurface(): Modifier = drawWithCache {
    val outline = ToastShape.createOutline(size, layoutDirection, this)
    val path = Path().apply { addOutline(outline) }
    val stroke = Stroke(2.dp.toPx())
    val edge = (6.dp.toPx() / size.height).coerceIn(0.02f, 0.5f)
    val rim = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.16f), edge to Color.Transparent)
    onDrawBehind {
        clipPath(path) {
            drawRect(Color(0xE0101115))
            drawOutline(outline, Color.White.copy(alpha = 0.08f), style = stroke)
            drawOutline(outline, rim, style = stroke)
        }
    }
}
