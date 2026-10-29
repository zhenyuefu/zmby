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
        MediaStream(i, "Audio", codec = codec, language = lang, title = title, profile = profile, isDefault = default, channels = 6, channelLayout = "5.1")
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
        assertEquals(listOf("HDR10", "DTS-HD MA", "AAC", "FLAC", "+1"), versionBadges(s))
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

    @Test fun `generic Chinese is not necessarily Mandarin and dialect labels follow the actual audio`() {
        assertEquals("中文", TrackFacts.audioLanguage(audio(1, " ZHO ", "aac")))
        assertEquals("中文", TrackFacts.audioLanguage(audio(1, "zh-Hans-CN", "aac")))
        assertEquals("国语", TrackFacts.audioLanguage(audio(1, "cmn-Hans", "aac")))
        assertEquals("国语", TrackFacts.audioLanguage(audio(1, "chi", "aac", title = "普通話")))
        assertEquals("粤语", TrackFacts.audioLanguage(audio(1, "chi", "aac", title = "粵語")))
        assertEquals(null, TrackFacts.audioLanguage(audio(1, "eng", "aac", title = "Cantoneseology")))
        val s = source(audio(1, "chi", "aac"), audio(2, "chi", "ac3", title = "粤语"), audio(3, "cmn", "ac3"))
        assertEquals(listOf("中文", "粤语", "国语"), audioRows(s).map { it.language })
        assertEquals(listOf("HDR10", "AAC", "AC3", "国语", "粤语", "中文"), versionBadges(s))
    }

    @Test fun `Chinese subtitle scripts may coexist in one track and missing language uses explicit script tags`() {
        assertEquals(listOf("简繁", "双语"), subtitleBadges(listOf(sub(1, "chi", "简繁双语"))))
        assertEquals(TrackFacts.Script.Both, TrackFacts.script(sub(1, "chi", "簡繁")))
        for (tag in listOf("SC", "CHS", "GB", "Hans", "Simplified")) {
            assertEquals(tag, listOf("简中"), subtitleBadges(listOf(sub(1, null, tag))))
        }
        assertEquals(listOf("繁中"), subtitleBadges(listOf(sub(1, "zh_Hant_TW"))))
        assertEquals(listOf("简中"), subtitleBadges(listOf(sub(1, "zh-Hans-CN"))))
    }

    @Test fun `file names contribute metadata tags without using film titles as language evidence`() {
        assertEquals(emptyList<String>(), subtitleBadges(listOf(sub(1, "eng", path = "/m/中国故事.eng.srt"))))
        assertEquals(emptyList<String>(), subtitleBadges(listOf(sub(1, null, path = "/m/中国故事.srt"))))
        assertEquals(emptyList<String>(), subtitleBadges(listOf(sub(1, "eng", path = "/m/Film.chs.srt"))))
        assertEquals(emptyList<String>(), subtitleBadges(listOf(sub(1, null, "Chineseology SpecialEffects"))))
        assertEquals(listOf("简中"), subtitleBadges(listOf(sub(1, null, path = "/m/Film.SC.srt"))))
        assertEquals(listOf("简中", "双语", "特效"), subtitleBadges(listOf(sub(1, null, path = "C:\\m\\Film.[简英特效].ass"))))
        assertEquals(listOf("特效"), subtitleBadges(listOf(sub(1, "eng", path = "/m/Film.eng.effects.ass"))))
        for (tag in listOf("chs.eng", "chs_eng", "chseng", "chs&eng")) {
            assertEquals(tag, listOf("简中", "双语"), subtitleBadges(listOf(sub(1, null, path = "/m/Film.$tag.ass"))))
        }
        assertEquals(listOf("双语"), subtitleBadges(listOf(sub(1, "eng", "English Japanese Bilingual"))))
        assertEquals(listOf("中字"), subtitleBadges(listOf(sub(1, null, path = "/m/Film.zh.srt"))))
        assertEquals(listOf("繁中"), subtitleBadges(listOf(sub(1, null, path = "/m/Film.zh-Hant-TW.srt"))))
    }

    @Test fun `subtitle status tags coexist in expanded rows and summary chips`() {
        val s = source(sub(1, "chi", "简体特效", external = true).copy(isDefault = true, isForced = true))
        val row = subtitleRows(s).single()
        assertEquals("ASS", row.format)
        assertEquals("默认 · 外挂 · 强制 · 特效", row.tag)
        assertEquals("ASS · 默认 · 外挂 · 强制 · 特效", subtitleChips(s, 1, null).single().detail)
        val byIndex = s.copy(defaultSubtitleStreamIndex = 1, mediaStreams = s.mediaStreams.map { it.copy(isDefault = false) })
        assertEquals(row.tag, subtitleRows(byIndex).single().tag)
    }

    @Test fun `audio summary follows the selected track and falls back to the server default`() {
        val s = source(audio(1, "eng", "aac"), audio(2, "chi", "truehd", profile = "Atmos", default = true))
        assertEquals("English · AAC 5.1", audioSummary(s, 1).first)
        assertEquals("中文 · TrueHD Atmos 5.1", audioSummary(s, 2).first)
        assertEquals(audioSummary(s, 2), audioSummary(s, null))
        assertEquals(audioSummary(s, 2), audioSummary(s, 999))
    }

    @Test fun `both-script subtitles satisfy either Chinese script preference`() {
        val s = source(sub(1, "eng", "English"), sub(2, "chi", "简繁双语"), sub(3, "chi", "繁體"))
        assertEquals(listOf("字幕关闭", "简繁双语", "繁體", "English"), subtitleChips(s, -1, "zh-TW").map { it.label })
    }

    @Test fun `missing codec information is omitted without hiding known track details or adding separators`() {
        for (codec in listOf(null, "", "  ", "?", "？")) {
            val track = MediaStream(1, "Audio", language = "eng", codec = codec)
            val s = source(track)
            assertEquals(null, audioFormat(track))
            assertEquals(listOf("HDR10"), versionBadges(s))
            assertEquals("", audioRows(s).single().format)
            assertEquals("English", audioSummary(s, 1).first)
            assertEquals("48 kHz", audioRows(source(track.copy(sampleRate = 48000))).single().format)
            assertEquals("English · 2.0", audioSummary(source(track.copy(channels = 2)), 1).first)
            val subtitles = source(sub(2, "chi", "简体", external = true).copy(codec = codec))
            assertEquals("", subtitleRows(subtitles).single().format)
            assertEquals("外挂", subtitleChips(subtitles, 2, null).single().detail)
        }
        // Missing formats neither consume one of the three visible slots nor inflate the overflow count.
        val s = source(MediaStream(1, "Audio", isDefault = true), audio(2, "eng", "aac"), audio(3, "eng", "flac"), audio(4, "eng", "opus"))
        assertEquals(listOf("HDR10", "AAC", "FLAC", "Opus"), versionBadges(s))
        assertEquals("VORBIS", audioFormat(MediaStream(1, "Audio", codec = "vorbis")))
    }

    @Test fun `playback info track with only ten channels retains its count without inventing a codec or layout`() {
        val s = source(MediaStream(1, "Audio", language = "chi", channels = 10, displayTitle = "Chinese 10 ch"),
            audio(2, "chi", "eac3"))
        assertEquals("10ch", audioRows(s).first().format)
        assertEquals("中文 · 10ch", audioSummary(s, 1).first)
        assertEquals(listOf("HDR10", "DDP", "中文"), versionBadges(s))
    }
}
