package app.zmby.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.zmby.ui.rememberInitialFocus
import app.zmby.ui.theme.Z

/**
 * A page with nothing to show yet (P4 遥控器体验 · 离线与错误处理): 「正在加载…」 while [message] is
 * null, else what went wrong with a focused 「重试」 — never a screen without anything to focus,
 * where the remote can only go back. [onServers] adds 「服务器状态」 when the cause may be the server.
 */
@Composable
fun LoadState(message: String?, onRetry: () -> Unit, title: String? = null, onServers: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (message == null) {
            Text("正在加载…", fontSize = 24.sp, color = Z.textSecondary)
            return@Box
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(28.dp)) {
            if (title != null) Text(title, fontSize = 36.sp, fontWeight = FontWeight.ExtraBold)
            Text(message, fontSize = 24.sp, color = Z.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 1000.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                GlassButton("重试", onRetry, Modifier.focusRequester(rememberInitialFocus(message)), glyph = Glyph.Refresh)
                if (onServers != null) GlassButton("服务器状态", onServers)
            }
        }
    }
}
