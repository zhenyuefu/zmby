package app.zmby.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.zmby.aggregate.VersionFacts
import app.zmby.aggregate.VersionPrefs
import app.zmby.aggregate.VersionRanker
import app.zmby.data.BitrateOrder
import app.zmby.data.CodecAvoid
import app.zmby.data.HdrFormat
import app.zmby.data.ImaxMode
import app.zmby.data.QualityPreference
import app.zmby.data.RankOrder
import app.zmby.emby.MediaSourceInfo
import app.zmby.emby.MediaStream
import app.zmby.media.discBadge
import app.zmby.media.qualityTier
import app.zmby.media.resolutionLabel
import app.zmby.media.workTitleOf
import app.zmby.ui.components.ChoiceSetting
import app.zmby.ui.components.Glyph
import app.zmby.ui.components.Health
import app.zmby.ui.components.Icon
import app.zmby.ui.components.SelectableRow
import app.zmby.ui.components.SettingsGroup
import app.zmby.ui.components.ToggleSetting
import app.zmby.ui.components.groupCard
import app.zmby.ui.theme.Z
import app.zmby.zmby
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.withContext

/** The 画质偏好 row's value: 「4K · 杜比视界 · IMAX」, plus the bitrate cap when one is set. */
internal fun qualitySummary(p: VersionPrefs): String = listOfNotNull(
    p.quality.label,
    p.hdrOrder.firstOrNull()?.label,
    "IMAX".takeIf { p.imax == ImaxMode.Prefer },
    "≤ ${p.bitrateCapMbps} Mbps".takeIf { p.bitrateCapMbps > 0 },
).joinToString(" · ")

/** 码率上限 choices in Mbps; 0 = 不限. */
internal val BITRATE_CAPS = listOf(0, 120, 80, 50, 30, 20)

/** Moves [item] one place up ([delta] −1) or down (+1) within [order]; at either end it stays. */
internal fun <T> moved(order: List<T>, item: T, delta: Int): List<T> {
    val from = order.indexOf(item)
    val to = from + delta
    if (from < 0 || to !in order.indices) return order
    return order.toMutableList().apply { removeAt(from); add(to, item) }
}

/**
 * 画质偏好 (ui-spec §2.22, SettingsQuality.dc.html): the rules on the left (about 870 wide), the
 * 「按当前偏好」 preview fixed on the right (470). Back returns to the settings row (handled by the screen).
 */
@Composable
internal fun QualitySettings(modifier: Modifier) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { awaitFrame(); runCatching { first.requestFocus() } }
    Column(modifier.padding(start = 56.dp, end = 72.dp, top = 56.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("画质偏好", fontSize = 44.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("播放 › 版本选择", fontSize = 20.sp, color = Z.textSecondary)
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 56.dp)
                    .focusRequester(first).focusGroup(),
                verticalArrangement = Arrangement.spacedBy(26.dp),
            ) { QualityRules() }
            QualityPreview(Modifier.width(470.dp))
        }
    }
}

@Composable
private fun QualityRules() {
    val settings = LocalContext.current.zmby.settings
    val quality by settings.quality.flow.collectAsState()
    val acceptLower by settings.acceptLowerResolution.flow.collectAsState()
    val imax by settings.imaxMode.flow.collectAsState()
    val cap by settings.bitrateCapMbps.flow.collectAsState()
    val bitrateOrder by settings.bitrateOrder.flow.collectAsState()
    val codec by settings.codecAvoid.flow.collectAsState()
    val lossless by settings.preferLossless.flow.collectAsState()
    val nonDisc by settings.preferNonDisc.flow.collectAsState()
    val order by settings.rankOrder.flow.collectAsState()
    SettingsGroup("分辨率") {
        val tiers = listOf(QualityPreference.UHD, QualityPreference.FHD, QualityPreference.Auto)
        ChoiceSetting("分辨率", tiers.map { it.label }, tiers.indexOf(quality), { settings.quality.set(tiers[it]) })
        ToggleSetting("没有时接受更低分辨率", acceptLower, { settings.acceptLowerResolution.set(!acceptLower) })
    }
    SettingsGroup("HDR 格式") { HdrRanking() }
    SettingsGroup("画幅") {
        ChoiceSetting("IMAX", ImaxMode.entries.map { it.label }, imax.ordinal, { settings.imaxMode.set(ImaxMode.entries[it]) })
    }
    SettingsGroup("码率") {
        val caps = (BITRATE_CAPS + cap).distinct()
        ChoiceSetting("码率上限", caps.map { if (it == 0) "不限" else "$it Mbps" }, caps.indexOf(cap), { settings.bitrateCapMbps.set(caps[it]) })
        ChoiceSetting("同档码率", BitrateOrder.entries.map { it.label }, bitrateOrder.ordinal, { settings.bitrateOrder.set(BitrateOrder.entries[it]) })
    }
    SettingsGroup("编码与文件") {
        ChoiceSetting("视频编码", CodecAvoid.entries.map { it.label }, codec.ordinal, { settings.codecAvoid.set(CodecAvoid.entries[it]) })
        ToggleSetting("无损音轨优先", lossless, { settings.preferLossless.set(!lossless) })
        ToggleSetting("优先非原盘", nonDisc, { settings.preferNonDisc.set(!nonDisc) })
    }
    SettingsGroup("排序方式") {
        ChoiceSetting("画质与线路", RankOrder.entries.map { it.label }, order.ordinal, { settings.rankOrder.set(RankOrder.entries[it]) })
    }
}

/**
 * The HDR order as a list to rearrange: OK picks a row up (white, the grip turns into ↑↓), Up and
 * Down move it, OK puts it down; Back puts it back where it was. Each move is stored at once so the
 * preview follows.
 */
@Composable
private fun HdrRanking() {
    val settings = LocalContext.current.zmby.settings
    val stored by settings.hdrOrder.flow.collectAsState()
    val order = remember(stored) { HdrFormat.parse(stored) }
    var moving by remember { mutableStateOf<HdrFormat?>(null) }
    var origin by remember { mutableStateOf<List<HdrFormat>?>(null) }
    BackHandler(enabled = moving != null) {
        origin?.let { settings.hdrOrder.set(HdrFormat.store(it)) }
        moving = null
        origin = null
    }
    Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        order.forEachIndexed { index, format ->
            // Keyed by format: the moved row keeps its focus as it changes place.
            key(format) {
                val picked = moving == format
                SelectableRow(
                    onClick = {
                        if (picked) { moving = null; origin = null } else { origin = order; moving = format }
                    },
                    modifier = Modifier.height(64.dp).onPreviewKeyEvent { e ->
                        if (!picked || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val delta = when (e.key) { Key.DirectionUp -> -1; Key.DirectionDown -> 1; Key.DirectionLeft, Key.DirectionRight -> 0; else -> return@onPreviewKeyEvent false }
                        if (delta != 0) settings.hdrOrder.set(HdrFormat.store(moved(order, format, delta)))
                        true
                    },
                    showCheck = false,
                    radius = 18.dp,
                    padding = PaddingValues(start = 20.dp, end = 18.dp),
                    label = "HDR ${format.label}",
                    focusKey = "hdr:${format.name}",
                ) { focused ->
                    val lit = focused || picked
                    Box(
                        Modifier.size(34.dp).background(if (lit) Z.onFocus else Color.White.copy(alpha = 0.12f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Text("${index + 1}", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = if (lit) Color.White else Z.textPrimary) }
                    Text(format.label, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (picked) Icon(Glyph.Reorder, size = 22.dp, strokeWidth = 2.4f)
                    else Box(Modifier.alpha(0.45f)) { Icon(Glyph.Grip, size = 22.dp, strokeWidth = 2.4f) }
                }
            }
        }
    }
    Row(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        KeyHint("OK", "拿起 放下")
        KeyHint("↑ ↓", "移动")
    }
}

/** One version in the preview card. */
internal data class PreviewVersion(val server: String, val order: Int, val healthy: Boolean, val latencyMs: Long?, val source: MediaSourceInfo)

internal data class PreviewWork(val title: String, val versions: List<PreviewVersion>, val sample: Boolean)

/** How a preview badge reads against the rules: matched (white), against them (outline) or neutral. */
internal enum class BadgeFit { Hit, Neutral, Miss }

internal data class PreviewBadge(val text: String, val fit: BadgeFit)

/** The badges of one preview row (ui-spec §2.22): resolution, HDR, IMAX, disc, avoided codec, bitrate. */
internal fun previewBadges(source: MediaSourceInfo, prefs: VersionPrefs, imax: Boolean): List<PreviewBadge> {
    val out = mutableListOf<PreviewBadge>()
    val wanted = VersionRanker.wantedTier(prefs.quality)
    resolutionLabel(source)?.let { label ->
        val tier = qualityTier(source)
        out += PreviewBadge(label, when {
            wanted == null -> BadgeFit.Neutral
            tier == wanted -> BadgeFit.Hit
            tier.ordinal > wanted.ordinal -> BadgeFit.Miss
            else -> BadgeFit.Neutral
        })
    }
    VersionRanker.hdrFormat(source)?.let { f ->
        val rank = prefs.hdrOrder.indexOf(f)
        out += PreviewBadge(f.label, if (f != HdrFormat.SDR && rank in 0..1) BadgeFit.Hit else BadgeFit.Neutral)
    }
    if (imax) out += PreviewBadge("IMAX", when (prefs.imax) { ImaxMode.Prefer -> BadgeFit.Hit; ImaxMode.Avoid -> BadgeFit.Miss; ImaxMode.Neutral -> BadgeFit.Neutral })
    discBadge(source)?.let { out += PreviewBadge(it, if (prefs.preferNonDisc) BadgeFit.Miss else BadgeFit.Neutral) }
    VersionRanker.codecOf(source)?.takeIf { it == prefs.codecAvoid }?.let { out += PreviewBadge(if (it == CodecAvoid.AV1) "AV1" else "H.264", BadgeFit.Miss) }
    VersionRanker.bitrateOf(source)?.let { bps ->
        out += PreviewBadge("${(bps + 500_000) / 1_000_000} Mbps", if (VersionRanker.overCap(source, prefs.bitrateCapMbps)) BadgeFit.Miss else BadgeFit.Neutral)
    }
    return out
}

/**
 * The most recently opened work with at least two versions in the version-card cache (other
 * servers' cards), else the sample. Server names and priority come from the registry.
 */
private suspend fun previewWork(app: app.zmby.ZmbyApp): PreviewWork {
    val servers = app.registry.servers.value.associateBy { it.id }
    for (key in app.store.recentVersionKeys()) {
        val rows = app.store.versions(key)
        var title: String? = null
        val versions = rows.flatMap { row ->
            val status = servers[row.serverId] ?: return@flatMap emptyList()
            if (title == null) title = app.store.decodeItem(row.itemJson)?.let(::workTitleOf)
            app.store.decodeSources(row.sourcesJson).map { PreviewVersion(status.name, status.order, status.health != Health.Off, status.latencyMs, it) }
        }
        if (versions.size >= 2) return PreviewWork(title ?: "最近打开的作品", versions, sample = false)
    }
    return SAMPLE
}

private fun sampleSource(id: String, w: Int, h: Int, ext: String?, mbps: Long, name: String, codec: String = "hevc", iso: Boolean = false) = MediaSourceInfo(
    id = id, name = name, path = "/media/$name", container = if (iso) "iso" else "mkv", bitrate = mbps * 1_000_000,
    mediaStreams = listOf(MediaStream(0, "Video", codec = codec, width = w, height = h, extendedVideoType = ext)),
)

private val SAMPLE = PreviewWork("示例影片", listOf(
    PreviewVersion("家里 NAS", 0, true, 6, sampleSource("a", 3840, 2160, "DolbyVision", 68, "Film.2160p.IMAX.DV.mkv")),
    PreviewVersion("朋友的服务器", 1, true, 40, sampleSource("b", 3840, 1600, "Hdr10Plus", 62, "Film.2160p.HDR10+.mkv")),
    PreviewVersion("公益服 A", 2, true, 80, sampleSource("c", 3840, 1600, "DolbyVision", 98, "Film.2160p.iso", iso = true)),
    PreviewVersion("公益服 C", 3, true, 120, sampleSource("d", 3840, 1600, "Hdr10", 24, "Film.2160p.AV1.mkv", codec = "av1")),
    PreviewVersion("家里 NAS", 0, true, 6, sampleSource("e", 1920, 800, null, 12, "Film.1080p.mkv", codec = "h264")),
), sample = true)

/** 「按当前偏好」: the first five versions of [previewWork] in the order the current rules give. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QualityPreview(modifier: Modifier) {
    val app = LocalContext.current.zmby
    val prefs = rememberVersionPrefs()
    val work by produceState<PreviewWork?>(null) { value = withContext(Dispatchers.IO) { previewWork(app) } }
    val ranked = remember(work, prefs) {
        val w = work ?: return@remember emptyList()
        VersionRanker.rank(w.versions, prefs) { VersionFacts(it.server, it.order, it.healthy, it.latencyMs, it.source) }
    }
    val imax = remember(work) { VersionRanker.imaxRule(work?.versions.orEmpty().map { it.source }) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("按当前偏好", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Z.textTertiary, letterSpacing = 1.sp, modifier = Modifier.padding(horizontal = 24.dp))
        Column(Modifier.fillMaxWidth().groupCard(RoundedCornerShape(28.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val w = work
            Row(Modifier.padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(w?.title ?: "读取中…", fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (w != null) Text("${w.versions.size} 个版本" + if (w.sample) " · 示例" else "", fontSize = 17.sp, color = Color.White.copy(alpha = 0.55f), maxLines = 1)
            }
            ranked.take(5).forEachIndexed { i, v ->
                val top = i == 0
                val shape = RoundedCornerShape(20.dp)
                Column(
                    Modifier.fillMaxWidth()
                        .background(Color.White.copy(alpha = if (top) 0.11f else 0.04f), shape)
                        .then(if (top) Modifier.border(2.dp, Color.White.copy(alpha = 0.75f), shape) else Modifier)
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${i + 1}", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Color.White.copy(alpha = 0.6f))
                        Text(v.server, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (top) Icon(Glyph.Check, size = 22.dp, strokeWidth = 2.8f)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        previewBadges(v.source, prefs, imax(v.source)).forEach { PreviewBadgeChip(it) }
                    }
                    if (VersionRanker.overCap(v.source, prefs.bitrateCapMbps)) Text("超过码率上限", fontSize = 16.sp, color = Color.White.copy(alpha = 0.55f))
                }
            }
        }
    }
}

@Composable
private fun PreviewBadgeChip(badge: PreviewBadge) {
    val shape = RoundedCornerShape(8.dp)
    val base = Modifier.height(28.dp)
    val styled = when (badge.fit) {
        BadgeFit.Hit -> base.background(Color.White, shape)
        BadgeFit.Miss -> base.border(1.dp, Color.White.copy(alpha = 0.22f), shape)
        BadgeFit.Neutral -> base.background(Color.White.copy(alpha = 0.12f), shape)
    }
    Box(styled.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
        Text(
            badge.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            color = when (badge.fit) { BadgeFit.Hit -> Z.onFocus; BadgeFit.Miss -> Color.White.copy(alpha = 0.55f); BadgeFit.Neutral -> Z.textPrimary },
        )
    }
}
