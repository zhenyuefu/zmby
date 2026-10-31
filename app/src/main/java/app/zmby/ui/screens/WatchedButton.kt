package app.zmby.ui.screens

import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.zmby.data.ServerItem
import app.zmby.data.withPlaybackProgress
import app.zmby.ui.components.GlassIconButton
import app.zmby.ui.components.Glyph
import app.zmby.zmby
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Keeps its focus identity when the action label changes, and serializes repeated remote presses. */
@Composable
internal fun WatchedButton(ref: ServerItem) {
    val context = LocalContext.current
    val app = context.zmby
    val scope = rememberCoroutineScope()
    val progress by app.recentProgress.collectAsState()
    val played = ref.withPlaybackProgress(progress).item.userData?.played == true
    var busy by remember { mutableStateOf(false) }
    GlassIconButton(Glyph.Check, onClick = {
        if (!busy) {
            busy = true
            scope.launch {
                try {
                    app.setWatched(ref, !played)
                } catch (e: CancellationException) { throw e
                } catch (_: Exception) {
                    Toast.makeText(context, "标记失败，请重试", Toast.LENGTH_SHORT).show()
                } finally { busy = false }
            }
        }
    }, on = played, label = "watched-toggle", modifier = Modifier.semantics {
        contentDescription = if (busy) "正在标记…" else if (played) "标记为未观看" else "标记为已观看"
    })
}
