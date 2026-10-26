package app.zmby.playback

import app.zmby.emby.BaseItemDto
import app.zmby.zidoo.ZidooResult

/** Another version to try when the chosen one does not start (播放失败自动换源). */
data class Alternate(
    val server: String,
    val item: BaseItemDto,
    val mediaSourceId: String,
    /** 「服务器名 · 版本名」, shown when switching to it. */
    val label: String,
)

/**
 * 播放失败自动换源: what counts as a failed start, and which version comes next. The versions are
 * tried in the default-version order of the detail page (VersionRanker), each at most once.
 */
object Failover {
    /** A player that gives up within this long, without the status API ever seeing a video, did not start. */
    const val START_WINDOW_MS = 20_000L

    /**
     * The player came back [elapsedMs] after launch: a failed start if the status API never saw the
     * video open, it did not complete and handed back no position. A user who backs out of a video
     * that did open (the API saw it) is not a failure.
     */
    fun failedToStart(elapsedMs: Long, sawVideo: Boolean, result: ZidooResult?): Boolean =
        !sawVideo && elapsedMs < START_WINDOW_MS && result?.completed != true && (result?.positionMs ?: 0) <= 0

    /** The next version not tried yet. */
    fun next(alternates: List<Alternate>, tried: Set<String>): Alternate? =
        alternates.firstOrNull { key(it) !in tried }

    fun key(a: Alternate) = key(a.server, a.mediaSourceId)
    fun key(server: String, sourceId: String?) = "$server|${sourceId.orEmpty()}"
}
