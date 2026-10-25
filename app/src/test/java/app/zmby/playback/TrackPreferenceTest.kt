package app.zmby.playback

import app.zmby.emby.MediaSourceInfo
import app.zmby.emby.MediaStream
import app.zmby.zidoo.ZidooTracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackPreferenceTest {
    private val source = MediaSourceInfo(
        id = "s",
        mediaStreams = listOf(
            MediaStream(0, "Video"),
            MediaStream(1, "Audio", language = "eng", isDefault = true),
            MediaStream(2, "Audio", language = "chi", title = "国语"),
            MediaStream(3, "Audio", language = "chi", title = "粤语", isDefault = false),
            MediaStream(4, "Subtitle", language = "chi", codec = "pgssub", isForced = true),
            MediaStream(5, "Subtitle", language = "chi", codec = "pgssub"),
            MediaStream(6, "Subtitle", language = "eng", codec = "pgssub", isExternal = true),
            MediaStream(7, "Subtitle", language = "eng", codec = "subrip", isExternal = true),
        ),
    )

    @Test fun `follow the server leaves both unset`() {
        assertEquals(TrackChoice(), TrackPreference.choose(source, TrackChoice(), TrackPreference.SERVER, TrackPreference.SERVER))
    }

    @Test fun `language picks the first matching track`() {
        val c = TrackPreference.choose(source, TrackChoice(), "chi", "chi")
        assertEquals(2, c.audio)
        // The forced Chinese track is skipped: it only covers foreign dialogue.
        assertEquals(5, c.subtitle)
    }

    @Test fun `external subtitles only when the player can load them`() {
        assertEquals(7, TrackPreference.choose(source, TrackChoice(), TrackPreference.SERVER, "eng").subtitle)
    }

    @Test fun `off turns subtitles off and a pick always wins`() {
        assertEquals(ZidooTracks.SUBTITLE_OFF, TrackPreference.choose(source, TrackChoice(), TrackPreference.SERVER, TrackPreference.OFF).subtitle)
        assertEquals(TrackChoice(3, 6), TrackPreference.choose(source, TrackChoice(3, 6), "eng", TrackPreference.OFF))
    }

    @Test fun `missing language falls back to the server`() {
        val c = TrackPreference.choose(source, TrackChoice(), "jpn", "jpn")
        assertNull(c.audio)
        assertNull(c.subtitle)
    }
}
