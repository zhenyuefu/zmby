package app.zmby.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import app.zmby.ZmbyApp
import app.zmby.data.SourceFeature
import app.zmby.stats.MonthStat
import app.zmby.stats.StatsSummary
import app.zmby.ui.Nav
import app.zmby.ui.Route
import app.zmby.ui.components.GlassButton
import app.zmby.ui.components.Glyph
import app.zmby.ui.components.groupCard
import app.zmby.ui.rememberInitialFocus
import app.zmby.ui.screenModel
import app.zmby.ui.theme.Z
import app.zmby.zmby
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 观影统计's state: the summary from the local store, refreshed from the servers in the background. */
class StatsViewModel(private val app: ZmbyApp) : ViewModel() {
    val summary: StateFlow<StatsSummary?> = app.stats.summary().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val refreshing = app.stats.refreshing
    val failed = app.stats.failed

    fun refresh(force: Boolean) {
        viewModelScope.launch { app.stats.refresh(force) }
    }
}

/**
 * 观影统计 (P4): watched films and episodes of the 观影统计 servers (设置 › 数据来源), each work
 * once; total time, the last twelve months and the series watched most. Local first: the numbers
 * show at once and change when the servers answer.
 */
@Composable
fun StatsScreen(nav: Nav) {
    val app = LocalContext.current.zmby
    val vm = screenModel("stats") { StatsViewModel(app) }
    val summary by vm.summary.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val failed by vm.failed.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh(force = false) }
    val sourceRows by app.sources.rows.collectAsStateWithLifecycle()
    val sourceLabel = remember(sourceRows) { app.sources.label(SourceFeature.Stats) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 80.dp, end = 80.dp, top = 56.dp, bottom = 56.dp),
        verticalArrangement = Arrangement.spacedBy(36.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Text("观影统计", fontSize = 48.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp, modifier = Modifier.weight(1f))
            val status = when {
                refreshing.isNotEmpty() -> "正在更新 ${refreshing.size} 台"
                failed.isNotEmpty() -> "${failed.size} 台未响应"
                else -> null
            }
            if (status != null) Text(status, fontSize = 20.sp, color = if (failed.isNotEmpty() && refreshing.isEmpty()) Z.warn else Z.textSecondary)
            GlassButton("刷新", { vm.refresh(force = true) }, Modifier.focusRequester(rememberInitialFocus(Unit)), glyph = Glyph.Refresh, height = 64.dp)
            GlassButton("来源 · $sourceLabel", { nav.go(Route.DataSource(SourceFeature.Stats)) }, height = 64.dp)
        }
        val s = summary
        when {
            s == null -> Text("正在读取…", fontSize = 24.sp, color = Z.textSecondary)
            s.empty -> Text(
                if (refreshing.isNotEmpty()) "正在从服务器读取播放记录…" else "还没有看过的影片。在观影统计来源的服务器上看完一部影片或一集后，这里会显示统计。",
                fontSize = 24.sp, color = Z.textSecondary,
            )
            else -> StatsBody(s)
        }
    }
}

@Composable
private fun StatsBody(s: StatsSummary) {
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Tile("电影", "${s.movies}", "部")
        Tile("剧集", "${s.episodes}", "集", sub = "${s.series} 部剧")
        Tile("观看时长", hours(s.totalMs), "小时")
        Tile("服务器", "${s.servers}", "台")
    }
    Column(Modifier.fillMaxWidth().groupCard(RoundedCornerShape(28.dp)).padding(32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("按月", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        MonthBars(s.months)
        if (s.undated > 0) Text("另有 ${s.undated} 部（集）没有观看日期", fontSize = 18.sp, color = Z.textTertiary)
    }
    if (s.topSeries.isNotEmpty()) {
        Column(Modifier.fillMaxWidth().groupCard(RoundedCornerShape(28.dp)).padding(32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("看得最多的剧", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            s.topSeries.forEachIndexed { i, (name, n) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text("${i + 1}", fontSize = 22.sp, color = Z.textTertiary, modifier = Modifier.width(32.dp))
                    Text(name, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
                    Text("$n 集", fontSize = 22.sp, color = Z.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun Tile(title: String, value: String, unit: String, sub: String? = null) {
    Column(Modifier.width(400.dp).groupCard(RoundedCornerShape(28.dp)).padding(28.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontSize = 20.sp, color = Z.textSecondary)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(value, fontSize = 56.sp, fontWeight = FontWeight.ExtraBold)
            Text(unit, fontSize = 22.sp, color = Z.textSecondary, modifier = Modifier.padding(bottom = 10.dp))
        }
        Text(sub ?: " ", fontSize = 18.sp, color = Z.textTertiary)
    }
}

/** Twelve bars of watched hours; the month label under each, the count above. */
@Composable
private fun MonthBars(months: List<MonthStat>) {
    val max = months.maxOfOrNull { it.watchedMs }?.coerceAtLeast(1) ?: 1
    Row(Modifier.fillMaxWidth().height(260.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Bottom) {
        months.forEach { m ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val n = m.movies + m.episodes
                Text(if (n > 0) "$n" else "", fontSize = 16.sp, color = Z.textSecondary)
                val fraction = (m.watchedMs.toFloat() / max).coerceIn(0f, 1f)
                Box(
                    Modifier.fillMaxWidth().height((180 * fraction).coerceAtLeast(if (n > 0) 4f else 2f).dp).clip(RoundedCornerShape(8.dp))
                        .background(if (n > 0) Z.focusFill.copy(alpha = 0.85f) else Z.textTertiary.copy(alpha = 0.25f)),
                )
                Text("${m.month.monthValue} 月", fontSize = 16.sp, color = Z.textTertiary)
            }
        }
    }
    Spacer(Modifier.height(2.dp))
}

private fun hours(ms: Long): String {
    val h = ms / 3_600_000.0
    return if (h >= 100) "%.0f".format(h) else "%.1f".format(h)
}
