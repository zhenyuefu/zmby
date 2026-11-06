package app.zmby.ui.screens

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import app.zmby.data.InputField
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import app.zmby.data.FavoriteSync
import app.zmby.data.SegmentProvider
import app.zmby.data.SourceFeature
import app.zmby.data.SourceRules
import app.zmby.store.DataSourceRow
import app.zmby.ui.Nav
import app.zmby.ui.components.GlassToggle
import app.zmby.ui.components.Glyph
import app.zmby.ui.components.Health
import app.zmby.ui.components.Icon
import app.zmby.ui.components.SelectableRow
import app.zmby.ui.components.SettingsGroup
import app.zmby.ui.components.ToggleSetting
import app.zmby.ui.components.secondaryText
import app.zmby.ui.rememberInitialFocus
import app.zmby.ui.theme.Z
import app.zmby.zmby
import kotlinx.coroutines.launch

/**
 * 设置 › 数据来源 › one feature (ui-spec §2.6): the servers, or for 片段标记来源 the providers, as an
 * ordered list with a switch each (menu key or a long OK picks a row up, up and down move it, OK
 * puts it down). A server list follows the server page's order until sorted here (统一数据层 ·
 * 每项来源的优先级); then 恢复为服务器顺序 ties it to the server page again.
 */
@Composable
fun DataSourceScreen(nav: Nav, feature: SourceFeature) {
    val app = LocalContext.current.zmby
    val rows by app.sources.rows.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 80.dp, end = 80.dp, top = 56.dp, bottom = 56.dp),
        verticalArrangement = Arrangement.spacedBy(30.dp),
    ) {
        Text(feature.label, fontSize = 48.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp)
        Column(Modifier.width(1100.dp)) {
            when {
                feature.ordered -> Providers(rows)
                feature == SourceFeature.Metadata -> MetadataSources(rows)
                else -> Servers(feature, rows)
            }
        }
    }
}

@Composable
private fun Servers(feature: SourceFeature, rows: Map<String, DataSourceRow>) {
    val app = LocalContext.current.zmby
    val servers by app.registry.servers.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val initial = rememberInitialFocus(servers.firstOrNull()?.id)
    val label = remember(rows, servers) { app.sources.label(feature) }
    val ordered = remember(rows, servers) { SourceRules.ordered(rows, feature, servers) }
    val custom = SourceRules.custom(rows, feature)
    var moving by remember { mutableStateOf<String?>(null) }
    var swallowClick by remember { mutableStateOf(false) }
    // 观看进度回写: why a server was switched off by a refused write.
    val refusals by androidx.compose.runtime.produceState(emptyMap<String, String>(), feature, rows) {
        if (feature == SourceFeature.WatchSync) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            app.store.sources.syncs(feature.key).mapNotNull { r -> r.lastError?.let { r.serverId to it } }.toMap()
        }
    }
    SettingsGroup(label) {
      Column(Modifier.focusGroup()) {
        ordered.forEachIndexed { i, s -> androidx.compose.runtime.key(s.id) {
            // Why a server cannot serve this feature now; it can still be switched for later.
            val reason = when {
                !s.enabled -> "已停用"
                feature == SourceFeature.Favorites && s.config.favoriteSync == FavoriteSync.Off -> s.config.favoriteSyncNote?.let { "拒绝写入收藏" } ?: "收藏同步为不同步"
                feature == SourceFeature.WatchSync && !SourceRules.enabled(rows, feature, s.id) && refusals[s.id] != null -> refusals[s.id]
                s.health == Health.Off -> "暂时连不上"
                else -> null
            }
            val on = SourceRules.enabled(rows, feature, s.id)
            val isMoving = moving == s.id
            SelectableRow(
                onClick = {
                    when {
                        swallowClick -> swallowClick = false
                        moving != null -> moving = null
                        else -> scope.launch { app.sources.set(feature, s.id, !on) }
                    }
                },
                modifier = Modifier
                    .defaultMinSize(minHeight = 70.dp)
                    .then(if (i == 0) Modifier.focusRequester(initial) else Modifier)
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when {
                            e.key == Key.Menu || (e.key in OK_KEYS && e.nativeKeyEvent.repeatCount > 0 && moving == null) -> {
                                moving = s.id
                                swallowClick = e.key != Key.Menu
                                true
                            }
                            isMoving && e.key == Key.DirectionUp -> { scope.launch { app.sources.moveServer(feature, s.id, -1) }; true }
                            isMoving && e.key == Key.DirectionDown -> { scope.launch { app.sources.moveServer(feature, s.id, 1) }; true }
                            isMoving && e.key == Key.Back -> { moving = null; true }
                            else -> false
                        }
                    },
                selected = isMoving,
                showCheck = false,
                dimmed = reason != null && !isMoving,
                radius = 20.dp,
                label = "${s.name} ${if (on) "开" else "关"}",
                focusKey = "source:${s.id}",
            ) {
                Text("${i + 1}", fontSize = 20.sp, color = secondaryText(), modifier = Modifier.width(36.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.name, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    val sub = if (isMoving) "上下移动，确认放下" else reason
                    if (sub != null) Text(sub, fontSize = 18.sp, color = secondaryText())
                }
                GlassToggle(on)
                Icon(Glyph.Grip, size = 26.dp, strokeWidth = 2.4f)
            }
        } }
      }
    }
    if (custom) {
        SettingsGroup("顺序") {
            SelectableRow(
                onClick = { scope.launch { app.sources.followServerOrder(feature) } },
                modifier = Modifier.defaultMinSize(minHeight = 70.dp),
                showCheck = false,
                radius = 20.dp,
                label = "恢复为服务器顺序",
                focusKey = "source:followServerOrder",
            ) {
                Column(Modifier.weight(1f)) {
                    Text("恢复为服务器顺序", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text("这一项改回跟随服务器页的顺序", fontSize = 18.sp, color = secondaryText())
                }
            }
        }
    }
}

/**
 * 影片信息来源 (统一数据层 ④): TMDB, Bangumi and the servers in one sortable list; each field of a
 * detail page comes from the first member switched on here that has it.
 */
@Composable
private fun MetadataSources(rows: Map<String, DataSourceRow>) {
    val app = LocalContext.current.zmby
    val servers by app.registry.servers.collectAsStateWithLifecycle()
    val token by app.settings.tmdbToken.flow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val members = remember(rows, servers) { SourceRules.metadata(rows, servers) }
    var moving by remember { mutableStateOf<String?>(null) }
    var swallowClick by remember { mutableStateOf(false) }
    val initial = rememberInitialFocus(Unit)
    SettingsGroup(SourceRules.label(members.count { it.enabled }, members.size, providers = true)) {
        Column(Modifier.focusGroup()) {
            members.forEachIndexed { i, m -> androidx.compose.runtime.key(m.key) {
                val reason = when {
                    m.key == "tmdb" && token.isBlank() -> "未设置 TMDB 令牌，暂不参与"
                    m.key == "bangumi" -> "只用于能确定 Bangumi 条目的作品"
                    m.server?.enabled == false -> "已停用"
                    m.server?.health == Health.Off -> "暂时连不上"
                    else -> null
                }
                val isMoving = moving == m.key
                SelectableRow(
                    onClick = {
                        when {
                            swallowClick -> swallowClick = false
                            moving != null -> moving = null
                            else -> scope.launch { app.sources.set(SourceFeature.Metadata, m.key, !m.enabled) }
                        }
                    },
                    modifier = Modifier
                        .defaultMinSize(minHeight = 70.dp)
                        .then(if (i == 0) Modifier.focusRequester(initial) else Modifier)
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when {
                                e.key == Key.Menu || (e.key in OK_KEYS && e.nativeKeyEvent.repeatCount > 0 && moving == null) -> {
                                    moving = m.key
                                    swallowClick = e.key != Key.Menu
                                    true
                                }
                                isMoving && e.key == Key.DirectionUp -> { scope.launch { app.sources.moveMetadata(m.key, -1) }; true }
                                isMoving && e.key == Key.DirectionDown -> { scope.launch { app.sources.moveMetadata(m.key, 1) }; true }
                                isMoving && e.key == Key.Back -> { moving = null; true }
                                else -> false
                            }
                        },
                    selected = isMoving,
                    showCheck = false,
                    dimmed = reason != null && m.key != "bangumi" && !isMoving,
                    radius = 20.dp,
                    label = "${m.label} ${if (m.enabled) "开" else "关"}",
                    focusKey = "metadata:${m.key}",
                ) {
                    Text("${i + 1}", fontSize = 20.sp, color = secondaryText(), modifier = Modifier.width(36.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.label, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        val sub = if (isMoving) "上下移动，确认放下" else reason
                        if (sub != null) Text(sub, fontSize = 18.sp, color = secondaryText())
                    }
                    GlassToggle(m.enabled)
                    Icon(Glyph.Grip, size = 26.dp, strokeWidth = 2.4f)
                }
            } }
        }
    }
    if (SourceRules.custom(rows, SourceFeature.Metadata)) {
        SettingsGroup("顺序") {
            SelectableRow(
                onClick = { scope.launch { app.sources.followServerOrder(SourceFeature.Metadata) } },
                modifier = Modifier.defaultMinSize(minHeight = 70.dp),
                showCheck = false,
                radius = 20.dp,
                label = "恢复默认顺序",
                focusKey = "metadata:default",
            ) {
                Column(Modifier.weight(1f)) {
                    Text("恢复默认顺序", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text("TMDB、Bangumi，然后按服务器页的顺序", fontSize = 18.sp, color = secondaryText())
                }
            }
        }
    }
}

@Composable
private fun Providers(rows: Map<String, DataSourceRow>) {
    val app = LocalContext.current.zmby
    val scope = rememberCoroutineScope()
    val key by app.settings.publicMetaDbKey.flow.collectAsStateWithLifecycle()
    val providers = remember(rows, key) { SourceRules.providers(rows) { key.isNotBlank() } }
    var moving by remember { mutableStateOf<SegmentProvider?>(null) }
    // A long OK picks a row up; the release that follows must not count as a click.
    var swallowClick by remember { mutableStateOf(false) }
    val initial = rememberInitialFocus(Unit)
    SettingsGroup(SourceRules.label(providers.count { it.enabled }, providers.size, providers = true)) {
        Column(Modifier.focusGroup()) {
            providers.forEachIndexed { i, p -> androidx.compose.runtime.key(p.provider) {
                val locked = p.provider.needsKey && key.isBlank()
                val isMoving = moving == p.provider
                SelectableRow(
                    onClick = {
                        when {
                            swallowClick -> swallowClick = false
                            moving != null -> moving = null
                            !locked -> scope.launch { app.sources.setProvider(p.provider, !p.enabled) }
                        }
                    },
                    modifier = Modifier
                        .defaultMinSize(minHeight = 70.dp)
                        .then(if (i == 0) Modifier.focusRequester(initial) else Modifier)
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when {
                                // Menu, or OK held down: pick the row up.
                                e.key == Key.Menu || (e.key in OK_KEYS && e.nativeKeyEvent.repeatCount > 0 && moving == null) -> {
                                    moving = p.provider
                                    swallowClick = e.key != Key.Menu
                                    true
                                }
                                isMoving && e.key == Key.DirectionUp -> { scope.launch { app.sources.moveProvider(p.provider, -1) }; true }
                                isMoving && e.key == Key.DirectionDown -> { scope.launch { app.sources.moveProvider(p.provider, 1) }; true }
                                isMoving && e.key == Key.Back -> { moving = null; true }
                                else -> false
                            }
                        },
                    selected = isMoving,
                    showCheck = false,
                    dimmed = locked,
                    radius = 20.dp,
                    label = "${p.provider.label} ${if (p.enabled) "开" else "关"}",
                    focusKey = "provider:${p.provider.key}",
                ) {
                    Text("${i + 1}", fontSize = 20.sp, color = secondaryText(), modifier = Modifier.width(36.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.provider.label, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        val sub = when {
                            isMoving -> "上下移动，确认放下"
                            locked -> "填写密钥后才能开启"
                            else -> null
                        }
                        if (sub != null) Text(sub, fontSize = 18.sp, color = secondaryText())
                    }
                    GlassToggle(p.enabled)
                    Icon(Glyph.Grip, size = 26.dp, strokeWidth = 2.4f)
                }
            } }
        }
    }
    // PublicMetaDB needs the user's own key; the agent never types it.
    SettingsGroup("PublicMetaDB") {
        var phone by remember { mutableStateOf(false) }
        PhoneInputRow("密钥", if (key.isBlank()) "未设置" else "已设置") { phone = true }
        if (phone) PhoneInputDialog(InputField.PublicMetaDbKey, onDismiss = { phone = false }, onRemote = { phone = false })
        Column(Modifier.padding(horizontal = 24.dp, vertical = 10.dp)) {
            var draft by remember(key) { mutableStateOf(key) }
            Field("密钥", draft, { draft = it }, KeyboardType.Ascii, placeholder = "未填写", secret = true, last = true, onSubmit = {
                app.settings.publicMetaDbKey.set(draft.trim())
                if (draft.isBlank()) scope.launch { app.sources.setProvider(SegmentProvider.PublicMetaDB, false) }
            })
        }
    }
}

private val OK_KEYS = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
