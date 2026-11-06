package app.zmby.ui

import app.zmby.ui.screens.searchCardKeys
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchCardKeysTest {
    @Test
    fun keysStayTheWorkKeyAndRepeatsGetASuffix() {
        assertEquals(listOf("movie:tmdb:1", "movie:tmdb:2", "movie:tmdb:1#2", "movie:tmdb:1#3"),
            searchCardKeys(listOf("movie:tmdb:1", "movie:tmdb:2", "movie:tmdb:1", "movie:tmdb:1")))
    }
}
