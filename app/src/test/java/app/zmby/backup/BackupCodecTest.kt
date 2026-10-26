package app.zmby.backup

import app.zmby.data.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BackupCodecTest {
    private val line = ServerLine("l","直连","https://nas.example.com")
    private val server = ServerConfig("s","Private NAS",listOf(line),line.id,"user-id","secret-test-token","alice","emby-id",enabled = false,aggregate = false)
    private val content = BackupContent(listOf(BackupServer(server,homeOff = listOf("movies"))))
    private val password = "test-password-123"

    @Test fun `encrypted backup authenticates all header fields and hides names addresses tokens`() {
        val options = BackupOptions()
        val encoded = BackupCodec.encode(content,options,password,"Mock TV",123)
        assertFalse(encoded.contains(server.name));assertFalse(encoded.contains(line.url));assertFalse(encoded.contains(server.token));assertFalse(encoded.contains(password))
        val (header,encrypted) = BackupCodec.header(encoded)
        assertEquals(1,header.serverCount);assertTrue(encrypted)
        assertEquals(content.servers,BackupCodec.decode(encoded,password).servers)
        assertNotEquals(encoded,BackupCodec.encode(content,options,password,"Mock TV",123))
        assertFails { BackupCodec.decode(encoded,"incorrect-password") }
        assertFails { BackupCodec.decode(encoded.replace("Mock TV","Wrong TV"),password) }
        assertFails { BackupCodec.decode(encoded.replace("310000","1"),password) }
    }

    @Test fun `tokens require eight characters and unencrypted export strips credentials`() {
        assertFails { BackupCodec.encode(content,BackupOptions(),"short","TV") }
        val encoded = BackupCodec.encode(content,BackupOptions(accounts = false,tokens = false,serverSettings = false),"","TV")
        assertFalse(encoded.contains(server.token)); assertFalse(encoded.contains("alice")); assertFalse(encoded.contains(server.userId))
        val restored = BackupCodec.decode(encoded,"")
        assertTrue(restored.servers.single().config.token.isEmpty());assertTrue(restored.servers.single().config.enabled)
        assertTrue(restored.servers.single().homeOff.isEmpty());assertFalse(BackupCodec.header(encoded).second)
    }

    @Test fun `application API credentials cannot be exported without encryption`() {
        val data = content.copy(settings = buildJsonObject { put("tmdb_read_token","private-api-token") })
        val options = BackupOptions(tokens = false,appSettings = true)
        assertFails { BackupCodec.encode(data,options,"","TV") }
        val encoded = BackupCodec.encode(data,options,password,"TV")
        assertFalse(encoded.contains("private-api-token"))
        assertEquals(data.settings,BackupCodec.decode(encoded,password).settings)
    }

    @Test fun `unsupported versions malformed lines and duplicate server IDs are rejected`() {
        val text = BackupCodec.encode(content,BackupOptions(tokens = false),"","TV")
        assertFails { BackupCodec.decode(text.replace("\"version\":1","\"version\":2"),"") }
        assertFails { BackupCodec.encode(content.copy(servers = content.servers+content.servers),BackupOptions(),password,"TV") }
        assertFails { BackupCodec.encode(content.copy(servers = listOf(BackupServer(server.copy(lines = listOf(line.copy(url="file:///private")))))),BackupOptions(),password,"TV") }
    }

    @Test fun `restore matching separates accounts and preserves incoming order`() {
        val other = server.copy(id="other",userId="other-user")
        val same = server.copy(id="new-id",lines=listOf(line,ServerLine("l2","备用","https://backup.example.com")))
        val fresh = server.copy(id="fresh",embyServerId="different",lines=listOf(ServerLine("new-line","主线","https://new.example.com")),lineId="new-line")
        val plan = RestorePlanner.plan(listOf(other,server),BackupContent(listOf(BackupServer(fresh),BackupServer(same))))
        assertEquals(listOf("新增","更新线路"),plan.rows.map { it.action }); assertEquals("s",plan.rows.last().existingId)
        assertEquals(1,plan.added);assertEquals(1,plan.updated);assertEquals(0,plan.skipped)
        assertEquals("新增",RestorePlanner.plan(listOf(other),content).rows.single().action)
    }

    @Test fun `merge retains current line token and IDs but unions addresses and optional settings`() {
        val addedLine=ServerLine("l","备用","https://new.example.com")
        val incoming=server.copy(id="foreign",token="different-token",enabled=true,lines=listOf(line.copy(id="x"),addedLine),lineId="x")
        val merged=RestorePlanner.merge(server,incoming,true)
        assertEquals(server.id,merged.id);assertEquals(server.lineId,merged.lineId);assertEquals(server.token,merged.token)
        assertEquals(2,merged.lines.size);assertEquals(2,merged.lines.map { it.id }.distinct().size);assertTrue(merged.enabled)
        assertFalse(RestorePlanner.merge(server,incoming,false).enabled)
    }

    @Test fun `server source choices and provider order obey optional backup parts`() {
        val data=content.copy(servers=listOf(content.servers.single().copy(sources=mapOf("aggregate" to false))),providers=listOf(BackupProvider("introdb",true,0)))
        val included=BackupCodec.decode(BackupCodec.encode(data,BackupOptions(appSettings=true),password,"TV"),password)
        assertEquals(false,included.servers.single().sources["aggregate"]);assertEquals("introdb",included.providers.single().key)
        val excluded=BackupCodec.decode(BackupCodec.encode(data,BackupOptions(tokens=false,serverSettings=false,appSettings=false),"","TV"),"")
        assertTrue(excluded.servers.single().sources.isEmpty());assertTrue(excluded.providers.isEmpty())
    }

    private fun assertFails(block: () -> Unit) { try { block(); fail("must reject invalid backup") } catch (_: IllegalArgumentException) {} }
}
