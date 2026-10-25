package app.zmby.ui

import app.zmby.emby.EmbyException
import app.zmby.media.folderLabel
import app.zmby.net.displayAddress
import app.zmby.ui.components.BoxBlur
import app.zmby.ui.screens.returnedLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** The UI shows no slashes (TV UI spec, second edition): labels built from paths, URLs and errors. */
class UiTextTest {
    @Test fun `folder of a file becomes chevron separated`() {
        assertEquals("movies › 沙丘2 (2024)", folderLabel("/movies/沙丘2 (2024)/Dune.mkv"))
        assertEquals("D: › Movies", folderLabel("D:\\Movies\\Film.mkv"))
        assertNull(folderLabel("Film.mkv"))
        assertNull(folderLabel(null))
    }

    @Test fun `server address without scheme or slashes`() {
        assertEquals("emby.example.net", displayAddress("https://emby.example.net"))
        assertEquals("192.168.1.10:8096", displayAddress("http://192.168.1.10:8096/"))
        assertEquals("host:8096 · emby", displayAddress("http://host:8096/emby"))
        assertEquals("[2001:db8::20]:8096", displayAddress("http://[2001:db8::20]:8096"))
    }

    @Test fun `search progress without a slash`() {
        assertEquals("已返回 9 台，共 12 台", returnedLabel(9, 12))
        assertEquals("12 台全部返回", returnedLabel(12, 12))
    }

    @Test fun `errors never show paths`() {
        val errors = listOf(
            EmbyException("HTTP 401 /emby/Users/AuthenticateByName: nope", 401),
            EmbyException("HTTP 500 /emby/Items/x/PlaybackInfo: boom", 500),
            EmbyException("HTTP 418 /x", 418),
            EmbyException("所选版本已不在服务器上（a/b）"),
            UnknownHostException("emby.example.net"),
            ConnectException("Failed to connect to /10.0.0.1:8096"),
            SocketTimeoutException("timeout"),
            IllegalStateException("something at /data/local/tmp"),
        )
        for (e in errors) assertFalse(e.userMessage(), '/' in e.userMessage())
        assertEquals("用户名或密码不对，或登录已失效（401）", errors[0].userMessage())
        assertEquals("所选版本已不在服务器上（a b）", errors[3].userMessage())
        assertEquals("连不上服务器", errors[5].userMessage())
    }

    @Test fun `box blur spreads a point and keeps a flat image flat`() {
        val w = 9
        val px = IntArray(w * w) { 0xFF000000.toInt() }
        px[4 * w + 4] = 0xFFFFFFFF.toInt()
        BoxBlur.blur(px, w, w, 2)
        val centre = px[4 * w + 4] and 0xFF
        val near = px[4 * w + 5] and 0xFF
        assert(centre in 1..254) { "centre $centre" }
        assert(near > 0) { "neighbour $near" }
        val flat = IntArray(16) { 0xFF336699.toInt() }
        BoxBlur.blur(flat, 4, 4, 3)
        assert(flat.all { it == 0xFF336699.toInt() })
    }
}
