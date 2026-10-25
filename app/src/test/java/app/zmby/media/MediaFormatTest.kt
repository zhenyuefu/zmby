package app.zmby.media

import app.zmby.emby.MediaSourceInfo
import app.zmby.emby.MediaStream
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaFormatTest {

    private fun video(w: Int?, h: Int?, ext: String? = null, sub: String? = null, range: String? = null) =
        MediaStream(index = 0, type = "Video", codec = "hevc", width = w, height = h, extendedVideoType = ext, extendedVideoSubType = sub, videoRangeType = range)

    private fun source(id: String, vararg streams: MediaStream, path: String? = null, container: String = "mkv", bitrate: Long? = null) =
        MediaSourceInfo(id = id, path = path, container = container, bitrate = bitrate, mediaStreams = streams.toList())

    @Test
    fun tiersFollowTheSpecThresholds() {
        assertEquals(QualityTier.UHD, qualityTier(source("a", video(3840, 1600))))
        assertEquals(QualityTier.UHD, qualityTier(source("b", video(3200, 1340))))
        assertEquals(QualityTier.FHD, qualityTier(source("c", video(1920, 800))))
        assertEquals(QualityTier.OTHER, qualityTier(source("d", video(1280, 720))))
    }

    @Test
    fun discImagesFallBackToTheFileName() {
        assertEquals(QualityTier.UHD, qualityTier(source("a", path = "/m/Dune.2160p.UHD.iso", container = "iso")))
        assertEquals(QualityTier.FHD, qualityTier(source("b", path = "/m/Dune.1080p.BluRay.iso", container = "iso")))
        assertEquals(QualityTier.OTHER, qualityTier(source("c", path = "/m/Dune.iso", container = "iso")))
    }

    @Test
    fun badges() {
        assertEquals("DV P7", hdrBadge(video(3840, 2160, ext = "DolbyVision", sub = "DoviProfile76")))
        assertEquals("HDR10+", hdrBadge(video(3840, 2160, ext = "Hdr10Plus")))
        assertEquals(null, hdrBadge(video(1920, 1080)))
        val atmos = MediaStream(index = 1, type = "Audio", codec = "truehd", profile = "TrueHD + Atmos", channels = 8)
        assertEquals("TrueHD Atmos", audioFormat(atmos))
        assertEquals("DTS-HD MA", audioFormat(MediaStream(index = 2, type = "Audio", codec = "dts", profile = "DTS-HD MA")))
        assertEquals("7.1", channelLabel(atmos))
    }

    @Test
    fun versionTitleAndOrder() {
        val remux = source("r", video(3840, 2160), path = "/m/Dune.2160p.REMUX.mkv", bitrate = 68_000_000)
        val web = source("w", video(3840, 2160), path = "/m/Dune.2160p.WEB-DL.mkv", bitrate = 24_000_000)
        val iso = source("i", path = "/m/Dune.2160p.iso", container = "iso", bitrate = 90_000_000)
        val hd = source("h", video(1920, 1080), path = "/m/Dune.1080p.mkv", bitrate = 10_000_000)
        assertEquals("2160p REMUX", versionTitle(remux))
        assertEquals(listOf("r", "w", "i", "h"), listOf(hd, iso, web, remux).sortedWith(versionOrder).map { it.id })
    }

    @Test
    fun durations() {
        assertEquals("1:12:08", formatClock(4_328_000))
        assertEquals("2 小时 46 分", formatDuration(9_972_000))
        assertEquals("49 分钟", formatDuration(2_940_000))
        assertEquals("58.2 GB", formatSize(62_491_774_157))
    }

    @Test
    fun unprobedSourcesShowFileNameAndContainer() {
        val src = app.zmby.emby.MediaSourceInfo(id = "x", path = "/data/new/Some.Film.2025.1080p.WEB-DL.mkv", container = "mkv")
        assertEquals("Some.Film.2025.1080p.WEB-DL", versionTitle(src))
        assertEquals(listOf("MKV"), versionBadges(src))
        assertEquals(QualityTier.FHD, qualityTier(src))
    }

    @Test
    fun progressFallsBackToPlayedPercentageWithoutRuntime() {
        val item = app.zmby.emby.BaseItemDto(
            id = "i",
            userData = app.zmby.emby.UserItemData(playbackPositionTicks = 10_000_000, playedPercentage = 40.0),
        )
        assertEquals(0.4f, progressFraction(item), 0.001f)
        assertEquals(null, remainingLabel(item))
    }
}
