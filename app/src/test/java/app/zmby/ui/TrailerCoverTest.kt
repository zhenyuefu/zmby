package app.zmby.ui

import app.zmby.ui.components.coverScale
import org.junit.Assert.assertEquals
import org.junit.Test

/** The trailer fills the backdrop like a cropped image: the overflowing axis is enlarged, never squeezed. */
class TrailerCoverTest {
    @Test
    fun sixteenByNineOnASixteenByNineScreenIsUnchanged() {
        assertEquals(1f to 1f, coverScale(1920, 1080, 16f / 9f))
    }

    @Test
    fun widerVideoOverflowsSideways() {
        val (sx, sy) = coverScale(1920, 1080, 2.39f)
        assertEquals(2.39f / (16f / 9f), sx, 1e-4f)
        assertEquals(1f, sy)
    }

    @Test
    fun narrowerVideoOverflowsVertically() {
        val (sx, sy) = coverScale(1920, 1080, 4f / 3f)
        assertEquals(1f, sx)
        assertEquals((16f / 9f) / (4f / 3f), sy, 1e-4f)
    }

    @Test
    fun unknownSizesLeaveItAlone() {
        assertEquals(1f to 1f, coverScale(0, 1080, 16f / 9f))
        assertEquals(1f to 1f, coverScale(1920, 1080, 0f))
    }
}
