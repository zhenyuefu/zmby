package app.zmby.playback

import app.zmby.emby.BaseItemDto
import app.zmby.emby.MediaSourceInfo
import app.zmby.emby.MediaStream
import app.zmby.zidoo.ZidooResult
import app.zmby.zidoo.ZidooTracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FailoverTest {
    @Test fun `a quick exit without the video ever opening is a failed start`() {
        assertTrue(Failover.failedToStart(4_000, sawVideo = false, result = null))
        assertTrue(Failover.failedToStart(4_000, sawVideo = false, result = ZidooResult(0, null, "user")))
        // The video opened: the user backed out, whatever the time.
        assertFalse(Failover.failedToStart(4_000, sawVideo = true, result = ZidooResult(null, null, "user")))
        // Too late to be a start, or it played somewhere.
        assertFalse(Failover.failedToStart(Failover.START_WINDOW_MS, sawVideo = false, result = null))
        assertFalse(Failover.failedToStart(4_000, sawVideo = false, result = ZidooResult(30_000, null, "user")))
        assertFalse(Failover.failedToStart(4_000, sawVideo = false, result = ZidooResult(null, 100, "playback_completion")))
    }

    @Test fun `versions are tried in order, each once`() {
        val item = BaseItemDto(id = "1")
        val a = Alternate("s1", item, "m1", "A · 4K")
        val b = Alternate("s2", item, "m2", "B · 1080p")
        val tried = mutableSetOf(Failover.key("s0", "m0"))
        assertEquals(a, Failover.next(listOf(a, b), tried)); tried += Failover.key(a)
        assertEquals(b, Failover.next(listOf(a, b), tried)); tried += Failover.key(b)
        assertNull(Failover.next(listOf(a, b), tried))
    }

    @Test fun `languages carry over to the next episode or version, not stream indexes`() {
        val source = MediaSourceInfo(
            id = "m", defaultAudioStreamIndex = 1, defaultSubtitleStreamIndex = 3,
            mediaStreams = listOf(
                MediaStream(1, "Audio", language = "eng"), MediaStream(2, "Audio", language = "chi"),
                MediaStream(3, "Subtitle", language = "chi", title = "简体"), MediaStream(4, "Subtitle", language = "fre"),
            ),
        )
        assertEquals(TrackLanguages("chi", "chi"), TrackPreference.carried(source, TrackChoice(audio = 2), "", ""))
        assertEquals(TrackLanguages("eng", "fre"), TrackPreference.carried(source, TrackChoice(subtitle = 4), "", ""))
        assertEquals(TrackLanguages("eng", TrackPreference.OFF), TrackPreference.carried(source, TrackChoice(subtitle = ZidooTracks.SUBTITLE_OFF), "", ""))
        val bare = MediaSourceInfo(id = "n")
        assertEquals(TrackLanguages("jpn", ""), TrackPreference.carried(bare, TrackChoice(), "jpn", ""))
    }
}
