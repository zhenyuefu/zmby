package app.zmby.data

import app.zmby.log.ZLog
import app.zmby.store.DataSourceRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 设置 › 数据来源 (local-data spec): each feature picks its own servers. */
enum class SourceFeature(val key: String, val label: String, val defaultOn: Boolean = true) {
    Aggregate("aggregate", "资源聚合（详情）"),
    Search("search", "搜索来源"),
    WatchHistory("watch", "观看记录来源"),
    /** 首页与影视: the servers home (全部), its source list and the 电影 / 剧集 pages show; a hidden one is still searched and played. */
    Browse("browse", "首页与影视来源"),
    Metadata("metadata", "影片信息来源"),
    Segments("segments", "片段标记来源"),
    Favorites("favorites", "收藏数据来源"),
    Stats("stats", "观影统计"),
    /** 观看进度回写 (cross-server-watch-sync): writes go to other servers, so nothing is written until switched on. */
    WatchSync("watchsync", "观看进度回写", defaultOn = false),
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

    /** A server is in a feature unless switched off there (missing row = the feature's default, on except 观看进度回写). */
    fun enabled(rows: Map<String, DataSourceRow>, feature: SourceFeature, server: String): Boolean =
        rows[rowKey(feature.key, server)]?.enabled ?: feature.defaultOn

    /** Fan-out scope: enabled servers in the feature's order that are in the feature's source. Cooling ones FanOut skips. */
    fun scope(rows: Map<String, DataSourceRow>, feature: SourceFeature, servers: List<ServerStatus>): List<String> =
        ordered(rows, feature, servers).filter { it.enabled && enabled(rows, feature, it.id) }.map { it.id }

    /** The marker row of a feature whose servers were put in an order of their own (`source` = this). */
    const val ORDER_MARK = "@order"

    /** [feature] has its own server order (sorted by hand), no longer the server page's. */
    fun custom(rows: Map<String, DataSourceRow>, feature: SourceFeature): Boolean =
        rows[rowKey(feature.key, ORDER_MARK)]?.enabled == true

    /**
     * [servers] (the server page's order) in [feature]'s order (统一数据层 · 每项来源的优先级): the
     * server page's until sorted by hand; then the stored `ord` (from 1), servers added since at the end.
     */
    fun ordered(rows: Map<String, DataSourceRow>, feature: SourceFeature, servers: List<ServerStatus>): List<ServerStatus> {
        if (feature.ordered || !custom(rows, feature)) return servers
        val placed = servers.filter { (rows[rowKey(feature.key, it.id)]?.ord ?: 0) > 0 }
            .sortedBy { rows.getValue(rowKey(feature.key, it.id)).ord }
        return placed + servers.filter { it !in placed }
    }

    /** Position by server id in [feature]'s order (smaller first), for merging and ranking. */
    fun order(rows: Map<String, DataSourceRow>, feature: SourceFeature, servers: List<ServerStatus>): Map<String, Int> =
        ordered(rows, feature, servers).withIndex().associate { (i, s) -> s.id to i }

    /** [feature]'s rows after moving [server] one place up (−1) or down (+1) among [servers]: every server numbered, plus the marker. */
    fun moveServer(rows: Map<String, DataSourceRow>, feature: SourceFeature, servers: List<ServerStatus>, server: String, delta: Int): List<DataSourceRow> {
        val list = ordered(rows, feature, servers).toMutableList()
        val i = list.indexOfFirst { it.id == server }
        val j = i + delta
        if (i >= 0 && j in list.indices) list.add(j, list.removeAt(i))
        return list.mapIndexed { k, s -> DataSourceRow(feature.key, s.id, enabled(rows, feature, s.id), k + 1) } +
            DataSourceRow(feature.key, ORDER_MARK, true)
    }

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

    // ---- 影片信息来源: TMDB, Bangumi and the servers in one order (统一数据层 ④).

    /** The catalogue members of 影片信息来源, before the servers by default. */
    val METADATA_CATALOGS = listOf("tmdb" to "TMDB", "bangumi" to "Bangumi")

    data class MetaSource(val key: String, val label: String, val enabled: Boolean, val catalog: Boolean, val server: ServerStatus? = null)

    /**
     * 影片信息来源 in order: TMDB, Bangumi, then the servers in the server page's order until sorted by
     * hand; then the stored `ord`, members added since at the end.
     */
    fun metadata(rows: Map<String, DataSourceRow>, servers: List<ServerStatus>): List<MetaSource> {
        val f = SourceFeature.Metadata
        val all = METADATA_CATALOGS.map { (k, label) -> MetaSource(k, label, enabled(rows, f, k), catalog = true) } +
            servers.map { MetaSource(it.id, it.name, enabled(rows, f, it.id), catalog = false, server = it) }
        if (!custom(rows, f)) return all
        val placed = all.filter { (rows[rowKey(f.key, it.key)]?.ord ?: 0) > 0 }.sortedBy { rows.getValue(rowKey(f.key, it.key)).ord }
        return placed + all.filter { it !in placed }
    }

    /** 影片信息来源's rows after moving [key] one place up (−1) or down (+1). */
    fun moveMetadata(rows: Map<String, DataSourceRow>, servers: List<ServerStatus>, key: String, delta: Int): List<DataSourceRow> {
        val list = metadata(rows, servers).toMutableList()
        val i = list.indexOfFirst { it.key == key }
        val j = i + delta
        if (i >= 0 && j in list.indices) list.add(j, list.removeAt(i))
        return list.mapIndexed { k, m -> DataSourceRow(SourceFeature.Metadata.key, m.key, m.enabled, k + 1) } +
            DataSourceRow(SourceFeature.Metadata.key, ORDER_MARK, true)
    }
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

    /** The servers [feature] asks, in its order: in its source, and switched on in the registry. */
    fun servers(feature: SourceFeature): List<String> = SourceRules.scope(_rows.value, feature, registry.all)

    /** Position by server id in [feature]'s order (every server, also those not in its source). */
    fun order(feature: SourceFeature): Map<String, Int> = SourceRules.order(_rows.value, feature, registry.all)

    fun custom(feature: SourceFeature): Boolean = SourceRules.custom(_rows.value, feature)

    /** [servers] with their priority replaced by [feature]'s order (版本排序、推荐合并按资源聚合的顺序). */
    fun inOrder(feature: SourceFeature, servers: List<ServerStatus>): List<ServerStatus> {
        val order = SourceRules.order(_rows.value, feature, servers)
        return servers.map { s -> order[s.id]?.let { if (it == s.order) s else s.copy(order = it) } ?: s }
    }

    /** Moves [server] one place up (−1) or down (+1) in [feature]'s own order (detaching it from the server page's). */
    suspend fun moveServer(feature: SourceFeature, server: String, delta: Int) {
        val rows = SourceRules.moveServer(_rows.value, feature, registry.all, server, delta)
        _rows.value = _rows.value + rows.associateBy { SourceRules.rowKey(it.feature, it.source) }
        persist(rows)
    }

    /** 影片信息来源 in its order (TMDB, Bangumi and the servers). */
    fun metadata(): List<SourceRules.MetaSource> = SourceRules.metadata(_rows.value, registry.all)

    suspend fun moveMetadata(key: String, delta: Int) {
        val rows = SourceRules.moveMetadata(_rows.value, registry.all, key, delta)
        _rows.value = _rows.value + rows.associateBy { SourceRules.rowKey(it.feature, it.source) }
        persist(rows)
    }

    /** 恢复为服务器顺序: [feature] follows the server page's order again. */
    suspend fun followServerOrder(feature: SourceFeature) {
        val row = DataSourceRow(feature.key, SourceRules.ORDER_MARK, false)
        _rows.value = _rows.value + (SourceRules.rowKey(feature.key, SourceRules.ORDER_MARK) to row)
        persist(listOf(row))
    }

    suspend fun set(feature: SourceFeature, server: String, on: Boolean) {
        // Keeps the server's place in a feature sorted by hand.
        val row = DataSourceRow(feature.key, server, on, _rows.value[SourceRules.rowKey(feature.key, server)]?.ord ?: 0)
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
    } else if (feature == SourceFeature.Metadata) {
        val m = metadata().filter { it.catalog || it.server?.enabled == true }
        SourceRules.label(m.count { it.enabled }, m.size, providers = true)
    } else {
        val candidates = registry.all.filter { it.enabled }
        SourceRules.label(candidates.count { enabled(feature, it.id) }, candidates.size)
    }

    private companion object { const val TAG = "Sources" }
}
