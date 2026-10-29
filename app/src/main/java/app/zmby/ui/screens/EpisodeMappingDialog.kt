package app.zmby.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import app.zmby.catalog.*
import app.zmby.data.ServerItem
import app.zmby.store.EpisodeMappingRule
import app.zmby.ui.components.*
import app.zmby.ui.rememberInitialFocus
import app.zmby.ui.theme.Z
import app.zmby.zmby
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class MappingPreview(val mapped: Int, val remaining: Int, val examples: List<String>, val protected: Int = 0)

internal fun mappingPreview(rule: EpisodeMappingRule, resources: List<ServerItem>, catalog: List<CatalogEpisode>, identities: Map<String, EpisodeIdentity> = emptyMap()): MappingPreview {
    val chosen = resources.distinctBy { it.key }.filter { rule.applies(rule.workKey, it) }
    val protected = chosen.filter { ref -> rule.itemId == null && (
        episodeProviderId(ref.item, rule.workKey.substringBefore(':'))?.let { it != rule.workKey.substringAfterLast(':') } == true ||
            identities[ref.key]?.state in setOf(EpisodeIdentityState.Matched, EpisodeIdentityState.Conflict, EpisodeIdentityState.Pending)) }.map { it.key }.toSet()
    val targets = chosen.filter { it.key !in protected }.map { it to rule.target(catalog, it) }
    return MappingPreview(targets.count { it.second != null }, targets.count { it.second == null }, targets.take(5).map { (ref, ep) ->
        val original = unsortedCode(ref.item) ?: "片源未编号"
        "$original → ${ep?.let { "第 ${it.season} 季 第 ${it.number} 集" } ?: "不在目录，保留未归类"}"
    }, protected.size)
}

private data class MappingScope(val server: String, val series: String, val season: Int?)
private fun ServerItem.mappingScope() = MappingScope(server, item.seriesId.orEmpty(), item.parentIndexNumber)
private data class MappingEditor(val source: ServerItem, val rule: EpisodeMappingRule? = null)

/** Local-only, explicit mappings: one concrete file, or an episode range in one server's season. */
@Composable
internal fun EpisodeMappingDialog(
    work: CatalogWork,
    catalog: List<CatalogEpisode>,
    resources: List<ServerItem>,
    identities: Map<String, EpisodeIdentity>,
    rules: List<EpisodeMappingRule>,
    selectedSeason: Int,
    initial: ServerItem?,
    onDismiss: () -> Unit,
) {
    val app = LocalContext.current.zmby
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var editor by remember { mutableStateOf(initial?.let { MappingEditor(it) }) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val preparedGroups by produceState<Map<MappingScope, List<ServerItem>>?>(null, resources) {
        value = withContext(Dispatchers.Default) {
            resources.filter { !it.item.seriesId.isNullOrBlank() }.distinctBy { it.key }
                .sortedWith(compareBy<ServerItem>({ it.server }, { it.item.seriesId }, { it.item.parentIndexNumber }, { it.item.indexNumber }, { it.item.id }))
                .groupBy { it.mappingScope() }
        }
    }
    val groups = preparedGroups.orEmpty()
    fun label(source: ServerItem): String {
        val group = source.mappingScope()
        val index = groups.keys.filter { it.server == group.server }.map { it.series }.distinct().indexOf(group.series) + 1
        val season = group.season?.let { "第 $it 季" } ?: "季号缺失"
        return "${app.registry.get(source.server)?.name ?: "服务器"} · $season · 片源组 $index · ${groups[group].orEmpty().size} 条"
    }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
      CompositionLocalProvider(LocalDensity provides density, LocalBackdrop provides null) {
        Column(Modifier.width(1320.dp).heightIn(max = 940.dp).panelSurface(RoundedCornerShape(30.dp)).padding(30.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("季集映射 · ${work.title}", fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                val close = rememberInitialFocus("mapping-close")
                GlassButton("关闭", { if (!busy) onDismiss() }, Modifier.focusRequester(close))
            }
            Text("只在本机生效，保留服务器原编号；删除映射即可恢复自动匹配。", fontSize = 19.sp, color = Z.textSecondary)
            error?.let { Text(it, fontSize = 19.sp, color = Z.warn) }
            val current = editor
            if (preparedGroups == null) {
                Text("正在读取片源…", fontSize = 20.sp)
            } else if (current != null) {
                key(current.source.key, current.rule?.id) {
                    MappingForm(work, catalog, groups[current.source.mappingScope()].orEmpty(), identities, current.source, current.rule, selectedSeason,
                        label(current.source), busy, onBack = { editor = null; error = null }) { rule ->
                        if (!busy) {
                            busy = true; error = null
                            scope.launch {
                                try { app.store.episodeMappings.save(rule); editor = null }
                                catch (e: Exception) { error = e.message ?: "映射保存失败" }
                                finally { busy = false }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(4.dp)) {
                    if (rules.isNotEmpty()) item(key = "saved-title") { Text("已有映射", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                    items(rules, key = { "rule:${it.id}" }, contentType = { "rule" }) { rule ->
                        val source = resources.firstOrNull { rule.applies(work.ref.key, it) }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("${app.registry.get(rule.serverId)?.name ?: "服务器"} · ${if (rule.itemId != null) "单集映射" else "片源第 ${rule.sourceSeason} 季"}", fontSize = 20.sp)
                                val to = if (rule.itemId != null) "这一集 → 第 ${rule.targetSeason} 季 第 ${rule.firstEpisode + rule.offset} 集"
                                    else "第 ${rule.firstEpisode}–${rule.lastEpisode} 集 → 第 ${rule.targetSeason} 季 · 集号偏移 ${rule.offset}"
                                Text(to, fontSize = 18.sp, color = Z.textSecondary)
                            }
                            GlassButton("修改", { if (!busy) { if (source != null) { editor = MappingEditor(source, rule); error = null } else error = "当前没有这组片源，仍可删除映射" } }, label = "mapping-edit:${rule.id}")
                            GlassButton("删除", { if (!busy) {
                                busy = true; error = null
                                scope.launch {
                                    try { app.store.episodeMappings.delete(rule.id) }
                                    catch (_: Exception) { error = "映射删除失败" }
                                    finally { busy = false }
                                }
                            } }, label = "mapping-delete:${rule.id}")
                        }
                    }
                    item(key = "new-title") { Text("选择要纠正的片源", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                    items(groups.entries.toList(), key = { "source:${it.key.server}:${it.key.series}:${it.key.season}" }, contentType = { "source" }) { (_, refs) ->
                        val source = refs.minByOrNull { it.item.indexNumber ?: Int.MAX_VALUE }!!
                        GlassButton(label(source), { if (!busy) { editor = MappingEditor(source); error = null } }, Modifier.fillMaxWidth())
                    }
                    if (groups.isEmpty()) item(key = "empty") { Text("还没有可映射的服务器片源", fontSize = 20.sp) }
                }
            }
        }
      }
    }
}

@Composable
private fun ColumnScope.MappingForm(work: CatalogWork, catalog: List<CatalogEpisode>, resources: List<ServerItem>, identities: Map<String, EpisodeIdentity>, initialSource: ServerItem,
    editing: EpisodeMappingRule?, selectedSeason: Int, sourceLabel: String, busy: Boolean, onBack: () -> Unit, onSave: (EpisodeMappingRule) -> Unit) {
    var sourceKey by remember { mutableStateOf(initialSource.key) }
    val source = resources.firstOrNull { it.key == sourceKey } ?: initialSource
    val seasons = work.seasons.map { it.number }.distinct().sorted()
    val initialSeason = editing?.targetSeason ?: seasons.singleOrNull { it > 0 } ?: selectedSeason.takeIf { it in seasons } ?: seasons.firstOrNull() ?: 1
    var kind by remember { mutableIntStateOf(if (editing?.itemId != null) 0 else 1) }
    var targetSeason by remember { mutableIntStateOf(initialSeason) }
    val sourceNumbers = remember(resources) { resources.mapNotNull { it.item.indexNumber }.filter { it >= 0 } }
    val minimum = sourceNumbers.minOrNull() ?: 1
    val maximum = sourceNumbers.maxOrNull() ?: minimum
    var first by remember { mutableIntStateOf(editing?.firstEpisode ?: minimum) }
    var last by remember { mutableIntStateOf(editing?.lastEpisode ?: maximum) }
    var offset by remember { mutableIntStateOf(editing?.offset ?: 0) }
    val episodes = remember(catalog, targetSeason) { catalog.filter { it.season == targetSeason }.sortedBy { it.number } }
    var selectedId by remember { mutableStateOf(editing?.targetEpisodeId) }
    val selected = episodes.firstOrNull { it.id == selectedId } ?: episodes.firstOrNull { it.number == source.item.indexNumber } ?: episodes.firstOrNull()
    val ruleId = remember { editing?.id ?: java.util.UUID.randomUUID().toString() }
    val rule = remember(work.ref.key, source, ruleId, kind, targetSeason, first, last, offset, selected) {
        EpisodeMappingRule(id = ruleId, workKey = work.ref.key, serverId = source.server,
            seriesId = source.item.seriesId.orEmpty(), sourceSeason = source.item.parentIndexNumber ?: 0,
            firstEpisode = if (kind == 0) source.item.indexNumber ?: 0 else first,
            lastEpisode = if (kind == 0) source.item.indexNumber ?: 0 else last, targetSeason = targetSeason,
            offset = if (kind == 0) (selected?.number ?: 0) - (source.item.indexNumber ?: 0) else offset,
            itemId = source.item.id.takeIf { kind == 0 }, targetEpisodeId = selected?.id.takeIf { kind == 0 })
    }
    val prepared by produceState<Pair<EpisodeMappingRule, MappingPreview>?>(null, rule, resources, catalog, source, identities) {
        value = rule to withContext(Dispatchers.Default) { mappingPreview(rule, if (kind == 0) listOf(source) else resources, catalog, identities) }
    }
    val preview = prepared?.takeIf { it.first == rule }?.second
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(sourceLabel, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        ChoiceSetting("映射范围", listOf("这一集", "季或指定范围"), kind, { kind = it })
        if (kind == 0 && resources.isNotEmpty()) ChoiceSetting("片源单集", resources.mapIndexed { index, ref ->
            "${index + 1}. ${unsortedCode(ref.item) ?: "未编号"} · ${ref.item.name}"
        }, resources.indexOfFirst { it.key == source.key }.coerceAtLeast(0), { sourceKey = resources[it].key; selectedId = null })
        if (seasons.isNotEmpty()) ChoiceSetting("目标季", seasons.map { "第 $it 季" }, seasons.indexOf(targetSeason).coerceAtLeast(0), { targetSeason = seasons[it]; selectedId = null })
        if (kind == 0) {
            if (episodes.isNotEmpty()) ChoiceSetting("目标单集", episodes.map { "第 ${it.number} 集 · ${it.title}" }, episodes.indexOf(selected).coerceAtLeast(0), { selectedId = episodes[it].id })
            else Text("目标季目录尚未取得，暂时不能保存", fontSize = 20.sp, color = Z.warn)
        } else {
            MappingNumber("片源起始集", first, 0..maxOf(maximum, last), { first = it; if (last < it) last = it })
            MappingNumber("片源结束集", last, first..maxOf(maximum, last), { last = it })
            MappingNumber("集号偏移", offset, -9999..9999, { offset = it })
            Text("目标集号 = 片源集号 + 偏移。分部拆季时可分别保存不重叠的编号范围。", fontSize = 18.sp, color = Z.textSecondary)
        }
        preview?.let { p ->
            Text("预览：${p.mapped} 条可归入 · ${p.remaining} 条保留未归类", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (p.protected > 0) Text("${p.protected} 条保留单集身份判定，不受本规则影响", fontSize = 18.sp, color = Z.textSecondary)
            p.examples.forEach { Text(it, fontSize = 19.sp, color = Z.textSecondary) }
        }
        Text(if (kind == 0) "保存后，这个文件使用你指定的单集。" else "明确的单集 ID 仍优先；本规则只纠正其余资源的编号。", fontSize = 18.sp, color = Z.textSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            GlassButton(if (busy) "正在保存…" else "保存映射", { if (!busy && preview?.mapped?.let { it > 0 } == true) onSave(rule) })
            GlassButton("返回列表", { if (!busy) onBack() })
        }
    }
}

@Composable
private fun MappingNumber(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, fontSize = 20.sp, modifier = Modifier.weight(1f).padding(top = 12.dp))
        for (delta in listOf(-10, -1)) GlassButton(delta.toString(), { onChange((value.toLong() + delta).coerceIn(range.first.toLong(), range.last.toLong()).toInt()) }, label = "$label $delta")
        Text(value.toString(), fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(90.dp).padding(top = 12.dp))
        for (delta in listOf(1, 10)) GlassButton("+$delta", { onChange((value.toLong() + delta).coerceIn(range.first.toLong(), range.last.toLong()).toInt()) }, label = "$label +$delta")
    }
}
