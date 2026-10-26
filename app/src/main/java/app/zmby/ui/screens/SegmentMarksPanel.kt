package app.zmby.ui.screens

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
import app.zmby.ui.components.ChoiceSetting
import app.zmby.ui.components.GlassButton
import app.zmby.ui.components.SelectableRow
import app.zmby.ui.components.groupCard
import app.zmby.ui.theme.Z
import app.zmby.zmby
import kotlinx.coroutines.launch

/**
 * 本地手动标记 (local-data spec): 片头结束 and 片尾开始 of [item], typed with the remote. Left and
 * right move a time by 10 seconds (held: a minute); 「取上次停止位置」 takes where zmby last stopped
 * this video; an episode can apply the marks to its whole season. Back closes without saving.
 */
@Composable
fun SegmentMarksPanel(server: String, item: BaseItemDto, onDismiss: () -> Unit) {
    val app = LocalContext.current.zmby
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onDismiss)
    var key by remember { mutableStateOf<String?>(null) }
    var intro by remember { mutableStateOf<Long?>(null) }
    var credits by remember { mutableStateOf<Long?>(null) }
    var season by remember { mutableStateOf(false) }
    var lastStop by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(server, item.id) {
        val k = app.workKeyOf(server, item)
        val marks = app.segments.manual(k)
        intro = marks.introEndMs
        credits = marks.creditsStartMs
        season = marks.season
        lastStop = app.segments.lastStop(k) ?: item.resumeMs.takeIf { it > 0 }
        key = k
    }
    val runtime = item.runtimeMs
    val seasonable = key?.let { SegmentRepository.seasonKey(it) } != null
    val first = remember { FocusRequester() }
    LaunchedEffect(key) { if (key != null) runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Z.bg.copy(alpha = 0.82f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(1120.dp).groupCard(RoundedCornerShape(28.dp)).padding(36.dp)
                .focusProperties { onExit = { cancelFocusChange() } }.focusGroup(),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Text("片头片尾", fontSize = 36.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                listOfNotNull(visibleText(item.seriesName), episodeCode(item), titleOf(item).takeUnless { it == visibleText(item.seriesName) }).joinToString("  ·  "),
                fontSize = 21.sp, color = Z.textSecondary, maxLines = 1,
            )
            if (key == null) return@Column
            MarkRow("片头结束", intro, { intro = it }, default = SegmentMarks.INTRO_DEFAULT_MS, runtime, lastStop, Modifier.focusRequester(first))
            MarkRow("片尾开始", credits, { credits = it }, default = runtime?.let { (it - SegmentMarks.CREDITS_DEFAULT_BEFORE_END_MS).coerceAtLeast(0) } ?: 0, runtime, lastStop)
            if (seasonable) {
                ChoiceSetting("适用范围", listOf("本集", "本季全部"), if (season) 1 else 0, { season = it == 1 })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(top = 8.dp)) {
                GlassButton("保存", {
                    val k = key ?: return@GlassButton
                    scope.launch {
                        runCatching { app.segments.saveManual(k, ManualMarks(intro, credits, season && seasonable)) }
                            .onFailure { ZLog.w("Segments", "manual marks not saved", it) }
                        onDismiss()
                    }
                })
                GlassButton("取消", onDismiss)
            }
        }
    }
}

@Composable
private fun MarkRow(title: String, value: Long?, onChange: (Long?) -> Unit, default: Long, runtime: Long?, lastStop: Long?, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        SelectableRow(
            onClick = {},
            modifier = modifier.width(560.dp).onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val sign = when (e.key) { Key.DirectionLeft -> -1; Key.DirectionRight -> 1; else -> return@onPreviewKeyEvent false }
                val step = if (e.nativeKeyEvent.repeatCount >= 5) SegmentMarks.FAST_STEP_MS else SegmentMarks.STEP_MS
                onChange(SegmentMarks.adjust(value, default, sign * step, runtime))
                true
            },
            showCheck = false,
            radius = 20.dp,
            label = title,
        ) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(value?.let(::formatClock) ?: "未设置", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("‹ ›", fontSize = 22.sp, color = Z.textTertiary)
        }
        if (lastStop != null) GlassButton("取上次停止位置 · ${formatClock(lastStop)}", { onChange(lastStop) }, height = 60.dp, horizontalPadding = 26.dp)
        if (value != null) GlassButton("清除", { onChange(null) }, height = 60.dp, horizontalPadding = 26.dp)
    }
}
