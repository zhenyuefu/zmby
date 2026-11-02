package app.zmby.playback

import app.zmby.log.ZLog
import app.zmby.zidoo.PlayerStatusSource
import app.zmby.zidoo.ZidooResult
import app.zmby.zidoo.ZidooStatus
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import app.zmby.segments.SegmentSkipper
import kotlin.math.abs

/** Where playback state goes; implemented against Emby's /Sessions/Playing endpoints. */
interface PlaybackReporter {
    suspend fun start(positionMs: Long)
    suspend fun progress(positionMs: Long, paused: Boolean)
    suspend fun stopped(positionMs: Long)
    /** Marks the item played explicitly (the watch threshold was passed). */
    suspend fun markPlayed() {}
}

/**
 * The playback overlay's side of one playback (ui-spec §2.16). Called on the monitor's coroutine
 * (the main thread); implementations hand drawing to their own views.
 */
interface MonitorOverlay {
    /** Every sample of this playback's file. */
    fun onSample(positionMs: Long, durationMs: Long, playing: Boolean)
    /**
     * A segment in 显示按钮 mode was run into: offer its button. False when the overlay cannot ask
     * (closed, permission revoked): the monitor then skips by itself, as without the overlay.
     */
    fun onOffer(offer: app.zmby.segments.SkipDecision.Offer): Boolean = false
    /** zmby moved the position (an automatic skip or a button on the overlay). */
    fun onSeeked(positionMs: Long) {}
    /** The player opened another file: hide everything, nothing here belongs to it. */
    fun onLost() {}
}

/** How a playback ended, as far as the app is concerned. */
data class PlaybackOutcome(val positionMs: Long?, val watched: Boolean)

data class MonitorConfig(
    /** The player may need to wake a NAS or buffer a remote stream before it opens the file. */
    val startupTimeoutMs: Long = 30_000,
    val startupPollMs: Long = 400,
    val pollMs: Long = 1_000,
    val reportIntervalMs: Long = 10_000,
    /** Re-seek after startup only if the player opened further away than this from the requested start. */
    val seekToleranceMs: Long = 10_000,
)

/**
 * Follows one Zidoo playback through the player's HTTP API and reports it to the server.
 *
 * [watch] polls until cancelled (the activity result arrives) or the player opens another
 * file; [finish] then reports the final position exactly once.
 */
class PlaybackMonitor(
    private val player: PlayerStatusSource,
    private val reporter: PlaybackReporter,
    private val startPositionMs: Long,
    private val runtimeMs: Long?,
    private val watchedPercent: Int = WatchThreshold.DEFAULT_PERCENT,
    private val config: MonitorConfig = MonitorConfig(),
    private val now: () -> Long = System::currentTimeMillis,
    /** 片头片尾跳过: looked up while the player starts; used from the first poll after it is ready. */
    private val skipper: Deferred<SegmentSkipper?>? = null,
    /** Ignore stale status for a previous file, especially between direct and relay attempts. */
    private val acceptsStatus: (ZidooStatus) -> Boolean = { true },
    private val onStarted: () -> Unit = {},
    /** 我的: this playback's session on this box; only playbacks the status API confirmed are recorded. */
    private val session: PlaySessionSink? = null,
    /** 播放浮层: told about every sample, offered skip buttons. */
    private val overlay: MonitorOverlay? = null,
    /** Application lifetime: finishing a playback drains reports after its polling job is cancelled. */
    reportScope: CoroutineScope,
) {
    private val reports = PlaybackReportQueue(reportScope, reporter, session)
    /** Seeks the overlay made (a button), applied on the next poll like zmby's own skips. */
    private val overlaySeeks = java.util.concurrent.ConcurrentLinkedQueue<Long>()

    /** Seeks zmby made past segments (for the log and tests). */
    var skips = 0
        private set

    var started = false
        private set
    var lastPositionMs: Long? = null
        private set
    /** The player's own duration, used when the server does not know the runtime yet. */
    var lastDurationMs: Long? = null
        private set
    private var fileKey: String? = null
    private var finished = false
    private var tracker: SessionTracker? = null

    suspend fun watch() {
        if (!awaitStartup()) return
        var lastReport = now()
        var lastPaused = false
        var previous: Long? = null
        while (!finished) {
            delay(config.pollMs)
            while (true) {
                val target = overlaySeeks.poll() ?: break
                // The overlay already seeked: record it as a jump and report the new position at once.
                skips++
                previous = target
                lastPositionMs = target
                tracker?.seeked(now(), target)
                reports.progress(target, lastPaused, tracker?.snapshot(), boundary = true)
                lastReport = now()
            }
            val status = player.status() ?: continue
            if (fileKey != null && status.fileKey != null && status.fileKey != fileKey) {
                // The player moved on to another file; it is no longer ours to report.
                ZLog.w(TAG, "player opened another file (${status.fileKey}); stop following")
                overlay?.onLost()
                return
            }
            overlay?.onSample(status.positionMs, status.durationMs, status.playing)
            if (status.positionMs > 0) lastPositionMs = status.positionMs
            if (status.durationMs > 0) lastDurationMs = status.durationMs
            tracker?.sample(now(), status.positionMs, status.playing)
            val paused = !status.playing
            val skip = skipper?.takeIf { it.isCompleted }?.getCompletedOrNull()
            val decision = if (skip != null && status.durationMs > 0) skip.decide(previous, status.positionMs, status.durationMs) else null
            val asked = decision is app.zmby.segments.SkipDecision.Offer && overlay?.onOffer(decision) == true
            val target = decision?.targetMs?.takeIf { !asked }
            previous = status.positionMs
            if (target != null) {
                // Like a user's seek: the new position is reported at once, so the server's resume
                // point never lags behind the jump.
                val ok = player.seekTo(target)
                ZLog.i(TAG, "skip ${status.positionMs}ms -> ${target}ms ${if (ok) "ok" else "failed"}")
                if (ok) {
                    skips++
                    previous = target
                    lastPositionMs = target
                    tracker?.seeked(now(), target)
                    overlay?.onSeeked(target)
                    reports.progress(target, paused, tracker?.snapshot(), boundary = true)
                    lastReport = now()
                    lastPaused = paused
                    continue
                }
            }
            val due = now() - lastReport >= config.reportIntervalMs
            if (due || paused != lastPaused) {
                ZLog.d(TAG, "poll pos=${status.positionMs}ms/${status.durationMs}ms ${if (paused) "paused" else "playing"}")
                lastPositionMs?.let { reports.progress(it, paused, tracker?.snapshot(), boundary = paused != lastPaused) }
                lastReport = now()
                lastPaused = paused
            }
        }
    }

    /**
     * A seek the user asked for on the overlay (跳过片头, 立即播放). The jump is reported on the next
     * poll, to this playback's own server only. False when the player refused it.
     */
    suspend fun seekFromOverlay(targetMs: Long): Boolean {
        if (finished) return false
        val ok = player.seekTo(targetMs)
        ZLog.i(TAG, "overlay seek -> ${targetMs}ms ${if (ok) "ok" else "failed"}")
        if (ok) {
            overlaySeeks += targetMs
            overlay?.onSeeked(targetMs)
        }
        return ok
    }

    private suspend fun awaitStartup(): Boolean {
        val deadline = now() + config.startupTimeoutMs
        while (!finished && now() < deadline) {
            val status = player.status()
            if (status != null && acceptsStatus(status)) {
                fileKey = status.fileKey
                if (status.durationMs > 0) lastDurationMs = status.durationMs
                var position = status.positionMs
                ZLog.i(TAG, "player up: file=${status.fileKey} pos=${position}ms/${status.durationMs}ms")
                if (startPositionMs > 0 && abs(position - startPositionMs) > config.seekToleranceMs) {
                    val ok = player.seekTo(startPositionMs)
                    ZLog.i(TAG, "opened ${position}ms away from requested ${startPositionMs}ms; seek ${if (ok) "ok" else "failed"}")
                    if (ok) position = startPositionMs
                }
                lastPositionMs = position.takeIf { it > 0 }
                started = true
                onStarted()
                if (session != null) {
                    val at = now()
                    tracker = SessionTracker(at, position, status.playing)
                }
                overlay?.onSample(position, status.durationMs, status.playing)
                reports.start(now(), position, recordSession = tracker != null)
                return true
            }
            delay(config.startupPollMs)
        }
        ZLog.w(TAG, "player status API never showed a video within ${config.startupTimeoutMs}ms")
        return false
    }

    /**
     * Reports the final position and, past the watch threshold, marks the item played. Call once
     * the player activity has returned; later calls return null.
     */
    suspend fun finish(result: ZidooResult?): PlaybackOutcome? {
        if (finished) return null
        finished = true
        // Unscraped items have no runtime on the server; the player knows the file's duration.
        val runtime = runtimeMs ?: result?.durationMs ?: lastDurationMs
        val position = resolveFinalPosition(result, lastPositionMs, runtime)
        val watched = result?.completed == true || (position != null && WatchThreshold.isWatched(position, runtime, watchedPercent))
        ZLog.i(TAG, "finish result=$result lastPolled=${lastPositionMs}ms runtime=${runtime}ms -> final=${position}ms watched=$watched")
        if (position == null) {
            reports.finish(null, runtime, false, tracker?.snapshot())
            return PlaybackOutcome(null, false)
        }
        if (!started) {
            // The status API never saw the player (e.g. it is disabled), but the result says it played.
            reports.start(now(), startPositionMs, recordSession = false)
            started = true
            if (result?.failed != true && (position > 0 || result?.completed == true)) onStarted()
        }
        reports.finish(position, runtime, watched, tracker?.snapshot())
        return PlaybackOutcome(position, watched)
    }
}

private const val TAG = "Monitor"

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private fun <T> Deferred<T>.getCompletedOrNull(): T? = runCatching { getCompleted() }.getOrNull()

/**
 * The position to report when playback ends, or null to leave the server's resume point alone.
 *
 * A completed playback reports the full runtime so the server marks the item played: the
 * player often omits the position on completion and the last poll can be seconds old.
 */
fun resolveFinalPosition(result: ZidooResult?, lastPolledMs: Long?, runtimeMs: Long?): Long? {
    if (result?.completed == true) {
        return result.durationMs ?: runtimeMs ?: result.positionMs ?: lastPolledMs
    }
    return result?.positionMs?.takeIf { it > 0 } ?: lastPolledMs
}

/** A fixed STRM name must not bind a new monitor to the previous episode's cached final sample. */
fun matchesPlaybackAttempt(status: app.zmby.zidoo.ZidooStatus, beforeLaunch: app.zmby.zidoo.ZidooStatus?, paths: Set<String>): Boolean =
    status != beforeLaunch && paths.any { matchesPlaybackPath(status.path, it) }

/**
 * Match an actual path, never the previous video's title or mere API availability. For an http(s)
 * stream the Zidoo player (Z9X 8K, 2026-10-26) reports only the URL's path, without host or query:
 * `/videos/12845/original.mkv` for `https://host/videos/12845/original.mkv?…&api_key=…`.
 */
fun matchesPlaybackPath(path: String?, uri: String): Boolean {
    if (path.isNullOrBlank()) return false
    if (path == uri) return true
    if (uri.startsWith("file:")) return path.removePrefix("file://") == uri.removePrefix("file://")
    val parsed = runCatching { java.net.URI(uri) }.getOrNull() ?: return false
    if (parsed.scheme != "http" && parsed.scheme != "https") return false
    val rawPath = parsed.rawPath?.takeIf { it.isNotEmpty() } ?: return false
    return path == rawPath || path == parsed.path
}
