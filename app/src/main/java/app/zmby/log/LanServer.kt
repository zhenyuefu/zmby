package app.zmby.log

import app.zmby.net.HttpExchange
import app.zmby.net.MiniHttpServer
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The box's small LAN web server: today the debug log (`/`, `/log`, `/log/stream`); later pages
 * such as the phone-side server import (plan §3.1) register themselves with [route]. Peers outside
 * the private address ranges are refused.
 */
class LanServer(
    private val buffer: LogBuffer = ZLog.buffer,
    private val ports: List<Int> = (DEFAULT_PORT until DEFAULT_PORT + 10).toList(),
    private val bind: InetAddress = InetAddress.getByName("0.0.0.0"),
    private val accept: (InetAddress) -> Boolean = MiniHttpServer::isLan,
) {
    private val routes = ConcurrentHashMap<String, (HttpExchange) -> Unit>()
    /** Paths that also take POST (the server import page). */
    private val posts = ConcurrentHashMap.newKeySet<String>()
    /** Handlers for every path under a prefix (the import page checks its own code and attempts). */
    private val prefixes = ConcurrentHashMap<String, Pair<Boolean, (HttpExchange) -> Unit>>()
    private val streams = AtomicInteger()
    private var http: MiniHttpServer? = null

    /** While the server import page is open, `/` asks for its code instead (the log page stays at `/logs`). */
    @Volatile var importLanding: ((HttpExchange) -> Unit)? = null

    init {
        route("/") { ex -> importLanding?.invoke(ex) ?: ex.sendText(200, LOG_PAGE, "text/html; charset=utf-8") }
        route("/logs") { it.sendText(200, LOG_PAGE, "text/html; charset=utf-8") }
        route("/log") { serveLog(it) }
        route("/log/stream") { streamLog(it) }
    }

    /** Serves [path] (exact match) with [handler]; [allowPost] also lets POST through. */
    fun route(path: String, allowPost: Boolean = false, handler: (HttpExchange) -> Unit) {
        routes[path] = handler
        if (allowPost) posts += path else posts -= path
    }

    fun unroute(path: String) {
        routes.remove(path)
        posts -= path
    }

    /** Serves every path starting with [prefix] that has no exact route. */
    fun routePrefix(prefix: String, allowPost: Boolean = false, handler: (HttpExchange) -> Unit) {
        prefixes[prefix] = allowPost to handler
    }

    fun unroutePrefix(prefix: String) { prefixes.remove(prefix) }

    val port: Int get() = http?.port ?: -1

    @Synchronized
    fun start(): Int {
        http?.let { if (it.running) return it.port }
        val server = MiniHttpServer("lan", bind, ports, accept) { ex ->
            val path = ex.request.path.trimEnd('/').ifEmpty { "/" }
            val prefixed = if (routes.containsKey(path)) null
                else prefixes.entries.filter { path.startsWith(it.key) }.maxByOrNull { it.key.length }?.value
            val handler = routes[path] ?: prefixed?.second
            when {
                handler == null -> ex.sendText(404, "not found\n")
                ex.request.method == "POST" && (path in posts || prefixed?.first == true) -> handler(ex)
                ex.request.method != "GET" && ex.request.method != "HEAD" -> ex.sendText(405, "GET only\n")
                else -> handler(ex)
            }
        }
        val p = server.start()
        http = server
        return p
    }

    @Synchronized
    fun stop() {
        http?.stop()
        http = null
        ZLog.i(TAG, "LAN server stopped")
    }

    private fun filters(ex: HttpExchange): Pair<LogLevel, String?> =
        (LogLevel.parse(ex.request.query["level"]) ?: LogLevel.V) to ex.request.query["q"]?.takeIf { it.isNotBlank() }

    private fun List<LogEntry>.matching(q: String?) = if (q == null) this else filter { q in it.tag || q in it.message }

    /** `GET /log[?since=<seq>][&level=I][&q=text][&limit=n]`: plain text, one entry per line. */
    private fun serveLog(ex: HttpExchange) {
        val (level, q) = filters(ex)
        val since = ex.request.query["since"]?.toLongOrNull() ?: 0
        val limit = ex.request.query["limit"]?.toIntOrNull()?.coerceIn(1, 5000) ?: 5000
        val last = buffer.lastSeq
        val lines = buffer.since(since, level).matching(q).takeLast(limit)
        val body = lines.joinToString("") { it.format() + "\n" }
        // X-Log-Last lets a poller continue with since=<it>, even when the filter matched nothing.
        ex.sendText(200, body, extra = listOf("X-Log-Last" to last.toString()))
    }

    /** `GET /log/stream`: the last 100 entries (or those after `since`), then new ones as they come. */
    private fun streamLog(ex: HttpExchange) {
        if (streams.incrementAndGet() > MAX_STREAMS) {
            streams.decrementAndGet()
            ex.sendText(503, "too many log streams\n")
            return
        }
        try {
            val (level, q) = filters(ex)
            ex.sendHead(200, listOf("Content-Type" to "text/plain; charset=utf-8", "Cache-Control" to "no-store", "X-Accel-Buffering" to "no"))
            if (ex.request.method == "HEAD") return
            var seq = ex.request.query["since"]?.toLongOrNull() ?: (buffer.lastSeq - 100).coerceAtLeast(0)
            while (true) {
                val batch = buffer.await(seq, LogLevel.V, IDLE_CHECK_MS)
                if (batch.isEmpty()) {
                    if (ex.peerClosed()) return
                    continue
                }
                seq = batch.last().seq
                val text = batch.filter { it.level >= level }.matching(q).joinToString("") { it.format() + "\n" }
                if (text.isNotEmpty()) {
                    ex.output.write(text.toByteArray(Charsets.UTF_8))
                    ex.output.flush()
                }
            }
        } finally {
            streams.decrementAndGet()
        }
    }

    companion object {
        private const val TAG = "Lan"
        const val DEFAULT_PORT = 8790
        private const val MAX_STREAMS = 8
        private const val IDLE_CHECK_MS = 10_000L

        /** The box's private IPv4 addresses, wired first (eth before wlan), for showing "ip:port". */
        fun lanAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("eth")) 0 else 1 }
                .flatMap { nic -> nic.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress } }
                .mapNotNull { it.hostAddress }
        }.getOrDefault(emptyList())
    }
}

private val LOG_PAGE = """
<!doctype html>
<html lang="zh"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>zmby 日志</title>
<style>
body{margin:0;background:#0a0b0d;color:#e5e5e5;font:13px/1.45 ui-monospace,Menlo,Consolas,monospace}
header{position:sticky;top:0;display:flex;gap:12px;align-items:center;padding:10px 14px;background:#16181c;border-bottom:1px solid #2a2d33;font-family:system-ui,sans-serif}
header b{font-size:15px}select,input{background:#22252b;color:#eee;border:1px solid #3a3e46;border-radius:6px;padding:4px 8px}
#log{white-space:pre-wrap;word-break:break-all;padding:8px 14px}
.V{color:#8b8f97}.D{color:#b5bac2}.I{color:#e8e8e8}.W{color:#fbbf24}.E{color:#f87171}
#st{margin-left:auto;color:#8b8f97;font-size:12px}
</style></head><body>
<header><b>zmby 日志</b>
<label>级别 <select id="lv"><option>V</option><option selected>D</option><option>I</option><option>W</option><option>E</option></select></label>
<label>过滤 <input id="q" size="18" placeholder="tag 或文字"></label>
<label><input type="checkbox" id="pause"> 暂停</label>
<span id="st"></span></header>
<div id="log"></div>
<script>
const box=document.getElementById('log'),lv=document.getElementById('lv'),q=document.getElementById('q'),st=document.getElementById('st'),pause=document.getElementById('pause');
let last=0,rows=[];
function reset(){last=0;rows=[];box.textContent='';}
lv.onchange=reset;q.onchange=reset;
async function tick(){
  if(!pause.checked){
    try{
      const u='/log?since='+last+'&level='+lv.value+(q.value?'&q='+encodeURIComponent(q.value):'')+(last?'':'&limit=2000');
      const r=await fetch(u,{cache:'no-store'});const t=await r.text();
      last=parseInt(r.headers.get('X-Log-Last')||last,10);
      const atBottom=window.innerHeight+window.scrollY>=document.body.scrollHeight-40;
      t.split(/\n(?=\d{6,} )/).filter(s=>s.trim()).forEach(s=>{const d=document.createElement('div');d.className=(s.match(/^\d+ \S+ \S+ (\w) /)||[])[1]||'';d.textContent=s.replace(/\n$/,'');box.appendChild(d);rows.push(d);});
      while(rows.length>5000)rows.shift().remove();
      if(atBottom)window.scrollTo(0,document.body.scrollHeight);
      st.textContent='#'+last+' · '+new Date().toLocaleTimeString();
    }catch(e){st.textContent='连接失败 '+e;}
  }
  setTimeout(tick,1000);
}
tick();
</script></body></html>
""".trimIndent()
