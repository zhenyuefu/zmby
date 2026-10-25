package app.zmby.ui.screens

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.zmby.data.FavoriteSync
import app.zmby.emby.EmbyClient
import app.zmby.emby.UaSetting
import app.zmby.emby.UaMode
import app.zmby.data.LibraryView
import app.zmby.data.Override
import app.zmby.data.ServerStatus
import app.zmby.net.displayAddress
import app.zmby.ui.Nav
import app.zmby.ui.Route
import app.zmby.ui.components.ChoiceSetting
import app.zmby.ui.components.GlassButton
import app.zmby.ui.components.Glyph
import app.zmby.ui.components.Health
import app.zmby.ui.components.HealthDot
import app.zmby.ui.components.Icon
import app.zmby.ui.components.SelectableRow
import app.zmby.ui.components.ToggleButton
import app.zmby.ui.components.ToggleSetting
import app.zmby.ui.components.ValueSetting
import app.zmby.ui.components.groupCard
import app.zmby.ui.components.secondaryText
import app.zmby.ui.rememberInitialFocus
import app.zmby.ui.theme.Z
import app.zmby.zmby
import kotlinx.coroutines.launch

private val CardShape = RoundedCornerShape(28.dp)

/**
 * Servers.dc.html: the servers in priority order on the left (moving onto one shows it; the menu
 * key moves it up), its lines, libraries and options on the right.
 */
@Composable
fun ServersScreen(nav: Nav) {
    val app = LocalContext.current.zmby
    val library = app.library
    val servers by library.servers.collectAsState()
    val scope = rememberCoroutineScope()
    var currentId by rememberSaveable { mutableStateOf<String?>(null) }
    val current = servers.firstOrNull { it.id == currentId } ?: servers.firstOrNull()
    val requesters = remember(servers.map { it.id }) { servers.associate { it.id to FocusRequester() } }

    Column(Modifier.fillMaxSize().padding(start = 80.dp, end = 80.dp, top = 44.dp, bottom = 56.dp), verticalArrangement = Arrangement.spacedBy(34.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Text("服务器", fontSize = 48.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp)
                Text(servers.size.toString(), fontSize = 48.sp, fontWeight = FontWeight.Bold, color = Z.textTertiary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                GlassButton("扫码导入", { nav.go(Route.Import) }, glyph = Glyph.Qr, height = 64.dp, horizontalPadding = 30.dp, fontSize = 21)
                GlassButton("添加服务器", { nav.go(Route.AddServer) }, glyph = Glyph.Plus, height = 64.dp, horizontalPadding = 30.dp, fontSize = 21)
                GlassButton("全部测速", { scope.launch { library.measure() } }, height = 64.dp, horizontalPadding = 30.dp, fontSize = 21)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            Column(
                Modifier
                    .width(860.dp)
                    .verticalScroll(rememberScrollState())
                    // Coming back from the right lands on the shown server, not the nearest row.
                    .focusProperties { onEnter = { if (requestedFocusDirection == FocusDirection.Left) current?.let { requesters[it.id]?.requestFocus() } } }
                    .focusGroup(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val first = rememberInitialFocus(servers.firstOrNull()?.id)
                servers.forEach { s ->
                    ServerRow(
                        s,
                        Modifier.focusRequester(requesters.getValue(s.id)).then(if (s.order == 0) Modifier.focusRequester(first) else Modifier),
                        onFocus = { currentId = s.id },
                        onMoveUp = { app.registry.move(s.id, -1) },
                    )
                }
            }
            current?.let { s ->
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    LinesCard(s)
                    LibrariesCard(s.id)
                    OptionsCard(s, onRemoved = { currentId = null })
                }
            }
        }
    }
}

private fun ServerStatus.latencyLabel() = when {
    !enabled -> "已停用"
    health == Health.Off && coolingUntil > System.currentTimeMillis() -> "暂停"
    !measured -> "未测"
    else -> latencyMs?.let { "$it ms" } ?: "离线"
}

@Composable
private fun ServerRow(s: ServerStatus, modifier: Modifier, onFocus: () -> Unit, onMoveUp: () -> Unit) {
    SelectableRow(
        {},
        modifier
            .height(76.dp)
            .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Menu) { onMoveUp(); true } else false },
        tile = true,
        showCheck = false,
        radius = 20.dp,
        dimmed = s.health == Health.Off,
        padding = PaddingValues(horizontal = 24.dp),
        onFocus = onFocus,
        label = "服务器 ${s.name}",
        focusKey = "server:${s.id}",
    ) {
        Text((s.order + 1).toString(), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = secondaryText(), modifier = Modifier.width(32.dp))
        HealthDot(s.health)
        Text(s.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(if (s.config.autoFastest) "自动 · ${s.lineName}" else s.lineName, fontSize = 18.sp, color = secondaryText(), maxLines = 1, modifier = Modifier.width(170.dp))
        Box(Modifier.width(100.dp), contentAlignment = Alignment.CenterEnd) { Text(s.latencyLabel(), fontSize = 18.sp, color = secondaryText()) }
    }
}

@Composable
private fun CardTitle(text: String, modifier: Modifier = Modifier) = Text(text, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = modifier.padding(start = 4.dp))

@Composable
private fun LinesCard(s: ServerStatus) {
    val app = LocalContext.current.zmby
    val scope = rememberCoroutineScope()
    var adding by remember(s.id) { mutableStateOf(false) }
    var url by remember(s.id) { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().groupCard(CardShape).padding(26.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CardTitle("线路", Modifier.weight(1f))
            Text("自动选最快", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            ToggleButton(s.config.autoFastest, {
                app.registry.update(s.id) { it.copy(autoFastest = !it.autoFastest) }
                scope.launch { app.library.measure(s.id) }
            }, label = "自动选最快")
        }
        s.config.lines.forEach { line ->
            val measured = line.id in s.lineLatency
            val ms = s.lineLatency[line.id]
            SelectableRow(
                { app.registry.selectLine(s.id, line.id) },
                selected = line.id == s.config.lineId,
                dimmed = measured && ms == null,
                radius = 20.dp,
                padding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                label = "线路 ${line.name}",
                focusKey = "line:${s.id}:${line.id}",
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(line.name, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text(displayAddress(line.url), fontSize = 17.sp, color = secondaryText(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(if (!measured) "未测" else ms?.let { "$it ms" } ?: "超时", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (adding) {
            Field("新线路地址", url, { url = it }, KeyboardType.Uri, placeholder = "cdn.example.net:8096", last = true, onSubmit = {
                if (url.isNotBlank()) {
                    val normalized = runCatching { EmbyClient.normalizeBaseUrl(url) }.getOrNull()
                    if (normalized != null) {
                        app.registry.addLine(s.id, "线路 ${s.config.lines.size + 1}", normalized)
                        scope.launch { app.library.measure(s.id) }
                    }
                }
                url = ""
                adding = false
            })
        } else {
            SelectableRow({ adding = true }, showCheck = false, radius = 20.dp, padding = PaddingValues(horizontal = 20.dp, vertical = 14.dp), label = "添加线路") {
                Icon(Glyph.Plus, size = 22.dp, strokeWidth = 2.4f)
                Text("添加线路", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun LibrariesCard(server: String) {
    val library = LocalContext.current.zmby.library
    var libs by remember(server) { mutableStateOf<List<LibraryView>>(emptyList()) }
    LaunchedEffect(server) { libs = runCatching { library.libraries(server) }.getOrDefault(emptyList()) }
    if (libs.isEmpty()) return
    Column(Modifier.fillMaxWidth().groupCard(CardShape).padding(26.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.padding(end = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            CardTitle("媒体库", Modifier.weight(1f))
            Text("首页", fontSize = 17.sp, color = Z.textTertiary, modifier = Modifier.width(84.dp), textAlign = TextAlign.Center)
            Text("搜索", fontSize = 17.sp, color = Z.textTertiary, modifier = Modifier.width(84.dp), textAlign = TextAlign.Center)
        }
        libs.forEach { lib ->
            Row(Modifier.padding(start = 4.dp, end = 20.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Text(lib.name, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                ToggleButton(lib.onHome, {
                    library.setOnHome(server, lib.id, !lib.onHome)
                    libs = libs.map { if (it.id == lib.id) it.copy(onHome = !lib.onHome) else it }
                }, label = "${lib.name} 首页")
                ToggleButton(lib.inSearch, {
                    library.setInSearch(server, lib.id, !lib.inSearch)
                    libs = libs.map { if (it.id == lib.id) it.copy(inSearch = !lib.inSearch) else it }
                }, label = "${lib.name} 搜索")
            }
        }
    }
}

/** Switches, sync policy, detected capabilities with overrides, priority and removal. */
@Composable
private fun OptionsCard(s: ServerStatus, onRemoved: () -> Unit) {
    val app = LocalContext.current.zmby
    val library = app.library
    val registry = app.registry
    var caps by remember(s.id) { mutableStateOf(library.capsOf(s.id)) }
    var confirmRemove by remember(s.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().groupCard(CardShape).padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        CardTitle("这台服务器", Modifier.padding(start = 10.dp, top = 10.dp, bottom = 8.dp))
        ToggleSetting("启用", s.config.enabled, { registry.update(s.id) { it.copy(enabled = !it.enabled) } }, sub = s.lastError?.let { "上次出错：${it.take(40).replace('/', ' ')}" })
        ChoiceSetting(
            "收藏同步",
            FavoriteSync.entries.map { it.label },
            s.config.favoriteSync.ordinal,
            { i -> registry.update(s.id) { it.copy(favoriteSync = FavoriteSync.entries[i], favoriteSyncNote = null) } },
            sub = s.config.favoriteSyncNote,
        )
        val pinyin = when (caps.pinyin) {
            true -> "检测：支持"
            false -> "检测：不支持"
            null -> "检测：未知"
        }
        ChoiceSetting("拼音搜索", listOf("自动", "支持", "不支持"), caps.pinyinOverride.ordinal, { i ->
            library.setPinyinOverride(s.id, Override.entries[i])
            caps = library.capsOf(s.id)
        }, sub = pinyin)
        val ua = s.config.ua
        ChoiceSetting("客户端身份", UaMode.entries.map { it.label }, ua.mode.ordinal, { i ->
            library.setUa(s.id, UaMode.entries[i])
            // Detection starts afresh: ping now, so the row shows what the server accepts.
            scope.launch { library.measure(s.id) }
        }, sub = uaLabel(ua, if (s.measured) s.latencyMs != null else null))
        if (ua.mode == UaMode.Custom) {
            Box(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                var text by remember(s.id) { mutableStateOf(ua.custom.orEmpty()) }
                Field("自定义 User-Agent", text, { text = it }, KeyboardType.Text, placeholder = "Mozilla 5.0 …", last = true, onSubmit = {
                    library.setUa(s.id, UaMode.Custom, text.trim())
                    scope.launch { library.measure(s.id) }
                })
            }
        }
        val stream = when (caps.redirects) {
            true -> "检测：302 跳转到网盘直链"
            false -> "检测：直接返回文件"
            null -> "检测：尚未播放过"
        }.let { if (caps.relay == Override.Auto && ua.needsRelay) "自动：走本地转发，播放器发不出${ua.effective.label} 身份" else it }
        ChoiceSetting("取流方式", listOf("自动", "本地转发", "直连"), caps.relay.ordinal, { i ->
            library.setRelayOverride(s.id, Override.entries[i])
            caps = library.capsOf(s.id)
        }, sub = stream)
        ChoiceSetting("优先级 ${s.order + 1}", listOf("上移", "下移"), -1, { i -> registry.move(s.id, if (i == 0) -1 else 1) })
        if (s.health == Health.Off && s.coolingUntil > System.currentTimeMillis()) {
            ValueSetting("连续失败，暂时跳过", "立即重试", onClick = { registry.retry(s.id) })
        }
        ValueSetting(if (confirmRemove) "再按一次确认删除" else "删除这台服务器", s.config.userName, onClick = {
            if (confirmRemove) {
                library.removeServer(s.id)
                onRemoved()
            } else {
                confirmRemove = true
            }
        })
    }
}

/** What the 客户端身份 row says under its title: the choice in use, and what 自动 found. */
internal fun uaLabel(ua: UaSetting, reachable: Boolean?): String = when (ua.mode) {
    UaMode.Auto -> when {
        ua.detected == UaMode.Official -> "检测：只接受官方客户端，已改用官方 Emby 身份"
        reachable == null -> "检测：尚未连接"
        reachable -> "检测：zmby 可用"
        else -> "检测：连不上"
    }
    UaMode.Zmby -> "固定为 zmby"
    UaMode.Official -> "固定为官方 Emby（Android TV）"
    UaMode.Custom -> if (ua.custom.isNullOrBlank()) "未填写，暂用 zmby" else "自定义 User-Agent，客户端名同官方"
}
