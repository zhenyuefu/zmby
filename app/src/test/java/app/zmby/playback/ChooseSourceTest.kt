package app.zmby.playback

import app.zmby.emby.EmbyException
import app.zmby.emby.MediaSourceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ChooseSourceTest {
    private val remux = MediaSourceInfo(id = "remux", container = "mkv")
    private val web = MediaSourceInfo(id = "web", container = "mkv")

    @Test
    fun picksTheRequestedVersion() {
        assertEquals("web", chooseSource(listOf(remux, web), "web").id)
    }

    @Test
    fun missingRequestedVersionIsAnErrorNotAnotherVersion() {
        // The server may answer PlaybackInfo with every source, or with a different one.
        assertThrows(EmbyException::class.java) { chooseSource(listOf(remux), "web") }
    }

    @Test
    fun withoutAChoiceTheFirstDirectSourceWins() {
        val transcodeOnly = MediaSourceInfo(id = "t", supportsDirectPlay = false, supportsDirectStream = false)
        assertEquals("remux", chooseSource(listOf(transcodeOnly, remux)).id)
        assertEquals("t", chooseSource(listOf(transcodeOnly)).id)
        assertThrows(EmbyException::class.java) { chooseSource(emptyList()) }
    }
}
