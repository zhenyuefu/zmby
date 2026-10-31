package app.zmby.ui

import app.zmby.ui.screens.detailHeadingFrame
import org.junit.Assert.*
import org.junit.Test

class DetailHeadingTest {
    @Test fun `large artwork and text titles converge to the bar without overflowing its height`() {
        for ((width, height) in listOf(520f to 150f, 360f to 87f, 860f to 134f)) {
            val start = detailHeadingFrame(0f, width, height, 22f)
            assertEquals(140f, start.titleY, 0f)
            assertEquals(1f, start.titleScale, 0f)
            assertEquals(140f + height + 22f, start.metaY, 0f)
            val end = detailHeadingFrame(300f, width, height, 22f)
            assertTrue(width * end.titleScale <= 420f)
            assertTrue(height * end.titleScale <= 64.001f)
            assertEquals(70f, end.titleY + height * end.titleScale / 2f, 0.001f)
            assertTrue(end.metaX >= width * end.titleScale + 23.99f)
        }
    }

    @Test fun `scrolling and reversing keep one continuous monotonic path`() {
        var previous = detailHeadingFrame(0f, 520f, 150f, 20f)
        for (scroll in 1..300) {
            val next = detailHeadingFrame(scroll.toFloat(), 520f, 150f, 20f)
            assertTrue(next.progress >= previous.progress)
            assertTrue(next.titleY <= previous.titleY)
            assertTrue(next.titleScale <= previous.titleScale)
            assertTrue(next.metaX >= previous.metaX)
            assertTrue(next.metaY <= previous.metaY)
            assertTrue(previous.titleY - next.titleY < 1f)
            assertTrue(next.metaX - previous.metaX < 2f)
            previous = next
        }
        assertEquals(detailHeadingFrame(0f, 520f, 150f, 20f), detailHeadingFrame(-20f, 520f, 150f, 20f))
        assertEquals(previous, detailHeadingFrame(800f, 520f, 150f, 20f))
    }

    @Test fun `metadata clears the title before entering the same vertical space`() {
        for ((width, height) in listOf(520f to 150f, 360f to 87f, 860f to 134f)) {
            for (scroll in 0..300) {
                val frame = detailHeadingFrame(scroll.toFloat(), width, height, 20f)
                if (frame.metaY < frame.titleY + height * frame.titleScale) {
                    assertTrue("overlap at scroll $scroll with $width x $height", frame.metaX >= width * frame.titleScale)
                }
            }
        }
    }
}
