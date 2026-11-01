package app.zmby.overlay

import app.zmby.segments.SegmentKind
import app.zmby.segments.SegmentSkipper
import app.zmby.segments.SkipDecision

/** What the overlay's focusable window shows (ui-spec §2.16); null: nothing, the player has every key. */
sealed interface OverlayPrompt {
    /** 「跳过片头 / 回顾 / 片尾」: OK seeks to [targetMs]. */
    data class Skip(val kind: SegmentKind, val targetMs: Long, val untilMs: Long?, val shownAtMs: Long) : OverlayPrompt

    /** The next-episode card. */
    data object Next : OverlayPrompt
}

/** What the user chose on the next-episode card; read when the playback ends. */
enum class UpNextChoice {
    /** Nothing shown or chosen: the usual prompt after playback. */
    None,
    /** 立即播放 (or the countdown ran out): the next episode plays as soon as this one ends. */
    PlayNow,
    /** 看完片尾: the usual prompt after playback, once more. */
    Later,
    /** 返回: no countdown at the end, the user picks. */
    Cancelled,
}

/**
 * Pure rules for when the overlay asks something (ui-spec §2.16; overlay research: the window that
 * asks takes the keys, so it shows only when needed and goes away by itself):
 * - a skip button shows when [SegmentSkipper] offers it, and goes once playback leaves the segment
 *   (it ended, or the user seeked out of it);
 * - the next-episode card shows once per playback, when playback runs into the credits or, without
 *   known credits, into the last [NEXT_BEFORE_END_MS]; it replaces a 跳过片尾 button.
 */
class OverlayPlan(
    /** The credits' start, once the segment lookup finished. */
    private val creditsStartMs: () -> Long?,
    /** A next episode is known for this playback (Up Next is on and the lookup found one). */
    private val nextAvailable: () -> Boolean,
) {
    var prompt: OverlayPrompt? = null
        private set
    private var nextOffered = false
    private var previous: Long? = null

    /** True when [prompt] changed. */
    fun onSample(positionMs: Long, durationMs: Long): Boolean {
        val prev = previous
        previous = positionMs
        val before = prompt
        (prompt as? OverlayPrompt.Skip)?.let { skip ->
            val passed = skip.untilMs != null && positionMs >= skip.untilMs
            val left = positionMs + SegmentSkipper.NATURAL_STEP_MS < skip.shownAtMs
            if (passed || left || positionMs >= skip.targetMs) prompt = null
        }
        if (!nextOffered && nextAvailable() && durationMs > 0 && prev != null && ranInto(prev, positionMs)) {
            val credits = creditsStartMs()
            val start = credits ?: (durationMs - NEXT_BEFORE_END_MS)
            if (positionMs >= start && prev < start + SegmentSkipper.NATURAL_STEP_MS && positionMs < durationMs - SegmentSkipper.END_GUARD_MS) {
                nextOffered = true
                prompt = OverlayPrompt.Next
            }
        }
        return prompt != before
    }

    /** True when [prompt] changed. */
    fun onOffer(offer: SkipDecision.Offer, atMs: Long): Boolean {
        if (prompt is OverlayPrompt.Next) return false
        val end = offer.kind == SegmentKind.Credits || offer.kind == SegmentKind.Preview
        if (end && nextAvailable()) {
            if (nextOffered) return false
            nextOffered = true
            prompt = OverlayPrompt.Next
            return true
        }
        prompt = OverlayPrompt.Skip(offer.kind, offer.targetMs, offer.untilMs, atMs)
        return true
    }

    /** zmby or the user moved the position: a later sample must not look like playback running on. */
    fun onSeeked(positionMs: Long) {
        previous = positionMs
    }

    fun dismiss() {
        prompt = null
    }

    private fun ranInto(prev: Long, now: Long) = prev <= now && now - prev <= SegmentSkipper.NATURAL_STEP_MS

    companion object {
        /** Without known credits the card shows this long before the end. */
        const val NEXT_BEFORE_END_MS = 30_000L
        /** The skip button goes away by itself. */
        const val SKIP_SECONDS = 8
        /** The next-episode card plays the next episode after this, when 自动播放下一集 is on. */
        const val NEXT_SECONDS = 7
    }
}
