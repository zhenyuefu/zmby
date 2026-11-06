package app.zmby.ui.screens

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zmby.media.visibleText
import app.zmby.media.titleOf
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.LocalContentColor
import app.zmby.ui.components.Focusable
import app.zmby.ui.components.Glyph
import app.zmby.ui.components.Icon
import app.zmby.ui.components.LocalInverted
import app.zmby.ui.components.Segment
import app.zmby.ui.components.SegmentedWell
import app.zmby.ui.components.onFill
import app.zmby.ui.components.panelSurface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.zmby.emby.BaseItemDto
import app.zmby.log.ZLog
import app.zmby.media.episodeCode
import app.zmby.media.formatClock
import app.zmby.segments.ManualMarks
import app.zmby.segments.SegmentMarks
import app.zmby.segments.SegmentRepository
import app.zmby.segments.SegmentLookup
import app.zmby.segments.SegmentKind
import app.zmby.data.ServerItem
import app.zmby.data.SegmentProvider
import app.zmby.ui.components.GlassButton
import app.zmby.ui.theme.Z
import app.zmby.zmby
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

internal data class SegmentStatus(val lookup: SegmentLookup? = null, val loading: Boolean = false, val failed: Boolean = false)

internal fun segmentTip(state: SegmentStatus): String = when {
    state.loading -> "片头片尾 · 正在查询…"
    state.failed -> "片头片尾 · 来源无法连接"
    state.lookup?.plan?.isNotEmpty() == true -> "片头片尾 · 已获取标记"
    else -> "片头片尾 · 未获取标记"
}

@Composable
internal fun rememberSegmentStatus(ref: ServerItem?): SegmentStatus {
    val app = LocalContext.current.zmby
    val revision by app.segments.changes.collectAsStateWithLifecycle()
    var status by remember(ref?.key, revision) { mutableStateOf(SegmentStatus(loading = ref != null)) }
    LaunchedEffect(ref?.key, revision) {
        if (ref == null) return@LaunchedEffect
        status = try {
            val result = app.segmentLookup(ref.server, ref.item)
            SegmentStatus(result, failed = result.unavailable.isNotEmpty())
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { SegmentStatus(failed = true) }
    }
    return status
}

/** A provider's line in the 来源 card (ui-spec §2.21). */
internal data class SegmentSourceRow(val provider: SegmentProvider, val name: String, val text: String, val failed: Boolean)

/** The provider's name as the panel shows it: Emby markers are 「服务器」. */
internal fun segmentProviderName(p: SegmentProvider): String = if (p == SegmentProvider.Emby) "服务器" else p.label

private fun span(s: app.zmby.segments.Segment) = "${s.kind.label} ${formatClock(s.startMs)}–${s.endMs?.let(::formatClock) ?: "结束"}"

/**
 * One row per provider that gave segments or could not be reached, in source order; the saved manual
 * marks (本地手动) are the time cards, not a source. A failed provider with an older answer says 「使用缓存」.
 */
internal fun segmentSourceRows(lookup: SegmentLookup?): List<SegmentSourceRow> {
    if (lookup == null) return emptyList()
    val providers = (lookup.answers.keys + lookup.unavailable).filter { it != SegmentProvider.Local }.distinct()
    return providers.mapNotNull { p ->
        val segments = lookup.answers[p].orEmpty().sortedBy { it.kind.ordinal }
        val failed = p in lookup.unavailable
        when {
            failed -> SegmentSourceRow(p, segmentProviderName(p), if (segments.isNotEmpty()) "无法连接 · 使用缓存" else "无法连接", true)
            segments.isNotEmpty() -> SegmentSourceRow(p, segmentProviderName(p), segments.joinToString("　　", transform = ::span), false)
            else -> null
        }
    }
}

/** The value a time card falls back on without a manual one: (provider name, time) of the first source that has it. */
internal fun inheritedMark(lookup: SegmentLookup?, kind: SegmentKind): Pair<String, Long>? {
    lookup ?: return null
    for ((p, segments) in lookup.answers) {
        if (p == SegmentProvider.Local) continue
        val s = segments.firstOrNull { it.kind == kind } ?: continue
        val at = if (kind == SegmentKind.Intro) s.endMs else s.startMs
        if (at != null) return segmentProviderName(p) to at
    }
    return null
}

/**
 * What a time card falls back on without a value of its own: under 本集 the season's manual mark
 * (「本季」, which playback uses for that kind), else the first source that has it.
 */
internal fun cardInherited(lookup: SegmentLookup?, kind: SegmentKind, seasonMark: Long?, seasonScope: Boolean): Pair<String, Long>? =
    seasonMark?.takeUnless { seasonScope }?.let { "本季" to it } ?: inheritedMark(lookup, kind)

/**
 * 片头片尾 (ui-spec §2.21, SegmentPanel.dc.html): a 1460 × 820 panel over the page darkened to 62 %.
 * The timeline shows the episode with the intro and credits spans (manual white, inherited 45 %)
 * and where zmby last stopped; the source card lists what each provider found; two time cards set
 * 片头结束 and 片尾开始 — Left and Right move 10 seconds, held a minute; an unset card starts from
 * the inherited time. 本集 | 本季全部 for episodes whose season is known; under 本集 a card without
 * its own time uses the season's (「沿用 本季」), and switching keeps what was typed. Back cancels.
 */
@Composable
fun SegmentMarksPanel(server: String, item: BaseItemDto, onDismiss: () -> Unit) {
    val app = LocalContext.current.zmby
    val scope = rememberCoroutineScope()
    val status = rememberSegmentStatus(ServerItem(server, item))
    BackHandler(onBack = onDismiss)
    var key by remember { mutableStateOf<String?>(null) }
    var intro by remember { mutableStateOf<Long?>(null) }
    var credits by remember { mutableStateOf<Long?>(null) }
    var season by remember { mutableStateOf(false) }
    var seasonMarks by remember { mutableStateOf(ManualMarks(season = true)) }
    var lastStop by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(server, item.id) {
        val k = app.workKeyOf(server, item)
        val saved = app.segments.manual(k)
        val marks = SegmentMarks.opening(saved)
        intro = marks.introEndMs
        credits = marks.creditsStartMs
        season = marks.season
        seasonMarks = saved.season
        lastStop = app.segments.lastStop(k) ?: item.resumeMs.takeIf { it > 0 }
        key = k
    }
    val runtime = item.runtimeMs?.takeIf { it > 0 }
    val seasonable = key?.let { SegmentRepository.seasonKey(it) } != null
    val introCard = remember { FocusRequester() }
    val retry = remember { FocusRequester() }
    LaunchedEffect(key) { if (key != null) runCatching { introCard.requestFocus() } }
    val inheritedIntro = cardInherited(status.lookup, SegmentKind.Intro, seasonMarks.introEndMs, season)
    val inheritedCredits = cardInherited(status.lookup, SegmentKind.Credits, seasonMarks.creditsStartMs, season)
    val sources = segmentSourceRows(status.lookup)
    val canRetry = !status.loading && sources.any { it.failed }
    Box(Modifier.fillMaxSize().background(Color(0xFF050608).copy(alpha = 0.62f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.size(1460.dp, 820.dp).panelSurface(RoundedCornerShape(36.dp)).padding(start = 52.dp, end = 52.dp, top = 44.dp, bottom = 40.dp)
                .focusProperties { onExit = { cancelFocusChange() } }.focusGroup(),
            verticalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Glyph.Marks, size = 34.dp, strokeWidth = 2.2f)
                Text("片头片尾", fontSize = 36.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    listOfNotNull(visibleText(item.seriesName), episodeCode(item), titleOf(item).takeUnless { it == visibleText(item.seriesName) }).joinToString(" · "),
                    fontSize = 21.sp, color = Z.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (runtime != null) Timeline(
                runtime,
                intro = intro ?: inheritedIntro?.second, introManual = intro != null,
                credits = credits ?: inheritedCredits?.second, creditsManual = credits != null,
                lastStop = lastStop,
            )
            SourceCard(status.loading, sources, retry) { app.segments.retryUnavailable() }
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                val up = Modifier.focusProperties { if (canRetry) this.up = retry }
                MarkColumn(
                    "片头结束", intro, inheritedIntro, { intro = it },
                    default = inheritedIntro?.second ?: SegmentMarks.INTRO_DEFAULT_MS, runtime, lastStop,
                    Modifier.weight(1f), up.focusRequester(introCard), enabled = key != null,
                )
                MarkColumn(
                    "片尾开始", credits, inheritedCredits, { credits = it },
                    default = inheritedCredits?.second ?: runtime?.let { (it - SegmentMarks.CREDITS_DEFAULT_BEFORE_END_MS).coerceAtLeast(0) } ?: 0,
                    runtime, lastStop, Modifier.weight(1f), up, enabled = key != null,
                )
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                if (seasonable) SegmentedWell(
                    items = listOf(Segment("本集"), Segment("本季全部")),
                    selected = if (season) 1 else 0,
                    onSelect = { i ->
                        if ((i == 1) != season) {
                            val next = SegmentMarks.rescope(ManualMarks(intro, credits, season), i == 1, seasonMarks)
                            intro = next.introEndMs; credits = next.creditsStartMs; season = next.season
                        }
                    },
                    selectOnFocus = false,
                    segHeight = 46.dp,
                    fontSize = 19,
                    horizontalPadding = 22.dp,
                    labelPrefix = "适用范围",
                )
                Row(Modifier.padding(start = 18.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    KeyHint("‹ ›", "10 秒")
                    Text("长按 1 分钟", fontSize = 17.sp, color = Color.White.copy(alpha = 0.6f))
                }
                Spacer(Modifier.weight(1f))
                GlassButton("保存", {
                    val k = key ?: return@GlassButton
                    scope.launch {
                        runCatching { app.segments.saveManual(k, ManualMarks(intro, credits, season && seasonable)) }
                            .onFailure { ZLog.w("Segments", "manual marks not saved", it) }
                        onDismiss()
                    }
                }, label = "片头片尾 保存")
                GlassButton("取消", onDismiss, label = "片头片尾 取消")
            }
        }
    }
}

private val BoxShape = RoundedCornerShape(24.dp)

/** Places its child with [anchor] (0 = left edge, 0.5 = centre, 1 = right edge) at [fraction] of the width, kept inside. */
private fun Modifier.atFraction(fraction: Float, anchor: Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0))
    val width = constraints.maxWidth
    layout(width, placeable.height) {
        val x = (fraction * width - anchor * placeable.width).toInt().coerceIn(0, (width - placeable.width).coerceAtLeast(0))
        placeable.place(x, 0)
    }
}

@Composable
private fun Timeline(runtime: Long, intro: Long?, introManual: Boolean, credits: Long?, creditsManual: Boolean, lastStop: Long?) {
    fun f(ms: Long) = (ms.toFloat() / runtime).coerceIn(0f, 1f)
    val label = androidx.compose.ui.text.TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold)
    Box(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.06f), BoxShape).padding(start = 30.dp, end = 30.dp, top = 22.dp, bottom = 26.dp)) {
        Box(Modifier.fillMaxWidth().height(92.dp).clearAndSetSemantics { }) {
            if (lastStop != null && lastStop in 0..runtime) {
                Text("上次停止 ${formatClock(lastStop)}", style = label, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.atFraction(f(lastStop), 0.5f))
                Box(Modifier.atFraction(f(lastStop), 0.5f).offset(y = 24.dp)) { Box(Modifier.width(2.dp).height(34.dp).background(Color.White.copy(alpha = 0.7f))) }
            }
            Box(Modifier.fillMaxWidth().offset(y = 34.dp).height(16.dp).clip(RoundedCornerShape(percent = 50)).background(Color.White.copy(alpha = 0.14f))) {
                if (intro != null) Box(Modifier.fillMaxHeight().fillMaxWidth(f(intro)).background(Color.White.copy(alpha = if (introManual) 1f else 0.45f)))
                if (credits != null) Box(Modifier.fillMaxHeight().fillMaxWidth().atFraction(f(credits), 0f)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(1f - f(credits)).background(Color.White.copy(alpha = if (creditsManual) 1f else 0.45f)))
                }
            }
            Box(Modifier.fillMaxWidth().offset(y = 62.dp)) {
                Text("0:00", style = label)
                if (intro != null) Text("片头结束 ${formatClock(intro)}", style = label, modifier = Modifier.atFraction(f(intro), 0f).padding(start = 14.dp))
                if (credits != null) Text("片尾开始 ${formatClock(credits)}", style = label, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.atFraction(f(credits), 1f).padding(end = 14.dp))
                Text(formatClock(runtime), style = label, modifier = Modifier.atFraction(1f, 1f))
            }
        }
    }
}

@Composable
private fun SourceCard(loading: Boolean, rows: List<SegmentSourceRow>, retry: FocusRequester, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.06f), BoxShape).padding(vertical = 8.dp)) {
        @Composable
        fun line(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) =
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp), content = content)
        when {
            loading -> line { Text("正在查询…", fontSize = 19.sp, color = Z.textSecondary) }
            rows.isEmpty() -> line { Text("未获取到标记", fontSize = 19.sp, color = Z.textSecondary) }
            else -> {
                val firstFailed = rows.indexOfFirst { it.failed }
                rows.forEachIndexed { i, row ->
                    line {
                        Box(Modifier.size(10.dp).background(if (row.failed) Z.warn else Z.ok, CircleShape))
                        Text(row.name, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(180.dp))
                        Text(row.text, fontSize = 19.sp, color = if (row.failed) Z.warn else Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (row.failed) GlassButton("重试", onRetry, if (i == firstFailed) Modifier.focusRequester(retry) else Modifier,
                            height = 48.dp, horizontalPadding = 20.dp, fontSize = 18, label = "${row.name} 重试")
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkColumn(
    title: String, value: Long?, inherited: Pair<String, Long>?, onChange: (Long?) -> Unit,
    default: Long, runtime: Long?, lastStop: Long?, modifier: Modifier, cardModifier: Modifier, enabled: Boolean,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        MarkCard(title, value, inherited, onChange, default, runtime, cardModifier, enabled)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (lastStop != null) GlassButton("上次停止位置 ${formatClock(lastStop)}", { onChange(lastStop) }, height = 48.dp, horizontalPadding = 20.dp, fontSize = 18, label = "$title 上次停止位置")
            if (value != null) GlassButton("清除", { onChange(null) }, height = 48.dp, horizontalPadding = 20.dp, fontSize = 18, label = "$title 清除")
        }
    }
}

/** `.mk`: 144 high; white and 1.03 when focused. The chip says 「手动」 or where the shown time comes from. */
@Composable
private fun MarkCard(title: String, value: Long?, inherited: Pair<String, Long>?, onChange: (Long?) -> Unit, default: Long, runtime: Long?, modifier: Modifier, enabled: Boolean) {
    val shape = RoundedCornerShape(26.dp)
    Focusable(
        onClick = {},
        modifier = modifier.fillMaxWidth().onPreviewKeyEvent { e ->
            if (!enabled || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val sign = when (e.key) { Key.DirectionLeft -> -1; Key.DirectionRight -> 1; else -> return@onPreviewKeyEvent false }
            val step = if (e.nativeKeyEvent.repeatCount >= 5) SegmentMarks.FAST_STEP_MS else SegmentMarks.STEP_MS
            onChange(SegmentMarks.adjust(value, default, sign * step, runtime))
            true
        },
        shape = shape,
        focusedScale = 1.03f,
        outline = 0.dp,
        shadow = 0.dp,
        label = "$title ${value?.let(::formatClock) ?: "未设置"}",
        focusKey = "mark:$title",
    ) { focused ->
        CompositionLocalProvider(LocalContentColor provides if (focused) Z.onFocus else Z.textPrimary, LocalInverted provides focused) {
            Row(
                Modifier.fillMaxWidth().height(144.dp)
                    .then(if (focused) Modifier.onFill(shape) else Modifier.background(Color.White.copy(alpha = 0.08f), shape))
                    .padding(horizontal = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text("‹", fontSize = 34.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.alpha(0.45f))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.alpha(0.75f))
                    if (value != null) Text(formatClock(value), fontSize = 60.sp, lineHeight = 60.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp)
                    else Text("未设置", fontSize = 40.sp, lineHeight = 60.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.alpha(0.45f))
                }
                val chip = if (value != null) "手动" else inherited?.let { (name, at) -> "沿用 $name ${formatClock(at)}" }
                if (chip != null) Text(
                    chip, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    modifier = Modifier.background(if (focused) Z.onFocus.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.14f), RoundedCornerShape(percent = 50))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                Text("›", fontSize = 34.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.alpha(0.45f))
            }
        }
    }
}
