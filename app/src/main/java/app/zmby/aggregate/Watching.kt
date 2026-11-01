package app.zmby.aggregate

import app.zmby.catalog.episodeProviderId
import app.zmby.data.ServerItem
import app.zmby.emby.BaseItemDto
import app.zmby.store.EpisodeMappingRule

/** A season and episode number, after the local season mapping. */
data class Slot(val season: Int, val episode: Int) : Comparable<Slot> {
    override fun compareTo(other: Slot): Int = compareValuesBy(this, other, Slot::season, Slot::episode)
}

/**
 * One 观看记录来源 server's lists as the store holds them (ui-spec §2.18 数据与智能合并):
 * Resume, NextUp and recently played films and episodes, plus the series those episodes belong to
 * (ProviderIds and Status, for identity) and, for series being watched, their episode lists.
 */
data class WatchSource(
    val server: String,
    val order: Int,
    val resume: List<BaseItemDto> = emptyList(),
    val nextUp: List<BaseItemDto> = emptyList(),
    val played: List<BaseItemDto> = emptyList(),
    val series: Map<String, BaseItemDto> = emptyMap(),
    val episodes: Map<String, List<BaseItemDto>> = emptyMap(),
)

enum class WatchState {
    /** Stopped part-way: 「继续」 that episode or film. */
    Resume,
    /** Finished an episode: 「下一集」 the one after it. */
    Next,
    /** The next episode has not aired, or no server has it: 「等待更新」, listed after the others. */
    Waiting,
}

/** 剧集整体进度: [season] null for the whole series; [segments] per episode (0–1 filled) up to 30 episodes. */
data class EpisodeBar(val season: Int?, val total: Int, val watched: Int, val fraction: Float, val segments: List<Float>?)

/**
 * One work in 在看. [target] is what OK plays (null while waiting with nothing to play) on the
 * server holding the current progress; [alternates] are the same episode or film on other
 * servers. [resumePoints] are the part-watched records 移除 clears, each on its own server.
 */
data class WatchCard(
    val key: String,
    val series: Boolean,
    val state: WatchState,
    val target: ServerItem?,
    val shown: ServerItem,
    val startMs: Long,
    val progress: Float,
    val lastActivity: Long?,
    val server: String,
    val servers: List<String>,
    val alternates: List<ServerItem>,
    val resumePoints: List<ServerItem>,
    val slot: Slot?,
    val airDate: String?,
    val bar: EpisodeBar?,
) {
    /** Nothing part-watched to clear: 移除 hides it on this box until the work is played again. */
    val nextUpOnly: Boolean get() = resumePoints.isEmpty()
}

/**
 * 在看 across servers (ui-spec §2.18), pure so it can be tested and run off the main thread:
 * one card per work. Films merge on TMDB / IMDb, series on their own external IDs, episodes on
 * series identity plus season and episode (after local season mappings); any conflicting ID keeps
 * the works apart — two cards are better than a hidden resume point. The newest activity on any
 * server decides between 继续 and 下一集; ties and missing times take the later episode.
 */
object WatchingMerge {
    private enum class Kind { Progress, Finished, Hint }

    private class Event(val server: String, val order: Int, val item: BaseItemDto, val kind: Kind, val time: Long?, val slot: Slot?, val fromDirectory: Boolean = false) {
        val ref get() = ServerItem(server, item)
        val positionMs get() = item.resumeMs
    }

    /** A film (or an episode without usable numbers) or a series on one server. */
    private data class Node(val server: String, val id: String, val series: Boolean)

    /** Nodes taken as one work; [apart] when kept on its own after a conflict (keyed by the item, not the shared ID). */
    private class Group(val nodes: List<Node>, val apart: Boolean = false) {
        fun split() = nodes.map { Group(listOf(it), apart = true) }
    }

    /** Newest first; equal or missing times: the later episode, finished over part-watched, further in, higher priority. */
    private val newest: Comparator<Event> = compareBy<Event, Long?>(nullsFirst()) { it.time }
        .thenBy(nullsFirst()) { it.slot }
        .thenBy { it.kind == Kind.Finished }
        .thenBy { it.positionMs }
        .thenByDescending { it.order }

    fun merge(
        sources: List<WatchSource>,
        rules: List<EpisodeMappingRule> = emptyList(),
        hidden: Map<String, Long> = emptyMap(),
        includeNext: Boolean = true,
        now: Long = System.currentTimeMillis(),
    ): List<WatchCard> {
        val orderOf = sources.associate { it.server to it.order }
        val ids = HashMap<Node, Map<String, String>>()
        val events = HashMap<Node, MutableList<Event>>()

        fun seriesIds(source: WatchSource, seriesId: String): Map<String, String> =
            source.series[seriesId]?.let(::externalIds).orEmpty()

        fun slotOf(source: WatchSource, item: BaseItemDto): Slot? {
            val s = item.parentIndexNumber ?: return null
            val e = item.indexNumber ?: return null
            val seriesId = item.seriesId ?: return null
            val work = seriesIds(source, seriesId)["tmdb"]?.let { "tmdb:tv:$it" }
            val ref = ServerItem(source.server, item)
            val rule = rules.firstOrNull { work != null && it.applies(work, ref) } ?: return Slot(s, e)
            // A rule for one file points at a catalogue episode this merge cannot see: that episode stays on its own.
            if (rule.itemId != null) return null
            return Slot(rule.targetSeason, e + rule.offset)
        }

        fun add(source: WatchSource, item: BaseItemDto, kind: Kind, fromDirectory: Boolean = false) {
            val type = item.type
            if (type == "Series" || type == "Season" || item.isFolder) return
            val seriesId = item.seriesId?.takeIf { type == "Episode" }
            val slot = if (seriesId != null) slotOf(source, item) else null
            val node = if (seriesId != null && slot != null) Node(source.server, seriesId, series = true) else Node(source.server, item.id, series = false)
            if (node !in ids) ids[node] = if (node.series) seriesIds(source, seriesId!!) else if (type == "Movie") externalIds(item) else emptyMap()
            events.getOrPut(node) { mutableListOf() } += Event(source.server, source.order, item, kind, time(item.userData?.lastPlayedDate), slot, fromDirectory)
        }

        for (source in sources) {
            source.resume.filter { (it.userData?.playbackPositionTicks ?: 0) > 0 && it.userData?.played != true }.forEach { add(source, it, Kind.Progress) }
            source.played.filter { it.userData?.played == true }.forEach { add(source, it, Kind.Finished) }
            source.nextUp.forEach { add(source, it, Kind.Hint) }
        }
        // Episode lists only add to series already being watched on that server.
        for (source in sources) {
            for ((seriesId, list) in source.episodes) {
                if (Node(source.server, seriesId, true) !in events) continue
                for (ep in list) {
                    when {
                        ep.userData?.played == true -> add(source, ep, Kind.Finished, fromDirectory = true)
                        (ep.userData?.playbackPositionTicks ?: 0) > 0 -> add(source, ep, Kind.Progress, fromDirectory = true)
                        else -> add(source, ep, Kind.Hint, fromDirectory = true)
                    }
                }
            }
        }

        val groups = group(ids.keys.toList(), ids, orderOf)
            .flatMap { g -> if (g.nodes.size > 1 && g.nodes.first().series && episodesConflict(g.nodes.flatMap { events[it].orEmpty() })) g.split() else listOf(g) }
        val cards = groups.mapNotNull { group ->
            val g = group.nodes
            val key = groupKey(group, ids)
            val raw = g.flatMap { events[it].orEmpty() }
            val all = dedupe(raw)
            val status = g.filter { it.series }.mapNotNull { n -> sources.firstOrNull { it.server == n.server }?.series?.get(n.id)?.status }
            // The episode list before deduplication: an episode also in NextUp is still part of it.
            val directory = raw.filter { it.fromDirectory }.mapNotNull { it.slot }.toSortedSet()
            if (g.first().series) seriesCard(key, all, directory, status, orderOf, now) else filmCard(key, all, orderOf)
        }
        return cards
            .filter { c -> includeNext || c.state == WatchState.Resume }
            .filter { c -> hidden[c.key]?.let { at -> (c.lastActivity ?: Long.MIN_VALUE) > at } ?: true }
            .sortedWith(
                compareBy<WatchCard> { it.state == WatchState.Waiting }
                    .thenByDescending { it.lastActivity ?: Long.MIN_VALUE }
                    .thenBy { orderOf[it.server] ?: Int.MAX_VALUE }
                    .thenBy { it.key },
            )
    }

    private fun filmCard(key: String, all: List<Event>, orderOf: Map<String, Int>): WatchCard? {
        val latest = all.filter { it.kind != Kind.Hint }.maxWithOrNull(newest) ?: return null
        if (latest.kind != Kind.Progress) return null
        val progress = all.filter { it.kind == Kind.Progress && !it.fromDirectory }
        return WatchCard(
            key = key, series = false, state = WatchState.Resume, target = latest.ref, shown = latest.ref,
            startMs = latest.positionMs, progress = fraction(latest.item), lastActivity = latest.time, server = latest.server,
            servers = servers(all, orderOf),
            alternates = all.filter { it.server != latest.server }.sortedBy { it.order }.distinctBy { it.server }.map { it.ref },
            resumePoints = progress.map { it.ref }.distinctBy { it.key },
            slot = null, airDate = null, bar = null,
        )
    }

    private fun seriesCard(key: String, all: List<Event>, directory: java.util.SortedSet<Slot>, status: List<String>, orderOf: Map<String, Int>, now: Long): WatchCard? {
        val fromLists = all.filter { !it.fromDirectory }
        // A series needs something current: a part-watched episode or a server's 下一集.
        if (fromLists.none { it.kind == Kind.Progress || it.kind == Kind.Hint }) return null
        val latest = all.filter { it.kind != Kind.Hint }.maxWithOrNull(newest)
        val watched = all.filter { it.kind == Kind.Finished }.mapNotNull { it.slot }.toSet()
        val resumePoints = all.filter { it.kind == Kind.Progress }.map { it.ref }.distinctBy { it.key }
        val servers = servers(all, orderOf)
        val preferred = latest?.server

        fun resourceAt(slot: Slot): List<Event> = all.filter { it.slot == slot && it.item.locationType != "Virtual" }
            .sortedWith(compareBy<Event> { it.server != preferred }.thenBy { it.order })
            .distinctBy { it.server }

        fun card(state: WatchState, current: Event, slot: Slot?, startMs: Long, progress: Float, airDate: String? = null, target: ServerItem? = current.ref): WatchCard {
            val others = slot?.let { s -> resourceAt(s).filter { it.server != target?.server }.map { it.ref } }.orEmpty()
            return WatchCard(
                key = key, series = true, state = state, target = target, shown = current.ref, startMs = startMs, progress = progress,
                lastActivity = latest?.time, server = preferred ?: current.server, servers = servers, alternates = others,
                resumePoints = resumePoints, slot = slot, airDate = airDate,
                bar = bar(directory, watched, slot, if (state == WatchState.Resume) progress else 0f),
            )
        }

        if (latest != null && latest.kind == Kind.Progress) {
            return card(WatchState.Resume, latest, latest.slot, latest.positionMs, fraction(latest.item))
        }
        val after = latest?.slot
        val hints = all.filter { it.kind == Kind.Hint && !it.fromDirectory }.mapNotNull { it.slot }
        val candidates = (hints + listOfNotNull(directory.firstOrNull { (after == null || it > after) && it !in watched }))
            .filter { (after == null || it > after) && it !in watched }
        val next = candidates.maxOrNull()
        if (next == null) {
            val shown = latest ?: return null
            val allSeen = directory.isNotEmpty() && directory.all { it in watched }
            if (allSeen && status.none { it.equals("Continuing", ignoreCase = true) }) return null
            return card(WatchState.Waiting, shown, null, 0, 0f, target = null)
        }
        val resource = resourceAt(next).firstOrNull()
        if (resource == null) {
            val virtual = all.firstOrNull { it.slot == next }
            return card(WatchState.Waiting, virtual ?: latest!!, next, 0, 0f, airDate = virtual?.item?.premiereDate, target = null)
        }
        val airs = resource.item.premiereDate?.let(::time)
        if (airs != null && airs > now) return card(WatchState.Waiting, resource, next, 0, 0f, airDate = resource.item.premiereDate, target = null)
        return card(WatchState.Next, resource, next, 0, 0f)
    }

    /** One season's bar when the series has several, the whole series when it has one; specials (season 0) aside. */
    private fun bar(directory: Set<Slot>, watched: Set<Slot>, current: Slot?, partial: Float): EpisodeBar? {
        val regular = directory.filter { it.season > 0 }
        val seasons = regular.map { it.season }.toSet()
        val season = if (seasons.size > 1) current?.season?.takeIf { it in seasons } else null
        if (seasons.size > 1 && season == null) return null
        val scope = regular.filter { season == null || it.season == season }.sorted()
        if (scope.isEmpty()) return null
        val seen = scope.count { it in watched && !(it == current && partial > 0f) }
        val fills = scope.map { s -> if (s == current && partial > 0f) partial else if (s in watched) 1f else 0f }
        return EpisodeBar(season, scope.size, seen, fills.sum() / scope.size, fills.takeIf { scope.size <= 30 })
    }

    /** The same record listed twice (Resume and the episode list): the newer copy. */
    private fun dedupe(events: List<Event>): List<Event> =
        events.groupBy { Triple(it.server, it.item.id, it.kind) }.values.map { same ->
            // Played lists come without images; the episode list's copy has them.
            same.sortedWith(compareBy<Event> { it.fromDirectory != (it.kind == Kind.Finished) }).maxWith(compareBy(nullsFirst()) { it.time })
        }

    private fun servers(all: List<Event>, orderOf: Map<String, Int>) = all.map { it.server }.distinct().sortedBy { orderOf[it] ?: Int.MAX_VALUE }

    /**
     * Works sharing an external ID become one group, unless any two of them disagree on a provider
     * (e.g. same IMDb, different TMDB): then every member stays on its own.
     */
    private fun group(nodes: List<Node>, ids: Map<Node, Map<String, String>>, orderOf: Map<String, Int>): List<Group> {
        val parent = HashMap<Node, Node>()
        fun find(n: Node): Node { var x = n; while (parent[x] != null && parent[x] != x) x = parent[x]!!; return x }
        val byId = HashMap<Triple<Boolean, String, String>, Node>()
        for (n in nodes.sortedBy { orderOf[it.server] ?: Int.MAX_VALUE }) {
            parent[n] = n
            for ((provider, value) in ids[n].orEmpty()) {
                val k = Triple(n.series, provider, value)
                val other = byId[k]
                if (other == null) byId[k] = n else parent[find(n)] = find(other)
            }
        }
        return nodes.groupBy(::find).values.flatMap { g ->
            val conflict = g.size > 1 && g.flatMap { ids[it].orEmpty().entries }.groupBy({ it.key }, { it.value }).values.any { it.toSet().size > 1 }
            val sorted = g.sortedBy { orderOf[it.server] ?: Int.MAX_VALUE }
            if (conflict) Group(sorted).split() else listOf(Group(sorted))
        }
    }

    /** Two servers' episodes at the same number with different episode IDs: the numbering is not shared. */
    private fun episodesConflict(events: List<Event>): Boolean =
        events.filter { it.slot != null }.groupBy { it.slot }.values.any { same ->
            same.map { it.server }.distinct().size > 1 && listOf("tmdb", "imdb", "tvdb").any { p ->
                same.mapNotNull { e -> episodeProviderId(e.item, p)?.lowercase()?.let { e.server to it } }.distinct()
                    .groupBy({ it.second }, { it.first }).size > 1
            }
        }

    /** The work's key (as the local store keys works), or the item's own when it was kept apart or has no IDs. */
    private fun groupKey(g: Group, ids: Map<Node, Map<String, String>>): String {
        val kind = if (g.nodes.first().series) "series" else "movie"
        val all = g.nodes.flatMap { ids[it].orEmpty().entries }.associate { it.key to it.value }
        if (!g.apart) for (p in listOf("tmdb", "imdb", "tvdb")) all[p]?.let { return "$kind:$p:$it" }
        val first = g.nodes.first()
        return "item:${first.server}:${first.id}"
    }

    /** TMDB, IMDb and TVDB of a film or series, without the junk values some servers send. */
    private fun externalIds(item: BaseItemDto): Map<String, String> =
        listOf("tmdb", "imdb", "tvdb").mapNotNull { p -> episodeProviderId(item, p)?.lowercase()?.let { p to it } }.toMap()

    private fun fraction(item: BaseItemDto): Float {
        val runtime = item.runtimeMs ?: return ((item.userData?.playedPercentage ?: 0.0) / 100).toFloat().coerceIn(0f, 1f)
        return if (runtime <= 0) 0f else (item.resumeMs.toFloat() / runtime).coerceIn(0f, 1f)
    }

    fun time(value: String?): Long? = value?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
}
