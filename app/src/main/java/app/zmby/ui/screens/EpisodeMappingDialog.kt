package app.zmby.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import app.zmby.catalog.*
import app.zmby.data.ServerItem
import app.zmby.store.EpisodeMappingRule
import app.zmby.ui.components.*
import app.zmby.ui.theme.Z
import app.zmby.ui.userMessage
import app.zmby.zmby
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One line of the preview: 「片源 第 4 季第 1 集 → 第 3 季第 1 集 · 分集标题」; [to] null stays 未归类. */
internal data class MappingExample(val from: String, val to: String?, val title: String?)

internal data class MappingPreview(val mapped: Int, val remaining: Int, val examples: List<MappingExample>, val protected: Int = 0)

/** 「第 4 季第 7 集」, or 「第 7 集」 without a season number. */
internal fun seasonEpisode(season: Int?, episode: Int?): String? =
    episode?.let { e -> season?.let { "第 $it 季第 $e 集" } ?: "第 $e 集" }

internal fun mappingPreview(rule: EpisodeMappingRule, resources: List<ServerItem>, catalog: List<CatalogEpisode>, identities: Map<String, EpisodeIdentity> = emptyMap()): MappingPreview {
    val chosen = resources.distinctBy { it.key }.filter { rule.applies(rule.workKey, it) }
    val protected = chosen.filter { ref -> rule.itemId == null && (
        episodeProviderId(ref.item, rule.workKey.substringBefore(':'))?.let { it != rule.workKey.substringAfterLast(':') } == true ||
            identities[ref.key]?.state in setOf(EpisodeIdentityState.Matched, EpisodeIdentityState.Conflict, EpisodeIdentityState.Pending)) }.map { it.key }.toSet()
    val targets = chosen.filter { it.key !in protected }.map { it to rule.target(catalog, it) }
    return MappingPreview(targets.count { it.second != null }, targets.count { it.second == null }, targets.take(4).map { (ref, ep) ->
        MappingExample("片源 ${seasonEpisode(ref.item.parentIndexNumber, ref.item.indexNumber) ?: "未编号"}",
            ep?.let { seasonEpisode(it.season, it.number) ?: it.title }, ep?.title?.takeIf { it.isNotBlank() })
    }, protected.size)
}

/** An existing rule in one sentence: 「第 4 季 1–8 集 → 第 3 季 1–8 集」 (one number when the range is one episode). */
internal fun ruleSentence(rule: EpisodeMappingRule): String {
    fun range(season: Int, first: Int, last: Int) = if (first == last) "第 $season 季第 $first 集" else "第 $season 季 $first–$last 集"
    return "${range(rule.sourceSeason, rule.firstEpisode, rule.lastEpisode)} → ${range(rule.targetSeason, rule.firstEpisode + rule.offset, rule.lastEpisode + rule.offset)}"
}

/** A source group's line under its title and its tag: 「未归类 n」 (yellow) or 「已对齐」. */
internal data class GroupState(val sub: String, val tag: String, val warn: Boolean)

internal fun groupState(size: Int, unsorted: List<String>): GroupState =
    if (unsorted.isEmpty()) GroupState("$size 条 · 已对齐", "已对齐", false)
    else GroupState("$size 条 · ${unsorted.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: "未归类"}", "未归类 ${unsorted.size}", true)

private data class MappingScope(val server: String, val series: String, val season: Int?)
private fun ServerItem.mappingScope() = MappingScope(server, item.seriesId.orEmpty(), item.parentIndexNumber)
private data class MappingEditor(val source: ServerItem, val rule: EpisodeMappingRule? = null)

/**
 * 季集映射 (MappingPanel.dc.html): local-only, explicit mappings — one concrete file, or an episode
 * range in one server's season. Left, the source groups and the saved rules; right, the form for the
 * group being edited with a live preview. Saving writes this TV's database only.
 */
@Composable
internal fun EpisodeMappingDialog(
    work: CatalogWork,
    catalog: List<CatalogEpisode>,
    resources: List<ServerItem>,
    identities: Map<String, EpisodeIdentity>,
    rules: List<EpisodeMappingRule>,
    selectedSeason: Int,
    initial: ServerItem?,
    /** Entries that are not under a catalogue episode, and why (未归类). */
    unsorted: List<ServerItem> = emptyList(),
    reasons: Map<String, String> = emptyMap(),
    onDismiss: () -> Unit,
) {
    val app = LocalContext.current.zmby
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val feedback = remember { ActionFeedback() }
    var editor by remember { mutableStateOf(initial?.let { MappingEditor(it) }) }
    var busy by remember { mutableStateOf(false) }
    val preparedGroups by produceState<Map<MappingScope, List<ServerItem>>?>(null, resources) {
        value = withContext(Dispatchers.Default) {
            resources.filter { !it.item.seriesId.isNullOrBlank() }.distinctBy { it.key }
                .sortedWith(compareBy<ServerItem>({ it.server }, { it.item.seriesId }, { it.item.parentIndexNumber }, { it.item.indexNumber }, { it.item.id }))
                .groupBy { it.mappingScope() }
        }
    }
    val groups = preparedGroups.orEmpty()
    val unsortedKeys = remember(unsorted) { unsorted.map { it.key }.toSet() }
    val groupStates by produceState(emptyMap<MappingScope, GroupState>(), groups, unsortedKeys, reasons) {
        value = withContext(Dispatchers.Default) {
            groups.mapValues { (_, refs) -> groupState(refs.size, refs.filter { it.key in unsortedKeys }.map { reasons[it.key] ?: "未归类" }) }
        }
    }
    // 「家里 NAS · 归入 8 条」 under each saved rule.
    val ruleCounts by produceState(emptyMap<String, Int>(), rules, resources, catalog, identities) {
        value = withContext(Dispatchers.Default) { rules.associate { it.id to mappingPreview(it, resources, catalog, identities).mapped } }
    }
    // Without a group from the 未归类 card, edit the first group that has unsorted entries.
    LaunchedEffect(groups, groupStates) {
        if (editor == null && groups.isNotEmpty() && groupStates.isNotEmpty()) {
            val first = groups.entries.firstOrNull { groupStates[it.key]?.warn == true } ?: groups.entries.first()
            editor = MappingEditor(first.value.minBy { it.item.indexNumber ?: Int.MAX_VALUE })
        }
    }
    fun serverName(id: String) = app.registry.get(id)?.name ?: "服务器"
    fun title(group: MappingScope): String {
        val sameServer = groups.keys.filter { it.server == group.server }.map { it.series }.distinct()
        val season = group.season?.let { "第 $it 季" } ?: "没有季号"
        return listOfNotNull(serverName(group.server), season, if (sameServer.size > 1) "片源组 ${sameServer.indexOf(group.series) + 1}" else null).joinToString(" · ")
    }
    fun save(rule: EpisodeMappingRule, replace: Boolean, mapped: Int) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                // The form keeps its rule id, so the next save updates this rule; focus stays on 保存.
                app.store.episodeMappings.save(rule, replace)
                feedback.success("已保存季集映射 · 归入 $mapped 条")
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                feedback.failure(failureNotice("保存季集映射", null, e.userMessage())) { save(rule, replace, mapped) }
            } finally { busy = false }
        }
    }
    fun delete(rule: EpisodeMappingRule) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                app.store.episodeMappings.delete(rule.id)
                if (editor?.rule?.id == rule.id) editor = editor?.copy(rule = null)
                feedback.success("已删除季集映射")
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                feedback.failure(failureNotice("删除季集映射", null, e.userMessage())) { delete(rule) }
            } finally { busy = false }
        }
    }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
      DialogDim(0.66f)
      CompositionLocalProvider(LocalDensity provides density, LocalBackdrop provides null) {
       ActionFeedbackHost(Modifier.fillMaxSize(), feedback, toastBottom = 24.dp) {
        Column(Modifier.align(Alignment.Center).width(1640.dp).height(900.dp).dialogPanel(RoundedCornerShape(40.dp)).padding(horizontal = 48.dp, vertical = 44.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                Text("季集映射", fontSize = 36.sp, fontWeight = FontWeight.ExtraBold)
                Text(listOf(work.title, "只影响这台电视，不修改服务器").joinToString(" · "), fontSize = 21.sp, color = Color.White.copy(alpha = 0.65f), maxLines = 1)
            }
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                val firstRow = remember { FocusRequester() }
                LazyColumn(Modifier.width(520.dp).fillMaxHeight().focusGroup(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    item(key = "groups-title", contentType = "title") { PanelHeading("片源分组") }
                    if (preparedGroups == null) item(key = "loading", contentType = "note") { Text("正在读取片源…", fontSize = 19.sp, color = Z.textSecondary) }
                    else if (groups.isEmpty()) item(key = "empty", contentType = "note") { Text("还没有可映射的服务器片源", fontSize = 19.sp, color = Z.textSecondary) }
                    val entries = groups.entries.toList()
                    items(entries, key = { "source:${it.key.server}:${it.key.series}:${it.key.season}" }, contentType = { "source" }) { (group, refs) ->
                        val state = groupStates[group]
                        val chosen = editor?.source?.mappingScope() == group
                        SelectableRow({ if (!busy) editor = MappingEditor(refs.minBy { it.item.indexNumber ?: Int.MAX_VALUE }) },
                            if (group == entries.firstOrNull { editor?.source?.mappingScope() == it.key }?.key) Modifier.focusRequester(firstRow) else Modifier,
                            selected = chosen, showCheck = false, selectedRing = true, padding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                            label = "mapping-source:${group.server}:${group.series}:${group.season}") {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(title(group), fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(state?.sub ?: "${refs.size} 条", fontSize = 16.sp, color = secondaryText(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            state?.let { GroupTag(it) }
                        }
                    }
                    if (rules.isNotEmpty()) item(key = "rules-title", contentType = "title") { PanelHeading("已有规则", Modifier.padding(top = 22.dp)) }
                    items(rules, key = { "rule:${it.id}" }, contentType = { "rule" }) { rule ->
                        val source = resources.firstOrNull { rule.applies(work.ref.key, it) }
                        Row(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(18.dp)).padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(ruleSentence(rule), fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOfNotNull(serverName(rule.serverId), if (rule.itemId != null) "单集" else null, ruleCounts[rule.id]?.let { "归入 $it 条" }).joinToString(" · "),
                                    fontSize = 16.sp, color = Color.White.copy(alpha = 0.65f), maxLines = 1)
                            }
                            MiniButton("修改", "mapping-edit:${rule.id}", enabled = source != null) {
                                if (!busy) {
                                    if (source != null) editor = MappingEditor(source, rule)
                                    else feedback.failure("当前没有这组片源，仍可删除映射")
                                }
                            }
                            MiniButton("删除", "mapping-delete:${rule.id}") { delete(rule) }
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    val current = editor
                    if (current != null) key(current.source.key, current.rule?.id) {
                        MappingForm(work, catalog, groups[current.source.mappingScope()].orEmpty(), identities, rules, current.source, current.rule, selectedSeason,
                            busy, onCancel = { if (!busy) onDismiss() }) { rule, replace, mapped -> save(rule, replace, mapped) }
                    }
                }
                LaunchedEffect(editor != null) {
                    if (editor == null) return@LaunchedEffect
                    awaitFrame()
                    runCatching { firstRow.requestFocus() }
                }
            }
        }
       }
      }
    }
}

@Composable
private fun PanelHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = Color.White.copy(alpha = 0.6f), modifier = modifier.padding(bottom = 2.dp))
}

@Composable
private fun GroupTag(state: GroupState) {
    val shape = RoundedCornerShape(percent = 50)
    Text(state.tag, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        color = if (state.warn) Z.warn else if (LocalInverted.current) Z.onFocus else Color.White,
        modifier = Modifier.background(if (state.warn) Z.warn.copy(alpha = 0.16f) else if (LocalInverted.current) Z.onFocus.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.12f), shape)
            .padding(horizontal = 12.dp, vertical = 5.dp))
}

/** `.mini-btn`: 40 high glass pill, 16/600 (修改 / 删除 inside a rule row). */
@Composable
private fun MiniButton(text: String, key: String, enabled: Boolean = true, onClick: () -> Unit) {
    GlassSurface(onClick, Modifier.alpha(if (enabled) 1f else 0.4f), label = key, focusKey = key) {
        Box(Modifier.height(40.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun ColumnScope.MappingForm(work: CatalogWork, catalog: List<CatalogEpisode>, resources: List<ServerItem>, identities: Map<String, EpisodeIdentity>,
    rules: List<EpisodeMappingRule>, initialSource: ServerItem, editing: EpisodeMappingRule?, selectedSeason: Int, busy: Boolean,
    onCancel: () -> Unit, onSave: (EpisodeMappingRule, replace: Boolean, mapped: Int) -> Unit) {
    val ordered = remember(resources) { resources.sortedWith(compareBy({ it.item.indexNumber ?: Int.MAX_VALUE }, { it.item.id })) }
    var sourceKey by remember { mutableStateOf(editing?.itemId?.let { id -> ordered.firstOrNull { it.item.id == id }?.key } ?: initialSource.key) }
    val source = ordered.firstOrNull { it.key == sourceKey } ?: initialSource
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
    val overlapping = remember(rule, rules) { rules.any(rule::overlaps) }
    Column(Modifier.weight(1f).focusGroup(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SegmentedWell(listOf(Segment("这一集"), Segment("整季或指定范围")), kind, { kind = it }, selectOnFocus = false, equalWidths = true,
            labelPrefix = "映射范围", modifier = Modifier.fillMaxWidth())
        if (kind == 0) {
            val index = ordered.indexOfFirst { it.key == source.key }.coerceAtLeast(0)
            MappingField("来源单集", source.item.name?.takeIf { it.isNotBlank() }) {
                Stepper(source.item.indexNumber?.toString() ?: "?", "来源单集", canDown = index > 0, canUp = index < ordered.lastIndex,
                    onDown = { sourceKey = ordered[index - 1].key; selectedId = null }, onUp = { sourceKey = ordered[index + 1].key; selectedId = null })
            }
        } else {
            MappingField("来源集号") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Stepper(first.toString(), "来源起始集", canDown = first > 0, canUp = true,
                        onDown = { first-- }, onUp = { first++; if (last < first) last = first })
                    Text("至", fontSize = 22.sp, color = Color.White.copy(alpha = 0.6f))
                    Stepper(last.toString(), "来源结束集", canDown = last > first, canUp = true, onDown = { last-- }, onUp = { last++ })
                }
            }
        }
        if (seasons.isNotEmpty()) MappingField("目标季") {
            val i = seasons.indexOf(targetSeason).coerceAtLeast(0)
            Stepper(targetSeason.toString(), "目标季", canDown = i > 0, canUp = i < seasons.lastIndex,
                onDown = { targetSeason = seasons[i - 1]; selectedId = null }, onUp = { targetSeason = seasons[i + 1]; selectedId = null })
        }
        if (kind == 0) {
            if (episodes.isNotEmpty() && selected != null) MappingField("目标集", selected.title.takeIf { it.isNotBlank() }) {
                val i = episodes.indexOf(selected)
                Stepper(selected.number?.toString() ?: "?", "目标集", canDown = i > 0, canUp = i < episodes.lastIndex,
                    onDown = { selectedId = episodes[i - 1].id }, onUp = { selectedId = episodes[i + 1].id })
            } else Text("目标季目录尚未取得，暂时不能保存", fontSize = 19.sp, color = Z.warn)
        } else {
            MappingField("集号偏移") {
                Stepper(offset.toString(), "集号偏移", canDown = offset > -9999, canUp = offset < 9999, onDown = { offset-- }, onUp = { offset++ })
            }
        }
        // The preview card: 「归入 n 条　保留未归类 m 条」 and up to four examples.
        Column(Modifier.padding(top = 6.dp).fillMaxWidth().background(Color.Black.copy(alpha = 0.25f), RoundedCornerShape(24.dp))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(24.dp)).padding(horizontal = 24.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (preview == null) Text("正在计算预览…", fontSize = 19.sp, color = Z.textSecondary)
            preview?.let { p ->
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    Text("归入 ${p.mapped} 条", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("保留未归类 ${p.remaining} 条", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.65f))
                }
                p.examples.forEach { e ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(e.from, fontSize = 18.sp, color = Color.White.copy(alpha = 0.7f), maxLines = 1, modifier = Modifier.width(200.dp))
                        Text("→", fontSize = 18.sp, color = Color.White.copy(alpha = 0.45f), modifier = Modifier.width(36.dp))
                        Text(e.to ?: "不在目录，保留未归类", fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.width(200.dp))
                        e.title?.let { Text(it, fontSize = 18.sp, color = Color.White.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
                if (p.protected > 0) Text("${p.protected} 条保留单集身份判定，不受本规则影响", fontSize = 16.sp, color = Color.White.copy(alpha = 0.65f))
                if (overlapping) Text("与已有规则重叠，保存后替换原规则", fontSize = 16.sp, color = Z.warn)
            }
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val canSave = !busy && preview?.mapped?.let { it > 0 } == true
            GlassButton(if (busy) "正在保存…" else "保存", { if (canSave) onSave(rule, overlapping, preview!!.mapped) },
                Modifier.alpha(if (canSave || busy) 1f else 0.4f), height = 64.dp, horizontalPadding = 34.dp, fontSize = 22, label = "mapping-save")
            GlassButton("取消", onCancel, height = 64.dp, horizontalPadding = 34.dp, fontSize = 22, label = "mapping-cancel")
        }
    }
}

/** `.field`: 72 high, the name on the left (22/600, with an optional detail line), the control on the right. */
@Composable
private fun MappingField(label: String, detail: String? = null, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            detail?.let { Text(it, fontSize = 16.sp, color = Color.White.copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        control()
    }
}

/**
 * `.step`: a sunken track with − value +; the focused − or + is a white circle. Holding OK repeats
 * the step, so a long range or a large offset does not take one press per number.
 */
@Composable
private fun Stepper(value: String, name: String, canDown: Boolean, canUp: Boolean, onDown: () -> Unit, onUp: () -> Unit) {
    Row(Modifier.well(RoundedCornerShape(percent = 50)).padding(5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        StepButton("−", "$name 减少", canDown, onDown)
        Text(value, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.widthIn(min = 72.dp))
        StepButton("+", "$name 增加", canUp, onUp)
    }
}

@Composable
private fun StepButton(text: String, key: String, enabled: Boolean, onClick: () -> Unit) {
    val step by rememberUpdatedState { if (enabled) onClick() }
    Focusable({ step() }, Modifier.onPreviewKeyEvent { e ->
        val ok = e.key == Key.DirectionCenter || e.key == Key.Enter
        // Auto-repeat while OK is held; the first press goes through the click as usual.
        if (ok && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount > 0) { step(); true } else false
    }, shape = CircleShape, focusedScale = 1.08f, outline = 0.dp, shadow = 0.dp, label = key, focusKey = key) { focused ->
        Box(Modifier.size(52.dp).then(if (focused) Modifier.liftShadow(CircleShape, 10.dp).onFill(CircleShape) else Modifier), contentAlignment = Alignment.Center) {
            Text(text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold,
                color = if (focused) Z.onFocus else if (enabled) Z.textPrimary else Z.textDisabled)
        }
    }
}
