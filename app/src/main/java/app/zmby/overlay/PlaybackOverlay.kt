package app.zmby.overlay

import android.content.Context
import android.view.Gravity
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import app.zmby.emby.BaseItemDto
import app.zmby.log.ZLog
import app.zmby.playback.MonitorOverlay
import app.zmby.segments.SegmentSkipper
import app.zmby.segments.SkipDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch

/** The next episode as the card shows it (ui-spec §2.16 `PlayerNext`). */
data class UpNextCard(
    val episode: BaseItemDto,
    val title: String,
    val imageUrl: String?,
    /** 「服务器 · 音轨语言 · 字幕语言」. */
    val meta: String,
)

/**
 * The playback overlay of one playback (ui-spec §2.16; overlay research: 建议集成路径). Bound to
 * this playback's monitor and so to its `serverId + itemId` and file: [close] runs when the player
 * returns, and [onLost] when it opens another file. The focusable window exists only while a
 * prompt shows; removing it gives the keys back to the player.
 */
class PlaybackOverlay(
    private val context: Context,
    private val scope: CoroutineScope,
    /** The skipper being looked up; the next-episode card starts with its credits. */
    private val skipper: Deferred<SegmentSkipper?>,
    private val upNext: Deferred<UpNextCard?>?,
    /** 自动播放下一集: the card counts down. */
    val countdown: Boolean,
    /** 弹幕 on the passive layer, when on; it sees every sample too. */
    private val danmu: DanmuLayer? = null,
) : MonitorOverlay {
    private val layers: List<MonitorOverlay> = listOfNotNull(danmu)

    /** Set once the monitor exists: seeks through it so the jump is reported to this playback's server. */
    var seek: (suspend (Long) -> Boolean)? = null

    @Volatile var choice: UpNextChoice = UpNextChoice.None
        private set

    val prompt = mutableStateOf<OverlayPrompt?>(null)
    val positionMs = mutableLongStateOf(0L)
    val durationMs = mutableLongStateOf(0L)

    private val plan = OverlayPlan(
        creditsStartMs = { completed(skipper)?.segment(app.zmby.segments.SegmentKind.Credits)?.startMs },
        nextAvailable = { choice == UpNextChoice.None && completed(upNext) != null },
    )
    private val scale = designScale(context)
    private val window = OverlayWindow(
        context,
        overlayParams(focusable = true, gravity = Gravity.BOTTOM or Gravity.END, x = (96 * scale).toInt(), y = (110 * scale).toInt()),
    )
    private var closed = false

    val card: UpNextCard? get() = completed(upNext)

    fun open() {
        OverlayService.start(context)
        danmu?.start()
    }

    override fun onSample(positionMs: Long, durationMs: Long, playing: Boolean) {
        if (closed) return
        if (!OverlayPermission.granted(context)) {
            // Revoked while playing: nothing more is drawn; skipping falls back to nothing asked.
            ZLog.w(TAG, "overlay permission gone; closing")
            return close()
        }
        this.positionMs.longValue = positionMs
        if (durationMs > 0) this.durationMs.longValue = durationMs
        layers.forEach { it.onSample(positionMs, durationMs, playing) }
        if (plan.onSample(positionMs, durationMs)) sync()
    }

    override fun onOffer(offer: SkipDecision.Offer): Boolean {
        if (closed) return false
        // Not shown on purpose (the next-episode card was already offered): still handled here.
        if (!plan.onOffer(offer, positionMs.longValue)) return true
        return sync()
    }

    override fun onSeeked(positionMs: Long) {
        plan.onSeeked(positionMs)
        this.positionMs.longValue = positionMs
        layers.forEach { it.onSeeked(positionMs) }
    }

    override fun onLost() {
        close()
    }

    /** OK on a skip button. */
    fun confirmSkip(skip: OverlayPrompt.Skip) {
        dismiss()
        scope.launch {
            val ok = seek?.invoke(skip.targetMs) ?: false
            if (!ok) ZLog.w(TAG, "skip ${skip.kind} to ${skip.targetMs}ms was refused by the player")
        }
    }

    /** 立即播放, or the countdown ran out: this episode ends now, the next starts when the player returns. */
    fun playNextNow() {
        choice = UpNextChoice.PlayNow
        dismiss()
        val end = durationMs.longValue
        scope.launch {
            val ok = end > 0 && seek?.invoke((end - SegmentSkipper.END_GUARD_MS).coerceAtLeast(0)) == true
            if (!ok) {
                // The player stays on this episode; the usual prompt follows at the end.
                ZLog.w(TAG, "could not end the episode for 立即播放")
                choice = UpNextChoice.Later
            }
        }
    }

    fun watchCredits() {
        choice = UpNextChoice.Later
        dismiss()
    }

    fun cancelNext() {
        choice = UpNextChoice.Cancelled
        dismiss()
    }

    fun dismiss() {
        plan.dismiss()
        sync()
    }

    fun close() {
        if (closed) return
        closed = true
        plan.dismiss()
        prompt.value = null
        window.hide()
        layers.forEach { it.onLost() }
        OverlayService.stop(context)
    }

    /** False when a prompt should show but its window could not be added. */
    private fun sync(): Boolean {
        val p = plan.prompt
        prompt.value = p
        if (p == null) window.hide()
        else if (!window.shown && !window.show { OverlayComposeView(context) { OverlayPrompts(this) } }) {
            // Not allowed or failed: forget the prompt, nothing is asked.
            plan.dismiss()
            prompt.value = null
            return false
        }
        return true
    }

    private companion object {
        const val TAG = "Overlay"

        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        fun <T> completed(d: Deferred<T>?): T? = d?.takeIf { it.isCompleted && !it.isCancelled }?.let { runCatching { it.getCompleted() }.getOrNull() }
    }
}
