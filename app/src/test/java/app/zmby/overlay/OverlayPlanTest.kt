package app.zmby.overlay

import app.zmby.segments.SegmentKind
import app.zmby.segments.SkipDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayPlanTest {
    private val d = 2_700_000L

    private fun plan(credits: Long? = 2_600_000, next: Boolean = true) = OverlayPlan({ credits }, { next })

    @Test fun `a skip button goes once playback leaves the segment or reaches its end`() {
        val p = plan(next = false)
        assertTrue(p.onOffer(SkipDecision.Offer(SegmentKind.Intro, 150_000, 150_000), 60_000))
        assertTrue(p.prompt is OverlayPrompt.Skip)
        assertFalse(p.onSample(70_000, d))
        assertTrue(p.onSample(150_500, d))
        assertNull(p.prompt)

        p.onOffer(SkipDecision.Offer(SegmentKind.Recap, 40_000, 40_000), 10_000)
        // The user seeked back before the segment: the button is not theirs any more.
        assertTrue(p.onSample(1_000, d))
        assertNull(p.prompt)
    }

    @Test fun `the next-episode card shows once when playback runs into the credits`() {
        val p = plan()
        p.onSample(2_598_000, d)
        assertTrue(p.onSample(2_600_500, d))
        assertEquals(OverlayPrompt.Next, p.prompt)
        p.dismiss()
        p.onSample(2_601_500, d)
        assertNull(p.prompt)
        // Going back and running into the credits again does not ask twice.
        p.onSeeked(2_590_000)
        p.onSample(2_599_000, d)
        p.onSample(2_600_100, d)
        assertNull(p.prompt)
    }

    @Test fun `seeking into the credits does not show the card`() {
        val p = plan()
        p.onSample(1_000_000, d)
        p.onSample(2_650_000, d)
        assertNull(p.prompt)
    }

    @Test fun `without known credits the card shows in the last 30 seconds`() {
        val p = plan(credits = null)
        p.onSample(d - 31_000, d)
        assertNull(p.prompt)
        p.onSample(d - 29_500, d)
        assertEquals(OverlayPrompt.Next, p.prompt)
    }

    @Test fun `a credits button becomes the next-episode card when a next episode exists`() {
        val withNext = plan(credits = null)
        assertTrue(withNext.onOffer(SkipDecision.Offer(SegmentKind.Credits, d - 1_500, null), 2_600_000))
        assertEquals(OverlayPrompt.Next, withNext.prompt)

        val film = plan(next = false)
        film.onOffer(SkipDecision.Offer(SegmentKind.Credits, d - 1_500, null), 2_600_000)
        assertTrue(film.prompt is OverlayPrompt.Skip)
    }

    @Test fun `no card without a next episode`() {
        val p = plan(next = false)
        p.onSample(2_598_000, d)
        p.onSample(2_600_500, d)
        assertNull(p.prompt)
    }
}
