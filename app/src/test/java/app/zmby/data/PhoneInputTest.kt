package app.zmby.data

import app.zmby.log.LanServer
import app.zmby.log.ZLog
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class PhoneInputTest {
    private val lan = LanServer(ports = listOf(0), bind = InetAddress.getLoopbackAddress())
    private val http = OkHttpClient()
    private val saved = mutableListOf<String>()
    private var now = 1_000L
    private val session = InputSession(
        lan, InputField.TmdbToken,
        validate = { v -> if (v.startsWith("good")) null else "TMDB 不认这个令牌" },
        save = { saved += it },
        clock = { now },
    )
    private fun call(code: String = session.code, path: String = "", body: String? = null): Pair<Int, String> {
        val b = Request.Builder().url("http://127.0.0.1:${lan.port}/input/$code$path")
        if (body != null) b.post(body.toRequestBody())
        return http.newCall(b.build()).execute().use { it.code to it.body!!.string() }
    }
    @After fun close() { session.close(); lan.stop() }

    @Test fun `the value is checked, saved once and never logged`() {
        ZLog.sink = { _, _, _ -> }
        session.open()
        val since = ZLog.buffer.lastSeq
        val (pageStatus, page) = call()
        assertEquals(200, pageStatus)
        assertTrue(page.contains("API 读取访问令牌") && page.contains("保存到电视"))
        val refused = call(body = "bad-secret-value-123456")
        assertTrue(refused.second.contains("\"ok\":false") && refused.second.contains("不认"))
        assertTrue(saved.isEmpty())
        assertTrue(session.state.value is InputState.Failed)
        val ok = call(body = "  good-secret\n-value-123456  ")
        assertTrue(ok.second.contains("\"ok\":true"))
        assertEquals(listOf("good-secret-value-123456"), saved)
        assertEquals(InputState.Saved, session.state.value)
        assertFalse(ZLog.buffer.since(since).any { "secret-value" in it.message })
    }

    @Test fun `status polls do not extend the page but an accepted value does`() {
        session.open()
        now += 60_000
        assertEquals(200, call(path = "/ping").first)
        assertEquals(1_000L + ImportSession.SESSION_MS, session.expiresAt.value)
        assertTrue(session.state.value is InputState.Opened)
        call(body = "bad")
        assertEquals(now + ImportSession.SESSION_MS, session.expiresAt.value)
        now += ImportSession.SESSION_MS + 1
        assertEquals(410, call(path = "/ping").first)
    }

    @Test fun `wrong codes are limited and a closed page serves nothing`() {
        session.open()
        val wrong = if (session.code == "123456") "654321" else "123456"
        repeat(4) { assertEquals(403, call(wrong).first) }
        assertEquals(429, call(wrong).first)
        assertEquals(429, call().first)
        session.close()
        assertEquals(404, call(path = "/ping").first)
    }

    @Test fun `values are cleaned per field`() {
        assertEquals("abc", InputSession.clean(" a b\nc ", InputField.TmdbToken))
        assertEquals("Mozilla/5.0 (Linux)", InputSession.clean("\n Mozilla/5.0 (Linux) \nsecond line", InputField.UserAgent))
        assertNull(InputSession.checkUserAgent("Mozilla/5.0 (Linux; Android 11)"))
        assertTrue(InputSession.checkUserAgent("浏览器") != null)
        assertTrue(InputSession.checkUserAgent("x".repeat(513)) != null)
    }
}
