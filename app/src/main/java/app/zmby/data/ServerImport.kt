package app.zmby.data

import app.zmby.log.LanServer
import app.zmby.log.ZLog
import app.zmby.net.HttpExchange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.security.SecureRandom

/** One server to log in to, from the phone-side import page. */
data class ImportEntry(
    val url: String,
    val user: String,
    val password: String,
    val name: String? = null,
    val lineName: String? = null,
)

/**
 * Bulk server entry (aggregation spec §3.1, 配置录入): typing dozens of addresses with a remote is
 * not an option, so a phone pastes them. Accepts a JSON array (or `{"servers": [...]}`) of
 * `{url?, user, pass, name?, lines?: [url or {url,name}…]}`, legacy rows or shared account text.
 */
object ServerImport {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): List<ImportEntry> {
        val t = text.trim()
        if (t.isEmpty()) return emptyList()
        if (t.startsWith("[") || t.startsWith("{")) {
            val root = runCatching { json.parseToJsonElement(t) }.getOrNull() ?: return ConnectionText.parse(t)
            val list = when (root) {
                is JsonArray -> root
                is JsonObject -> (root.entries.firstOrNull { it.key.equals("servers", true) }?.value as? JsonArray) ?: JsonArray(listOf(root))
                else -> return emptyList()
            }
            return list.mapNotNull { it as? JsonObject }.flatMap(::entriesOf)
        }
        return ConnectionText.parse(t)
    }

    private fun entriesOf(o: JsonObject): List<ImportEntry> {
        fun JsonObject.field(vararg names: String): String? = entries.firstOrNull { e -> names.any { it.equals(e.key, true) } }
            ?.value?.let { (it as? JsonPrimitive)?.contentOrNull }
        val user = o.field("user", "username", "name_user", "login").orEmpty()
        val pass = o.field("pass", "password", "pw").orEmpty()
        val name = o.field("name", "title", "label")?.takeIf { it.isNotBlank() }
        val primary = o.field("url", "address", "host", "server")?.takeIf { it.isNotBlank() }
            ?.let { listOf(it to o.field("lineName")) }.orEmpty()
        val extra = (o.entries.firstOrNull { it.key.equals("lines", true) }?.value as? JsonArray).orEmpty().mapNotNull { l ->
            when (l) {
                is JsonPrimitive -> l.contentOrNull?.let { it to null }
                is JsonObject -> l.field("url", "address")?.let { it to l.field("name", "label") }
                else -> null
            }
        }
        // Authenticate each line: only matching Emby server IDs and user IDs are merged.
        return (primary + extra).distinctBy { it.first }.map { (url, line) -> ImportEntry(url, user, pass, name, line) }
    }

    /** A backup of the server list: names, lines and user names, never tokens or passwords. */
    fun export(servers: List<ServerConfig>): String = buildJsonArray {
        for (c in servers) addJsonObject {
            put("name", c.name)
            put("url", c.line.url)
            c.userName?.let { put("user", it) }
            putJsonArray("lines") { c.lines.filter { it.id != c.lineId }.forEach { add(it.url) } }
        }
    }.toString()
}

/** The outcome of one imported entry, for the TV and the phone. */
data class ImportResult(val label: String, val ok: Boolean, val message: String)

/**
 * The temporary import page on the box's LAN server, open while the import screen is. The path
 * carries a one-time six-digit code (also in the QR code), so other devices on the LAN cannot post
 * servers; `/` shows a code prompt while it is open.
 */
class ImportSession(
    private val lan: LanServer,
    private val add: suspend (ImportEntry) -> ServerConfig,
    private val export: () -> String,
) {
    val code: String = (SecureRandom().nextInt(900_000) + 100_000).toString()
    private val path = "/import/$code"
    private val _results = MutableStateFlow<List<ImportResult>>(emptyList())
    val results: StateFlow<List<ImportResult>> = _results.asStateFlow()

    /** Starts the page; returns the port. */
    fun open(): Int {
        val port = lan.start()
        lan.route(path, allowPost = true, ::handle)
        lan.route("$path/parse", allowPost = true) { ex ->
            if (ex.request.method != "POST") ex.sendText(405, "POST only\n")
            else {
                val text = ex.readBody()?.toString(Charsets.UTF_8)
                if (text == null) ex.sendText(413, "too large\n")
                else ex.sendText(200, preview(ServerImport.parse(text)), "application/json; charset=utf-8")
            }
        }
        lan.route("$path/export") { it.sendText(200, export(), "application/json; charset=utf-8") }
        lan.importLanding = { ex -> ex.sendText(200, landingPage(), "text/html; charset=utf-8") }
        ZLog.i(TAG, "import page open on port $port")
        return port
    }

    fun close() {
        lan.unroute(path)
        lan.unroute("$path/export")
        lan.unroute("$path/parse")
        lan.importLanding = null
        ZLog.i(TAG, "import page closed")
    }

    private fun handle(ex: HttpExchange) {
        if (ex.request.method != "POST") return ex.sendText(200, ServerImportPage.html, "text/html; charset=utf-8")
        val text = ex.readBody()?.toString(Charsets.UTF_8) ?: return ex.sendText(413, "too large\n")
        val entries = ServerImport.parse(text)
        if (entries.any { it.user.isBlank() || ConnectionText.normalizeUrl(it.url) == null }) {
            return ex.sendText(400, "[{\"ok\":false,\"label\":\"请检查表单\",\"message\":\"每条线路需要有效的 HTTP(S) 地址和账号\"}]", "application/json; charset=utf-8")
        }
        if (entries.isEmpty()) return ex.sendText(400, "[]", "application/json; charset=utf-8")
        ZLog.i(TAG, "importing ${entries.size} entries")
        val results = runBlocking {
            entries.map { e ->
                val line = e.lineName ?: e.url
                withTimeoutOrNull(PER_ENTRY_MS) {
                    runCatching { add(e.copy(url = ConnectionText.normalizeUrl(e.url)!!)) }.fold(
                        { c -> ImportResult(e.name ?: c.name, true, "$line · 已添加") },
                        { err -> ImportResult(e.name ?: e.url, false, "$line · 失败：${err.message ?: err.javaClass.simpleName}") },
                    )
                } ?: ImportResult(e.name ?: e.url, false, "$line · 失败：连接超时")
            }
        }
        _results.value = _results.value + results
        val body = buildJsonArray { results.forEach { r -> addJsonObject { put("label", r.label); put("ok", r.ok); put("message", r.message) } } }
        ex.sendText(200, body.toString(), "application/json; charset=utf-8")
    }

    /** Recognition does no authentication and never writes credentials to the registry or log. */
    private fun preview(entries: List<ImportEntry>): String = buildJsonArray {
        entries.groupBy { Triple(it.name, it.user, it.password) }.forEach { (key, lines) -> addJsonObject {
            put("name", key.first.orEmpty()); put("user", key.second); put("pass", key.third)
            putJsonArray("lines") { lines.forEach { e -> addJsonObject { put("url", e.url); put("name", e.lineName.orEmpty()) } } }
        } }
    }.toString()

    private fun landingPage() = """
        <!doctype html><html lang="zh"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>zmby 导入服务器</title><style>$STYLE</style></head><body><main>
        <h1>zmby 导入服务器</h1><p>输入电视上显示的导入码</p>
        <form onsubmit="location.href='/import/'+document.getElementById('c').value.replace(/\s/g,'');return false">
        <input id="c" inputmode="numeric" autofocus placeholder="六位数字"><button>继续</button></form>
        <p><a href="/logs">查看日志</a></p></main></body></html>
    """.trimIndent()

    companion object {
        private const val TAG = "Import"
        private const val PER_ENTRY_MS = 25_000L

        private const val STYLE = "body{margin:0;background:#0a0b0d;color:#eee;font:16px/1.5 system-ui,sans-serif}" +
            "main{max-width:720px;margin:0 auto;padding:24px}h1{font-size:22px}textarea,input{width:100%;box-sizing:border-box;background:#16181c;color:#eee;" +
            "border:1px solid #3a3e46;border-radius:12px;padding:12px;font:14px ui-monospace,Menlo,monospace}textarea{height:240px}" +
            "button{margin-top:12px;padding:12px 28px;border:0;border-radius:999px;background:#fff;color:#0a0b0d;font-weight:700;font-size:16px}" +
            "li.ok{color:#4ade80}li.no{color:#fbbf24}a{color:#9cc5ff}code{color:#ccc}"

    }
}
