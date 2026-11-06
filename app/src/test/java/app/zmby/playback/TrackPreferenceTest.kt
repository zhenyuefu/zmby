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

    @Test fun `original language resolves ISO codes independently of defaults and explicit picks`() {
        val korean = source.copy(mediaStreams = source.mediaStreams + listOf(
            MediaStream(8, "Audio", language = "kor"), MediaStream(9, "Subtitle", language = "ko-KR")))
        val original = TrackPreference.ORIGINAL
        assertEquals(TrackChoice(8, 9), TrackPreference.choose(korean, TrackChoice(), original, original, "ko"))
        assertEquals(TrackChoice(2, 9), TrackPreference.choose(korean, TrackChoice(audio = 2), original, original, "ko"))
        assertEquals(TrackChoice(), TrackPreference.choose(korean, TrackChoice(), original, original))
    }

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

    @Test fun `styled subtitles first only when asked and only within the language`() {
        val fansub = MediaSourceInfo(
            id = "f",
            mediaStreams = listOf(
                MediaStream(0, "Video"),
                MediaStream(1, "Subtitle", language = "chi", codec = "subrip", title = "简体", isDefault = true),
                MediaStream(2, "Subtitle", language = "chi", codec = "ass", title = "简体"),
                MediaStream(3, "Subtitle", language = "chi", codec = "ass", title = "简体特效"),
                MediaStream(4, "Subtitle", language = "eng", codec = "ass", title = "English 特效"),
            ),
        )
        val server = TrackPreference.SERVER
        assertEquals(1, TrackPreference.choose(fansub, TrackChoice(), server, "chi").subtitle)
        assertEquals(3, TrackPreference.choose(fansub, TrackChoice(), server, "chi", preferStyled = true).subtitle)
        // Like the 「特效」 badge: ASS alone is not an effects subtitle, so the server default stays.
        val plainAss = fansub.copy(mediaStreams = fansub.mediaStreams.filter { it.index != 3 })
        assertEquals(1, TrackPreference.choose(plainAss, TrackChoice(), server, "chi", preferStyled = true).subtitle)
        // An external file named …特效.ass counts, as it does for the badge.
        val external = plainAss.copy(mediaStreams = plainAss.mediaStreams + MediaStream(5, "Subtitle", language = "chi", codec = "ass",
            isExternal = true, path = "/m/Film.chs.特效.ass"))
        assertEquals(5, TrackPreference.choose(external, TrackChoice(), server, "chi", preferStyled = true).subtitle)
        // 跟随服务器, 关闭 and a pick are untouched.
        assertNull(TrackPreference.choose(fansub, TrackChoice(), server, server, preferStyled = true).subtitle)
        assertEquals(ZidooTracks.SUBTITLE_OFF, TrackPreference.choose(fansub, TrackChoice(), server, TrackPreference.OFF, preferStyled = true).subtitle)
        assertEquals(1, TrackPreference.choose(fansub, TrackChoice(subtitle = 1), server, "chi", preferStyled = true).subtitle)
    }
}
