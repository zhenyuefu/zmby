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
        val user = o.field("user", "username", "name_user", "login", "account").orEmpty()
        val pass = o.field("pass", "password", "pw").orEmpty()
        val name = o.field("name", "title", "label")?.takeIf { it.isNotBlank() }
        if (o.field("sourceType")?.let { it.isNotBlank() && !it.equals("emby",true) } == true) return emptyList()
        val primary = o.field("url", "address", "host", "server", "nowAddress", "originalAddress")?.takeIf { it.isNotBlank() }
            ?.let { listOf(it to o.field("lineName")) }.orEmpty()
        val extra = (o.entries.firstOrNull { it.key.equals("lines", true) }?.value as? JsonArray).orEmpty().mapNotNull { l ->
            when (l) {
                is JsonPrimitive -> l.contentOrNull?.let { it to null }
                is JsonObject -> l.field("url", "address")?.let { it to l.field("name", "label") }
                else -> null
            }
        }
        // Authenticate each line: only matching Emby server IDs and user IDs are merged.
        val original = o.field("originalAddress")?.takeIf { it.isNotBlank() }?.let { listOf(it to "原始线路") }.orEmpty()
        return (primary + extra + original).distinctBy { ConnectionText.normalizeUrl(it.first) ?: it.first }
            .map { (url,line) -> ImportEntry(url,user,pass,name,line ?: extra.firstOrNull { it.first == url }?.second) }
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
data class ImportResult(val label: String, val ok: Boolean, val message: String, val serverId: String = label, val lineName: String = "", val url: String = "", val merged: Boolean = false)

/**
 * A remote request the TV must allow with the remote: exporting a backup or restoring one. No
 * answer before [expiresAt] (or closing the page) refuses it.
 */
data class Approval(val id: Long, val title: String, val device: String, val lines: List<String>, val expiresAt: Long)

/**
 * The temporary import page on the box's LAN server, open while the import screen is. Every path is
 * `/import/<code>…` with a one-time six-digit code (also in the QR code); [ImportGuard] limits wrong
 * codes per source and in total. Nothing (server names, addresses, user names) is served before the
 * code is right. Backups and restores also wait for the TV's own confirmation ([pending]); only
 * accepted requests extend the session.
 */
class ImportSession(
    private val lan: LanServer,
    private val add: suspend (ImportEntry) -> ServerConfig,
    private val backups: app.zmby.backup.RemoteBackups? = null,
    private val relogin: () -> List<ServerConfig> = { emptyList() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val approvalMs: Long = APPROVAL_MS,
    private val guard: ImportGuard = ImportGuard(clock),
    val code: String = (SecureRandom().nextInt(900_000) + 100_000).toString(),
) {
    @Volatile private var active = false
    @Volatile private var importJob: kotlinx.coroutines.Job? = null
    private val _expiresAt = MutableStateFlow(clock() + SESSION_MS)
    val expiresAt: StateFlow<Long> = _expiresAt.asStateFlow()
    private val _devices = MutableStateFlow<List<String>>(emptyList())
    val devices: StateFlow<List<String>> = _devices.asStateFlow()
    private val clients = java.util.concurrent.ConcurrentHashMap<String, Pair<String,Long>>()
    private val _progress = MutableStateFlow("")
    val progress: StateFlow<String> = _progress.asStateFlow()
    private val submitting = java.util.concurrent.atomic.AtomicBoolean(false)
    /** The request waiting for the TV's answer, if any (one at a time). */
    private val _pending = MutableStateFlow<Approval?>(null)
    val pending: StateFlow<Approval?> = _pending.asStateFlow()
    @Volatile private var answer: kotlinx.coroutines.CompletableDeferred<Boolean>? = null
    private val approvals = java.util.concurrent.atomic.AtomicLong()
    /** True once too many wrong codes closed the page; the TV offers a new code. */
    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()
    /** What remote devices did (exports, refusals), newest last, for the TV's right column. */
    private val _activity = MutableStateFlow<List<String>>(emptyList())
    val activity: StateFlow<List<String>> = _activity.asStateFlow()
    private fun valid() = active && clock() < _expiresAt.value
    private fun touch() { if (active) _expiresAt.value = clock() + SESSION_MS }
    private fun note(text: String) { _activity.value = (_activity.value + text).takeLast(20) }

    private val _results = MutableStateFlow<List<ImportResult>>(emptyList())
    val results: StateFlow<List<ImportResult>> = _results.asStateFlow()

    /** Starts the page; returns the port. */
    fun open(): Int {
        val port = lan.start()
        active = true
        touch()
        lan.routePrefix(PREFIX, allowPost = true, handler = ::dispatch)
        lan.importLanding = { ex -> ex.sendText(200, landingPage(), "text/html; charset=utf-8") }
        ZLog.i(TAG, "import page open on port $port")
        return port
    }

    fun close() {
        active = false
        importJob?.cancel()
        answer?.complete(false)
        lan.unroutePrefix(PREFIX)
        lan.importLanding = null
        ZLog.i(TAG, "import page closed")
    }

    /** The TV's answer to [Approval.id]; a stale id (already timed out) is ignored. */
    fun answer(id: Long, allow: Boolean) {
        if (_pending.value?.id == id) answer?.complete(allow)
    }

    private fun dispatch(ex: HttpExchange) {
        if (!valid()) return ex.sendText(410, "会话已失效，请在电视上重新打开")
        val source = ex.request.remote.hostAddress.orEmpty()
        guard.locked(source)?.let { return locked(ex, it.untilMs) }
        val rest = ex.request.path.trimEnd('/').removePrefix(PREFIX)
        val given = rest.substringBefore('/')
        val suffix = rest.substringAfter('/', "").let { if (it.isEmpty()) "" else "/$it" }
        when (val verdict = guard.check(source, ImportGuard.same(given, code))) {
            ImportGuard.Verdict.Ok -> Unit
            is ImportGuard.Verdict.Wrong -> {
                ZLog.w(TAG, "wrong import code from $source (${verdict.left} left)")
                return wrongCode(ex, "导入码不对，请核对电视上的六位数字。再错 ${verdict.left} 次将暂停这台设备的尝试。")
            }
            is ImportGuard.Verdict.Locked -> {
                ZLog.w(TAG, "import code attempts from $source locked")
                return locked(ex, verdict.untilMs)
            }
            ImportGuard.Verdict.Closed -> {
                ZLog.w(TAG, "too many wrong import codes: page closed")
                ex.sendText(410, "错误次数过多，页面已关闭，请在电视上重新打开")
                close()
                _locked.value = true
                return
            }
        }
        when (suffix) {
            "" -> handle(ex)
            "/ping" -> ping(ex)
            "/parse" -> parse(ex)
            "/backup" -> backup(ex)
            "/restore" -> restore(ex, true)
            "/restore/preview" -> restore(ex, false)
            else -> ex.sendText(404, "not found\n")
        }
    }

    private fun wrongCode(ex: HttpExchange, message: String) =
        if (ex.request.method == "GET") ex.sendText(403, notice(message), "text/html; charset=utf-8") else ex.sendText(403, message)

    private fun locked(ex: HttpExchange, until: Long) {
        val minutes = if (until == Long.MAX_VALUE) null else ((until - clock() + 59_999) / 60_000).coerceAtLeast(1)
        val message = if (minutes == null) "错误次数过多，页面已关闭，请在电视上重新打开" else "错误次数过多，请 $minutes 分钟后再试"
        val retry = minutes?.let { listOf("Retry-After" to (it * 60).toString()) }.orEmpty()
        if (ex.request.method == "GET") ex.sendText(429, notice(message), "text/html; charset=utf-8", retry) else ex.sendText(429, message, extra = retry)
    }

    private fun deviceOf(ex: HttpExchange): String {
        val ua = ex.request.header("user-agent").orEmpty()
        return when { "iPhone" in ua -> "iPhone"; "iPad" in ua -> "iPad"; "Android" in ua && "Mobile" in ua -> "Android 手机"; "Android" in ua -> "Android 平板"; "Macintosh" in ua -> "Mac"; "Windows" in ua -> "Windows"; else -> "浏览器" }
    }

    /** Status for the page; it does not extend the session (only accepted requests do). */
    private fun ping(ex: HttpExchange) {
        val ua = ex.request.header("user-agent").orEmpty()
        clients[ex.request.remote.hostAddress.orEmpty() + ua] = deviceOf(ex) to clock()
        clients.entries.removeIf { clock() - it.value.second > 30_000 }
        _devices.value = clients.values.map { it.first }
        val body = buildJsonObject {
            put("serverCount",backups?.serverCount ?: 0); put("device", android.os.Build.MODEL); put("remaining", ((_expiresAt.value-clock())/1000).coerceAtLeast(0)); put("progress", _progress.value)
            put("waiting", _pending.value != null)
            putJsonArray("relogin") { relogin().forEach { c -> addJsonObject {
                put("name", c.name); put("user",c.userName.orEmpty()); putJsonArray("lines") { c.lines.forEach { l -> addJsonObject { put("name",l.name); put("url",l.url) } } }
            } } }
        }
        ex.sendText(200, body.toString(), "application/json; charset=utf-8")
    }

    private fun parse(ex: HttpExchange) {
        if (ex.request.method != "POST") return ex.sendText(405, "POST only\n")
        val text = ex.readBody()?.toString(Charsets.UTF_8) ?: return ex.sendText(413, "too large\n")
        touch()
        ex.sendText(200, preview(ServerImport.parse(text)), "application/json; charset=utf-8")
    }

    private fun handle(ex: HttpExchange) {
        if (ex.request.method != "POST") return ex.sendText(200, ServerImportPage.html, "text/html; charset=utf-8")
        val text = ex.readBody()?.toString(Charsets.UTF_8) ?: return ex.sendText(413, "too large\n")
        val entries = ServerImport.parse(text)
        if (entries.any { it.user.isBlank() || ConnectionText.normalizeUrl(it.url) == null }) {
            return ex.sendText(400, "[{\"ok\":false,\"label\":\"请检查表单\",\"message\":\"每条线路需要有效的 HTTP(S) 地址和账号\"}]", "application/json; charset=utf-8")
        }
        if (entries.isEmpty()) return ex.sendText(400, "[]", "application/json; charset=utf-8")
        if (!submitting.compareAndSet(false,true)) return ex.sendText(409, "正在处理上一批，请稍候")
        touch()
        ZLog.i(TAG, "importing ${entries.size} entries")
        val existingIds = backups?.serverIds.orEmpty()
        val results = try {
            runBlocking {
                importJob = coroutineContext[kotlinx.coroutines.Job]
                entries.mapIndexed { index, e ->
                    if (!active) return@mapIndexed ImportResult(e.name.orEmpty(), false, "会话已关闭")
                    _progress.value = "正在登录 · 第 ${index+1} 条，共 ${entries.size} 条"
                    val line = e.lineName ?: e.url
                    withTimeoutOrNull(PER_ENTRY_MS) {
                        runCatching { add(e.copy(url = ConnectionText.normalizeUrl(e.url)!!)) }.fold(
                            { c -> ImportResult(e.name ?: c.name, true, "$line · 已添加", c.id, e.lineName ?: "主线路", e.url, c.id in existingIds) },
                            { _ -> ImportResult(e.name ?: e.url, false, "$line · 登录失败，请检查地址和账号", lineName = e.lineName.orEmpty(), url = e.url) },
                        )
                    } ?: ImportResult(e.name ?: e.url, false, "$line · 失败：连接超时")
                }
            }
        } finally { importJob = null; submitting.set(false); _progress.value = "" }
        touch()
        _results.value = _results.value + results
        val body = buildJsonArray { results.forEach { r -> addJsonObject { put("label", r.label); put("ok", r.ok); put("message", r.message) } } }
        ex.sendText(200, body.toString(), "application/json; charset=utf-8")
    }

    /**
     * Blocks this request until the TV allows or refuses it; no answer within [approvalMs] refuses.
     * Returns null when another request is already waiting.
     */
    private fun approve(title: String, device: String, lines: List<String>): Boolean? {
        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
        synchronized(this) {
            if (_pending.value != null || !active) return null
            answer = deferred
            _pending.value = Approval(approvals.incrementAndGet(), title, device, lines, clock() + approvalMs)
        }
        return try {
            runBlocking { withTimeoutOrNull(approvalMs) { deferred.await() } } ?: false
        } finally {
            synchronized(this) { answer = null; _pending.value = null }
        }
    }

    private fun request(ex: HttpExchange): kotlinx.serialization.json.JsonObject? {
        if (ex.request.method != "POST") { ex.sendText(405, "POST only"); return null }
        val bytes = ex.readBody(app.zmby.backup.BackupCodec.MAX_BYTES * 2) ?: run { ex.sendText(413,"文件过大"); return null }
        return runCatching { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()
            ?: run { ex.sendText(400,"请求无法识别"); null }
    }
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.content.orEmpty()

    private fun backup(ex: HttpExchange) {
        val repo = backups ?: return ex.sendText(503,"备份尚未就绪")
        val body = request(ex) ?: return
        val device = "${deviceOf(ex)} · ${ex.request.remote.hostAddress.orEmpty()}"
        val options: app.zmby.backup.BackupOptions
        val text: String
        try {
            options = app.zmby.backup.BackupCodec.json.decodeFromJsonElement(app.zmby.backup.BackupOptions.serializer(), body["options"] ?: JsonObject(emptyMap()))
            require(body.text("password") == body.text("confirm")) { "两次密码不一致" }
            text = runBlocking { repo.export(options,body.text("password")) }
        } catch (_: Exception) { return ex.sendText(400,"无法导出，请检查备份密码（至少 8 位）和选项") }
        val parts = listOfNotNull("服务器名称与线路", "账号名".takeIf { options.accounts }, "登录令牌".takeIf { options.tokens },
            "服务器设置".takeIf { options.serverSettings }, "应用设置".takeIf { options.appSettings })
        val lines = listOf("${repo.serverCount} 台服务器", parts.joinToString(" · "), if (body.text("password").isEmpty()) "未加密" else "已用备份密码加密")
        when (approve("允许这台设备下载备份？", device, lines)) {
            null -> return ex.sendText(409, "电视上有待确认的请求，请稍候")
            false -> { note("已拒绝 · $device 下载备份"); return ex.sendText(403, "电视上已拒绝或未及时确认") }
            true -> Unit
        }
        touch()
        note("已允许 · $device 下载备份")
        val name = "zmby-backup-${java.text.SimpleDateFormat("yyyyMMdd-HHmmss",java.util.Locale.ROOT).format(java.util.Date())}.zmby"
        ex.sendText(200,text,"application/octet-stream",listOf("Content-Disposition" to "attachment; filename=\"$name\""))
    }

    private fun restore(ex: HttpExchange, apply: Boolean) {
        val repo = backups ?: return ex.sendText(503,"备份尚未就绪")
        val body = request(ex) ?: return
        if (!submitting.compareAndSet(false,true)) return ex.sendText(409,"正在处理上一批，请稍候")
        val device = "${deviceOf(ex)} · ${ex.request.remote.hostAddress.orEmpty()}"
        try {
            _progress.value = "正在读取备份…"
            val data = repo.decode(body.text("file"), body.text("password"))
            val mode = if (body.text("mode") == "Replace") app.zmby.backup.RestoreMode.Replace else app.zmby.backup.RestoreMode.Merge
            var plan = repo.preview(data)
            if (apply) {
                require(mode != app.zmby.backup.RestoreMode.Replace || body.text("confirmed") == "true") { "请再次确认全部替换" }
                val removed = if (mode == app.zmby.backup.RestoreMode.Replace) repo.removedBy(plan) else emptyList()
                val lines = listOfNotNull(
                    if (mode == app.zmby.backup.RestoreMode.Replace) "方式 · 全部替换" else "方式 · 合并到现有",
                    "新增 ${plan.added} 台 · 更新线路 ${plan.updated} 台 · 相同跳过 ${plan.skipped} 台",
                    removed.takeIf { it.isNotEmpty() }?.let { "将删除 ${it.size} 台：" + it.take(5).joinToString("、") + if (it.size > 5) " 等" else "" },
                )
                _progress.value = "等待电视确认…"
                when (approve(if (mode == app.zmby.backup.RestoreMode.Replace) "允许这台设备替换全部服务器？" else "允许这台设备恢复备份？", device, lines)) {
                    null -> return ex.sendText(409, "电视上有待确认的请求，请稍候")
                    false -> { note("已拒绝 · $device 恢复备份"); return ex.sendText(403, "电视上已拒绝或未及时确认") }
                    true -> Unit
                }
                note("已允许 · $device 恢复备份")
                _progress.value = "正在验证登录…"
                plan = runBlocking { repo.restore(data, mode, true) { done, total -> _progress.value = "正在验证登录 · 已完成 $done 台，共 $total 台" } }
            }
            touch()
            val result = buildJsonObject {
                put("added",plan.added); put("updated",plan.updated); put("skipped",plan.skipped)
                putJsonArray("servers") { plan.rows.forEach { row -> addJsonObject { put("name",row.incoming.config.name); put("lines",row.incoming.config.lines.size); put("action",row.action.label); put("needsLogin",if(apply) repo.restoredConfig(row)?.needsLogin ?: true else row.needsLogin) } } }
            }
            if (apply) _results.value = _results.value + plan.rows.flatMap { row ->
                val config=repo.restoredConfig(row) ?: row.incoming.config
                config.lines.map { line -> ImportResult(config.name,!config.needsLogin,if(config.needsLogin) "需重新登录" else "已恢复",config.id,line.name,line.url,row.existingId != null) }
            }
            ex.sendText(200,result.toString(),"application/json; charset=utf-8")
        } catch (e: Exception) { ex.sendText(400, if (e is IllegalArgumentException) e.message ?: "备份无法恢复" else "备份无法恢复，请重试") }
        finally { _progress.value = ""; submitting.set(false) }
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
        <form onsubmit="location.href='/import/'+document.getElementById('c').value.replace(/\D/g,'');return false">
        <input id="c" inputmode="numeric" autofocus placeholder="六位数字"><button>继续</button></form>
        <p><a href="/logs">查看日志</a></p></main></body></html>
    """.trimIndent()

    private fun notice(message: String) = """
        <!doctype html><html lang="zh"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>zmby 导入服务器</title><style>$STYLE</style></head><body><main>
        <h1>zmby 导入服务器</h1><p>${message.replace("<", "&lt;")}</p><p><a href="/">重新输入导入码</a></p></main></body></html>
    """.trimIndent()

    companion object {
        private const val TAG = "Import"
        private const val PREFIX = "/import/"
        const val SESSION_MS = 10 * 60_000L
        /** How long a backup or restore request waits for the TV before it counts as refused. */
        const val APPROVAL_MS = 60_000L
        private const val PER_ENTRY_MS = 25_000L

        private const val STYLE = "body{margin:0;background:#0a0b0d;color:#eee;font:16px/1.5 system-ui,sans-serif}" +
            "main{max-width:720px;margin:0 auto;padding:24px}h1{font-size:22px}textarea,input{width:100%;box-sizing:border-box;background:#16181c;color:#eee;" +
            "border:1px solid #3a3e46;border-radius:12px;padding:12px;font:14px ui-monospace,Menlo,monospace}textarea{height:240px}" +
            "button{margin-top:12px;padding:12px 28px;border:0;border-radius:999px;background:#fff;color:#0a0b0d;font-weight:700;font-size:16px}" +
            "li.ok{color:#4ade80}li.no{color:#fbbf24}a{color:#9cc5ff}code{color:#ccc}"

    }
}

/** Web and TV wording of a restore action; logic only compares the enum. */
val app.zmby.backup.RestoreAction.label: String get() = when (this) {
    app.zmby.backup.RestoreAction.Add -> "新增"
    app.zmby.backup.RestoreAction.UpdateLines -> "更新线路"
    app.zmby.backup.RestoreAction.Same -> "相同跳过"
}
