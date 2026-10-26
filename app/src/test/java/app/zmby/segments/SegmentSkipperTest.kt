package app.zmby.segments

import app.zmby.data.SegmentProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SegmentSkipperTest {
    private val d = 2_700_000L
    private val intro = Segment(SegmentKind.Intro, 60_000, 150_000)
    private val credits = Segment(SegmentKind.Credits, 2_600_000, null)
    private fun skipper(intro: Boolean = true, end: Boolean = true) = SegmentSkipper(listOf(this.intro, credits), skipIntro = intro, skipRecap = true, skipEnd = end)

    @Test fun `playback running into the intro jumps to its end once`() {
        val s = skipper()
        assertNull(s.onSample(58_000, 59_000, d))
        assertEquals(150_000L, s.onSample(59_000, 60_500, d))
        // The user went back to watch it: not skipped again.
        assertNull(s.onSample(140_000, 61_000, d))
        assertNull(s.onSample(61_000, 62_000, d))
    }

    @Test fun `seeking into the intro is the user's choice and is not undone`() {
        val s = skipper()
        assertNull(s.onSample(10_000, 100_000, d))
        assertNull(s.onSample(100_000, 101_000, d))
    }

    @Test fun `a playback that starts inside the intro skips it, one resumed in the credits does not`() {
        assertEquals(150_000L, skipper().onSample(null, 70_000, d))
        assertNull(skipper().onSample(null, 2_650_000, d))
    }

    @Test fun `credits running to the end jump close to the end so the player finishes`() {
        assertEquals(d - SegmentSkipper.END_GUARD_MS, skipper().onSample(2_599_000, 2_600_200, d))
    }

    @Test fun `credits followed by a scene jump to the credits end`() {
        val s = SegmentSkipper(listOf(Segment(SegmentKind.Credits, 2_400_000, 2_500_000)), skipIntro = true, skipRecap = true, skipEnd = true)
        assertEquals(2_500_000L, s.onSample(2_399_500, 2_400_400, d))
    }

    @Test fun `switches and the episodes-only credits setting`() {
        assertNull(skipper(intro = false).onSample(59_000, 60_500, d))
        assertNull(skipper(end = false).onSample(2_599_000, 2_600_200, d))
        val plan = mapOf(SegmentKind.Credits to (SegmentProvider.TheIntroDB to credits))
        assertNull(SegmentSkipper.of(plan, episode = false, intro = true, recap = true, credits = CreditsSkip.Episodes).onSample(2_599_000, 2_600_200, d))
        assertEquals(d - SegmentSkipper.END_GUARD_MS, SegmentSkipper.of(plan, episode = true, intro = true, recap = true, credits = CreditsSkip.Episodes).onSample(2_599_000, 2_600_200, d))
        assertEquals(d - SegmentSkipper.END_GUARD_MS, SegmentSkipper.of(plan, episode = false, intro = true, recap = true, credits = CreditsSkip.All).onSample(2_599_000, 2_600_200, d))
    }

    @Test fun `intro and recap can each be skipped or preserved independently`() {
        val recap = Segment(SegmentKind.Recap, 0, 40_000)
        val plan = mapOf(
            SegmentKind.Intro to (SegmentProvider.TheIntroDB to intro),
            SegmentKind.Recap to (SegmentProvider.TheIntroDB to recap),
        )
        for (skipIntro in listOf(false, true)) for (skipRecap in listOf(false, true)) {
            val s = SegmentSkipper.of(plan, episode = true, intro = skipIntro, recap = skipRecap, credits = CreditsSkip.Off)
            assertEquals(if (skipRecap) 40_000L else null, s.onSample(null, 1_000, d))
            assertEquals(if (skipIntro) 150_000L else null, s.onSample(59_000, 60_500, d))
        }
    }

    @Test fun `a jump too short to matter is not made`() {
        val s = SegmentSkipper(listOf(Segment(SegmentKind.Intro, 0, 60_000)), skipIntro = true, skipRecap = true, skipEnd = true)
        assertNull(s.onSample(58_500, 59_000, d))
    }
}
