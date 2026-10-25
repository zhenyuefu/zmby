package app.zmby.zidoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZidooPlayerTest {

    private fun request(start: Long, container: String? = "mkv") = ZidooLaunchRequest(
        url = "http://emby:8096/Videos/1/stream.mkv?Static=true&api_key=t",
        container = container,
        title = "Dune",
        startPositionMs = start,
    )

    @Test
    fun resumeOverridesThePlayersOwnBookmark() {
        val spec = buildZidooLaunch(request(start = 4_328_000))
        assertEquals(false, spec.extras["from_start"])
        assertEquals(4_328_000, spec.extras["position"])
        assertEquals(true, spec.extras["return_result"])
        assertEquals("Dune", spec.extras["title"])
        assertEquals("video/mkv", spec.mimeType)
    }

    @Test
    fun currentFirmwarePlayerIsPreferred() {
        assertEquals("com.zidoo.player", ZidooPlayer.COMPONENTS.first().pkg)
        assertEquals("com.android.gallery3d", ZidooPlayer.COMPONENTS.last().pkg)
    }

    @Test
    fun userAgentIsPassedWhenGiven() {
        val spec = buildZidooLaunch(request(start = 0).copy(userAgent = "zmby/0.1.0"))
        assertEquals("zmby/0.1.0", spec.extras["User-Agent"])
        assertFalse(buildZidooLaunch(request(start = 0)).extras.containsKey("User-Agent"))
    }

    @Test
    fun startFromBeginningIsExplicit() {
        val spec = buildZidooLaunch(request(start = 0))
        assertEquals(true, spec.extras["from_start"])
        assertFalse(spec.extras.containsKey("position"))
    }

    @Test
    fun discImagesAndUnknownContainersAreSniffed() {
        assertEquals("video/*", mimeTypeFor(null))
        assertEquals("video/*", mimeTypeFor("iso"))
        assertEquals("video/*", mimeTypeFor("strm"))
        assertEquals("video/mp4", mimeTypeFor("mp4,m4v"))
    }

    @Test
    fun resultParsesCompletionAndPosition() {
        val done = ZidooResult.parse(mapOf("end_by" to "playback_completion", "duration" to 9_972_000)::get)
        assertTrue(done.completed)
        assertEquals(9_972_000L, done.durationMs)
        assertNull(done.positionMs)

        val stopped = ZidooResult.parse(mapOf("end_by" to "user", "position" to 120_500)::get)
        assertFalse(stopped.completed)
        assertEquals(120_500L, stopped.positionMs)
    }
}
