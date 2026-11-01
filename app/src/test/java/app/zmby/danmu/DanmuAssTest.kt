package app.zmby.danmu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit checks of the danmu2ass port; byte parity with the CLI is in [DanmuAssParityTest]. */
class DanmuAssTest {
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
