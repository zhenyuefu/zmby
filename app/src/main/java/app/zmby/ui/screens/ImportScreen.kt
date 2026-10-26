package app.zmby.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
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
import app.zmby.data.*
import app.zmby.log.LanServer
import app.zmby.ui.*
import app.zmby.ui.components.*
import app.zmby.ui.theme.Z
import app.zmby.zmby
import com.google.zxing.*
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.*

/** ImportTV.dc.html: QR/code on the left, browser state and grouped results on the right. */
@Composable
fun ImportScreen(onDone: () -> Unit, onBackup: () -> Unit = {}, backupTab: Boolean = false) {
    val app = LocalContext.current.zmby
    var generation by remember { mutableIntStateOf(0) }
    val session = remember(generation) {
        ImportSession(app.lanServer,
            add = { e -> app.library.addServer(e.url,e.user,e.password,e.name,e.lineName) },
            export = { ServerImport.export(app.registry.all.map { it.config }) }, backups = app.backups,
            relogin = { app.registry.all.map { it.config }.filter { it.needsLogin } })
    }
    var port by remember(session) { mutableStateOf<Int?>(null) }
    var ip by remember(session) { mutableStateOf<String?>(null) }
    var failed by remember(session) { mutableStateOf(false) }
    LaunchedEffect(session) { withContext(Dispatchers.IO) {
        try { port = session.open(); ip = LanServer.lanAddresses().firstOrNull() } catch (_: Exception) { failed = true }
    } }
    DisposableEffect(session) { onDispose { session.close() } }
    DisposableEffect(Unit) { onDispose { if (!app.settings.lanLog.value) app.appScope.launch(Dispatchers.IO) { app.lanServer.stop() } } }
    val results by session.results.collectAsState()
    val devices by session.devices.collectAsState()
    val progress by session.progress.collectAsState()
    val expires by session.expiresAt.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(session) { while (true) { delay(1000); now = System.currentTimeMillis(); if (port != null && now >= expires) { generation++; break } } }
    val url = if (port != null && ip != null) "http://$ip:$port/import/${session.code}" + if (backupTab) "#backup" else "" else null
    val remaining = ((expires-now)/1000).coerceAtLeast(0)
    Column(Modifier.fillMaxSize().padding(horizontal = 80.dp, vertical = 56.dp), verticalArrangement = Arrangement.spacedBy(40.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("扫码添加服务器", fontSize = 48.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            GlassButton("备份与恢复", onBackup)
            Spacer(Modifier.width(18.dp))
            GlassButton("完成", onDone, Modifier.focusRequester(rememberInitialFocus(Unit)))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(64.dp)) {
            Column(Modifier.width(600.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                Box(Modifier.size(420.dp).background(Color.White,RoundedCornerShape(32.dp)).padding(30.dp), contentAlignment = Alignment.Center) {
                    if (url != null) QrCode(url,Modifier.fillMaxSize()) else Text(if (failed) "局域网页面无法启动" else "正在启动…", color = Z.onFocus)
                }
                Text("手机、平板或电脑扫码打开", fontSize = 22.sp, color = Z.textSecondary)
                if (url != null) Text(url!!, fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text("导入码", fontSize = 20.sp, color = Z.textSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    session.code.forEachIndexed { i,c ->
                        if (i == 3) Spacer(Modifier.width(12.dp))
                        Box(Modifier.size(72.dp,92.dp).well(RoundedCornerShape(18.dp)),contentAlignment = Alignment.Center) {
                            Text(c.toString(),fontSize = 46.sp,fontWeight = FontWeight.Bold,fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
            Column(Modifier.weight(1f),verticalArrangement = Arrangement.spacedBy(22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    HealthDot(if(devices.isEmpty()) Health.Off else Health.Ok)
                    Text(when(devices.size) { 0 -> "等待扫码"; 1 -> "${devices.first()} 已打开页面"; else -> "${devices.size} 台设备已打开页面" },fontSize = 21.sp)
                    Spacer(Modifier.weight(1f))
                    Text("剩余 ${remaining/60} 分 ${remaining%60} 秒",fontSize = 20.sp,color = Z.textSecondary)
                }
                val added = results.filter { it.ok }.distinctBy { it.serverId }.size
                Text(if(results.isEmpty()) "还没有添加" else "已添加 $added 台服务器 · ${results.count { it.ok }} 条线路",fontSize = 30.sp,fontWeight = FontWeight.Bold)
                if(progress.isNotBlank()) Text(progress,fontSize = 22.sp,color = Z.warn)
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    results.asReversed().groupBy { it.serverId }.forEach { (_,rows) ->
                        Column(Modifier.fillMaxWidth().groupCard(RoundedCornerShape(28.dp)).padding(26.dp),verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                HealthDot(if(rows.all { it.ok }) Health.Ok else Health.Warn)
                                Text(rows.first().label,fontSize = 24.sp,fontWeight = FontWeight.Bold,modifier = Modifier.weight(1f))
                                Badge(if(rows.any { it.merged }) "合并到现有" else "新增")
                            }
                            rows.forEach { r -> Text(listOf(r.lineName,ServerRegistry.hostOf(r.url),r.message.replace('/',' ')).filter { it.isNotBlank() }.joinToString(" · "),fontSize = 20.sp,color = if(r.ok) Z.textSecondary else Z.warn) }
                        }
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
