package app.zmby.net

import app.zmby.log.ZLog
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** One parsed HTTP/1.1 request head. Header names are lower-cased. */
class HttpRequest(
    val method: String,
    val target: String,
    val headers: Map<String, String>,
    val remote: InetAddress,
) {
    val path: String = target.substringBefore('?')
    val query: Map<String, String> = parseQuery(target.substringAfter('?', ""))

    fun header(name: String): String? = headers[name.lowercase()]

    companion object {
        fun parseQuery(raw: String): Map<String, String> = raw.split('&').filter { it.isNotEmpty() }.associate {
            decode(it.substringBefore('=')) to decode(it.substringAfter('=', ""))
        }

        private fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

        /** Reads the request line and headers; null when the client sent nothing usable. */
        fun read(input: InputStream, remote: InetAddress, maxBytes: Int = 16 * 1024): HttpRequest? {
            val lines = mutableListOf<String>()
            val line = StringBuilder()
            var total = 0
            while (true) {
                val b = input.read()
                if (b < 0) return null
                if (++total > maxBytes) return null
                if (b == '\n'.code) {
                    val text = line.toString().trimEnd('\r')
                    line.setLength(0)
                    if (text.isEmpty()) {
                        if (lines.isEmpty()) continue else break
                    }
                    lines += text
                } else {
                    line.append(b.toChar())
                }
            }
            val parts = lines.first().split(' ')
            if (parts.size < 2) return null
            val headers = lines.drop(1).mapNotNull { h ->
                val i = h.indexOf(':')
                if (i <= 0) null else h.substring(0, i).trim().lowercase() to h.substring(i + 1).trim()
            }.toMap()
            return HttpRequest(parts[0].uppercase(), parts[1], headers, remote)
        }
    }
}

/**
 * One request and its connection. Every response closes the connection: players pipeline range
 * requests onto kept-alive connections, and a relay that swallows one of them stalls playback.
 */
class HttpExchange(val request: HttpRequest, private val socket: Socket, private val input: InputStream) {
    val output: OutputStream = socket.getOutputStream()
    var responded = false
        private set

    fun sendHead(status: Int, headers: List<Pair<String, String>>) {
        responded = true
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
            headers.filter { !it.first.equals("Connection", true) }.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
    }

    fun sendText(status: Int, body: String, contentType: String = "text/plain; charset=utf-8", extra: List<Pair<String, String>> = emptyList()) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        sendHead(status, listOf("Content-Type" to contentType, "Content-Length" to bytes.size.toString(), "Cache-Control" to "no-store") + extra)
        if (request.method != "HEAD") output.write(bytes)
        output.flush()
    }

    /** The request body (Content-Length only, no chunked uploads), at most [maxBytes]; null when larger or absent. */
    fun readBody(maxBytes: Int = 256 * 1024): ByteArray? {
        val length = request.header("content-length")?.toIntOrNull() ?: return null
        if (length < 0 || length > maxBytes) return null
        val out = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(out, read, length - read)
            if (n < 0) return null
            read += n
        }
        return out
    }

    /** Whether the peer has hung up; for long-lived responses that are idle between writes. */
    fun peerClosed(): Boolean {
        if (socket.isClosed) return true
        val old = socket.soTimeout
        return try {
            socket.soTimeout = 1
            input.read() < 0
        } catch (e: java.net.SocketTimeoutException) {
            false
        } catch (e: IOException) {
            true
        } finally {
            runCatching { socket.soTimeout = old }
        }
    }

    companion object {
        fun reason(status: Int) = when (status) {
            200 -> "OK"
            206 -> "Partial Content"
            302 -> "Found"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            416 -> "Range Not Satisfiable"
            500 -> "Internal Server Error"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            else -> "Status"
        }
    }
}

/**
 * A minimal thread-per-connection HTTP server, shared by the LAN log page and the stream relay.
 * Handles one request per connection. [accept] filters peers before anything is read.
 */
class MiniHttpServer(
    private val name: String,
    private val bind: InetAddress,
    private val ports: List<Int>,
    private val accept: (InetAddress) -> Boolean = { true },
    private val readTimeoutMs: Int = 15_000,
    private val handler: (HttpExchange) -> Unit,
) {
    @Volatile private var server: ServerSocket? = null
    private var pool: ExecutorService? = null
    private val active = AtomicInteger()

    val port: Int get() = server?.localPort ?: -1
    val running: Boolean get() = server?.isClosed == false
    val connections: Int get() = active.get()

    /** Binds the first free port in [ports] (0 = any) and returns it. */
    @Synchronized
    fun start(): Int {
        server?.let { if (!it.isClosed) return it.localPort }
        var lastError: IOException? = null
        for (p in ports) {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(bind, p), 32)
                server = s
                val threads = AtomicInteger()
                val exec = Executors.newCachedThreadPool { r -> Thread(r, "$name-${threads.incrementAndGet()}").apply { isDaemon = true } }
                pool = exec
                exec.execute { acceptLoop(s, exec) }
                ZLog.i(TAG, "$name listening on ${bind.hostAddress}:${s.localPort}")
                return s.localPort
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("no port to bind")
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        pool?.shutdownNow()
        pool = null
    }

    private fun acceptLoop(s: ServerSocket, exec: ExecutorService) {
        while (!s.isClosed) {
            val socket = try {
                s.accept()
            } catch (e: IOException) {
                break
            }
            val peer = socket.inetAddress
            if (!accept(peer)) {
                ZLog.w(TAG, "$name refused ${peer.hostAddress}")
                runCatching { socket.close() }
                continue
            }
            try {
                exec.execute { serve(socket) }
            } catch (e: Exception) {
                runCatching { socket.close() }
            }
        }
    }

    private fun serve(socket: Socket) {
        active.incrementAndGet()
        try {
            socket.soTimeout = readTimeoutMs
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream())
            val request = HttpRequest.read(input, socket.inetAddress) ?: return
            val exchange = HttpExchange(request, socket, input)
            try {
                handler(exchange)
            } catch (e: SocketException) {
                // The peer went away mid-response: normal for players and curl -N.
            } catch (e: IOException) {
                ZLog.d(TAG, "$name ${request.method} ${request.path}: ${e.message}")
            } catch (e: Exception) {
                ZLog.e(TAG, "$name ${request.method} ${request.path} failed", e)
                if (!exchange.responded) runCatching { exchange.sendText(500, "internal error\n") }
            }
            runCatching { exchange.output.flush() }
        } catch (e: IOException) {
            // Timed out or reset before a request arrived.
        } finally {
            active.decrementAndGet()
            runCatching { socket.close() }
        }
    }

    companion object {
        private const val TAG = "Http"

        /** Loopback and private (RFC 1918 / link-local / IPv6 ULA) peers only. */
        fun isLan(addr: InetAddress): Boolean {
            if (addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress) return true
            if (addr is Inet6Address) {
                val first = addr.address[0].toInt() and 0xFF
                if (first and 0xFE == 0xFC) return true
                // IPv4-mapped peers arrive as ::ffff:a.b.c.d on dual-stack sockets.
                if (addr.isIPv4CompatibleAddress) return isLan(InetAddress.getByAddress(addr.address.copyOfRange(12, 16)))
            }
            return false
        }
    }
}
