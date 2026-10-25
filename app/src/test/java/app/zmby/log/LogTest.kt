package app.zmby.log

import app.zmby.net.MiniHttpServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class RedactorTest {
    private val r = Redactor()

    private fun assertMasked(input: String, vararg secrets: String) {
        val out = r.redact(input)
        for (s in secrets) assertFalse("$s leaked in: $out", s in out)
        assertTrue(out, Redactor.MASK in out)
    }

    @Test
    fun masksApiKeyInStreamUrls() {
        val url = "http://nas:8096/emby/Videos/1/stream.mkv?Static=true&MediaSourceId=ms1&api_key=0123456789abcdef&DeviceId=d"
        val out = r.redact(url)
        assertEquals("http://nas:8096/emby/Videos/1/stream.mkv?Static=true&MediaSourceId=ms1&api_key=***&DeviceId=d", out)
    }

    @Test
    fun masksTokenHeadersButKeepsTheRestOfTheAuthorizationHeader() {
        val out = r.redact("""X-Emby-Authorization: Emby UserId="u1", Client="zmby", Token="secret-token-1", DeviceId="dev"""")
        assertFalse(out, "secret-token-1" in out)
        assertTrue(out, "Client=\"zmby\"" in out && "UserId=\"u1\"" in out)
        assertMasked("X-Emby-Token: secret-token-2", "secret-token-2")
        assertMasked("Authorization: Bearer abc.def.ghi", "abc.def.ghi")
    }

    @Test
    fun masksPasswordsAndTokensInJson() {
        assertMasked("""{"Username":"me","Pw":"hunter22"}""", "hunter22")
        assertMasked("""{"User":{"Id":"u1"},"AccessToken":"tok-123456789","ServerId":"s"}""", "tok-123456789")
    }

    @Test
    fun masksSignatureParametersOfSignedLinks() {
        assertMasked("302 -> https://cdn.example.com/file.mkv?sign=abcdef123456:0&expires=1700000000", "abcdef123456")
        assertMasked("https://bucket.s3.amazonaws.com/a.mkv?X-Amz-Credential=AKIA123&X-Amz-Signature=deadbeef99", "AKIA123", "deadbeef99")
        assertMasked("https://pan.example/d?auth_key=1700000000-0-0-0f0e0d0c", "0f0e0d0c")
    }

    @Test
    fun masksLearnedSecretsAnywhere() {
        r.addSecret("plain-session-token-xyz")
        assertMasked("weird place: /path/plain-session-token-xyz/x", "plain-session-token-xyz")
        r.addSecret("short") // too short to mask safely
        assertEquals("a short word", r.redact("a short word"))
    }

    @Test
    fun leavesOrdinaryTextAlone() {
        val text = "GET http://nas/emby/Users/u1/Items?SearchTerm=keyboard&Limit=10 -> 200 31ms"
        assertEquals(text, r.redact(text))
    }

    @Test
    fun linkShapeKeepsOnlyParameterNames() {
        assertEquals(
            "https://cdn.115.com/a/b.mkv?t=***&u=***&s=***",
            Redactor.linkShape("https://cdn.115.com/a/b.mkv?t=1700&u=42&s=9f8e7d"),
        )
    }
}

class LogBufferTest {
    @Test
    fun keepsTheNewestEntriesAndNumbersThem() {
        val b = LogBuffer(capacity = 3)
        repeat(5) { b.append(LogLevel.I, "T", "m$it") }
        assertEquals(5, b.lastSeq)
        assertEquals(listOf("m2", "m3", "m4"), b.since(0).map { it.message })
        assertEquals(listOf("m4"), b.since(4).map { it.message })
    }

    @Test
    fun filtersByLevel() {
        val b = LogBuffer()
        b.append(LogLevel.D, "T", "debug")
        b.append(LogLevel.W, "T", "warn")
        b.append(LogLevel.E, "T", "error")
        assertEquals(listOf("warn", "error"), b.since(0, LogLevel.W).map { it.message })
        assertEquals(LogLevel.W, LogLevel.parse("w"))
        assertEquals(LogLevel.E, LogLevel.parse("6"))
    }

    @Test
    fun awaitReturnsWhenSomethingArrives() {
        val b = LogBuffer()
        Thread { Thread.sleep(100); b.append(LogLevel.I, "T", "late") }.start()
        val got = b.await(0, LogLevel.V, 5_000)
        assertEquals(listOf("late"), got.map { it.message })
        assertTrue(b.await(1, LogLevel.V, 50).isEmpty())
    }

    @Test
    fun zlogRedactsBeforeBuffering() {
        ZLog.sink = { _, _, _ -> }
        ZLog.i("Emby", "GET http://h/Items?api_key=abcdefgh12345 -> 200")
        val last = ZLog.buffer.since(ZLog.buffer.lastSeq - 1).single()
        assertFalse(last.message, "abcdefgh12345" in last.message)
    }
}

class LanServerTest {
    private val buffer = LogBuffer()
    private val server = LanServer(buffer, ports = listOf(0), bind = InetAddress.getLoopbackAddress())
    private val http = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()

    @After
    fun tearDown() = server.stop()

    private fun get(path: String) = http.newCall(Request.Builder().url("http://127.0.0.1:${server.port}$path").build()).execute()

    @Test
    fun servesTheLogAsTextWithSinceAndLevel() {
        server.start()
        buffer.append(LogLevel.D, "Emby", "first")
        buffer.append(LogLevel.W, "Play", "second")
        buffer.append(LogLevel.I, "Nav", "third")

        get("/log").use { r ->
            assertEquals(200, r.code)
            assertEquals("3", r.header("X-Log-Last"))
            val lines = r.body!!.string().trim().lines()
            assertEquals(3, lines.size)
            assertTrue(lines[0], lines[0].matches(Regex("""000001 \d\d-\d\d \d\d:\d\d:\d\d\.\d{3} D Emby: first""")))
        }
        get("/log?since=1&level=i").use { r -> assertEquals(listOf("second", "third"), r.body!!.string().trim().lines().map { it.substringAfterLast(": ") }) }
        get("/log?q=Nav").use { r -> assertEquals(1, r.body!!.string().trim().lines().size) }
        get("/").use { r -> assertTrue(r.header("Content-Type")!!.startsWith("text/html")) }
        get("/nope").use { r -> assertEquals(404, r.code) }
    }

    @Test
    fun streamFollowsNewLines() {
        server.start()
        buffer.append(LogLevel.I, "T", "backlog")
        val r = get("/log/stream")
        val source = r.body!!.source()
        assertTrue(source.readUtf8LineStrict().endsWith("T: backlog"))
        buffer.append(LogLevel.I, "T", "live")
        assertTrue(source.readUtf8LineStrict().endsWith("T: live"))
        r.close()
    }

    @Test
    fun extraRoutesCanBeRegistered() {
        server.route("/config") { it.sendText(200, "config page") }
        server.start()
        get("/config").use { r -> assertEquals("config page", r.body!!.string()) }
    }

    @Test
    fun onlyPrivatePeersAreLan() {
        fun lan(ip: String) = MiniHttpServer.isLan(InetAddress.getByName(ip))
        assertTrue(lan("192.168.1.10"))
        assertTrue(lan("10.0.2.2"))
        assertTrue(lan("172.20.0.5"))
        assertTrue(lan("127.0.0.1"))
        assertTrue(lan("fd00::1"))
        assertFalse(lan("8.8.8.8"))
        assertFalse(lan("100.64.0.1"))
        assertFalse(lan("2001:db8::1"))
    }
}
