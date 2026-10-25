package app.zmby.data

import app.zmby.emby.ServerSession
import app.zmby.ui.components.Health
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistryTest {
    private fun config(id: String, url: String, emby: String? = null, user: String = "u") =
        ServerConfig(id, id, listOf(ServerLine("$id-l", "主线路", url)), "$id-l", user, "tok-$id", embyServerId = emby)

    @Test fun `the same emby server and user gains a line instead of a second entry`() {
        val reg = ServerRegistry(MemoryRegistryStore())
        reg.add(config("a", "http://lan:8096", emby = "E1"))
        reg.add(config("b", "http://ddns.example:8096", emby = "E1"))
        assertEquals(1, reg.all.size)
        assertEquals(listOf("http://lan:8096", "http://ddns.example:8096"), reg.all.single().config.lines.map { it.url })
    }

    @Test fun `priority moves and line switches persist`() {
        val store = MemoryRegistryStore()
        val reg = ServerRegistry(store)
        reg.add(config("a", "http://a"))
        reg.add(config("b", "http://b"))
        reg.move("b", -1)
        assertEquals(listOf("b", "a"), store.saved.map { it.id })
        reg.addLine("a", "CDN", "http://cdn")
        val cdn = reg.get("a")!!.config.lines.last()
        reg.selectLine("a", cdn.id)
        assertEquals("http://cdn", ServerRegistry(store).session("a")!!.baseUrl)
    }

    @Test fun `health from speed tests and failures, auto fastest picks the quickest line`() {
        var now = 0L
        val reg = ServerRegistry(MemoryRegistryStore(), clock = { now })
        reg.add(config("a", "http://a").copy(autoFastest = true, lines = listOf(ServerLine("l1", "内网", "http://a"), ServerLine("l2", "DDNS", "http://b")), lineId = "l1"))
        assertEquals(Health.Warn, reg.get("a")!!.health)
        reg.recordLatency("a", "l1", null, null)
        reg.recordLatency("a", "l2", 35, "4.9")
        assertEquals("l2", reg.get("a")!!.config.lineId)
        assertEquals(Health.Ok, reg.get("a")!!.health)
        reg.onFailure("a", RuntimeException("x"), false)
        reg.onFailure("a", RuntimeException("x"), true)
        assertTrue(reg.isCooling("a"))
        assertEquals(Health.Off, reg.get("a")!!.health)
        reg.retry("a")
        assertEquals(false, reg.isCooling("a"))
    }

    @Test fun `the single P1 login becomes the first entry`() {
        val c = ServerRegistry.fromLegacy(ServerSession("http://192.168.1.10:8096", "u1", "tok", "srv-1", "家里 NAS"))
        assertEquals("家里 NAS", c.name)
        assertEquals("http://192.168.1.10:8096", c.line.url)
        assertEquals("srv-1", c.embyServerId)
        assertEquals("tok", c.session().accessToken)
        assertEquals(c.id, c.session().capsKey)
    }

    @Test fun `bulk import text and json, export carries no secrets`() {
        val lines = ServerImport.parse("# 我的服务器\nhttp://a:8096 me secret 家里 NAS\nhttps://b.example user2 pw2\nbad-line\n")
        assertEquals(listOf(ImportEntry("http://a:8096", "me", "secret", "家里 NAS"), ImportEntry("https://b.example", "user2", "pw2")), lines)
        val json = ServerImport.parse("""{"servers":[{"URL":"http://c","Username":"x","Password":"y","lines":["http://c2"]}]}""")
        assertEquals(listOf("http://c", "http://c2"), json.map { it.url })
        val out = ServerImport.export(listOf(config("a", "http://a").copy(userName = "me")))
        assertTrue(out.contains("http://a") && !out.contains("tok-a"))
    }
}
