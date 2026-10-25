package app.zmby.data

import app.zmby.log.LanServer
import app.zmby.log.ZLog
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class ServerImportTest {
    @Test fun `shared Chinese account message fills every line without losing punctuation in password`() {
        val entries = ServerImport.parse("""
            家里 NAS
            直连：https://emby.example.com:8920/emby
            备用：https://backup.example.com:8920
            账号：myname
            密码：p@ss:word!#&=+，。
        """.trimIndent())
        assertEquals(2, entries.size)
        assertEquals(listOf("直连", "备用"), entries.map { it.lineName })
        assertEquals(listOf("https://emby.example.com:8920/emby", "https://backup.example.com:8920"), entries.map { it.url })
        assertTrue(entries.all { it.user == "myname" && it.password == "p@ss:word!#&=+，。" && it.name == "家里 NAS" })
    }

    @Test fun `English inline credentials and missing protocol preserve reverse proxy paths`() {
        val entries = ServerImport.parse("线路1: emby.example.com:8096/MyEmby\nUsername: alice, Password: x=y+z&!\n线路2: 192.168.1.12:8096")
        assertEquals(listOf("http://emby.example.com:8096/MyEmby", "http://192.168.1.12:8096"), entries.map { it.url })
        assertTrue(entries.all { it.user == "alice" && it.password == "x=y+z&!" })
    }

    @Test fun `URLs on one line and markdown line labels are recognized`() {
        val entries = ServerImport.parse("[直连] https://a.example https://b.example\n用户：bob\n口令：123")
        assertEquals(2, entries.size)
        assertEquals("直连", entries.first().lineName)
        assertTrue(entries.all { it.user == "bob" })
    }

    @Test fun `address only and blank passwords stay editable and duplicate lines disappear`() {
        val entries = ServerImport.parse("https://a.example\nhttps://a.example/\nhttps://b.example")
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.user.isEmpty() && it.password.isEmpty() })
        assertEquals("", ServerImport.parse("https://a.example bob").single().password)
    }

    @Test fun `JSON form accepts named lines without a primary URL and keeps password spaces`() {
        val entries = ServerImport.parse("""{"servers":[{"name":"NAS","user":"a","pass":" p @:! ","lines":[{"url":"https://a.example","name":"内网"},{"url":"https://b.example","name":"外网"}]}]}""")
        assertEquals(listOf("内网", "外网"), entries.map { it.lineName })
        assertTrue(entries.all { it.password == " p @:! " && it.name == "NAS" })
        assertTrue(ServerImport.parse("not a server message").isEmpty())
    }

    @Test fun `separate named account messages keep their own credentials and lines`() {
        val entries = ServerImport.parse("""
            第一台
            https://a.example
            账号：alice
            密码：first

            第二台
            https://b.example
            账号：bob
            密码：second
        """.trimIndent())
        assertEquals(listOf("第一台", "第二台"), entries.map { it.name })
        assertEquals(listOf("alice", "bob"), entries.map { it.user })
        assertEquals(listOf("first", "second"), entries.map { it.password })
    }

    @Test fun `bot status message extracts inline password and markdown URL but ignores code and expiry`() {
        val entries = ServerImport.parse("""
            用户名：demo.user@example.com
            验证码：123456
            有效期：2026-08-28T20:00:21.525270
            正在检查 goemby 线路开通状态...
            您已开通，密码：demo.test，线路：[https://test.test](https://test.test)
        """.trimIndent())
        assertEquals(listOf(ImportEntry("https://test.test", "demo.user@example.com", "demo.test")), entries)
    }

    @Test fun `inline bot message with several named markdown lines keeps each URL once`() {
        val entries = ServerImport.parse("""
            用户名：demo.user@example.com
            验证码：123456
            有效期：2026-08-28T20:00:21.525270
            您已开通，密码：p@ss.test!，线路1：[直连](https://a.example/emby)，线路2：[https://b.example](https://b.example)
        """.trimIndent())
        assertEquals(listOf("https://a.example/emby", "https://b.example"), entries.map { it.url })
        assertEquals(listOf("线路1", "线路2"), entries.map { it.lineName })
        assertTrue(entries.all { it.user == "demo.user@example.com" && it.password == "p@ss.test!" && it.name == null })
    }

    @Test fun `pipe separated account card ignores safety password and unwraps markdown server name`() {
        val entries = ServerImport.parse("""
            · 用户名称 | demo-user
            · 用户密码 | 11223344
            · 安全密码 | 987654（仅发送一次）
            · 到期时间 | \_\_若30天无观看将封禁\_\_
            · 服务器 | **测试1服**
            · 当前线路：
            CF线路：[https://cf.example.com:443](https://cf.example.com:443)
        """.trimIndent())
        assertEquals(listOf(ImportEntry("https://cf.example.com", "demo-user", "11223344", "测试1服", "CF线路")), entries)
    }

    @Test fun `server address label still means a URL while full width pipes separate credentials`() {
        val entries = ServerImport.parse("""服务器：https://a.example
用户名称 ｜ demo
用户密码 ｜ login-password
安全密码 ｜ other-password""")
        assertEquals(listOf(ImportEntry("https://a.example", "demo", "login-password")), entries)
    }

    @Test fun `unsafe schemes and embedded URL credentials cannot be submitted`() {
        assertNull(ConnectionText.normalizeUrl("file:///etc/passwd"))
        assertNull(ConnectionText.normalizeUrl("javascript:alert(1)"))
        assertNull(ConnectionText.normalizeUrl("https://alice:secret@example.com"))
        assertNull(ConnectionText.normalizeUrl("http://example.com:99999"))
    }

    @Test fun `same server keeps imported line names when merged`() {
        val registry = ServerRegistry(MemoryRegistryStore())
        fun config(id: String, line: String, url: String) = ServerConfig(id, "NAS", listOf(ServerLine(id, line, url)), id, "user", "token", embyServerId = "same")
        registry.add(config("a", "内网", "http://a.example"))
        registry.add(config("b", "备用", "http://b.example"))
        assertEquals(listOf("内网", "备用"), registry.all.single().config.lines.map { it.name })
    }
}

class ImportSessionTest {
    private val lan = LanServer(ports = listOf(0), bind = InetAddress.getLoopbackAddress())
    private val added = mutableListOf<ImportEntry>()
    private val session = ImportSession(lan, add = { e ->
        added += e
        if (e.url.contains("offline")) error("测试线路不可用")
        ServerConfig("s", e.name ?: "NAS", listOf(ServerLine("l", e.lineName ?: "主线路", e.url)), "l", e.user, "token")
    }, export = { "[]" })
    private val http = OkHttpClient()
    private fun request(path: String = "", body: String? = null): Pair<Int, String> {
        val builder = Request.Builder().url("http://127.0.0.1:${lan.port}/import/${session.code}$path")
        if (body != null) builder.post(body.toRequestBody("application/json".toMediaType()))
        return http.newCall(builder.build()).execute().use { it.code to it.body!!.string() }
    }
    @After fun close() { session.close(); lan.stop() }

    @Test fun `preview fills multiple lines with no login or credential logging and closes with session`() {
        ZLog.sink = { _, _, _ -> }
        session.open()
        val since = ZLog.buffer.lastSeq
        val (status, body) = request("/parse", "直连：https://a.example\n备用：https://b.example\n账号：alice\n密码：special-secret-password")
        assertEquals(200, status)
        assertTrue(body.contains("special-secret-password") && body.contains("备用"))
        assertTrue(added.isEmpty())
        assertFalse(ZLog.buffer.since(since).any { "special-secret-password" in it.message })
        assertTrue(request().second.contains("id=\"form\""))
        assertEquals(405, request("/parse").first)
        session.close()
        assertEquals(404, request("/parse", "https://a.example bob pass").first)
        assertEquals(404, request().first)
    }

    @Test fun `submission validates all entries before login and reports partial failures`() {
        session.open()
        assertEquals(400, request(body = """{"user":"bob","lines":[{"url":"file:///bad"}]}""").first)
        assertEquals(400, request(body = """{"lines":["https://a.example"]}""").first)
        assertTrue(added.isEmpty())
        val (status, body) = request(body = """{"user":"bob","pass":"secret","lines":[{"name":"主线","url":"a.example:8096"},{"name":"备用","url":"https://offline.example"}]}""")
        assertEquals(200, status)
        assertEquals(2, added.size)
        assertEquals("http://a.example:8096", added.first().url)
        assertEquals("备用", added.last().lineName)
        assertTrue(body.contains("\"ok\":true") && body.contains("\"ok\":false"))
        assertEquals(listOf(true, false), session.results.value.map { it.ok })
    }
}
