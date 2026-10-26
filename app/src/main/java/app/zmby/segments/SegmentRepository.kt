package app.zmby.segments

import app.zmby.data.ProviderState
import app.zmby.data.SegmentProvider
import app.zmby.emby.BaseItemDto
import app.zmby.log.ZLog
import app.zmby.store.SegmentMarker
import app.zmby.store.SourceDao
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** 本地手动标记 of one episode or film, or of a whole season ([season] true). */
data class ManualMarks(val introEndMs: Long? = null, val creditsStartMs: Long? = null, val season: Boolean = false) {
    val isEmpty: Boolean get() = introEndMs == null && creditsStartMs == null
}

/** What a playback is given: the merged segments with the provider of each. */
typealias SegmentPlan = Map<SegmentKind, Pair<SegmentProvider, Segment>>

/**
 * Segment markers for a playback (local-data spec, 片段标记提供方). Local marks and Emby chapters
 * are read at once; the external databases are asked only with external IDs, at most once a day
 * for "nothing there" and once a month for found segments (`segment_marker`), and not while a
 * provider is rate limited or refuses its key. Nothing here blocks playback: the player starts
 * whether or not the answer has arrived.
 */
class SegmentRepository(
    private val markers: SourceDao,
    private val clients: List<SegmentClient>,
    private val providers: () -> List<ProviderState>,
    private val key: (SegmentProvider) -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Providers not to ask until then (429 Retry-After, a refused key, a failure). */
    private val pausedUntil = ConcurrentHashMap<SegmentProvider, Long>()

    /**
     * The plan for [item] (an episode with its [series], or a film) keyed [workKey] in the store,
     * played in a version of [durationMs]. [borrowable] are the same work's copies on other servers
     * (for Emby markers this server lacks).
     */
    suspend fun plan(item: BaseItemDto, series: BaseItemDto?, workKey: String, durationMs: Long?, borrowable: suspend () -> List<BaseItemDto>): SegmentPlan {
        val order = providers().filter { it.enabled }.map { it.provider }
        if (order.isEmpty()) return emptyMap()
        val query = SegmentQuery.of(item, series, durationMs)
        val answers = coroutineScope {
            order.associateWith { p ->
                async {
                    runCatching { segmentsFrom(p, item, workKey, query, durationMs, borrowable) }
                        .onFailure { ZLog.w(TAG, "${p.label} failed", it) }
                        .getOrDefault(emptyList())
                }
            }.mapValues { it.value.await() }
        }
        val merged = SegmentRules.merge(order, answers)
        if (merged.isNotEmpty()) {
            ZLog.i(TAG, "$workKey: " + merged.entries.joinToString { (k, v) -> "${k.key} ${v.second.startMs}-${v.second.endMs ?: "end"} from ${v.first.label}" })
        }
        return merged
    }

    private suspend fun segmentsFrom(p: SegmentProvider, item: BaseItemDto, workKey: String, query: SegmentQuery, durationMs: Long?, borrowable: suspend () -> List<BaseItemDto>): List<Segment> = when (p) {
        SegmentProvider.Local -> SegmentRules.valid(SegmentRules.fromRows(localRows(workKey)).segments, durationMs)
        SegmentProvider.Emby -> SegmentRules.valid(SegmentRules.fromChapters(item).ifEmpty { SegmentRules.borrow(durationMs, borrowable()) }, durationMs)
        else -> external(p, workKey, query, durationMs)
    }

    /** The episode's own marks, else its season's. */
    private suspend fun localRows(workKey: String): List<SegmentMarker> {
        val keys = listOfNotNull(workKey, seasonKey(workKey))
        val rows = markers.markers(keys, SOURCE_LOCAL).filter { SegmentKind.of(it.kind) != null }
        return rows.filter { it.workKey == workKey }.ifEmpty { rows }
    }

    private suspend fun external(p: SegmentProvider, workKey: String, query: SegmentQuery, durationMs: Long?): List<Segment> {
        // Only works keyed by external IDs are shared knowledge; a server's own item ID is never sent.
        if (!query.hasIds || !app.zmby.store.StoreKeys.isExternal(workKey)) return emptyList()
        val client = clients.firstOrNull { it.provider == p } ?: return emptyList()
        val cached = markers.markers(listOf(workKey), p.key)
        val now = clock()
        val answer = if (SegmentRules.fresh(cached, now)) {
            SegmentRules.fromRows(cached)
        } else {
            if ((pausedUntil[p] ?: 0) > now) return cached.takeIf { it.isNotEmpty() }?.let { SegmentRules.fromRows(it).valid(durationMs) }.orEmpty()
            val k = key(p)?.also { app.zmby.log.ZLog.secret(it) }
            when (val a = withTimeoutOrNull(FETCH_TIMEOUT_MS) { client.fetch(query, k) } ?: ProviderAnswer.Failed("timed out")) {
                is ProviderAnswer.Found -> a.result.also { markers.replaceMarkers(workKey, p.key, SegmentRules.toRows(workKey, p.key, it, now)) }
                ProviderAnswer.Empty -> ProviderSegments(emptyList()).also { markers.replaceMarkers(workKey, p.key, SegmentRules.toRows(workKey, p.key, it, now)) }
                ProviderAnswer.NotAsked -> return emptyList()
                is ProviderAnswer.Failed -> {
                    pausedUntil[p] = now + (a.retryAfterMs ?: FAILURE_PAUSE_MS)
                    // An old answer is better than none.
                    SegmentRules.fromRows(cached)
                }
            }
        }
        return answer.valid(durationMs)
    }

    private fun ProviderSegments.valid(durationMs: Long?) = SegmentRules.valid(segments, durationMs, this.durationMs)

    // ---- 本地手动标记 (detail page)

    /** The marks shown when editing [workKey]: the episode's own, else its season's. */
    suspend fun manual(workKey: String): ManualMarks {
        val season = seasonKey(workKey)
        val rows = markers.markers(listOfNotNull(workKey, season), SOURCE_LOCAL)
        val own = rows.filter { it.workKey == workKey }
        val used = own.ifEmpty { rows.filter { it.workKey == season } }
        return ManualMarks(
            introEndMs = used.firstOrNull { it.kind == SegmentKind.Intro.key }?.endMs,
            creditsStartMs = used.firstOrNull { it.kind == SegmentKind.Credits.key }?.startMs,
            season = own.isEmpty() && used.isNotEmpty(),
        )
    }

    /**
     * Saves marks for the episode or film [workKey], or for its whole season ([marks].season); the
     * other scope's own marks of this episode are cleared so the choice is what applies.
     */
    suspend fun saveManual(workKey: String, marks: ManualMarks) {
        val target = if (marks.season) seasonKey(workKey) ?: workKey else workKey
        val now = clock()
        val rows = listOfNotNull(
            marks.introEndMs?.takeIf { it > 0 }?.let { SegmentMarker(target, SOURCE_LOCAL, SegmentKind.Intro.key, null, 0, it, now) },
            marks.creditsStartMs?.takeIf { it > 0 }?.let { SegmentMarker(target, SOURCE_LOCAL, SegmentKind.Credits.key, null, it, -1, now) },
        )
        markers.replaceMarkers(target, SOURCE_LOCAL, rows)
        if (target != workKey) markers.deleteMarkers(workKey, SOURCE_LOCAL)
        ZLog.i(TAG, "manual marks for $target: intro end ${marks.introEndMs}, credits ${marks.creditsStartMs}")
    }

    /** Where the user last stopped [workKey] in zmby (「取上次停止位置」). */
    suspend fun lastStop(workKey: String): Long? = markers.markers(listOf(workKey), SOURCE_LAST_STOP).firstOrNull()?.startMs

    suspend fun recordStop(workKey: String, positionMs: Long) {
        if (positionMs <= 0) return
        markers.replaceMarkers(workKey, SOURCE_LAST_STOP, listOf(SegmentMarker(workKey, SOURCE_LAST_STOP, KIND_STOP, null, positionMs, positionMs, clock())))
    }

    companion object {
        private const val TAG = "Segments"
        const val SOURCE_LOCAL = "local"
        /** Not a provider: the last stop position kept for 「取上次停止位置」. */
        const val SOURCE_LAST_STOP = "laststop"
        private const val KIND_STOP = "stop"
        const val FETCH_TIMEOUT_MS = 6_000L
        const val FAILURE_PAUSE_MS = 3_600_000L

        /** `series:tmdb:1:s01:e02` → `series:tmdb:1:s01`; null for a film or an unkeyed item. */
        fun seasonKey(workKey: String): String? = Regex("^(.+:s\\d+):e\\d+$").find(workKey)?.groupValues?.get(1)
    }
}
