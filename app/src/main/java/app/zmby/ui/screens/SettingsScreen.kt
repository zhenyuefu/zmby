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
import app.zmby.R
import app.zmby.catalog.CatalogChart
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
import app.zmby.ui.rememberInitialFocus
import app.zmby.ui.theme.Z
import app.zmby.zmby
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class Category(val label: String) {
    General("通用"), Metadata("元数据与图片"), Cache("本地缓存"), Playback("播放"), Tracks("音频与字幕"), Home("首页与媒体库"), Servers("服务器"), Sources("数据来源"), Appearance("外观"), About("关于")
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
                Category.General -> General(nav)
                Category.Metadata -> MetadataSettings()
                Category.Cache -> CacheSettings()
                Category.Playback -> Playback(nav)
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
private fun General(nav: Nav) {
    val settings = LocalContext.current.zmby.settings
    SettingsGroup("观影") {
        ValueSetting("观影统计", null, onClick = { nav.go(Route.Stats) }, glyph = Glyph.Chart)
    }
    val percent by settings.watchedPercent.collectAsState()
    val steps = remember(percent) { (listOf(70, 80, 85, 90, 95) + percent).distinct().sorted() }
    SettingsGroup("进度") {
        ChoiceSetting("看完阈值", steps.map { "$it%" }, steps.indexOf(percent), { settings.setWatchedPercent(steps[it]) })
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
        ToggleSetting("倒计时自动播放下一集", countdown && offerNext, { settings.upNextCountdown.set(!countdown) }, dimmed = !offerNext,
            sub = "沿用同一服务器、线路与音轨字幕语言")
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
        ValueSetting("备份与恢复", "导出与恢复", onClick = { nav.go(Route.Backup) })
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

@Composable
private fun MetadataSettings() {
    val app = LocalContext.current.zmby
    val settings = app.settings
    val token by settings.tmdbToken.flow.collectAsState()
    val text by settings.metadataLanguage.flow.collectAsState()
    val poster by settings.posterLanguage.flow.collectAsState()
    val logo by settings.logoLanguage.flow.collectAsState()
    val chart by settings.tmdbChart.flow.collectAsState()
    val search by settings.tmdbSearch.flow.collectAsState()
    val anime by settings.animeAliases.flow.collectAsState()
    val languages = listOf("zh-CN" to "简体中文", "zh-TW" to "繁體中文（台灣）", "zh-HK" to "繁體中文（香港）", "en-US" to "English", "ja-JP" to "日本語", "ko-KR" to "한국어")
    SettingsGroup("影视数据库直连") {
        Column(Modifier.padding(24.dp)) {
            var draft by remember(token) { mutableStateOf(token) }
            Field("TMDB API Read Access Token", draft, { draft = it }, KeyboardType.Ascii, secret = true, last = true, onSubmit = {
                ZLog.secret(draft.trim())
                settings.tmdbToken.set(draft.trim())
            })
        }
        ToggleSetting("搜索包含 TMDB", search, { settings.tmdbSearch.set(!search) })
        ToggleSetting("Bangumi 与 bangumi-data 番剧别名", anime, { settings.animeAliases.set(!anime) }, sub = "按原名与译名查找番剧；季集关系不明确时保留待确认")
    }
    SettingsGroup("语言偏好") {
        ChoiceSetting("元数据语言", languages.map { it.second }, languages.indexOfFirst { it.first == text }.coerceAtLeast(0), { settings.metadataLanguage.set(languages[it].first) })
        ChoiceSetting("海报语言", languages.map { it.second }, languages.indexOfFirst { it.first == poster }.coerceAtLeast(0), { settings.posterLanguage.set(languages[it].first) })
        ChoiceSetting("Logo 语言", languages.map { it.second }, languages.indexOfFirst { it.first == logo }.coerceAtLeast(0), { settings.logoLanguage.set(languages[it].first) })
        Text("图片严格匹配语言；缺失时回退原语言。无法确认简繁的中文图片不作为匹配结果。", fontSize = 18.sp, color = Z.textSecondary, modifier = Modifier.padding(24.dp))
    }
    SettingsGroup("首页轮播榜单") {
        val charts = CatalogChart.entries
        ChoiceSetting("TMDB 榜单", charts.map { it.label }, charts.indexOf(CatalogChart.of(chart)), { settings.tmdbChart.set(charts[it].name) })
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
    SettingsGroup("元数据缓存") {
        ChoiceSetting("元数据上限", CacheLimits.metadataMb.map(CacheLimits::label), CacheLimits.metadataMb.indexOf(metadataLimit),
            { app.settings.metadataCacheMb.set(CacheLimits.metadataMb[it]) }, sub = "立即生效，超限时优先清理较少使用的资料")
        ValueSetting("已用容量", CacheLimits.usage(metadataSize), sub = "影视资料、人物、季集、搜索和榜单缓存")
    }
    SettingsGroup("图片缓存") {
        ChoiceSetting("图片上限", CacheLimits.imagesMb.map(CacheLimits::label), CacheLimits.imagesMb.indexOf(imageLimit),
            { app.settings.imageCacheMb.set(CacheLimits.imagesMb[it]) },
            sub = if (imageLimit != app.activeImageCacheMb) "已保存，重启应用后生效；当前上限 ${CacheLimits.label(app.activeImageCacheMb)}" else "调整后需重启应用，优先清理较少使用的图片")
        ValueSetting("已用容量", imageSize?.let(CacheLimits::usage) ?: "正在读取…", sub = "海报、背景、Logo 和人物头像")
    }
    Text("收藏、待看和观看记录单独保存，不计入以上缓存上限。", fontSize = 19.sp, color = Z.textSecondary, modifier = Modifier.padding(24.dp))
}
