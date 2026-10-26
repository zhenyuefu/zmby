package app.zmby.ui

import app.zmby.ui.screens.Accordion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccordionTest {
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    @Test fun cardFocusScrollsItsListWithoutMovingThePageAnchor() {
        val page = app.zmby.ui.theme.detailPageScrollSpec
        val innerList = app.zmby.ui.theme.detailCardScrollSpec
        assertEquals(0f, page.calculateScrollDistance(1200f, 200f, 1080f), 0f)
        assertEquals(0f, page.calculateScrollDistance(-100f, 200f, 1080f), 0f)
        assertTrue(innerList.calculateScrollDistance(600f, 80f, 400f) > 0f)
        assertTrue(innerList.calculateScrollDistance(-100f, 80f, 400f) < 0f)
        // The first/last row already fits within the card's padding. An extra viewport margin
        // would be unreachable and leak unconsumed bring-into-view scroll into the whole page.
        assertEquals(0f, innerList.calculateScrollDistance(4f, 93f, 386f), 0f)
        assertEquals(0f, innerList.calculateScrollDistance(300f, 82f, 386f), 0f)
        assertEquals(
            androidx.compose.ui.geometry.Offset(0f, -19f),
            app.zmby.ui.theme.detailCardScrollBoundary.onPostScroll(
                androidx.compose.ui.geometry.Offset.Zero,
                androidx.compose.ui.geometry.Offset(12f, -19f),
                androidx.compose.ui.input.nestedscroll.NestedScrollSource.SideEffect,
            ),
        )
    }

    @Test fun castKeepsBothCompactSourceRowsAboveIt() {
        val accordion = Accordion("versions").apply { order = listOf("episodes", "versions", "tracks", "cast", "recs") }
        assertEquals("versions", accordion.contextFor("cast"))
        assertEquals("versions", accordion.contextFor("tracks"))
        assertEquals("cast", accordion.contextFor("recs"))
    }

    @Test fun heldVersionsDirectionUsesTheSameOneJumpRule() {
        val accordion = Accordion("versions")
        val jumps = mutableListOf<Int>()
        assertTrue(accordion.sectionShortcut("versions", true, false, false, 500, jumps::add))
        assertTrue(accordion.sectionShortcut("tracks", true, false, false, 700, jumps::add))
        assertEquals(listOf(1), jumps)
    }

    @Test fun sourceStaysExpandedUntilDestinationCanReceiveFocus() {
        val accordion = Accordion("tracks")
        accordion.gainedFocus("tracks")
        accordion.prepareHandoff("cast", FocusMemory().apply { lastKey = "audio:mock:source:1" })
        assertEquals("tracks", accordion.leaving)
        assertEquals("tracks", accordion.active)
        assertEquals("cast", accordion.handoff)
        // A delayed focus callback from the old layout must not undo the switch.
        accordion.gainedFocus("tracks")
        assertEquals("tracks", accordion.active)
        accordion.gainedFocus("cast")
        assertEquals("cast", accordion.active)
    }

    @Test fun heldTrackDirectionJumpsOnlyOnceUntilRelease() {
        val accordion = Accordion("tracks")
        val jumps = mutableListOf<Int>()
        assertFalse(accordion.sectionShortcut("tracks", true, false, false, 0, jumps::add))
        assertTrue(accordion.sectionShortcut("tracks", true, false, false, 500, jumps::add))
        // The next repeat is delivered to the cast block that now has focus.
        assertTrue(accordion.sectionShortcut("cast", true, false, false, 600, jumps::add))
        assertEquals(listOf(1), jumps)
        assertTrue(accordion.sectionShortcut("cast", true, false, true, 700, jumps::add))
        assertFalse(accordion.sectionShortcut("cast", true, false, false, 0, jumps::add))
    }

    @Test fun heldUpReturnsToPreviousBlockAndShortTapsStayInTracks() {
        val accordion = Accordion("tracks")
        val jumps = mutableListOf<Int>()
        assertFalse(accordion.sectionShortcut("tracks", false, true, false, 300, jumps::add))
        assertFalse(accordion.sectionShortcut("tracks", false, true, true, 350, jumps::add))
        assertTrue(accordion.sectionShortcut("tracks", false, true, false, 500, jumps::add))
        assertEquals(listOf(-1), jumps)
    }

    @Test
    fun eachBlockKeepsItsOwnFocusPosition() {
        // Z9X 8K, 最后的餐桌: 剧集 → 版本 → back up to 剧集 → down again put focus on the off-screen 播放.
        val accordion = Accordion("episodes").apply { order = listOf("episodes", "versions") }
        val memory = FocusMemory()

        // Focus on an episode, then Down into 版本 (nothing remembered there yet).
        memory.lastKey = "episode:s1:e1"
        assertEquals(null, accordion.prepareHandoff("versions", memory))
        accordion.gainedFocus("versions")
        accordion.noteFocus("episodes", memory)
        memory.lastKey = "画质 1080p"

        // Up into 剧集: the memory is pointed at the episode before 版本 reports losing focus.
        val back = accordion.prepareHandoff("episodes", memory)
        accordion.gainedFocus("episodes")
        assertEquals("episode:s1:e1", back)
        memory.lastKey = back
        memory.beginRestore()
        accordion.noteFocus("versions", memory)
        memory.restorePending = false

        // Down again: 版本 returns to its own element, not to the episode card that is collapsing.
        assertEquals("画质 1080p", accordion.prepareHandoff("versions", memory))
    }

    @Test
    fun leavingWithoutARestoreStillRecordsTheLastElement() {
        val accordion = Accordion("episodes").apply { order = listOf("episodes", "versions", "cast") }
        val memory = FocusMemory()
        accordion.prepareHandoff("versions", memory)
        accordion.gainedFocus("versions")
        memory.lastKey = "线路 主线路"
        accordion.noteFocus("versions", memory)
        accordion.prepareHandoff("cast", memory)
        accordion.gainedFocus("cast")
        assertEquals("线路 主线路", accordion.prepareHandoff("versions", memory))
    }
}
