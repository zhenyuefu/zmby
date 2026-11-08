package app.zmby.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.zmby.trailer.TrailerFormat
import app.zmby.trailer.TrailerInfo
import app.zmby.ui.components.ActionIconButton
import app.zmby.ui.components.GlassButton
import app.zmby.ui.components.GlassIconButton
import app.zmby.ui.components.Glyph
import app.zmby.ui.components.KeyCap
import app.zmby.ui.components.TrailerFullSurface
import app.zmby.ui.components.TrailerPlayer
import app.zmby.ui.components.rememberTrailerPlayer
import app.zmby.ui.components.trailerClock
import app.zmby.zmby
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

/*
 * The detail pages' 预告片 button and the full-screen trailer it opens (ui-spec §2.23;
 * TrailerDetail.dc.html, TrailerFull.dc.html). zmby's own player plays it with sound, on a SurfaceView,
 * never the Zidoo player, and records no progress. The background trailer stops for the rest of the visit.
 */

/** The page's full-screen trailer: open or not, and how to get back to the button that opened it. */
@Stable
internal class FullTrailerState {
    var open by mutableStateOf(false)
        private set

    /** Opened once this visit: the background trailer does not start again. */
    var used by mutableStateOf(false)
        private set

    /** Back on the page after closing: focus returns to the 预告片 button. */
    internal var returning by mutableStateOf(false)
    internal val button = FocusRequester()

    /** What 「播放正片」 does: the same as the header's main button (set by the header). */
    internal var playMain: (() -> Unit)? = null

    fun show() {
        used = true
        open = true
    }

    fun close() {
        open = false
        returning = true
    }
}

@Composable
internal fun rememberFullTrailer(): FullTrailerState = remember { FullTrailerState() }

/** 「预告片 · 2:31」, or 「预告片」 when the length is not known. */
internal fun trailerTip(info: TrailerInfo): String =
    info.runtimeSec?.takeIf { it > 0 }?.let { "预告片 · ${trailerClock(it * 1000L)}" } ?: "预告片"

/** 「预告片 · IMDb」, 「预告片 · 烂番茄」, or the server's name for its own trailer. */
internal fun trailerSourceLabel(info: TrailerInfo, serverName: (String) -> String?): String = "预告片 · " + when (info.format) {
    TrailerFormat.Mp4 -> "IMDb"
    TrailerFormat.Hls -> "烂番茄"
    TrailerFormat.Server -> serverName(info.source) ?: "服务器"
}

/**
 * The header's 预告片 icon button, first after the divider; absent (taking no place) until a trailer
 * is found. Its focus tag reads 「预告片 · 2:31」.
 */
@Composable
internal fun TrailerAction(info: TrailerInfo?, state: FullTrailerState) {
    if (info == null) return
    LaunchedEffect(state.returning) {
        if (state.returning) {
            state.button.requestWhenReady()
            state.returning = false
        }
    }
    ActionIconButton(Glyph.Trailer, trailerTip(info), state::show, Modifier.focusRequester(state.button), solidWhenOn = false, focusKey = "action:trailer")
}

/** Controls fade out after this long without a key; any key brings them back. */
private const val CONTROLS_HIDE_MS = 3_000L

/**
 * The full-screen trailer over the whole page while [FullTrailerState.open]: the video letterboxed on
 * black, the title and source at the top left, the progress bar and 暂停 · 声音 · 播放正片 · 返回 关闭
 * at the bottom. Focus starts on 暂停; it closes itself at the end, and Back closes it.
 */
@Composable
internal fun FullTrailerHost(state: FullTrailerState, info: TrailerInfo?, title: String, background: TrailerPlayer) {
    if (!state.open || info == null) return
    val app = LocalContext.current.zmby
    val player = rememberTrailerPlayer()
    val key = "full:${info.cacheKey}"
    LaunchedEffect(key) {
        background.stop()
        player.load(key, info, sound = true)
    }
    // Played to its end: back to the page, focus on the button.
    LaunchedEffect(player, key) {
        snapshotFlow { player.finished }.filter { it == key }.first()
        state.close()
    }
    BackHandler(onBack = state::close)
    var keys by remember { mutableIntStateOf(0) }
    var shown by remember { mutableStateOf(true) }
    LaunchedEffect(keys, shown, player.paused) {
        if (shown && !player.paused) {
            delay(CONTROLS_HIDE_MS)
            shown = false
        }
    }
    val controls by animateFloatAsState(if (shown) 1f else 0f, tween(if (shown) 200 else 500), label = "trailerControls")
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    LaunchedEffect(player) {
        while (true) {
            position = player.positionMs()
            duration = player.durationMs()
            delay(250)
        }
    }
    val pause = remember { FocusRequester() }
    LaunchedEffect(Unit) { pause.requestWhenReady() }
    val source = remember(info) { trailerSourceLabel(info) { id -> app.registry.get(id)?.config?.name } }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                keys++
                // Hidden controls: the first key only brings them back (Back still closes).
                if (!shown && e.key != Key.Back) {
                    shown = true
                    true
                } else false
            },
    ) {
        if (player.key != null) TrailerFullSurface(player)
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = controls }) {
            Box(Modifier.fillMaxWidth().height(260.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent))))
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(380.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)))))
            Column(Modifier.padding(start = 96.dp, top = 64.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1)
                Text(source, fontSize = 20.sp, color = Color.White.copy(alpha = 0.72f), maxLines = 1)
            }
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 96.dp, end = 96.dp, bottom = 72.dp)
                    .fillMaxWidth()
                    .focusProperties { onExit = { cancelFocusChange() } }
                    .focusGroup(),
                verticalArrangement = Arrangement.spacedBy(26.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    Text(trailerClock(position, roundUp = false), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    ProgressBar(if (duration > 0) position.toFloat() / duration else 0f, Modifier.weight(1f))
                    Text(if (duration > 0) trailerClock(duration) else "--:--", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.7f))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    GlassIconButton(if (player.paused) Glyph.Play else Glyph.Pause, player::togglePause, Modifier.focusRequester(pause),
                        size = 64.dp, glyphSize = 26.dp, label = if (player.paused) "播放" else "暂停")
                    GlassIconButton(if (player.sound) Glyph.Sound else Glyph.Muted, player::toggleSound,
                        size = 64.dp, glyphSize = 26.dp, label = if (player.sound) "关闭声音" else "打开声音")
                    Spacer(Modifier.width(10.dp))
                    GlassButton("播放正片", {
                        val play = state.playMain
                        state.close()
                        play?.invoke()
                    }, glyph = Glyph.Play, height = 64.dp, horizontalPadding = 30.dp, fontSize = 22)
                    Spacer(Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KeyCap("返回")
                        Text("关闭", fontSize = 18.sp, color = Color.White.copy(alpha = 0.65f))
                    }
                }
            }
        }
    }
}

/** The 8-high track with its white fill and a 20 round knob at the playing point. */
@Composable
private fun ProgressBar(fraction: Float, modifier: Modifier) {
    Canvas(modifier.height(20.dp)) {
        val h = 8.dp.toPx()
        val top = (size.height - h) / 2
        val f = fraction.coerceIn(0f, 1f)
        drawRoundRect(Color.White.copy(alpha = 0.25f), Offset(0f, top), Size(size.width, h), CornerRadius(h / 2))
        drawRoundRect(Color.White, Offset(0f, top), Size(size.width * f, h), CornerRadius(h / 2))
        drawCircle(Color.Black.copy(alpha = 0.3f), radius = 11.dp.toPx(), center = Offset(size.width * f, size.height / 2 + 1.dp.toPx()))
        drawCircle(Color.White, radius = 10.dp.toPx(), center = Offset(size.width * f, size.height / 2))
    }
}
