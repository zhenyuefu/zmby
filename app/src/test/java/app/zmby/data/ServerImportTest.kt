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
    @Test fun `AFuseKt exports map account password and named lines without logging credentials`() {
        val text = """[{"name":"Mock NAS","sourceType":"emby","account":"alice","password":"fake-import-password","nowAddress":"https://backup.example.com","originalAddress":"https://main.example.com","lines":[{"address":"https://main.example.com","name":"直连","isNow":false},{"address":"https://backup.example.com","name":"备用","isNow":true}]},{"name":"Other","sourceType":"webdav","account":"bob","password":"not-used","lines":[{"address":"https://dav.example.com"}]}]"""
        val entries = ServerImport.parse(text)
        assertEquals(2,entries.size);assertEquals("alice",entries.first().user);assertEquals("fake-import-password",entries.first().password)
        assertEquals("https://backup.example.com",entries.first().url);assertEquals("备用",entries.first().lineName)
        assertEquals("直连",entries.last().lineName)
    }

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

/** A registry of two servers; restores record what they were asked. */
private class FakeBackups : app.zmby.backup.RemoteBackups {
    val restored = mutableListOf<app.zmby.backup.RestoreMode>()
    val existing = listOf(ServerConfig("a", "家里", listOf(ServerLine("la", "主线路", "http://a.example")), "la", "u", "t"),
        ServerConfig("b", "朋友", listOf(ServerLine("lb", "主线路", "http://b.example")), "lb", "u", "t"))
    override val serverCount get() = existing.size
    override val serverIds get() = existing.map { it.id }.toSet()
    override suspend fun export(options: app.zmby.backup.BackupOptions, password: String) =
        app.zmby.backup.BackupCodec.encode(app.zmby.backup.BackupContent(existing.map { app.zmby.backup.BackupServer(it) }), options, password, "test")
    override fun decode(text: String, password: String) = app.zmby.backup.BackupCodec.decode(text, password)
    override fun preview(content: app.zmby.backup.BackupContent) = app.zmby.backup.RestorePlanner.plan(existing, content)
    override fun removedBy(plan: app.zmby.backup.RestorePlan) = existing.filter { e -> plan.rows.none { it.existingId == e.id } }.map { it.name }
    override suspend fun restore(content: app.zmby.backup.BackupContent, mode: app.zmby.backup.RestoreMode, replaceConfirmed: Boolean, progress: (Int, Int) -> Unit): app.zmby.backup.RestorePlan {
        restored += mode
        val plan = preview(content)
        plan.rows.indices.forEach { progress(it + 1, plan.rows.size) }
        return plan
    }
    override fun restoredConfig(row: app.zmby.backup.RestoreRow) = row.incoming.config
}

class ImportSessionTest {
    private val backups = FakeBackups()
    private val lan = LanServer(ports = listOf(0), bind = InetAddress.getLoopbackAddress())
    private val added = mutableListOf<ImportEntry>()
    private val session = ImportSession(lan, add = { e ->
        added += e
        if (e.url.contains("offline")) error("测试线路不可用")
        ServerConfig("s", e.name ?: "NAS", listOf(ServerLine("l", e.lineName ?: "主线路", e.url)), "l", e.user, "token")
    }, backups = backups)
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
    @Test fun `heartbeat does not extend the session but an accepted request does`() {
        var now = 1_000L
        val timed = ImportSession(lan, add = { e -> ServerConfig("s", "NAS", listOf(ServerLine("l", "主线路", e.url)), "l", e.user, "token") }, clock = { now })
        timed.open()
        val url = "http://127.0.0.1:${lan.port}/import/${timed.code}"
        now += 60_000
        http.newCall(Request.Builder().url("$url/ping").build()).execute().use { assertEquals(200, it.code) }
        assertEquals(listOf("浏览器"), timed.devices.value)
        assertEquals(1_000L + ImportSession.SESSION_MS, timed.expiresAt.value)
        http.newCall(Request.Builder().url("$url/parse").post("https://a.example bob pw".toRequestBody()).build()).execute().use { assertEquals(200, it.code) }
        assertEquals(now + ImportSession.SESSION_MS, timed.expiresAt.value)
        timed.close()
        http.newCall(Request.Builder().url("$url/ping").build()).execute().use { assertEquals(404, it.code) }
        http.newCall(Request.Builder().url("$url/backup").post("{}".toRequestBody()).build()).execute().use { assertEquals(404, it.code) }
    }

    @Test fun `wrong codes are limited per source and nothing is served before the code`() {
        session.open()
        val wrong = (session.code.toInt() % 900_000 + 100_001).toString()
        fun attempt(code: String, path: String = "/ping") = http.newCall(Request.Builder().url("http://127.0.0.1:${lan.port}/import/$code$path").build()).execute().use { it.code to it.body!!.string() }
        repeat(4) { i ->
            val (status, body) = attempt(wrong)
            assertEquals(403, status)
            assertFalse("no server data before the code", body.contains("serverCount") || body.contains("家里"))
            assertTrue(body.contains("再错 ${4 - i} 次"))
        }
        assertEquals(429, attempt(wrong).first)
        // Locked: even the right code is refused from this source for a while.
        assertEquals(429, attempt(session.code).first)
        assertEquals(429, request("/backup", "{}").first)
    }

    @Test fun `too many wrong codes in total close the page`() {
        var now = 0L
        val guard = ImportGuard({ now }, perSource = 5, lockMs = 1_000, total = 6)
        val guarded = ImportSession(lan, add = { error("no login") }, clock = { now }, guard = guard)
        guarded.open()
        fun attempt(code: String) = http.newCall(Request.Builder().url("http://127.0.0.1:${lan.port}/import/$code/ping").build()).execute().use { it.code }
        val wrong = if (guarded.code == "123456") "654321" else "123456"
        repeat(5) { assertEquals(if (it < 4) 403 else 429, attempt(wrong)) }
        now = 2_000
        assertEquals(410, attempt(wrong))
        assertTrue(guarded.locked.value)
        assertEquals(404, attempt(guarded.code))
        guarded.close()
    }

    private fun answerWhenAsked(allow: Boolean): Thread = Thread {
        val deadline = System.currentTimeMillis() + 5_000
        while (session.pending.value == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        session.pending.value?.let { session.answer(it.id, allow) }
    }.apply { start() }

    @Test fun `a backup download waits for the TV and a refusal sends nothing`() {
        session.open()
        val body = """{"options":{"accounts":true,"tokens":true},"password":"backup-pass","confirm":"backup-pass"}"""
        answerWhenAsked(false)
        val (refused, text) = request("/backup", body)
        assertEquals(403, refused)
        assertFalse(text.contains("zmby-backup"))
        assertTrue(session.activity.value.last().startsWith("已拒绝"))
        val asker = answerWhenAsked(true)
        val (allowed, file) = request("/backup", body)
        asker.join()
        assertEquals(200, allowed)
        assertTrue(file.contains("zmby-backup"))
        assertNull(session.pending.value)
    }

    @Test fun `no answer from the TV refuses after the timeout`() {
        val quick = ImportSession(lan, add = { error("no login") }, backups = backups, approvalMs = 200)
        quick.open()
        val response = http.newCall(Request.Builder().url("http://127.0.0.1:${lan.port}/import/${quick.code}/backup")
            .post("""{"options":{"tokens":false,"accounts":true},"password":"","confirm":""}""".toRequestBody()).build()).execute().use { it.code }
        assertEquals(403, response)
        assertNull(quick.pending.value)
        quick.close()
    }

    @Test fun `restores need the TV's confirmation, replace names what it deletes`() {
        session.open()
        val file = app.zmby.backup.BackupCodec.encode(app.zmby.backup.BackupContent(listOf(app.zmby.backup.BackupServer(backups.existing.first()))),
            app.zmby.backup.BackupOptions(), "backup-pass", "test")
        fun body(mode: String, confirmed: Boolean) = kotlinx.serialization.json.buildJsonObject {
            put("file", kotlinx.serialization.json.JsonPrimitive(file)); put("password", kotlinx.serialization.json.JsonPrimitive("backup-pass"))
            put("mode", kotlinx.serialization.json.JsonPrimitive(mode)); put("confirmed", kotlinx.serialization.json.JsonPrimitive(confirmed.toString()))
        }.toString()
        // Preview needs no confirmation and changes nothing.
        val (previewed, preview) = request("/restore/preview", body("Replace", false))
        assertEquals(200, previewed)
        assertTrue(preview.contains("相同跳过"))
        assertTrue(backups.restored.isEmpty())
        answerWhenAsked(false)
        assertEquals(403, request("/restore", body("Merge", false)).first)
        assertTrue(backups.restored.isEmpty())
        // Replace still needs the page's own second confirmation before the TV is asked.
        assertEquals(400, request("/restore", body("Replace", false)).first)
        assertNull(session.pending.value)
        var lines: List<String> = emptyList()
        val asker = Thread {
            val deadline = System.currentTimeMillis() + 5_000
            while (session.pending.value == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
            session.pending.value?.let { lines = it.lines; session.answer(it.id, true) }
        }.apply { start() }
        assertEquals(200, request("/restore", body("Replace", true)).first)
        asker.join()
        assertTrue(lines.any { "将删除 1 台：朋友" in it })
        assertEquals(listOf(app.zmby.backup.RestoreMode.Replace), backups.restored)
    }

    @Test fun `expired session rejects both mutation and heartbeat`() {
        var now=0L
        val expiring=ImportSession(lan,add={error("must not authenticate")},clock={now})
        expiring.open();now=ImportSession.SESSION_MS+1
        val url="http://127.0.0.1:${lan.port}/import/${expiring.code}"
        http.newCall(Request.Builder().url("$url/ping").build()).execute().use { assertEquals(410,it.code) }
        http.newCall(Request.Builder().url(url).post("{}".toRequestBody("application/json".toMediaType())).build()).execute().use { assertEquals(410,it.code) }
        expiring.close()
    }

}
