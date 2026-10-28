package app.zmby.ui.screens

import app.zmby.data.SourceFeature
import android.os.Build
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.key
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.zmby.R
import app.zmby.catalog.TmdbEndpoint
import app.zmby.catalog.CatalogChart
import app.zmby.data.InputField
import androidx.compose.foundation.layout.fillMaxWidth
import app.zmby.ui.components.GlassButton
import app.zmby.log.ZLog
import app.zmby.playback.TrackPreference
import app.zmby.segments.CreditsSkip
import app.zmby.data.CacheLimits
import coil.imageLoader
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
import app.zmby.ui.components.groupCard
import app.zmby.ui.rememberInitialFocus
import app.zmby.ui.theme.Z
import app.zmby.zmby
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.withContext

private enum class Category(val label: String, val glyph: Glyph, val value: String) {
    Playback("播放", Glyph.Play, "Zidoo"), Metadata("影视资料", Glyph.Film, "TMDB · Bangumi"),
    Home("首页", Glyph.Home, "主推 · 媒体库"), Servers("服务器与来源", Glyph.Servers, "连接 · 聚合"),
    Cache("缓存", Glyph.Stack, "资料 · 图片"), About("关于", Glyph.Info, "zmby")
}

/** Settings.dc.html: categories on the left (moving onto one shows it), grouped rows on the right. */
@Composable
fun SettingsScreen(nav: Nav) {
    var category by rememberSaveable { mutableStateOf(Category.Playback) }
    val requesters = remember { Category.entries.associateWith { FocusRequester() } }
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier.width(420.dp).fillMaxHeight().background(Z.aside).padding(start = 48.dp, end = 32.dp, top = 56.dp, bottom = 48.dp),
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
                        onClick = {
                            category = c
                            scope.launch { awaitFrame(); focus.moveFocus(FocusDirection.Right) }
                        },
                        modifier = Modifier.focusRequester(requesters.getValue(c)).then(if (c == category) Modifier.focusRequester(initial) else Modifier).height(88.dp),
                        selected = c == category,
                        showCheck = false,
                        radius = 20.dp,
                        onFocus = { category = c },
                        label = "设置 ${c.label}",
                    ) {
                        Icon(c.glyph, size = 26.dp)
                        Text(c.label, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ZmbyMark(30.dp)
                Text("zmby ${appVersion()}", fontSize = 18.sp, color = Z.textTertiary)
            }
        }
        key(category) {
          Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 56.dp, end = 72.dp, top = 56.dp, bottom = 56.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
          ) {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(category.label, fontSize = 44.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(category.value, fontSize = 20.sp, color = Z.textSecondary)
            }
            when (category) {
                Category.Metadata -> MetadataSettings()
                Category.Cache -> CacheSettings()
                Category.Playback -> { Playback(nav); Tracks(); General(nav) }
                Category.Home -> { HomeRows(nav); Appearance() }
                Category.Servers -> { ServersSummary(nav); Sources(nav) }
                Category.About -> About()
            }
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
private fun General(nav: Nav) {
    val settings = LocalContext.current.zmby.settings
    val percent by settings.watchedPercent.collectAsState()
    val steps = remember(percent) { (listOf(70, 80, 85, 90, 95) + percent).distinct().sorted() }
    SettingsGroup("播放进度") {
        ChoiceSetting("看完阈值", steps.map { "$it%" }, steps.indexOf(percent), { settings.setWatchedPercent(steps[it]) })
        ValueSetting("观影统计", null, onClick = { nav.go(Route.Stats) }, glyph = Glyph.Chart)
    }
}

@Composable
private fun Playback(nav: Nav) {
    val app = LocalContext.current.zmby
    val settings = app.settings
    val offerNext by settings.offerNextEpisode.flow.collectAsState()
    val countdown by settings.upNextCountdown.flow.collectAsState()
    val failover by settings.autoFailover.flow.collectAsState()
    SettingsGroup("播放器") {
        ToggleSetting("看完后提示下一集", offerNext, { settings.offerNextEpisode.set(!offerNext) })
        if (offerNext) ToggleSetting("自动播放下一集", countdown, { settings.upNextCountdown.set(!countdown) })
        ToggleSetting("播放失败自动换源", failover, { settings.autoFailover.set(!failover) })
    }
    val skipIntro by settings.skipIntro.flow.collectAsState()
    val skipRecap by settings.skipRecap.flow.collectAsState()
    val skipCredits by settings.skipCredits.flow.collectAsState()
    val rows by app.sources.rows.collectAsState()
    SettingsGroup("片头片尾") {
        ToggleSetting("跳过片头", skipIntro, { settings.skipIntro.set(!skipIntro) })
        ToggleSetting("跳过回顾", skipRecap, { settings.skipRecap.set(!skipRecap) })
        ChoiceSetting("跳过片尾", CreditsSkip.entries.map { it.label }, skipCredits.ordinal, { settings.skipCredits.set(CreditsSkip.entries[it]) })
        rows.size
        ValueSetting(SourceFeature.Segments.label, app.sources.label(SourceFeature.Segments), onClick = { nav.go(Route.DataSource(SourceFeature.Segments)) })
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
    val chart by settings.tmdbChart.flow.collectAsState()
    SettingsGroup("主推内容") {
        val charts = CatalogChart.entries
        ChoiceSetting("TMDB 榜单", charts.map { it.label }, charts.indexOf(CatalogChart.of(chart)), { settings.tmdbChart.set(charts[it].name) })
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
                else -> s.latencyMs?.let { "$it ms" } ?: "待重试"
            }
            ValueSetting(s.name, listOf(s.lineName, state).joinToString(" · "), onClick = { nav.go(Route.Servers) })
        }
        ValueSetting("备份与恢复", "扫码操作", onClick = { nav.go(Route.Backup) })
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
        SourceFeature.entries.filter { it != SourceFeature.Metadata }.forEach { f ->
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
    SettingsGroup("数据来源与致谢") {
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.tmdb_logo), contentDescription = "TMDB", modifier = Modifier.padding(24.dp).width(180.dp).height(24.dp))
        Text("TMDB · https://www.themoviedb.org\nThis product uses the TMDB API but is not endorsed or certified by TMDB.\nBangumi · https://bgm.tv\nbangumi-data/bangumi-data · CC BY 4.0", fontSize = 18.sp, color = Z.textSecondary, modifier = Modifier.padding(24.dp))
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

/** A long-text setting: its name, 「已设置」 or 「未设置」, and the glass 「用手机输入」 button. */
@Composable
internal fun PhoneInputRow(title: String, value: String, onPhone: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(value, fontSize = 20.sp, color = Z.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 400.dp))
        GlassButton("用手机输入", onPhone, height = 52.dp, horizontalPadding = 22.dp, fontSize = 19, label = "$title 用手机输入")
    }
}

@Composable
private fun MetadataSettings() {
    val app = LocalContext.current.zmby
    val settings = app.settings
    val token by settings.tmdbToken.flow.collectAsState()
    val apiBase by settings.tmdbApiBase.flow.collectAsState()
    val text by settings.metadataLanguage.flow.collectAsState()
    val poster by settings.posterLanguage.flow.collectAsState()
    val logo by settings.logoLanguage.flow.collectAsState()
    val search by settings.tmdbSearch.flow.collectAsState()
    val anime by settings.animeAliases.flow.collectAsState()
    val languages = listOf("zh-CN" to "简体中文", "zh-TW" to "繁體中文（台灣）", "zh-HK" to "繁體中文（香港）", "en-US" to "English", "ja-JP" to "日本語", "ko-KR" to "한국어")
    SettingsGroup("TMDB 连接") {
        var addressPhone by remember { mutableStateOf(false) }
        var addressTyping by rememberSaveable { mutableStateOf(false) }
        PhoneInputRow("TMDB API 地址", apiBase) { addressPhone = true }
        if (addressPhone) PhoneInputDialog(InputField.TmdbApiBase, onDismiss = { addressPhone = false },
            onRemote = { addressPhone = false; addressTyping = true })
        if (addressTyping) Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            var draft by remember(apiBase) { mutableStateOf(apiBase) }
            var error by remember { mutableStateOf<String?>(null) }
            fun saveAddress() {
                error = TmdbEndpoint.check(draft)
                if (error == null) { settings.tmdbApiBase.set(draft); addressTyping = false }
            }
            Field("TMDB API 地址", draft, { draft = it; error = null }, KeyboardType.Uri, last = true, onSubmit = { saveAddress() })
            error?.let { Text(it, color = Z.warn, fontSize = 18.sp) }
            GlassButton("保存地址", { saveAddress() })
        }
        if (apiBase != TmdbEndpoint.DEFAULT) ValueSetting("恢复默认 API 地址", "api.tmdb.org",
            onClick = { settings.tmdbApiBase.set(""); addressTyping = false })
        // 用手机输入 (ui-spec §2.13): two hundred characters are not typed with a remote; the remote
        // field stays behind 「用遥控器输入」.
        var phone by remember { mutableStateOf(false) }
        var typing by rememberSaveable { mutableStateOf(false) }
        PhoneInputRow("TMDB 令牌", if (token.isBlank()) "未设置" else "已设置") { phone = true }
        if (typing) Column(Modifier.padding(24.dp)) {
            var draft by remember(token) { mutableStateOf(token) }
            Field("TMDB API Read Access Token", draft, { draft = it }, KeyboardType.Ascii, secret = true, last = true, onSubmit = {
                ZLog.secret(draft.trim())
                settings.tmdbToken.set(draft.trim())
            })
        }
        if (phone) PhoneInputDialog(InputField.TmdbToken, onDismiss = { phone = false }, onRemote = { phone = false; typing = true })
    }
    SettingsGroup("显示语言") {
        ChoiceSetting("影视资料语言", languages.map { it.second }, languages.indexOfFirst { it.first == text }.coerceAtLeast(0), { settings.metadataLanguage.set(languages[it].first) })
        ChoiceSetting("海报语言", languages.map { it.second }, languages.indexOfFirst { it.first == poster }.coerceAtLeast(0), { settings.posterLanguage.set(languages[it].first) })
        ChoiceSetting("Logo 语言", languages.map { it.second }, languages.indexOfFirst { it.first == logo }.coerceAtLeast(0), { settings.logoLanguage.set(languages[it].first) })
    }
    SettingsGroup("查找内容") {
        ToggleSetting("TMDB 搜索", search, { settings.tmdbSearch.set(!search) })
        ToggleSetting("番剧别名匹配", anime, { settings.animeAliases.set(!anime) })
    }
}

@OptIn(coil.annotation.ExperimentalCoilApi::class)
@Composable
private fun CacheSettings() {
    val context = LocalContext.current
    val app = context.zmby
    val metadataLimit by app.settings.metadataCacheMb.flow.collectAsState()
    val imageLimit by app.settings.imageCacheMb.flow.collectAsState()
    val metadataSize by app.catalogCache.size.collectAsState(0L)
    val imageSize by androidx.compose.runtime.produceState<Long?>(null, imageLimit) {
        value = withContext(Dispatchers.IO) { context.imageLoader.diskCache?.size }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        SettingsGroup("影视资料", Modifier.weight(1f)) {
            CacheMeter(metadataSize, metadataLimit, "立即生效")
            ChoiceSetting("资料上限", CacheLimits.metadataMb.map(CacheLimits::label), CacheLimits.metadataMb.indexOf(metadataLimit),
                { app.settings.metadataCacheMb.set(CacheLimits.metadataMb[it]) })
        }
        SettingsGroup("图片", Modifier.weight(1f)) {
            CacheMeter(imageSize, app.activeImageCacheMb, if (imageLimit != app.activeImageCacheMb) "待重启 · ${CacheLimits.label(imageLimit)}" else "重启后生效")
            ChoiceSetting("图片上限", CacheLimits.imagesMb.map(CacheLimits::label), CacheLimits.imagesMb.indexOf(imageLimit),
                { app.settings.imageCacheMb.set(CacheLimits.imagesMb[it]) })
        }
    }
    SettingsGroup("资源查找") {
        ValueSetting("版本与剧集目录", "6 小时", glyph = Glyph.Clock)
    }
}

@Composable
private fun CacheMeter(bytes: Long?, limitMb: Int, status: String) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(bytes?.let(CacheLimits::usage) ?: "读取中…", fontSize = 38.sp, fontWeight = FontWeight.Bold)
        Box(Modifier.fillMaxWidth().height(10.dp).background(Z.well, RoundedCornerShape(5.dp))) {
            val used = ((bytes ?: 0).toFloat() / (limitMb * CacheLimits.MIB)).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth(used).height(10.dp).background(Z.ok, RoundedCornerShape(5.dp)))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("上限 ${CacheLimits.label(limitMb)}", fontSize = 18.sp, color = Z.textSecondary)
            Text(status, fontSize = 18.sp, color = Z.textTertiary)
        }
    }
}
