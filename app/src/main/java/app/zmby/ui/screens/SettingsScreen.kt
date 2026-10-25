package app.zmby.ui.screens

import app.zmby.data.SourceFeature
import android.os.Build
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.zmby.playback.TrackPreference
import app.zmby.data.QualityPreference
import app.zmby.aggregate.VersionPrefs
import androidx.compose.ui.text.input.KeyboardType
import app.zmby.ui.Nav
import app.zmby.ui.Route
import app.zmby.ui.components.ChoiceSetting
import app.zmby.ui.components.Glyph
import app.zmby.ui.components.Icon
import app.zmby.ui.components.SelectableRow
import app.zmby.ui.components.SettingsGroup
import app.zmby.ui.components.ToggleSetting
import app.zmby.ui.components.ValueSetting
import app.zmby.ui.components.ZmbyMark
import app.zmby.ui.rememberInitialFocus
import app.zmby.ui.theme.Z
import app.zmby.zmby
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class Category(val label: String) {
    General("通用"), Playback("播放"), Tracks("音频与字幕"), Home("首页与媒体库"), Servers("服务器"), Sources("数据来源"), Appearance("外观"), About("关于")
}

/** Settings.dc.html: categories on the left (moving onto one shows it), grouped rows on the right. */
@Composable
fun SettingsScreen(nav: Nav) {
    var category by rememberSaveable { mutableStateOf(Category.General) }
    val requesters = remember { Category.entries.associateWith { FocusRequester() } }
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier.width(560.dp).fillMaxHeight().padding(start = 80.dp, end = 40.dp, top = 44.dp, bottom = 56.dp),
            verticalArrangement = Arrangement.spacedBy(30.dp),
        ) {
            Text("设置", fontSize = 48.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp, modifier = Modifier.padding(top = 4.dp))
            val initial = rememberInitialFocus(Unit)
            Column(
                Modifier
                    // Coming back from the panel lands on the shown category, not the nearest one
                    // (which would switch the panel under the user).
                    .focusProperties { onEnter = { if (requestedFocusDirection == FocusDirection.Left) requesters.getValue(category).requestFocus() } }
                    .focusGroup(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Category.entries.forEach { c ->
                    SelectableRow(
                        onClick = { if (c == Category.Servers) nav.go(Route.Servers) },
                        modifier = Modifier.focusRequester(requesters.getValue(c)).then(if (c == category) Modifier.focusRequester(initial) else Modifier).height(68.dp),
                        selected = c == category,
                        showCheck = false,
                        radius = 20.dp,
                        onFocus = { category = c },
                        label = "设置 ${c.label}",
                    ) {
                        Text(c.label, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Icon(Glyph.Chevron, size = 20.dp, strokeWidth = 2.4f, color = androidx.tv.material3.LocalContentColor.current.copy(alpha = 0.5f))
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ZmbyMark(30.dp)
                Text("zmby ${appVersion()}", fontSize = 18.sp, color = Z.textTertiary)
            }
        }
        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 80.dp, top = 56.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            when (category) {
                Category.General -> General()
                Category.Playback -> Playback()
                Category.Tracks -> Tracks()
                Category.Home -> HomeRows(nav)
                Category.Servers -> ServersSummary(nav)
                Category.Sources -> Sources(nav)
                Category.Appearance -> Appearance()
                Category.About -> About()
            }
        }
    }
}

@Composable
private fun appVersion(): String {
    val context = LocalContext.current
    return remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "" }
}

@Composable
private fun General() {
    val settings = LocalContext.current.zmby.settings
    val percent by settings.watchedPercent.collectAsState()
    val steps = remember(percent) { (listOf(70, 80, 85, 90, 95) + percent).distinct().sorted() }
    SettingsGroup("进度") {
        ChoiceSetting("看完阈值", steps.map { "$it%" }, steps.indexOf(percent), { settings.setWatchedPercent(steps[it]) })
    }
}

@Composable
private fun Playback() {
    val settings = LocalContext.current.zmby.settings
    val offerNext by settings.offerNextEpisode.flow.collectAsState()
    SettingsGroup("播放器") {
        ToggleSetting("看完后提示下一集", offerNext, { settings.offerNextEpisode.set(!offerNext) })
    }
    val quality by settings.quality.flow.collectAsState()
    val nonDisc by settings.preferNonDisc.flow.collectAsState()
    val pattern by settings.versionPattern.flow.collectAsState()
    SettingsGroup("版本选择") {
        ChoiceSetting("画质偏好", QualityPreference.entries.map { it.label }, quality.ordinal, { settings.quality.set(QualityPreference.entries[it]) })
        ToggleSetting("优先非原盘", nonDisc, { settings.preferNonDisc.set(!nonDisc) })
        // 版本偏好正则: marks the preferred version (e.g. 2160p.*REMUX); never hides others.
        Column(Modifier.padding(horizontal = 24.dp, vertical = 10.dp)) {
            var draft by remember(pattern) { mutableStateOf(pattern) }
            Field("版本偏好正则", draft, { draft = it }, KeyboardType.Ascii, placeholder = "2160p.*REMUX", last = true, onSubmit = { settings.versionPattern.set(draft.trim()) })
            if (draft.isNotBlank() && VersionPrefs.pattern(draft) == null) Text("这个正则无效，不会生效", fontSize = 18.sp, color = Z.warn, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun Tracks() {
    val settings = LocalContext.current.zmby.settings
    val audio by settings.audioLanguage.flow.collectAsState()
    val subtitle by settings.subtitleLanguage.flow.collectAsState()
    val audioCodes = listOf(TrackPreference.SERVER) + TrackPreference.LANGUAGES.map { it.first }
    val subtitleCodes = listOf(TrackPreference.SERVER) + TrackPreference.LANGUAGES.take(2).map { it.first } + TrackPreference.OFF
    fun label(code: String) = when (code) {
        TrackPreference.SERVER -> "跟随服务器"
        TrackPreference.OFF -> "关闭"
        else -> TrackPreference.LANGUAGES.first { it.first == code }.second
    }
    SettingsGroup("默认轨道") {
        ChoiceSetting("音轨语言", audioCodes.map(::label), audioCodes.indexOf(audio).coerceAtLeast(0), { settings.audioLanguage.set(audioCodes[it]) })
        ChoiceSetting("字幕", subtitleCodes.map(::label), subtitleCodes.indexOf(subtitle).coerceAtLeast(0), { settings.subtitleLanguage.set(subtitleCodes[it]) })
    }
}

@Composable
private fun HomeRows(nav: Nav) {
    val settings = LocalContext.current.zmby.settings
    val nextUp by settings.showNextUpRow.flow.collectAsState()
    val latest by settings.showLatestRow.flow.collectAsState()
    val watchlist by settings.showWatchlistRow.flow.collectAsState()
    SettingsGroup("首页") {
        ToggleSetting("「下一集」行", nextUp, { settings.showNextUpRow.set(!nextUp) })
        ToggleSetting("「最新添加」行", latest, { settings.showLatestRow.set(!latest) })
        ToggleSetting("「待看」行", watchlist, { settings.showWatchlistRow.set(!watchlist) })
    }
    SettingsGroup("媒体库") {
        ValueSetting("首页与搜索开关", "按服务器", onClick = { nav.go(Route.Servers) })
    }
}

@Composable
private fun ServersSummary(nav: Nav) {
    val library = LocalContext.current.zmby.library
    val servers by library.servers.collectAsState()
    SettingsGroup("服务器") {
        servers.take(6).forEach { s ->
            val state = when {
                !s.enabled -> "已停用"
                !s.measured -> "未测"
                else -> s.latencyMs?.let { "$it ms" } ?: "离线"
            }
            ValueSetting(s.name, listOf(s.lineName, state).joinToString(" · "), onClick = { nav.go(Route.Servers) })
        }
        ValueSetting("管理服务器", "共 ${servers.size} 台", onClick = { nav.go(Route.Servers) })
    }
}

/** 数据来源 (ui-spec §2.6): one row per feature, the value is 「全部」「n 台」「n 个」 or 「关闭」. */
@Composable
private fun Sources(nav: Nav) {
    val app = LocalContext.current.zmby
    val rows by app.sources.rows.collectAsState()
    val servers by app.registry.servers.collectAsState()
    SettingsGroup("数据来源") {
        // Recompose on changes to the choices or the servers.
        rows.size; servers.size
        SourceFeature.entries.forEach { f ->
            ValueSetting(f.label, app.sources.label(f), onClick = { nav.go(Route.DataSource(f)) }, glyph = sourceGlyph(f))
        }
    }
}

private fun sourceGlyph(f: app.zmby.data.SourceFeature): Glyph = when (f) {
    app.zmby.data.SourceFeature.Aggregate -> Glyph.Stack
    app.zmby.data.SourceFeature.Search -> Glyph.Search
    app.zmby.data.SourceFeature.WatchHistory -> Glyph.Clock
    app.zmby.data.SourceFeature.Metadata -> Glyph.Info
    app.zmby.data.SourceFeature.Segments -> Glyph.Skip
    app.zmby.data.SourceFeature.Favorites -> Glyph.Heart
    app.zmby.data.SourceFeature.Stats -> Glyph.Chart
}

@Composable
private fun Appearance() {
    val settings = LocalContext.current.zmby.settings
    val rotates by settings.heroRotates.flow.collectAsState()
    SettingsGroup("主推区") {
        ToggleSetting("自动轮播", rotates, { settings.heroRotates.set(!rotates) })
    }
}

@Composable
private fun About() {
    val app = LocalContext.current.zmby
    val lanLog by app.settings.lanLog.collectAsState()
    val address by app.lanAddress.collectAsState()
    LaunchedEffect(lanLog) { withContext(Dispatchers.IO) { app.refreshLanAddress() } }
    SettingsGroup("zmby") {
        ValueSetting("版本", appVersion())
        ValueSetting("设备", listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.distinct().joinToString(" "))
    }
    SettingsGroup("日志") {
        ToggleSetting(
            "局域网日志",
            lanLog,
            { app.settings.setLanLog(!lanLog) },
            // Typed into a browser as is; no scheme, so the screen shows no slashes.
            sub = if (lanLog) address?.substringAfter("://") ?: "正在启动…" else null,
            mono = lanLog && address != null,
        )
    }
}
