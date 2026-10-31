package app.zmby.ui

import app.zmby.ui.components.BackdropLuminance
import org.junit.Assert.*
import org.junit.Test

class BackdropLuminanceTest {
    @Test fun `bright background guard uses the card region rather than the whole picture`() {
        val map = BackdropLuminance.fromPixels(intArrayOf(-1, -1, 0xff000000.toInt(), 0xff000000.toInt()), 4, 1)
        assertTrue(map.average(0f, 0f, 0.5f, 1f) > 0.55f)
        assertTrue(map.average(0.5f, 0f, 1f, 1f) < 0.55f)
        assertEquals(0.5f, map.average(0f, 0f, 1f, 1f), 0.001f)
    }

    @Test fun `scrolled and cropped cards clamp sampling to visible image pixels`() {
        val map = BackdropLuminance.fromPixels(intArrayOf(-1, 0xff000000.toInt(), 0xff000000.toInt(), -1), 2, 2)
        assertEquals(1f, map.average(-1f, -1f, 0.5f, 0.5f), 0.001f)
        assertEquals(1f, map.average(1.5f, 1.5f, 2f, 2f), 0.001f)
        assertEquals(0f, map.average(0f, 0.5f, 0.5f, 1f), 0.001f)
    }

    @Test fun `sRGB gray is converted to relative luminance before the threshold comparison`() {
        val map = BackdropLuminance.fromPixels(intArrayOf(0xff808080.toInt()), 1, 1)
        assertEquals(0.21586f, map.average(0f, 0f, 1f, 1f), 0.0001f)
    }
}
