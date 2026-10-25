package app.zmby.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.zmby.data.ImportSession
import app.zmby.data.ServerImport
import app.zmby.log.LanServer
import app.zmby.ui.components.GlassButton
import app.zmby.ui.components.Health
import app.zmby.ui.components.HealthDot
import app.zmby.ui.components.groupCard
import app.zmby.ui.rememberInitialFocus
import app.zmby.ui.theme.Z
import app.zmby.zmby
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter

/**
 * 扫码导入 (aggregation spec §3.1): a temporary page on the box's LAN server; the phone scans the QR
 * code (or opens the address and types the code) and pastes servers. Closing the screen closes
 * the page; the LAN server itself stops again unless the LAN log is on.
 */
@Composable
fun ImportScreen(onDone: () -> Unit) {
    val app = LocalContext.current.zmby
    val session = remember {
        ImportSession(
            app.lanServer,
            add = { e -> app.library.addServer(e.url, e.user, e.password, e.name, e.lineName) },
            export = { ServerImport.export(app.registry.all.map { it.config }) },
        )
    }
    var port by remember { mutableStateOf<Int?>(null) }
    var failed by remember { mutableStateOf(false) }
    DisposableEffect(session) {
        port = runCatching { session.open() }.onFailure { failed = true }.getOrNull()
        onDispose {
            session.close()
            if (!app.settings.lanLog.value) app.lanServer.stop()
        }
    }
    val results by session.results.collectAsState()
    val ip = remember(port) { LanServer.lanAddresses().firstOrNull() }
    val url = if (port != null && ip != null) "http://$ip:$port/import/${session.code}" else null

    Row(Modifier.fillMaxSize().padding(start = 80.dp, end = 80.dp, top = 56.dp, bottom = 56.dp), horizontalArrangement = Arrangement.spacedBy(64.dp)) {
        Column(Modifier.width(620.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            Text("扫码导入", fontSize = 48.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp)
            Box(Modifier.size(400.dp).background(Color.White, RoundedCornerShape(28.dp)).padding(28.dp), contentAlignment = Alignment.Center) {
                if (url != null) QrCode(url, Modifier.fillMaxSize())
                else Text(if (failed) "局域网页面无法启动" else "正在启动…", fontSize = 22.sp, color = Z.onFocus)
            }
            if (url != null) {
                Text("手机扫码，或在浏览器打开", fontSize = 22.sp, color = Z.textSecondary)
                Text(url, fontSize = 27.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("导入码", fontSize = 22.sp, color = Z.textSecondary)
                    Text(session.code.chunked(3).joinToString(" "), fontSize = 34.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("已添加 ${results.count { it.ok }} 条线路", fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                GlassButton("完成", onDone, Modifier.focusRequester(rememberInitialFocus(Unit)))
            }
            Column(
                Modifier.fillMaxWidth().groupCard(RoundedCornerShape(28.dp)).padding(26.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (results.isEmpty()) Text("等待手机提交", fontSize = 21.sp, color = Z.textTertiary)
                results.asReversed().forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        HealthDot(if (r.ok) Health.Ok else Health.Warn)
                        Text(r.label, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(320.dp), maxLines = 1)
                        Text(r.message.replace('/', ' '), fontSize = 19.sp, color = Z.textSecondary, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun QrCode(text: String, modifier: Modifier) {
    val matrix: BitMatrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
    }
    Canvas(modifier) {
        val cell = size.minDimension / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) drawRect(Z.onFocus, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}
