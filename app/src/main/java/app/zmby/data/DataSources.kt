package app.zmby.data

import app.zmby.log.ZLog
import app.zmby.store.DataSourceRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 设置 › 数据来源 (local-data spec): each feature picks its own servers. */
enum class SourceFeature(val key: String, val label: String) {
    Aggregate("aggregate", "资源聚合（详情）"),
    Search("search", "搜索来源"),
    WatchHistory("watch", "观看记录来源"),
    Metadata("metadata", "影片信息来源"),
    Segments("segments", "片段标记来源"),
    Favorites("favorites", "收藏数据来源"),
    Stats("stats", "观影统计"),
    ;

    /** Segment markers come from providers in order, the rest from servers. */
    val ordered: Boolean get() = this == Segments
}

/**
 * Where intro, recap and credits markers come from (P4 uses them; P3 only stores the choice). Each
 * kind of segment is taken from the first provider in the list that has it.
 */
enum class SegmentProvider(val key: String, val label: String, val defaultOn: Boolean, val needsKey: Boolean = false) {
    Local("local", "本地手动", true),
    Emby("emby", "Emby", true),
    TheIntroDB("theintrodb", "TheIntroDB", true),
    IntroDB("introdb", "IntroDB", true),
    PublicMetaDB("publicmetadb", "PublicMetaDB", false, needsKey = true),
    // TheOtherDB: listed by Forward, no public API found; not offered until confirmed.
}

data class ProviderState(val provider: SegmentProvider, val enabled: Boolean, val order: Int)

/** The rules without I/O, for tests. */
object SourceRules {
    /**
     * The value a settings row shows: 「全部」 when every candidate is chosen, 「关闭」 when none,
     * else 「n 台」 (servers) or 「n 个」 (providers); never a slash (ui-spec §5).
     */
    fun label(selected: Int, total: Int, providers: Boolean = false): String = when {
        selected <= 0 -> "关闭"
        selected >= total -> "全部"
        else -> "$selected ${if (providers) "个" else "台"}"
    }

    /** A server is in a feature unless switched off there (missing row = on). */
    fun enabled(rows: Map<String, DataSourceRow>, feature: SourceFeature, server: String): Boolean =
        rows[rowKey(feature.key, server)]?.enabled ?: true

    /** Fan-out scope: enabled servers in priority order that are in the feature's source. Cooling ones FanOut skips. */
    fun scope(rows: Map<String, DataSourceRow>, feature: SourceFeature, servers: List<ServerStatus>): List<String> =
        servers.filter { it.enabled && enabled(rows, feature, it.id) }.map { it.id }

    /**
     * First start with data sources: the old 允许聚合 switch carries over to resource aggregation,
     * search and watch history (it decided all three before), so nothing the user turned off turns on.
     */
    fun migrate(servers: List<ServerConfig>): List<DataSourceRow> = servers.filter { !it.aggregate }.flatMap { s ->
        listOf(SourceFeature.Aggregate, SourceFeature.Search, SourceFeature.WatchHistory).map { DataSourceRow(it.key, s.id, enabled = false) }
    }

    /** Providers in their order, defaults for the ones never stored; PublicMetaDB stays off without a key. */
    fun providers(rows: Map<String, DataSourceRow>, hasKey: (SegmentProvider) -> Boolean): List<ProviderState> =
        SegmentProvider.entries.map { p ->
            val row = rows[rowKey(SourceFeature.Segments.key, p.key)]
            ProviderState(p, (row?.enabled ?: p.defaultOn) && (!p.needsKey || hasKey(p)), row?.ord ?: (p.ordinal + 1000))
        }.sortedBy { it.order }

    /** Moves [p] one place up (−1) or down (+1); returns the providers renumbered. */
    fun move(list: List<ProviderState>, p: SegmentProvider, delta: Int): List<ProviderState> {
        val i = list.indexOfFirst { it.provider == p }
        val j = i + delta
        if (i < 0 || j !in list.indices) return list.mapIndexed { k, s -> s.copy(order = k) }
        return list.toMutableList().apply { add(j, removeAt(i)) }.mapIndexed { k, s -> s.copy(order = k) }
    }

    fun rowKey(feature: String, source: String) = "$feature|$source"
}

/**
 * The data source choices, kept in the local store's `data_source` table and mirrored in memory so
 * fan-out callers can read them without waiting. Switching a source off keeps its cached data; the
 * server is only no longer asked for that feature.
 */
class DataSources(
    private val registry: ServerRegistry,
    private val persist: suspend (List<DataSourceRow>) -> Unit,
    private val providerKey: (SegmentProvider) -> String?,
) {
    private val _rows = MutableStateFlow<Map<String, DataSourceRow>>(emptyMap())
    /** Changes whenever a choice does (screens recompose on it). */
    val rows: StateFlow<Map<String, DataSourceRow>> = _rows.asStateFlow()

    /** At startup (background): the stored choices, or the old switches carried over the first time. */
    suspend fun load(stored: List<DataSourceRow>, migrated: Boolean, markMigrated: suspend () -> Unit) {
        var all = stored
        if (!migrated) {
            val carried = SourceRules.migrate(registry.all.map { it.config })
            if (carried.isNotEmpty()) {
                persist(carried)
                ZLog.i(TAG, "carried 允许聚合 over for ${carried.size / 3} server(s)")
            }
            all = stored + carried
            markMigrated()
        }
        _rows.value = all.associateBy { SourceRules.rowKey(it.feature, it.source) }
    }

    suspend fun restore(rows: List<DataSourceRow>) {
        _rows.value = _rows.value + rows.associateBy { SourceRules.rowKey(it.feature,it.source) }
        persist(rows)
    }

    fun enabled(feature: SourceFeature, server: String) = SourceRules.enabled(_rows.value, feature, server)

    /** The servers [feature] asks, in priority order: in its source, and switched on in the registry. */
    fun servers(feature: SourceFeature): List<String> = SourceRules.scope(_rows.value, feature, registry.all)

    suspend fun set(feature: SourceFeature, server: String, on: Boolean) {
        val row = DataSourceRow(feature.key, server, on)
        _rows.value = _rows.value + (SourceRules.rowKey(feature.key, server) to row)
        persist(listOf(row))
        ZLog.i(TAG, "${feature.label}: ${registry.get(server)?.name ?: server} ${if (on) "on" else "off"}")
    }

    fun providers(): List<ProviderState> = SourceRules.providers(_rows.value) { !providerKey(it).isNullOrBlank() }

    suspend fun setProvider(p: SegmentProvider, on: Boolean) = save(providers().map { if (it.provider == p) it.copy(enabled = on) else it })

    suspend fun moveProvider(p: SegmentProvider, delta: Int) = save(SourceRules.move(providers(), p, delta))

    private suspend fun save(list: List<ProviderState>) {
        val rows = list.mapIndexed { i, s -> DataSourceRow(SourceFeature.Segments.key, s.provider.key, s.enabled, i) }
        _rows.value = _rows.value + rows.associateBy { SourceRules.rowKey(it.feature, it.source) }
        persist(rows)
    }

    /** A settings row's value. */
    fun label(feature: SourceFeature): String = if (feature.ordered) {
        val p = providers()
        SourceRules.label(p.count { it.enabled }, p.size, providers = true)
    } else {
        val candidates = registry.all.filter { it.enabled }
        SourceRules.label(candidates.count { enabled(feature, it.id) }, candidates.size)
    }

    private companion object { const val TAG = "Sources" }
}
