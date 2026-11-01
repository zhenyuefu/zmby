package app.zmby.ui

import org.junit.Assert.*
import org.junit.Test

class FocusMemoryTest {
    @Test fun closingAnOverlayKeepsItsReturnTargetWhenAutomaticFocusMovesElsewhere() {
        val memory = FocusMemory()
        memory.lastKey = "watching:episode"
        memory.beginRestore()
        memory.lastKey = "search"
        assertTrue(memory.restorePending)
        assertEquals("watching:episode", memory.restoreKey)
    }

    @Test fun aNewRestoreRequestSupersedesTheOldTargetAndEmptyTargetsDoNotRestore() {
        val memory = FocusMemory()
        memory.lastKey = "watching:removed"
        memory.beginRestore()
        val request = memory.restoreRequest
        memory.lastKey = "watching:neighbor"
        memory.beginRestore()
        assertEquals("watching:neighbor", memory.restoreKey)
        assertTrue(memory.restoreRequest > request)
        memory.lastKey = null
        memory.beginRestore()
        assertFalse(memory.restorePending)
        assertNull(memory.restoreKey)
    }
}
