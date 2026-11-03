package app.zmby.playback

import app.zmby.catalog.episodeProviderId
import app.zmby.data.ServerItem
import app.zmby.emby.BaseItemDto
import app.zmby.emby.EmbyException
import app.zmby.emby.msToTicks
import app.zmby.emby.ticksToMs
import app.zmby.log.ZLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.abs

/** What 观看进度回写 sends to one copy: only the fields that are set. */
data class WatchWrite(val positionTicks: Long?, val played: Boolean?)

/** How one copy came out. */
enum class SyncResult {
    Written,
    /** Already in that state, played there since this playback began, or another cut: left alone. */
    Skipped,
    /** 401, 403 or 405 on the write: the server is taken out of 观看进度回写. */
    Refused,
    /** Anything else; not retried (the user chose no retries). */
    Failed,
}

/**
 * The rules of 观看进度回写 (cross-server-watch-sync), without I/O. The copies are the ones the
 * screen had already matched (versions on the detail or series page, a 在看 card's alternates);
 * nothing is looked up again, and a conflicting external ID keeps a copy out.
 */
object WatchSyncRules {
    /** A version whose runtimes all lie within this of the one played is the same cut. */
    const val RUNTIME_TOLERANCE_MS = 15_000L
    /** Below this share of the runtime nothing is resumable (Emby's default MinResumePct). */
    const val MIN_RESUME_PERCENT = 5
    /** A copy already this close to the position needs no write. */
    const val SAME_POSITION_MS = 10_000L
    /** Activity on a copy this shortly before the playback began still counts as newer (clock skew). */
    const val CLOCK_SKEW_MS = 60_000L
    private val PROVIDERS = listOf("tmdb", "imdb", "tvdb", "bangumi")

    /** The copies to write: other servers in 观看进度回写, the same type, no conflicting external ID. */
    fun targets(played: ServerItem, copies: List<ServerItem>, allowed: (String) -> Boolean): List<ServerItem> =
        copies.filter {
            it.server != played.server && it.server != "catalog" && it.item.type == played.item.type &&
                allowed(it.server) && !conflicting(played.item, it.item)
        }.distinctBy { it.key }

    /** Both carry the same kind of external ID with different values (for an episode, its own IDs). */
    fun conflicting(a: BaseItemDto, b: BaseItemDto): Boolean = PROVIDERS.any { k ->
        val x = episodeProviderId(a, k)
        val y = episodeProviderId(b, k)
        x != null && y != null && !x.equals(y, ignoreCase = true)
    }

    /**
     * What the stop asks of every copy, or null: watched → played (any cut); part-way from
     * [MIN_RESUME_PERCENT] of [runtimeMs] → the position; less, or no known runtime → nothing.
     */
    fun intent(outcome: PlaybackOutcome, runtimeMs: Long?): WatchWrite? {
        if (outcome.watched) return WatchWrite(0, true)
        val position = outcome.positionMs ?: return null
        val runtime = runtimeMs?.takeIf { it > 0 } ?: return null
        if (position * 100 < runtime * MIN_RESUME_PERCENT) return null
        return WatchWrite(msToTicks(position), null)
    }

    /** Every runtime [copy] can play at: each source's, else the item's. */
    fun runtimesOf(copy: BaseItemDto): List<Long> =
        copy.mediaSources.mapNotNull { s -> s.runTimeTicks?.takeIf { it > 0 }?.let(::ticksToMs) }
            .ifEmpty { listOfNotNull(copy.runtimeMs?.takeIf { it > 0 }) }

    /** Every runtime of [copy] is within [RUNTIME_TOLERANCE_MS] of [playedMs]; unknown on either side is not. */
    fun sameCut(playedMs: Long?, copy: BaseItemDto): Boolean {
        val played = playedMs?.takeIf { it > 0 } ?: return false
        val runtimes = runtimesOf(copy)
        return runtimes.isNotEmpty() && runtimes.all { abs(it - played) <= RUNTIME_TOLERANCE_MS }
    }

    /**
     * [intent] for [copy] as its server has it now, or null to leave it: played there since the
     * playback began ([startedAt]), already in that state, or (for a position) another cut.
     */
    fun writeFor(intent: WatchWrite, playedRuntimeMs: Long?, copy: BaseItemDto, startedAt: Long): WatchWrite? {
        val data = copy.userData
        val last = data?.lastPlayedDate?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
        if (last != null && last > startedAt - CLOCK_SKEW_MS) return null
        if (intent.played == true) return intent.takeUnless { data?.played == true && data.playbackPositionTicks == 0L }
        val ticks = intent.positionTicks ?: return null
        if (!sameCut(playedRuntimeMs, copy)) return null
        if (data != null && abs(ticks - data.playbackPositionTicks) <= msToTicks(SAME_POSITION_MS)) return null
        return intent
    }

    fun refusal(code: Int?) = code == 401 || code == 403 || code == 405
}

/**
 * 观看进度回写 after a playback stopped: each matched copy on another server in the source gets the
 * position or played state, once. The playing server already has the stop report; a failure is
 * logged and not retried; a refusal takes that server out of the source ([refused]).
 */
class WatchSync(
    private val allowed: (String) -> Boolean,
    /** The copy as its server has it now, with its sources and user data. */
    private val fetch: suspend (server: String, id: String) -> BaseItemDto,
    private val send: suspend (server: String, id: String, write: WatchWrite) -> Unit,
    /** The local rows take the written state. */
    private val recorded: suspend (server: String, id: String, write: WatchWrite) -> Unit,
    private val refused: suspend (server: String, code: Int) -> Unit,
    private val name: (String) -> String = { it },
) {
    suspend fun afterPlayback(
        played: ServerItem, playedRuntimeMs: Long?, outcome: PlaybackOutcome, copies: List<ServerItem>, startedAt: Long,
    ): Map<String, SyncResult> {
        val intent = WatchSyncRules.intent(outcome, playedRuntimeMs) ?: return emptyMap()
        val targets = WatchSyncRules.targets(played, copies, allowed)
        if (targets.isEmpty()) return emptyMap()
        val results = coroutineScope {
            targets.map { t -> async { t.key to one(t, intent, playedRuntimeMs, startedAt) } }.awaitAll()
        }.toMap()
        ZLog.i(TAG, "${played.key} ${if (intent.played == true) "watched" else "at ${ticksToMs(intent.positionTicks ?: 0) / 1000}s"}: " +
            results.entries.joinToString { (k, r) -> "$k $r" })
        return results
    }

    private suspend fun one(target: ServerItem, intent: WatchWrite, playedRuntimeMs: Long?, startedAt: Long): SyncResult {
        val server = target.server
        return try {
            val now = fetch(server, target.item.id)
            val write = WatchSyncRules.writeFor(intent, playedRuntimeMs, now, startedAt) ?: return SyncResult.Skipped
            try {
                send(server, target.item.id, write)
            } catch (e: EmbyException) {
                if (!WatchSyncRules.refusal(e.code)) throw e
                ZLog.w(TAG, "${name(server)} refused a user data write (${e.code}); 观看进度回写 turned off there")
                refused(server, e.code!!)
                return SyncResult.Refused
            }
            runCatching { recorded(server, target.item.id, write) }.onFailure { ZLog.w(TAG, "local row of ${target.key} not updated", it) }
            SyncResult.Written
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ZLog.w(TAG, "not written to ${name(server)}: ${e.message}")
            SyncResult.Failed
        }
    }

    private companion object { const val TAG = "WatchSync" }
}
