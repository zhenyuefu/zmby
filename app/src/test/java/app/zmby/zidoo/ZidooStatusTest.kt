package app.zmby.zidoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZidooStatusTest {

    // Shape documented at apidoc.zidoo.tv for /ZidooVideoPlay/getPlayStatus.
    private fun body(status: Int, position: Long, duration: Long, width: Int = 3840) = """
        {"status":200,
         "video":{"status":$status,"title":"Dune.mkv","path":"http://emby/Videos/1/stream.mkv",
                  "currentPosition":$position,"duration":$duration,"width":$width,"height":2160,
                  "fps":24,"formt":"MKV","output":"HDMI"},
         "audio":{"index":0},"subtitle":{"index":1}}
    """.trimIndent()

    @Test
    fun parsesPlayingVideo() {
        val s = ZidooStatus.parse(body(status = 1, position = 61_000, duration = 9_972_000))!!
        assertTrue(s.playing)
        assertEquals(61_000L, s.positionMs)
        assertEquals("http://emby/Videos/1/stream.mkv", s.fileKey)
    }

    @Test
    fun pausedIsStillOpen() {
        val s = ZidooStatus.parse(body(status = 0, position = 5_000, duration = 10_000))!!
        assertFalse(s.playing)
    }

    @Test
    fun positionWithoutDurationIsNotTrusted() {
        assertEquals(0L, ZidooStatus.parse(body(status = 1, position = 5_000, duration = 0))!!.positionMs)
    }

    @Test
    fun nothingOpen() {
        // Device response when no video is playing (address intentionally omitted).
        assertNull(ZidooStatus.parse("""{"status":806,"msg":"The resource does not exist"}"""))
        assertNull(ZidooStatus.parse(body(status = 1, position = 1, duration = 1, width = 0)))
        assertNull(ZidooStatus.parse("not json"))
    }
}
