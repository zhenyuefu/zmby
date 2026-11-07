package app.zmby.danmu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit checks of the danmu2ass port; byte parity with the CLI is in [DanmuAssParityTest]. */
class DanmuAssTest {
    @Test fun `fixed comments stay centered on separate lanes and expire on both renderers`() {
        val list = listOf(requireNotNull(DanmuText.parse("0,5,255", "顶")), requireNotNull(DanmuText.parse("0,4,255", "底")),
            Danmaku(0, "滚动"))
        val stage = DanmuStage(list, { _, _ -> 100 }, { it }, 2).apply { layout(1920, 20, 0) }
        stage.update(0, 0)
        assertEquals(listOf(0, 0, 1), stage.active.map { it.row })
        assertEquals(910, stage.x(stage.active.first(), 120))
        stage.update(DanmuStage.FIXED_MS, stage.ticksFor(DanmuStage.FIXED_MS))
        assertEquals(listOf("滚动"), stage.active.map { it.danmaku.text })

        val config = DanmuAss.Config.of(2, 100, fontSize = 30, lineGap = 4, topMargin = 12)
        val ass = DanmuAss.build(DanmuAss.items(list), config)
        assertTrue(ass.contains("0:00:04.00,Top,,0,0,0,,{\\an8\\pos(960, 12)"))
        assertTrue(ass.contains(",Bottom,,0,0,0,,{\\an2\\pos(960, 1068)"))
        assertTrue(ass.contains("\\move(1920, 46,"))
    }

    @Test fun `a film below 40 fps crosses slower by the overlay's ratio, others keep the duration`() {
        val c = DanmuAss.Config.of(3, 100)
        assertEquals(20.0, c.forFrameRate(23.976).duration, 1e-9)
        assertEquals(20.0, c.forFrameRate(29.97).duration, 1e-9)
        assertEquals(10.0, c.forFrameRate(59.94).duration, 1e-9)
        assertEquals(10.0, c.forFrameRate(50.0).duration, 1e-9)
        assertEquals(10.0, c.forFrameRate(null).duration, 1e-9)
        assertEquals(10.0, c.forFrameRate(0.0).duration, 1e-9)
        val film = listOf(app.zmby.emby.MediaStream(0, type = "Audio"), app.zmby.emby.MediaStream(1, type = "Video", averageFrameRate = 23.976))
        assertEquals(23.976, DanmuAss.frameRate(film)!!, 1e-9)
        assertEquals(null, DanmuAss.frameRate(listOf(app.zmby.emby.MediaStream(0, type = "Audio"))))
        // Each scrolling line lasts the whole slower crossing.
        val ass = DanmuAss.build(DanmuAss.items(listOf(Danmaku(1_000, "慢"))), c.forFrameRate(23.976))
        assertTrue(ass, ass.contains("Dialogue: 2,0:00:01.00,0:00:21.00,Float"))
    }

    @Test fun `text length is danmu2ass's thirds of an em times the width ratio`() {
        val c = DanmuAss.Config(fontSize = 42, widthRatio = 1.2)
        // 4 non-ASCII (3 each) + 2 ASCII (2 each) = 16 thirds: 42 × 16 / 3 = 224 (integer), × 1.2.
        assertEquals(224 * 1.2, DanmuAss.length("前方高能ab", c), 0.0)
        // An emoji is one character, as in Rust, not two UTF-16 units.
        assertEquals((42L * 3 / 3) * 1.2, DanmuAss.length("😂", c), 0.0)
    }

    @Test fun `the user's rows and opacity become exact lanes and the alpha byte`() {
        for (rows in 1..5) assertEquals(rows, DanmuAss.Config.of(rows, 100).lanes)
        assertEquals(0, DanmuAss.Config.of(3, 100).opacity)
        assertEquals(63, DanmuAss.Config.of(3, 75).opacity)
        assertEquals(38, DanmuAss.Config().opacity) // alpha 0.85, as tried on the box
    }

    @Test fun `times use Rust's rounding of the seconds`() {
        assertEquals("0:00:01.12", DanmuAss.time(1.125))
        assertEquals("0:00:02.38", DanmuAss.time(2.375))
        assertEquals("1:01:01.50", DanmuAss.time(3661.5))
        assertEquals("0:00:00.00", DanmuAss.time(0.0))
    }

    @Test fun `a comment that frees within a second is delayed into that lane, later ones are dropped`() {
        val c = DanmuAss.Config.of(1, 100)
        val drawn = DanmuAss.layout(listOf(DanmuAss.Item(0.0, "一二三四五", 0xFFFFFF), DanmuAss.Item(0.3, "六七八九十", 0xFFFFFF), DanmuAss.Item(0.35, "很长很长很长很长很长很长的一条", 0xFFFFFF)), c)
        assertEquals(2, drawn.size)
        // 252 px each at (1920 + 252) / 10 px/s: the second needs (20 + 186.8) / 217.2 ≈ 0.95 s more.
        assertEquals(0.3 + (206.84 / 217.2 + 0.01), drawn[1].item.timeline, 1e-9)
        assertEquals(0, drawn[1].y)
    }

    @Test fun `text is made safe before conversion, not by it`() {
        assertEquals("｛a｝＼b", DanmuAss.safeText("{a}\\b"))
        assertEquals("a\\Nb", DanmuAss.escape("  a\nb "))
        assertTrue(DanmuAss.items(listOf(Danmaku(1_500, "{x}", 0xFF0000))).single().let { it.timeline == 1.5 && it.content == "｛x｝" && it.rgb == 0xFF0000 })
    }
}
