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
    @Test fun `only an explicit start error allows automatic failover`() {
        assertFalse(Failover.failedToStart(sawVideo = false, result = null))
        assertFalse(Failover.failedToStart(sawVideo = false, result = ZidooResult(0, null, "user")))
        assertTrue(Failover.failedToStart(sawVideo = false, result = ZidooResult(0, null, "playback_error")))
        // The video opened: the user backed out, whatever the time.
        assertFalse(Failover.failedToStart(sawVideo = true, result = ZidooResult(null, null, "user")))
        // Too late to be a start, or it played somewhere.
        assertFalse(Failover.failedToStart(sawVideo = false, result = null))
        assertFalse(Failover.failedToStart(sawVideo = false, result = ZidooResult(30_000, null, "user")))
        assertFalse(Failover.failedToStart(sawVideo = false, result = ZidooResult(null, 100, "playback_completion")))
    }

    @Test fun `relay return policy never retries a user exit completion or missing status automatically`() {
        for (sawVideo in listOf(false, true)) {
            for (end in listOf("user", "playback_completion", "unknown")) {
                assertEquals(RelayReturnAction.Finish, relayReturnAction(true, sawVideo, ZidooResult(0, null, end)))
            }
            assertEquals(RelayReturnAction.Retry, relayReturnAction(true, sawVideo, ZidooResult(50_000, null, "playback_error")))
            assertEquals(RelayReturnAction.Finish, relayReturnAction(false, sawVideo, ZidooResult(0, null, "playback_error")))
        }
        assertEquals(RelayReturnAction.OfferRetry, relayReturnAction(true, false, null))
        assertEquals(RelayReturnAction.Finish, relayReturnAction(true, true, null))
        assertEquals(RelayReturnAction.Finish, relayReturnAction(true, false, ZidooResult(1, null, null)))
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
