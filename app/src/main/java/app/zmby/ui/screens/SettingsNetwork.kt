package app.zmby.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import app.zmby.log.ZLog
import app.zmby.net.ProxyAddress
import app.zmby.net.ProxyApi
import app.zmby.net.ProxyKind
import app.zmby.net.displayAddress
import app.zmby.ui.components.ChoiceSetting
import app.zmby.ui.components.SettingsGroup
import app.zmby.ui.components.ToggleSetting
import app.zmby.ui.components.ValueSetting
import app.zmby.ui.theme.Z
import app.zmby.zmby

/** Why the chosen lines and APIs do not use the proxy yet, or null once they do. */
internal fun proxyNote(kind: ProxyKind, address: String): String? = when {
    kind == ProxyKind.Off -> "代理已关闭，下面的选择暂不生效"
    ProxyAddress.parse(address) == null -> "填写代理地址后生效"
    else -> null
}

/**
 * 设置 › 网络: one proxy (HTTP or SOCKS5) and what uses it: outside APIs by name, server lines one
 * by one. Nothing else changes route; the choices take effect on the next connection.
 */
@Composable
internal fun NetworkSettings() {
    val app = LocalContext.current.zmby
    val settings = app.settings
    val kind by settings.proxyKind.flow.collectAsStateWithLifecycle()
    val address by settings.proxyAddress.flow.collectAsStateWithLifecycle()
    val user by settings.proxyUser.flow.collectAsStateWithLifecycle()
    val password by settings.proxyPassword.flow.collectAsStateWithLifecycle()
    val apisRaw by settings.proxyApis.flow.collectAsStateWithLifecycle()
    val servers by app.registry.lineup.collectAsStateWithLifecycle()
    val apis = ProxyApi.parse(apisRaw)
    val note = proxyNote(kind, address)
    SettingsGroup("代理服务器") {
        ChoiceSetting("代理类型", ProxyKind.entries.map { it.label }, kind.ordinal, { settings.proxyKind.set(ProxyKind.entries[it]) }, sub = note, warn = true)
        TextSetting("地址", address, null, save = { v ->
            if (v.isEmpty()) { settings.proxyAddress.set(""); null }
            else ProxyAddress.check(v) ?: run { settings.proxyAddress.set(v); null }
        }, type = KeyboardType.Uri, mono = true, placeholder = "192.168.1.2:7890")
        TextSetting("用户名", user, null, save = { v -> settings.proxyUser.set(v); null })
        TextSetting("密码", password, null, save = { v ->
            ZLog.secret(v.takeIf { it.isNotEmpty() })
            settings.proxyPassword.set(v)
            null
        }, type = KeyboardType.Password, secret = true)
        Text("用户名为空时不认证。HTTP 代理用 Basic 认证；SOCKS5 认证由系统网络库处理。", fontSize = 18.sp, color = Z.textSecondary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
    }
    SettingsGroup("使用代理的 API") {
        ProxyApi.entries.forEach { api ->
            val on = api in apis
            ToggleSetting(api.label, on, { settings.proxyApis.set(ProxyApi.store(if (on) apis - api else apis + api)) },
                sub = api.sub, dimmed = note != null, focusKey = "proxy-api:${api.name}")
        }
    }
    SettingsGroup("使用代理的服务器线路") {
        if (servers.isEmpty()) ValueSetting("还没有服务器", null)
        servers.forEach { s ->
            SettingsSubtitle(s.name)
            s.config.lines.forEach { line ->
                ToggleSetting(line.name, line.proxy, { app.registry.setLineProxy(s.id, line.id, !line.proxy) },
                    sub = displayAddress(line.url), mono = true, dimmed = note != null, focusKey = "proxy-line:${s.id}:${line.id}")
            }
        }
        Text("走代理的线路由本机转发给播放器；网盘 302 跳转后的播放地址仍直连。", fontSize = 18.sp, color = Z.textSecondary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
    }
}
