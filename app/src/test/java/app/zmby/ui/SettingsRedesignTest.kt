package app.zmby.ui

import app.zmby.aggregate.VersionPrefs
import app.zmby.data.CodecAvoid
import app.zmby.data.HdrFormat
import app.zmby.data.ImaxMode
import app.zmby.data.QualityPreference
import app.zmby.data.SegmentProvider
import app.zmby.emby.MediaSourceInfo
import app.zmby.emby.MediaStream
import app.zmby.segments.Segment
import app.zmby.segments.SegmentKind
import app.zmby.segments.SegmentLookup
import app.zmby.ui.screens.BadgeFit
import app.zmby.ui.screens.DanmuLook
import app.zmby.ui.screens.PreviewBadge
import app.zmby.ui.screens.danmuLookLabel
import app.zmby.ui.screens.inheritedMark
import app.zmby.ui.screens.moved
import app.zmby.ui.screens.previewBadges
import app.zmby.ui.screens.versionSummary
import app.zmby.ui.screens.segmentSourceRows
import app.zmby.ui.screens.cardInherited
import app.zmby.ui.screens.textSettingValue
import app.zmby.ui.screens.versionPatternError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ui-spec §2.20–2.22: text rows, the 版本偏好 summary, preview badges and the 片头片尾 panel's sources. */
class SettingsRedesignTest {
    @Test fun `text rows show unset, masked or the value`() {
        assertEquals("未设置", textSettingValue("", secret = false))
        assertEquals("未设置", textSettingValue(" ", secret = true))
        assertEquals("已设置 · ••••••••", textSettingValue("token", secret = true))
        assertEquals("2160p.*REMUX", textSettingValue("2160p.*REMUX", secret = false))
    }

    @Test fun `version pattern errors only for an invalid pattern`() {
        assertNull(versionPatternError(""))
        assertNull(versionPatternError("2160p.*REMUX"))
        assertEquals("这个正则无效，请检查括号与转义", versionPatternError("2160p.*(REMUX"))
    }

    @Test fun `quality summary names resolution, first hdr, imax and the cap`() {
        assertEquals("自动 · 杜比视界", versionSummary(VersionPrefs()))
        assertEquals("4K · 杜比视界 · IMAX · ≤ 80 Mbps", versionSummary(VersionPrefs(quality = QualityPreference.UHD, imax = ImaxMode.Prefer, bitrateCapMbps = 80)))
        assertEquals("1080p · SDR", versionSummary(VersionPrefs(quality = QualityPreference.FHD, hdrOrder = HdrFormat.parse("SDR"))))
        assertEquals("2160p.*REMUX · 自动 · 杜比视界", versionSummary(VersionPrefs(pattern = VersionPrefs.pattern(" 2160p.*REMUX "))))
    }

    @Test fun `moving a ranked row stays within the list`() {
        val order = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), moved(order, "b", -1))
        assertEquals(listOf("a", "c", "b"), moved(order, "b", 1))
        assertEquals(order, moved(order, "a", -1))
        assertEquals(order, moved(order, "c", 1))
    }

    @Test fun `preview badges read hit, neutral and miss`() {
        val prefs = VersionPrefs(quality = QualityPreference.UHD, bitrateCapMbps = 80, codecAvoid = CodecAvoid.AV1, imax = ImaxMode.Prefer)
        val dv = MediaSourceInfo("a", path = "/m/a.mkv", container = "mkv", bitrate = 68_000_000,
            mediaStreams = listOf(MediaStream(0, "Video", codec = "hevc", width = 3840, height = 2160, extendedVideoType = "DolbyVision")))
        assertEquals(
            listOf(PreviewBadge("2160p", BadgeFit.Hit), PreviewBadge("杜比视界", BadgeFit.Hit), PreviewBadge("IMAX", BadgeFit.Hit), PreviewBadge("68 Mbps", BadgeFit.Neutral)),
            previewBadges(dv, prefs, imax = true),
        )
        val av1 = MediaSourceInfo("b", path = "/m/b.mkv", container = "mkv", bitrate = 98_000_000,
            mediaStreams = listOf(MediaStream(0, "Video", codec = "av1", width = 1920, height = 1080)))
        assertEquals(
            listOf(PreviewBadge("1080p", BadgeFit.Miss), PreviewBadge("SDR", BadgeFit.Neutral), PreviewBadge("AV1", BadgeFit.Miss), PreviewBadge("98 Mbps", BadgeFit.Miss)),
            previewBadges(av1, prefs, imax = false),
        )
        val named = prefs.copy(pattern = VersionPrefs.pattern("/a\\.mkv"))
        assertEquals(PreviewBadge("匹配", BadgeFit.Hit), previewBadges(dv, named, imax = true).first())
        assertEquals("1080p", previewBadges(av1, named, imax = false).first().text)
    }

    @Test fun `danmu preview tag`() {
        assertEquals("3 行 · 30 px · 75%", danmuLookLabel(DanmuLook(3, 30, 4, 12, 75)))
    }

    @Test fun `segment sources skip manual marks and say when a cache is used`() {
        val lookup = SegmentLookup(
            answers = linkedMapOf(
                SegmentProvider.Local to listOf(Segment(SegmentKind.Intro, 0, 92_000)),
                SegmentProvider.Emby to listOf(Segment(SegmentKind.Credits, 2_530_000, null), Segment(SegmentKind.Intro, 0, 90_000)),
                SegmentProvider.IntroDB to emptyList(),
                SegmentProvider.TheIntroDB to listOf(Segment(SegmentKind.Intro, 2_000, 91_000)),
            ),
            unavailable = setOf(SegmentProvider.TheIntroDB, SegmentProvider.PublicMetaDB),
        )
        val rows = segmentSourceRows(lookup)
        assertEquals(listOf("服务器", "TheIntroDB", "PublicMetaDB"), rows.map { it.name })
        assertEquals("片头 0:00–1:30　　片尾 42:10–结束", rows[0].text)
        assertEquals("无法连接 · 使用缓存", rows[1].text)
        assertEquals("无法连接", rows[2].text)
        assertEquals("服务器" to 90_000L, inheritedMark(lookup, SegmentKind.Intro))
        assertEquals("服务器" to 2_530_000L, inheritedMark(lookup, SegmentKind.Credits))
        assertNull(inheritedMark(SegmentLookup(), SegmentKind.Intro))
        // Under 本集 the season's manual mark is what a card without its own time inherits.
        assertEquals("本季" to 85_000L, cardInherited(lookup, SegmentKind.Intro, 85_000, seasonScope = false))
        assertEquals("服务器" to 90_000L, cardInherited(lookup, SegmentKind.Intro, 85_000, seasonScope = true))
        assertEquals("服务器" to 2_530_000L, cardInherited(lookup, SegmentKind.Credits, null, seasonScope = false))
        assertEquals(emptyList<Any>(), segmentSourceRows(null))
    }
}
