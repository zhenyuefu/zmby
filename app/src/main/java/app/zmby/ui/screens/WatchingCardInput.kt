package app.zmby.ui.screens

import android.view.ViewConfiguration
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

internal enum class WatchingPress { None, Play, Menu }

/** Decide on release so a held OK cannot also play or activate the newly opened menu. */
internal class WatchingCardPress(private val longPressMs: Long) {
    private var pressed = false
    private var repeated = false

    fun down(repeat: Boolean) {
        if (!pressed && repeat) return
        pressed = true
        repeated = repeated || repeat
    }

    fun up(heldMs: Long): WatchingPress {
        val result = when {
            !pressed -> WatchingPress.None
            repeated || heldMs >= longPressMs -> WatchingPress.Menu
            else -> WatchingPress.Play
        }
        cancel()
        return result
    }

    fun cancel() { pressed = false; repeated = false }
}

/** Remote Menu or held OK opens the card's actions; a short OK activates the card. */
internal fun Modifier.watchingCardInput(onPlay: () -> Unit, onMenu: () -> Unit): Modifier = composed {
    val press = remember { WatchingCardPress(ViewConfiguration.getLongPressTimeout().toLong()) }
    onFocusChanged { if (!it.hasFocus) press.cancel() }
        .onPreviewKeyEvent { e ->
            when (e.key) {
                Key.Menu -> {
                    if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) onMenu()
                    true
                }
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                    when (e.type) {
                        KeyEventType.KeyDown -> press.down(e.nativeKeyEvent.repeatCount > 0 || e.nativeKeyEvent.isLongPress)
                        KeyEventType.KeyUp -> when (press.up(e.nativeKeyEvent.eventTime - e.nativeKeyEvent.downTime)) {
                            WatchingPress.Play -> onPlay()
                            WatchingPress.Menu -> onMenu()
                            WatchingPress.None -> Unit
                        }
                    }
                    true
                }
                else -> false
            }
        }
}
