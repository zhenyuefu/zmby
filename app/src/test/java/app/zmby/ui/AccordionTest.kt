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

    @Test fun alignmentRowLiesAboveEveryBlockThatChanges() {
        val series = Accordion("episodes").apply { order = listOf("episodes", "versions", "tracks", "cast", "recs") }
        assertEquals("episodes", series.alignmentRow("episodes"))
        assertEquals("versions", series.alignmentRow("versions"))
        assertEquals("versions", series.alignmentRow("tracks"))
        assertEquals("versions", series.alignmentRow("cast"))
        assertEquals("cast", series.alignmentRow("recs"))
        val noVersions = Accordion("tracks").apply { order = listOf("tracks", "cast", "recs") }
        assertEquals("tracks", noVersions.alignmentRow("cast"))
        val onlyCast = Accordion("cast").apply { order = listOf("cast", "recs") }
        assertEquals("cast", onlyCast.alignmentRow("cast"))
    }

    @Test fun onlyTheActiveVersionsOrTracksBlockIsExpanded() {
        val accordion = Accordion("episodes").apply { order = listOf("episodes", "versions", "tracks", "cast", "recs") }
        assertTrue(accordion.isExpanded("episodes"))
        assertFalse(accordion.isExpanded("versions"))
        assertFalse(accordion.isExpanded("tracks"))
        accordion.gainedFocus("tracks")
        assertTrue(accordion.isExpanded("tracks"))
        assertFalse(accordion.isExpanded("versions"))
        // 剧集, 演职人员 and 为你推荐 never collapse, whichever block has focus.
        assertTrue(accordion.isExpanded("episodes"))
        assertTrue(accordion.isExpanded("cast"))
        assertTrue(accordion.isExpanded("recs"))
        val single = Accordion("cast").apply { order = listOf("versions") }
        assertTrue(single.isExpanded("versions"))
    }

    @Test fun heldVersionsDirectionUsesTheSameOneJumpRule() {
        val accordion = Accordion("versions")
        val jumps = mutableListOf<Int>()
        assertTrue(accordion.sectionShortcut("versions", true, false, false, 500, jumps::add))
        assertTrue(accordion.sectionShortcut("tracks", true, false, false, 700, jumps::add))
        assertEquals(listOf(1), jumps)
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

    @Test fun eachBlockKeepsItsOwnLastElement() {
        val memory = FocusMemory()
        val accordion = Accordion("episodes").apply { order = listOf("episodes", "versions"); this.memory = memory }
        memory.lastKey = "episode:s1:e1"
        accordion.noteFocus("episodes")
        memory.lastKey = "画质 1080p"
        accordion.noteFocus("versions")
        assertEquals("episode:s1:e1", accordion.remembered("episodes"))
        assertEquals("画质 1080p", accordion.remembered("versions"))
        // While focus is being restored into a block, lastKey already names that block's element.
        memory.lastKey = "episode:s1:e1"
        memory.beginRestore()
        accordion.noteFocus("versions")
        assertEquals("画质 1080p", accordion.remembered("versions"))
    }
}
