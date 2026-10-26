package app.zmby.ui

import app.zmby.ui.components.LogoTone
import org.junit.Assert.assertEquals
import org.junit.Test

/** Dark title Logos are lightened, empty ones fall back to the text title (ui-spec §1.3). */
class LogoToneTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    /** [share] of the pixels are [p], the rest fully transparent. */
    private fun logo(p: Int, share: Double, size: Int = 1000): IntArray =
        IntArray(size) { i -> if (i < size * share) p else 0 }

    @Test
    fun blackStrokesOnTransparentAreDark() {
        // 血谜拼图's clearlogo: a third of the pixels opaque, all of them black.
        assertEquals(LogoTone.Dark, LogoTone.of(logo(argb(255, 0, 0, 0), 0.32)))
        assertEquals(LogoTone.Dark, LogoTone.of(logo(argb(255, 120, 0, 0), 0.4)))
    }

    @Test
    fun whiteAndColouredLogosStay() {
        assertEquals(LogoTone.Light, LogoTone.of(logo(argb(255, 255, 255, 255), 0.3)))
        assertEquals(LogoTone.Light, LogoTone.of(logo(argb(255, 230, 180, 40), 0.3)))
        // Black outline around white letters: light on average.
        val mixed = IntArray(1000) { i -> if (i < 200) argb(255, 0, 0, 0) else if (i < 500) argb(255, 255, 255, 255) else 0 }
        assertEquals(LogoTone.Light, LogoTone.of(mixed))
    }

    @Test
    fun transparentPixelsDoNotCount() {
        // Dark colour channels under alpha 0 (common in PNG exports) say nothing about the Logo.
        val pixels = IntArray(1000) { i -> if (i < 300) argb(255, 255, 255, 255) else argb(0, 0, 0, 0) }
        assertEquals(LogoTone.Light, LogoTone.of(pixels))
    }

    @Test
    fun nothingVisibleIsBlank() {
        assertEquals(LogoTone.Blank, LogoTone.of(IntArray(1000)))
        assertEquals(LogoTone.Blank, LogoTone.of(logo(argb(255, 255, 255, 255), 0.001)))
        assertEquals(LogoTone.Blank, LogoTone.of(IntArray(0)))
    }
}
