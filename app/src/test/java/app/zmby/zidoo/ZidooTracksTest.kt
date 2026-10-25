package app.zmby.zidoo

import app.zmby.emby.MediaSourceInfo
import app.zmby.emby.MediaStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZidooTracksTest {
    // Video 0, audio 1-3, embedded subs 4-5, external subs 6-7 (Emby indexes).
    private val source = MediaSourceInfo(
        id = "ms",
        mediaStreams = listOf(
            MediaStream(0, type = "Video"),
            MediaStream(1, type = "Audio"),
            MediaStream(2, type = "Audio"),
            MediaStream(3, type = "Audio"),
            MediaStream(4, type = "Subtitle"),
            MediaStream(5, type = "Subtitle"),
            MediaStream(6, type = "Subtitle", isExternal = true),
            MediaStream(7, type = "Subtitle", isExternal = true),
        ),
        defaultAudioStreamIndex = 2,
        defaultSubtitleStreamIndex = -1,
    )

    @Test
    fun audioIsTheZeroBasedPositionAmongAudioTracks() {
        assertEquals(0, ZidooTracks.audioIdx(source, 1))
        assertEquals(2, ZidooTracks.audioIdx(source, 3))
        assertNull(ZidooTracks.audioIdx(source, 4))
    }

    @Test
    fun subtitlesAreOneBasedWithZeroForOffAndExternalsLast() {
        assertEquals(0, ZidooTracks.subtitleIdx(source, ZidooTracks.SUBTITLE_OFF))
        assertEquals(1, ZidooTracks.subtitleIdx(source, 4))
        assertEquals(2, ZidooTracks.subtitleIdx(source, 5))
        assertEquals(3, ZidooTracks.subtitleIdx(source, 6))
        assertEquals(4, ZidooTracks.subtitleIdx(source, 7))
        assertNull(ZidooTracks.subtitleIdx(source, 2))
    }

    @Test
    fun choiceFallsBackToTheServerDefaults() {
        assertEquals(1 to null, ZidooTracks.choose(source, null, null))
        assertEquals(0 to 0, ZidooTracks.choose(source, 1, ZidooTracks.SUBTITLE_OFF))
        assertEquals(1 to 3, ZidooTracks.choose(source, null, 6))
    }

    @Test
    fun externalSubtitlesGoOutAsUriArrayWithNames() {
        val spec = buildZidooLaunch(
            ZidooLaunchRequest(
                url = "http://h/v.mkv", container = "mkv", title = null, startPositionMs = 0,
                audioIdx = 1, subtitleIdx = 3,
                externalSubtitles = listOf(ExternalSubtitle("http://h/s.ass?api_key=t", "简英双语", "v.chi.6.ass")),
            ),
        )
        assertEquals(1, spec.extras["audio_idx"])
        assertEquals(3, spec.extras["subtitle_idx"])
        assertEquals(UriArray(listOf("http://h/s.ass?api_key=t")), spec.extras["subs"])
        assertEquals(listOf("简英双语"), spec.extras["subs.name"])
        assertEquals(listOf("v.chi.6.ass"), spec.extras["subs.filename"])
    }

    @Test
    fun audioIsAlwaysSentAndUnsupportedExternalsAreLeftToThePlayer() {
        val noDefaults = source.copy(defaultAudioStreamIndex = null, defaultSubtitleStreamIndex = 6)
        // Without a server default the first embedded audio track is requested, so the player's own bookmark cannot win.
        assertEquals(0, ZidooTracks.choose(noDefaults, null, null).first)
        // com.zidoo.player cannot load external subtitles from the intent: send no subtitle_idx for them.
        assertEquals(0 to null, ZidooTracks.choose(noDefaults, null, null, externalAllowed = false))
        assertEquals(0 to 2, ZidooTracks.choose(noDefaults, null, 5, externalAllowed = false))
    }

    @Test
    fun logoIsSentOnlyWhenThereIsOne() {
        val base = ZidooLaunchRequest(url = "http://h/v.mkv", container = "mkv", title = null, startPositionMs = 0)
        assertNull(buildZidooLaunch(base).extras["clearlogo_image"])
        assertEquals("http://h/logo", buildZidooLaunch(base.copy(clearLogoUrl = "http://h/logo")).extras["clearlogo_image"])
    }

    @Test
    fun zidooPlayerTakesOnlyTextSubtitlesAsLocalFiles() {
        val zidoo = ZidooPlayer.COMPONENTS.first()
        assertEquals(ZidooPlayer.SubtitleDelivery.LocalStrm, zidoo.subtitles)
        assertEquals(true, zidoo.supportsExternal("subrip"))
        assertEquals(true, zidoo.supportsExternal("ASS"))
        assertEquals(true, zidoo.supportsExternal("ssa"))
        assertEquals(false, zidoo.supportsExternal("pgssub"))
        assertEquals(false, zidoo.supportsExternal("webvtt"))
        assertEquals(true, ZidooPlayer.COMPONENTS.last().supportsExternal("pgssub"))
    }

    @Test
    fun localStrmLaunchHasNoActionAndCarriesThePid() {
        val spec = buildZidooLaunch(
            ZidooLaunchRequest(
                url = "file:///storage/emulated/0/Download/zmby-playback/s1/zmby_k.strm", container = "mkv", title = "T",
                startPositionMs = 60_000, audioIdx = 1, subtitlePid = "0123456789abcdef0123456789abcdef",
            ),
        )
        assertEquals(false, spec.viewAction)
        assertEquals("video/*", spec.mimeType)
        assertEquals("0123456789abcdef0123456789abcdef", spec.extras["subtitlePid"])
        assertEquals(60_000, spec.extras["position"])
        assertEquals(true, spec.extras["return_result"])
        assertNull(spec.extras["subtitle_idx"])
        val http = buildZidooLaunch(ZidooLaunchRequest(url = "http://h/v.mkv", container = "mkv", title = null, startPositionMs = 0))
        assertEquals(true, http.viewAction)
        assertNull(http.extras["subtitlePid"])
    }

    @Test
    fun subtitleListParsesThePlayersFields() {
        val body = """{"status":200,"currentIndex":2,"size":3,"subtitles":[
            {"title":"关闭","laungaue":"","subtitleFormat":"","isExternal":false,"isClose":true,"index":0},
            {"title":"中文","laungaue":"chi","subtitleFormat":"SRT","isExternal":false,"isClose":false,"index":1},
            {"title":"zmby_k.chi.6","laungaue":"chi","subtitleFormat":"SRT","isExternal":true,"isClose":false,"index":2}]}"""
        val list = ZidooSubtitleList.parse(body)!!
        assertEquals(2, list.currentIndex)
        assertEquals(listOf(2), list.externals.map { it.index })
        assertEquals("chi", list.items[1].language)
        assertNull(ZidooSubtitleList.parse("""{"status":806}"""))
    }
}
