package app.zmby.media

import app.zmby.emby.MediaSourceInfo
import app.zmby.emby.MediaStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackBadgesTest {
    private val video = MediaStream(0, "Video", codec = "hevc", width = 3840, height = 2160, videoRange = "HDR", extendedVideoType = "Hdr10")
    private fun audio(i: Int, lang: String, codec: String, title: String? = null, profile: String? = null, default: Boolean = false) =
        MediaStream(i, "Audio", codec = codec, language = lang, title = title, profile = profile, isDefault = default, channels = 6)
    private fun sub(i: Int, lang: String?, title: String? = null, codec: String = "ass", external: Boolean = false, path: String? = null) =
        MediaStream(i, "Subtitle", codec = codec, language = lang, title = title, isExternal = external, path = path)
    private fun source(vararg streams: MediaStream) = MediaSourceInfo("s", container = "mkv", mediaStreams = listOf(video) + streams)

    @Test fun `badges follow every audio track and the Chinese subtitles`() {
        val s = source(
            audio(1, "eng", "truehd", profile = "TrueHD + Atmos", default = true),
            audio(2, "chi", "ac3", title = "国语"),
            audio(3, "chi", "ac3", title = "粤语"),
            sub(4, "chi", "简体"),
            sub(5, "chi", "繁體"),
            sub(6, "chi", "简英双语", external = true),
        )
        assertEquals(listOf("HDR10", "TrueHD Atmos", "AC3", "国语", "粤语", "简繁", "双语"), versionBadges(s))
    }

    @Test fun `an effects subtitle is named by its title or file, not by the ASS codec alone`() {
        assertEquals(listOf("中字"), subtitleBadges(listOf(sub(1, "chi", "Chinese"))))
        assertFalse("特效" in subtitleBadges(listOf(sub(1, "chi", "简体", codec = "ass"))))
        assertTrue("特效" in subtitleBadges(listOf(sub(1, "chi", "简体特效"))))
        assertTrue("特效" in subtitleBadges(listOf(sub(1, null, external = true, path = "/m/Film.2024.chs&eng.effects.ass"))))
        assertEquals(listOf("简中", "双语", "特效"), subtitleBadges(listOf(sub(1, null, external = true, path = "/m/Film.chs&eng.特效.ass"))))
    }

    @Test fun `short tags match whole words only`() {
        assertEquals(TrackFacts.Script.Unknown, TrackFacts.script(sub(1, "chi", "Director's Score Commentary")))
        assertEquals(TrackFacts.Script.Hans, TrackFacts.script(sub(1, "chi", "SC")))
        assertEquals(TrackFacts.Script.Hant, TrackFacts.script(sub(1, "chi", "CHT")))
    }

    @Test fun `an unprobed file shows only its container`() {
        assertEquals(listOf("ISO"), versionBadges(MediaSourceInfo("iso", container = "iso")))
    }

    @Test fun `audio formats are distinct, default first, at most three`() {
        val s = source(audio(1, "eng", "aac"), audio(2, "eng", "dts", profile = "DTS-HD MA", default = true), audio(3, "jpn", "aac"), audio(4, "kor", "flac"), audio(5, "fre", "opus"))
        assertEquals(listOf("HDR10", "DTS-HD MA", "AAC", "FLAC"), versionBadges(s))
    }

    @Test fun `subtitle chips put the current one first, then preferred language, other Chinese, English, the rest`() {
        val s = source(sub(1, "jpn", "日本語", codec = "pgssub"), sub(2, "eng", "English", codec = "subrip"), sub(3, "chi", "繁體", codec = "pgssub"),
            sub(4, "chi", "简体", codec = "pgssub"), sub(5, "kor", "한국어", codec = "pgssub"), sub(6, "chi", "简英特效", external = true))
        val chips = subtitleChips(s, current = 2, preferredLanguage = "zh-CN")
        assertEquals(listOf("English", "简体", "简英特效", "繁體", "日本語", "한국어"), chips.map { it.label })
        assertTrue(chips.first().current)
        assertEquals("ASS · 外挂 · 特效", chips[2].detail)
        assertEquals("字幕关闭", subtitleChips(s, current = -1, preferredLanguage = null).first().label)
    }

    @Test fun `audio summary names the current track and the other languages once`() {
        val s = source(audio(1, "eng", "truehd", profile = "TrueHD + Atmos", default = true), audio(2, "chi", "ac3"), audio(3, "chi", "ac3"), audio(4, "jpn", "ac3"))
        val (first, more) = audioSummary(s, 1)
        assertTrue(first!!.contains("TrueHD Atmos 5.1"))
        assertEquals("另有 3 条音轨：中文 · 日本語", more)
    }
}
