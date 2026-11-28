package app.zmby.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import app.zmby.data.SegmentProvider
import app.zmby.data.ServerItem
import app.zmby.emby.BaseItemDto
import app.zmby.log.ZLog
import app.zmby.media.episodeCode
import app.zmby.media.formatClock
import app.zmby.media.titleOf
import app.zmby.media.visibleText
import app.zmby.segments.ManualMarks
import app.zmby.segments.Segment as PlaybackSegment
import app.zmby.segments.SegmentKind
import app.zmby.segments.SegmentLookup
import app.zmby.segments.SegmentMarks
import app.zmby.segments.SegmentPlan
import app.zmby.segments.SegmentRepository
import app.zmby.segments.SegmentRules
import app.zmby.ui.LocalNavEntry
import app.zmby.ui.components.Focusable
import app.zmby.ui.components.GlassButton
import app.zmby.ui.components.Glyph
import app.zmby.ui.components.Icon
import app.zmby.ui.components.LocalBackdrop
import app.zmby.ui.components.LocalInverted
import app.zmby.ui.components.Segment
import app.zmby.ui.components.SegmentedWell
import app.zmby.ui.components.SelectableRow
import app.zmby.ui.components.onFill
import app.zmby.ui.theme.Z
import app.zmby.zmby
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

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

internal fun segmentProviderName(p: SegmentProvider): String = if (p == SegmentProvider.Emby) "服务器" else p.label

private fun span(s: PlaybackSegment) = "${formatClock(s.startMs)}–${s.endMs?.let(::formatClock) ?: "结束"}"

/** Preview unsaved choices against exactly the same enabled providers and cut checks as playback. */
internal fun editorPlan(lookup: SegmentLookup?, effective: ManualMarks, runtime: Long?): SegmentPlan {
    val answers = lookup?.answers.orEmpty().mapValues { (provider, segments) ->
        if (provider == SegmentProvider.Local) SegmentRules.valid(effective.segments(), runtime) else segments
    }
    return SegmentRules.merge(answers.keys.toList(), answers, effective.sources)
}

@Composable
fun SegmentMarksPanel(server: String, item: BaseItemDto, onDismiss: () -> Unit) {
    val density = LocalDensity.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        DialogDim(0.62f)
        CompositionLocalProvider(LocalDensity provides density, LocalBackdrop provides null, LocalNavEntry provides null) {
            SegmentMarksEditor(server, item, onDismiss)
        }
    }
}

@Composable
private fun SegmentMarksEditor(server: String, item: BaseItemDto, onDismiss: () -> Unit) {
    val app = LocalContext.current.zmby
    val scope = rememberCoroutineScope()
    val status = rememberSegmentStatus(ServerItem(server, item))
    var key by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf(ManualMarks()) }
    var choosing by remember { mutableStateOf<SegmentKind?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var seasonMarks by remember { mutableStateOf(ManualMarks(season = true)) }
    var lastStop by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(server, item.id) {
        val k = app.workKeyOf(server, item)
        val saved = app.segments.manual(k)
        draft = SegmentMarks.opening(saved)
        seasonMarks = saved.season
        lastStop = app.segments.lastStop(k) ?: item.resumeMs.takeIf { it > 0 }
        key = k
    }
    val runtime = item.runtimeMs?.takeIf { it > 0 }
    val seasonable = key?.let { SegmentRepository.seasonKey(it) } != null
    val introCard = remember { FocusRequester() }
    val creditsCard = remember { FocusRequester() }
    LaunchedEffect(Unit) { introCard.requestFocus() }
    val effective = if (draft.season) draft else draft.inheriting(seasonMarks)
    val plan = editorPlan(status.lookup, effective, runtime)
    fun inherited(kind: SegmentKind): Pair<String, Long>? {
        val (provider, mark) = plan[kind] ?: return null
        val time = if (kind == SegmentKind.Intro) mark.endMs else mark.startMs
        return time?.let { (if (provider == SegmentProvider.Local) "本季" else segmentProviderName(provider)) to it }
    }
    val inheritedIntro = inherited(SegmentKind.Intro)
    val inheritedCredits = inherited(SegmentKind.Credits)
    Column(
        Modifier.size(1460.dp, 820.dp).dialogPanel(RoundedCornerShape(36.dp)).padding(horizontal = 52.dp, vertical = 40.dp)
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
            plan = plan, draft = draft,
            lastStop = lastStop,
        )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (kind in SegmentKind.entries) {
                    val provider = effective.sources[kind]
                    if (kind == SegmentKind.Preview && provider == null && kind !in plan) continue
                    val active = plan[kind]?.first
                    val name = active?.let(::segmentProviderName) ?: provider?.let(::segmentProviderName) ?: "自动"
                    GlassButton("${kind.label} · $name", { if (key != null) choosing = kind },
                        height = 58.dp, horizontalPadding = 24.dp, fontSize = 21, label = "${kind.label}来源 $name")
                }
            }
            val unavailable = status.lookup?.unavailable.orEmpty()
            when {
                status.loading -> Text("正在查询片段…", fontSize = 19.sp, color = Z.textSecondary)
                status.failed -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    val names = unavailable.joinToString("、", transform = ::segmentProviderName)
                    Text("${names.ifEmpty { "来源" }} 无法连接", fontSize = 19.sp, color = Z.warn)
                    GlassButton("重试", { app.segments.retryUnavailable() }, height = 44.dp, fontSize = 18)
                }
                plan.isEmpty() -> Text("未获取到标记", fontSize = 19.sp, color = Z.textSecondary)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            MarkColumn(
                "片头结束", draft.introEndMs, inheritedIntro, { draft = draft.withTime(SegmentKind.Intro, it) },
                default = inheritedIntro?.second ?: SegmentMarks.INTRO_DEFAULT_MS, runtime, lastStop,
                Modifier.weight(1f), introCard, enabled = key != null,
            )
            MarkColumn(
                "片尾开始", draft.creditsStartMs, inheritedCredits, { draft = draft.withTime(SegmentKind.Credits, it) },
                default = inheritedCredits?.second ?: runtime?.let { (it - SegmentMarks.CREDITS_DEFAULT_BEFORE_END_MS).coerceAtLeast(0) } ?: 0,
                runtime, lastStop, Modifier.weight(1f), creditsCard, enabled = key != null,
            )
        }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            if (seasonable) SegmentedWell(
                items = listOf(Segment("本集"), Segment("本季全部")),
                selected = if (draft.season) 1 else 0,
                onSelect = { i ->
                    if ((i == 1) != draft.season) draft = SegmentMarks.rescope(draft, i == 1, seasonMarks)
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
            if (saveFailed) Text("保存失败，请重试", color = Z.warn, fontSize = 18.sp)
            GlassButton("保存", {
                val k = key ?: return@GlassButton
                if (saving) return@GlassButton
                val marks = draft
                saving = true
                scope.launch {
                    try {
                        app.segments.saveManual(k, marks)
                        onDismiss()
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        ZLog.w("Segments", "manual marks not saved", e)
                        saveFailed = true
                    } finally { saving = false }
                }
            }, label = "片头片尾 保存")
            GlassButton("取消", onDismiss, label = "片头片尾 取消")
        }
    }
    choosing?.let { kind ->
        SourcePicker(kind, status, draft, seasonMarks, plan[kind], onDismiss = { choosing = null }) { provider ->
            draft = draft.withSource(kind, provider)
            choosing = null
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
private fun Timeline(runtime: Long, plan: SegmentPlan, draft: ManualMarks, lastStop: Long?) {
    fun f(ms: Long) = (ms.toFloat() / runtime).coerceIn(0f, 1f)
    val intro = plan[SegmentKind.Intro]?.second?.endMs
    val credits = plan[SegmentKind.Credits]?.second?.startMs
    val label = androidx.compose.ui.text.TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold)
    Box(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.06f), BoxShape).padding(start = 30.dp, end = 30.dp, top = 22.dp, bottom = 26.dp)) {
        Box(Modifier.fillMaxWidth().height(92.dp).clearAndSetSemantics { }) {
            if (lastStop != null && lastStop in 0..runtime) {
                Text("上次停止 ${formatClock(lastStop)}", style = label, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.atFraction(f(lastStop), 0.5f))
                Box(Modifier.atFraction(f(lastStop), 0.5f).offset(y = 24.dp)) { Box(Modifier.width(2.dp).height(34.dp).background(Color.White.copy(alpha = 0.7f))) }
            }
            Canvas(Modifier.fillMaxWidth().offset(y = 34.dp).height(16.dp).clip(RoundedCornerShape(percent = 50)).background(Color.White.copy(alpha = 0.14f))) {
                for ((kind, entry) in plan) {
                    val (provider, segment) = entry
                    val start = size.width * f(segment.startMs)
                    val end = size.width * f(segment.endMs ?: runtime)
                    val manual = provider == SegmentProvider.Local && draft.time(kind) != null
                    drawRect(Color.White.copy(alpha = if (manual) 1f else 0.45f), Offset(start, 0f), Size(end - start, size.height))
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
private fun SourcePicker(
    kind: SegmentKind, status: SegmentStatus, draft: ManualMarks, season: ManualMarks,
    active: Pair<SegmentProvider, PlaybackSegment>?, onDismiss: () -> Unit, onSelect: (SegmentProvider?) -> Unit,
) {
    val density = LocalDensity.current
    val initial = remember { FocusRequester() }
    val preferred = draft.sources[kind]
    val inherit = !draft.season && (season.time(kind) != null || kind in season.sources)
    val current = draft.time(kind)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        DialogDim(0.62f)
        CompositionLocalProvider(LocalDensity provides density) {
            Column(Modifier.width(760.dp).dialogPanel(BoxShape).padding(36.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${kind.label}来源", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                val actual = active?.let { (p, mark) -> "${segmentProviderName(p)}　${span(mark)}" } ?: "暂无标记"
                Text("当前使用　$actual", fontSize = 21.sp, color = Z.textSecondary)
                val auto = preferred == null && current == null
                SelectableRow({ onSelect(null) }, if (auto) Modifier.focusRequester(initial) else Modifier, selected = auto) {
                    Text(if (inherit) "沿用本季" else "自动（按来源顺序）", Modifier.weight(1f))
                }
                if (current != null) SelectableRow(onDismiss, Modifier.focusRequester(initial), selected = true) {
                    Text("本地手动　${formatClock(current)}", Modifier.weight(1f))
                }
                val answers = status.lookup?.answers.orEmpty()
                for (provider in (answers.keys + listOfNotNull(preferred))) {
                    if (provider == SegmentProvider.Local) continue
                    val candidate = answers[provider]?.firstOrNull { it.kind == kind }
                    val selected = preferred == provider
                    if (candidate == null && !selected) continue
                    val text = candidate?.let(::span) ?: "当前无可用标记"
                    val cache = if (candidate != null && provider in status.lookup?.unavailable.orEmpty()) " · 使用缓存" else ""
                    SelectableRow({ onSelect(provider) }, if (selected) Modifier.focusRequester(initial) else Modifier, selected = selected) {
                        Text("${segmentProviderName(provider)}　$text$cache", Modifier.weight(1f))
                    }
                }
            }
            LaunchedEffect(Unit) { initial.requestFocus() }
        }
    }
}

@Composable
private fun MarkColumn(
    title: String, value: Long?, inherited: Pair<String, Long>?, onChange: (Long?) -> Unit,
    default: Long, runtime: Long?, lastStop: Long?, modifier: Modifier, requester: FocusRequester, enabled: Boolean,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        MarkCard(title, value, inherited, onChange, default, runtime, Modifier.focusRequester(requester), enabled)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (enabled && lastStop != null) GlassButton("上次停止位置 ${formatClock(lastStop)}", { onChange(lastStop) }, height = 48.dp, horizontalPadding = 20.dp, fontSize = 18, label = "$title 上次停止位置")
            if (value != null) GlassButton("清除", { requester.requestFocus(); onChange(null) }, height = 48.dp, horizontalPadding = 20.dp, fontSize = 18, label = "$title 清除")
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
        label = "$title ${(value ?: inherited?.second)?.let(::formatClock) ?: "未设置"}",
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
                    val displayed = value ?: inherited?.second
                    if (displayed != null) Text(formatClock(displayed), fontSize = 60.sp, lineHeight = 60.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp)
                    else Text("未设置", fontSize = 40.sp, lineHeight = 60.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.alpha(0.45f))
                }
                val chip = if (value != null) "手动" else inherited?.let { (name, _) -> "沿用 $name" }
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
