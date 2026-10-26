package app.zmby.ui

import app.zmby.ui.screens.heroWindow
import org.junit.Assert.assertEquals
import org.junit.Test

/** The home hero fetches the current title first, then its neighbours alternately right and left. */
class HeroWindowTest {

    private val five = listOf("a", "b", "c", "d", "e")

    @Test
    fun nearestFirstAlternatingRightAndLeft() {
        assertEquals(listOf("c", "d", "b", "e", "a"), heroWindow(five, 2))
        assertEquals(listOf("c", "d", "b"), heroWindow(five, 2, radius = 1))
    }

    @Test
    fun wrapsBothWaysWithAnUnboundedCounter() {
        assertEquals(listOf("a", "b", "e", "c", "d"), heroWindow(five, 0))
        assertEquals(listOf("e", "a", "d", "b", "c"), heroWindow(five, -1))
        assertEquals(listOf("b", "c", "a", "d", "e"), heroWindow(five, 11))
    }

    @Test
    fun fewTitlesAppearOnce() {
        assertEquals(listOf("a", "b"), heroWindow(listOf("a", "b"), 0))
        assertEquals(listOf("a"), heroWindow(listOf("a"), 7))
        assertEquals(emptyList<String>(), heroWindow(emptyList<String>(), 3))
    }
}
