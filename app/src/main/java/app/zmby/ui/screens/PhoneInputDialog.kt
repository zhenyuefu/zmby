package app.zmby.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.zmby.ZmbyApp
import app.zmby.data.InputField
import app.zmby.data.InputSession
import app.zmby.data.InputState
import app.zmby.emby.UaMode
import app.zmby.log.LanServer
import app.zmby.log.ZLog
import app.zmby.ui.components.GlassButton
import app.zmby.ui.components.Health
import app.zmby.ui.components.HealthDot
import app.zmby.ui.components.LocalBackdrop
import app.zmby.ui.components.glass
import app.zmby.ui.components.panelSurface
import app.zmby.ui.components.well
import app.zmby.ui.theme.Z
import app.zmby.zmby
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How a field is checked and where a good value goes (ui-spec §2.13). [server] is for the User-Agent. */
internal fun ZmbyApp.inputTarget(field: InputField, server: String? = null): Pair<suspend (String) -> String?, (String) -> Unit> = when (field) {
    InputField.TmdbApiBase -> Pair(
        { v -> app.zmby.catalog.TmdbEndpoint.check(v) },
        { v -> settings.tmdbApiBase.set(v) },
    )
    InputField.TmdbToken -> Pair(
        { v -> catalog.verifyToken(v) },
        { v -> ZLog.secret(v); settings.tmdbToken.set(v) },
    )
    InputField.PublicMetaDbKey -> Pair(
        { v -> if (v.length < 8 || v.length > 512) "密钥长度不对，请检查是否复制完整" else null },
        { v -> ZLog.secret(v); settings.publicMetaDbKey.set(v) },
    )
    InputField.UserAgent -> Pair(
        { v -> InputSession.checkUserAgent(v) },
        { v -> server?.let { library.setUa(it, UaMode.Custom, v) } },
    )
}

/**
 * 用手机输入 on the TV (TokenTV.dc.html): a 1200 × 700 panel over the page darkened to 70 %: the QR code,
 * the field, the full URL and the six-digit code, a status pill (等待手机 → iPhone 已打开页面 → 正在验证…
 * → 已保存, or the reason in yellow), 取消 (focused first) and 用遥控器输入. A saved value closes the
 * panel after 1.5 s; closing it ends the page at once.
 */
@Composable
fun PhoneInputDialog(field: InputField, server: String? = null, onDismiss: () -> Unit, onRemote: (() -> Unit)? = null) {
    BlockSideNav()
    val app = LocalContext.current.zmby
    var generation by remember { mutableIntStateOf(0) }
    val session = remember(generation) {
        val (validate, save) = app.inputTarget(field, server)
        InputSession(app.lanServer, field, validate, save)
    }
    var port by remember(session) { mutableStateOf<Int?>(null) }
    var ip by remember(session) { mutableStateOf<String?>(null) }
    var failed by remember(session) { mutableStateOf(false) }
    LaunchedEffect(session) {
        withContext(Dispatchers.IO) {
            try { port = session.open(); ip = LanServer.lanAddresses().firstOrNull() } catch (_: Exception) { failed = true }
        }
    }
    DisposableEffect(session) { onDispose { session.close() } }
    DisposableEffect(Unit) { onDispose { if (!app.settings.lanLog.value) app.appScope.launch(Dispatchers.IO) { app.lanServer.stop() } } }
    val state by session.state.collectAsState()
    val locked by session.locked.collectAsState()
    val expires by session.expiresAt.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(session) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
            // Ten minutes without an accepted request: a new code and QR code, the panel stays.
            if (port != null && !locked && state != InputState.Saved && now >= expires) { generation++; break }
        }
    }
    LaunchedEffect(state) { if (state == InputState.Saved) { delay(1500); onDismiss() } }
    val cancel = remember { FocusRequester() }
    LaunchedEffect(Unit) { cancel.requestWhenReady() }
    val url = if (port != null && ip != null && !locked) "http://$ip:$port/input/${session.code}" else null
    val remaining = ((expires - now) / 1000).coerceAtLeast(0)
    // Its own window, so it covers the whole screen from wherever it is opened; Back closes it. The
    // window brings its own density: the design grid's is passed in again.
    val design = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
    // Glass here cannot read the page's backdrop: the dialog is another window (another layout tree).
    androidx.compose.runtime.CompositionLocalProvider(LocalBackdrop provides null, androidx.compose.ui.platform.LocalDensity provides design) {
    Box(Modifier.fillMaxSize().background(Color(0x80050608))) {
        Row(
            Modifier
                .padding(start = 360.dp, top = 190.dp)
                .size(1200.dp, 700.dp)
                .panelSurface(RoundedCornerShape(40.dp))
                .padding(56.dp)
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(56.dp),
        ) {
            Box(Modifier.size(380.dp).background(Color.White, RoundedCornerShape(32.dp)).padding(28.dp), contentAlignment = Alignment.Center) {
                if (url != null) QrCode(url, Modifier.fillMaxSize())
                else Text(
                    when { locked -> "输入码错误次数过多，页面已关闭"; failed -> "局域网页面无法启动"; else -> "正在启动…" },
                    color = Z.onFocus, fontSize = 22.sp,
                )
            }
            Column(Modifier.weight(1f).height(588.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                Text("用手机输入", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Z.textTertiary)
                Text(field.heading, fontSize = 40.sp, lineHeight = 46.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2)
                if (url != null) Text(url, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                if (!locked) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    session.code.forEachIndexed { i, c ->
                        if (i == 3) Spacer(Modifier.width(10.dp))
                        Box(Modifier.size(64.dp, 82.dp).well(RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                            Text(c.toString(), fontSize = 40.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                val (health, text) = when (val s = state) {
                    InputState.Waiting -> Health.Off to "等待手机 · 剩余 ${remaining / 60} 分 ${remaining % 60} 秒"
                    is InputState.Opened -> Health.Ok to if (s.count > 1) "${s.count} 台设备已打开页面" else "${s.device} 已打开页面"
                    InputState.Verifying -> Health.Warn to "正在验证…"
                    InputState.Saved -> Health.Ok to if (field == InputField.TmdbToken) "已保存 · 有效" else "已保存"
                    is InputState.Failed -> Health.Warn to s.reason
                }
                Row(
                    Modifier.glass(RoundedCornerShape(percent = 50), shadow = false).height(52.dp).padding(horizontal = 22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    HealthDot(if (locked) Health.Warn else health)
                    Text(if (locked) "输入码错误次数过多" else text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = if (state is InputState.Failed) Z.warn else Z.textPrimary, maxLines = 1)
                }
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    GlassButton("取消", onDismiss, Modifier.focusRequester(cancel), label = "用手机输入 取消")
                    if (locked) GlassButton("生成新的输入码", { generation++ })
                    if (onRemote != null) GlassButton("用遥控器输入", onRemote)
                }
            }
        }
    }
}
}
}
