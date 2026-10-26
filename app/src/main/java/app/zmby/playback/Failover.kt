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
    /** Only an explicit playback error proves failure. Missing results and `user` mean no retry. */
    fun failedToStart(sawVideo: Boolean, result: ZidooResult?): Boolean =
        !sawVideo && result?.failed == true && (result.positionMs ?: 0) <= 0

    /** The next version not tried yet. */
    fun next(alternates: List<Alternate>, tried: Set<String>): Alternate? =
        alternates.firstOrNull { key(it) !in tried }

    fun key(a: Alternate) = key(a.server, a.mediaSourceId)
    fun key(server: String, sourceId: String?) = "$server|${sourceId.orEmpty()}"
}

/** Decisions after the external player returns; no timeout or activity result code proves failure. */
enum class RelayReturnAction { Retry, OfferRetry, Finish }

fun relayReturnAction(canRetry: Boolean, sawVideo: Boolean, result: ZidooResult?): RelayReturnAction = when {
    !canRetry -> RelayReturnAction.Finish
    result?.failed == true -> RelayReturnAction.Retry
    !sawVideo && result?.endBy == null && (result?.positionMs ?: 0) <= 0 -> RelayReturnAction.OfferRetry
    else -> RelayReturnAction.Finish
}
