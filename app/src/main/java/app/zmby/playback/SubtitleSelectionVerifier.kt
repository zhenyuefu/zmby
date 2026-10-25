package app.zmby.playback

import app.zmby.log.ZLog
import app.zmby.zidoo.PlayerStatusSource
import app.zmby.zidoo.PlayerSubtitleControl
import app.zmby.zidoo.ZidooPlayerStrings
import kotlinx.coroutines.delay

/**
 * After a local-STRM launch: waits until the player has this session's .strm open and lists its
 * external subtitle, then makes sure that subtitle is the selected one. `subtitlePid` normally did
 * that already; this covers start-up paths where the player drops the PID. Bounded in time and
 * corrects at most once, so it never fights a choice the user makes in the player afterwards.
 * It only proves the player selected the file, not that the text renders.
 */
class SubtitleSelectionVerifier(
    private val status: PlayerStatusSource,
    private val subtitles: PlayerSubtitleControl,
    /**
     * The player's own title suffixes for MKV-internal text tracks and scanned files ("内置" / "外挂"
     * on a Chinese box). Its API flags internal text tracks `isExternal` too, so only the title tells
     * them apart; null when the player's resources could not be read.
     */
    private val markers: ZidooPlayerStrings = ZidooPlayerStrings(),
    private val windowMs: Long = 25_000,
    private val pollMs: Long = 500,
    /** Time the player gets to apply the PID itself after the subtitle shows up in its list. */
    private val graceMs: Long = 2_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    enum class Result { AlreadySelected, Corrected, SetFailed, NotListed, Ambiguous, OtherFile, NeverOpened }

    /** [strmTail] is the .strm's unique file name: the player may report the path under another mount alias. */
    suspend fun verify(strmTail: String): Result {
        val deadline = now() + windowMs
        var opened = false
        var listedAt: Long? = null
        while (now() < deadline) {
            val s = status.status()
            val path = s?.path
            if (path != null) {
                if (path.trimEnd('/').endsWith("/$strmTail")) {
                    opened = true
                } else if (opened) {
                    return done(Result.OtherFile, "player moved on to another file")
                }
            }
            if (opened) {
                val list = subtitles.subtitleList()
                val externals = list?.externals.orEmpty().filter { markers.isScannedFile(it.title) }
                if (externals.size > 1) return done(Result.Ambiguous, "${externals.size} external subtitles listed: $externals")
                val target = externals.singleOrNull()
                if (list != null && target != null) {
                    if (list.currentIndex == target.index) return done(Result.AlreadySelected, "external subtitle ${target.index} selected")
                    val since = listedAt ?: now().also { listedAt = it }
                    if (now() - since >= graceMs) {
                        val ok = subtitles.setSubtitle(target.index) && subtitles.subtitleList()?.currentIndex == target.index
                        return done(
                            if (ok) Result.Corrected else Result.SetFailed,
                            "player had ${list.currentIndex}, set external ${target.index}: ${if (ok) "ok" else "failed"}",
                        )
                    }
                }
            }
            delay(pollMs)
        }
        return done(if (opened) Result.NotListed else Result.NeverOpened, if (opened) "external subtitle never listed" else "player never reported $strmTail")
    }

    private fun done(result: Result, detail: String): Result {
        if (result == Result.AlreadySelected || result == Result.Corrected) ZLog.i(TAG, "subtitle check $result: $detail")
        else ZLog.w(TAG, "subtitle check $result: $detail")
        return result
    }

    private companion object {
        const val TAG = "Subs"
    }
}
