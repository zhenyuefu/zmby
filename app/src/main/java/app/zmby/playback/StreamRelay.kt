package app.zmby.playback

import app.zmby.log.Redactor
import app.zmby.log.ZLog
import app.zmby.net.HttpExchange
import app.zmby.net.MiniHttpServer
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Local forwarding for 302 cloud-drive servers (plan §3.3). The player only ever sees
 * `http://127.0.0.1:<port>/r/<id>/<name>`; the relay asks Emby for the stream without following
 * the redirect, caches the signed link with its `max-age`, re-resolves 30 s before it expires or
 * as soon as the link answers 401/403/410, and streams the bytes through with the server's
 * User-Agent (zmby's, or the official client's for a server that checks it; the player itself can
 * only send `okhttp/4.11.0`).
 *
 * Hard rules from LinPlayer's experience: every player connection fetches on its own (no shared
 * cursor); responses always close the connection; Range / Content-Range / Content-Length /
 * Accept-Ranges pass through, and a short upstream body is resumed rather than passed off as the
 * whole range; idle (per-read) timeouts, not a total timeout; a player that disconnects cancels
 * its upstream request. No prefetching and no disk cache.
 */
class StreamRelay(
    http: OkHttpClient,
    private val userAgent: String,
    private val now: () -> Long = System::currentTimeMillis,
    // Not getLoopbackAddress(): on Android that is ::1, while the player is given 127.0.0.1.
    bind: InetAddress = InetAddress.getByName("127.0.0.1"),
) {
    /** Asks Emby where the stream is: redirects are the answer, not something to follow. */
    private val resolver = http.newBuilder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Fetches the bytes; a slow link may take long for a large range, so only idle reads time out. */
    private val fetcher = http.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS)
        // Keep OkHttp's route retry: after a connection breaks mid-body its route is postponed, and
        // without retries a single-address link would then never be dialled again.
        .retryOnConnectionFailure(true)
        .build()

    private val server = MiniHttpServer("relay", bind, listOf(0), accept = { it.isLoopbackAddress }, readTimeoutMs = 30_000) { serve(it) }
    private val sessions = object : LinkedHashMap<String, RelaySession>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RelaySession>): Boolean {
            if (size <= MAX_SESSIONS) return false
            eldest.value.close()
            return true
        }
    }
    private val random = SecureRandom()
    /** The call behind each open upstream response, so a departed player can cancel it. */
    private val calls = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Response, Call>())

    /**
     * Registers [upstream] (an Emby stream URL) and returns the local URL to give the player; Emby and
     * the link it redirects to are asked with [agent].
     */
    fun register(upstream: String, fileName: String?, agent: String = userAgent): String {
        val port = server.start()
        val id = ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        synchronized(sessions) { sessions[id] = RelaySession(upstream, agent) }
        val name = fileName?.replace(Regex("[^\\w.\\-]"), "_")?.takeIf { it.isNotBlank() } ?: "stream"
        ZLog.i(TAG, "relay $id for $upstream on port $port")
        return "http://127.0.0.1:$port/r/$id/$name"
    }

    /** Revoke only this playback; other sessions and the listening server remain available. */
    fun unregister(url: String) {
        val id = url.substringAfter("/r/", "").substringBefore('/')
        synchronized(sessions) { sessions.remove(id) }?.close()
    }

    fun stop() {
        synchronized(sessions) { sessions.values.forEach { it.close() }; sessions.clear() }
        server.stop()
    }

    private class Target(val url: String, val redirected: Boolean, val expiresAt: Long?, val agent: String)

    /** One played stream: the Emby URL and the signed link it currently resolves to. */
    private inner class RelaySession(val upstream: String, val agent: String) {
        private val activeCalls = java.util.concurrent.ConcurrentHashMap.newKeySet<Call>()
        @Volatile private var closed = false
        fun track(call: Call) {
            activeCalls.add(call)
            if (closed) call.cancel()
        }
        fun untrack(call: Call) { activeCalls.remove(call) }
        fun close() {
            closed = true
            activeCalls.forEach { it.cancel() }
            activeCalls.clear()
        }
        private var target: Target? = null

        @Synchronized
        fun current(): Target {
            val t = target
            if (t != null && (t.expiresAt == null || now() < t.expiresAt)) return t
            return resolve(if (t == null) "first use" else "link expires soon")
        }

        /** Re-resolves unless another connection already did so after [failed] was handed out. */
        @Synchronized
        fun replace(failed: Target, why: String): Target {
            val t = target
            if (t != null && t !== failed) return t
            return resolve(why)
        }

        private fun resolve(why: String): Target {
            val request = Request.Builder().url(upstream).header("User-Agent", agent).header("Range", "bytes=0-0").get().build()
            val started = System.nanoTime()
            val call = resolver.newCall(request)
            track(call)
            val t = try { call.execute().use { r ->
                val location = r.header("Location")
                if (r.code in 300..399 && location != null) {
                    val url = r.request.url.resolve(location)?.toString() ?: location
                    val maxAge = StreamProbe.maxAge(r.header("Cache-Control"))
                    // Re-resolve 30 s early; without max-age keep the link until it fails.
                    Target(url, true, maxAge?.let { now() + (it - EARLY_SECONDS).coerceAtLeast(MIN_TTL_SECONDS) * 1000 }, agent)
                } else if (r.isSuccessful) {
                    Target(upstream, false, null, agent)
                } else {
                    throw IOException("upstream answered ${r.code} while resolving")
                }
            } } finally { untrack(call) }
            ZLog.i(
                TAG,
                "resolve ($why): ${if (t.redirected) "302 -> ${Redactor.linkShape(t.url)}" else "no redirect"}" +
                    "${t.expiresAt?.let { " valid ${(it - now()) / 1000}s" } ?: ""} in ${(System.nanoTime() - started) / 1_000_000}ms",
            )
            target = t
            return t
        }
    }

    private fun serve(ex: HttpExchange) {
        val req = ex.request
        val id = req.path.removePrefix("/r/").substringBefore('/')
        val session = synchronized(sessions) { sessions[id] }
        if (!req.path.startsWith("/r/") || session == null) return ex.sendText(404, "unknown stream\n")
        if (req.method != "GET" && req.method != "HEAD") return ex.sendText(405, "GET only\n")
        val opened = mutableListOf<Call>()
        try {
            val range = parseRange(req.header("range"))
            ZLog.d(TAG, "player ${req.method} ${req.header("range") ?: "(no range)"} on $id")

            var target = try {
                session.current()
            } catch (e: IOException) {
                ZLog.w(TAG, "cannot resolve stream: $e")
                return ex.sendText(502, "cannot resolve stream\n")
            }
            var response = open(session, opened, target, range?.let { "bytes=${it.first}-${it.second ?: ""}" })
            if (response == null || response.code in RETRYABLE) {
                response?.close()
                target = runCatching { session.replace(target, "link answered ${response?.code ?: "nothing"}") }.getOrNull()
                    ?: return ex.sendText(502, "cannot resolve stream\n")
                response = open(session, opened, target, range?.let { "bytes=${it.first}-${it.second ?: ""}" })
                    ?: return ex.sendText(502, "upstream unreachable\n")
            }
            response.use { r ->
                if (r.code !in 200..299) {
                    ZLog.w(TAG, "upstream answered ${r.code} for ${req.header("range")}")
                    ex.sendHead(r.code, listOf("Content-Length" to "0"))
                    return
                }
                val headers = PASS_THROUGH.mapNotNull { h -> r.header(h)?.let { h to it } }
                ex.sendHead(r.code, headers)
                if (req.method == "HEAD") return
                val expected = r.header("Content-Length")?.toLongOrNull()
                var sent = copy(r, ex)
                // A short body is not the whole range: continue from where it stopped.
                var resumes = 0
                while (expected != null && sent < expected && resumes < MAX_RESUMES) {
                    resumes++
                    val from = (range?.first ?: 0) + sent
                    val to = range?.second ?: contentRangeEnd(r.header("Content-Range"))
                    ZLog.w(TAG, "upstream body ended at $sent of $expected bytes; resuming at $from (try $resumes)")
                    if (now() >= (target.expiresAt ?: Long.MAX_VALUE)) target = session.current()
                    var next = open(session, opened, target, "bytes=$from-${to ?: ""}")
                    if (next == null || next.code in RETRYABLE) {
                        next?.close()
                        target = runCatching { session.replace(target, "resume answered ${next?.code ?: "nothing"}") }.getOrNull() ?: break
                        next = open(session, opened, target, "bytes=$from-${to ?: ""}") ?: break
                    }
                    next.use { n ->
                        if (n.code != 206) return@use
                        sent += copy(n, ex)
                    }
                }
                if (expected != null && sent < expected) throw IOException("gave up after $sent of $expected bytes")
            }
        } finally {
            opened.forEach { it.cancel(); session.untrack(it) }
        }
    }

    /** Null when the link cannot be reached at all. */
    private fun open(session: RelaySession, opened: MutableList<Call>, target: Target, range: String?): Response? {
        val request = Request.Builder().url(target.url).header("User-Agent", target.agent)
            .apply { if (range != null) header("Range", range) }
            .get().build()
        val call = fetcher.newCall(request)
        session.track(call)
        opened.add(call)
        return try {
            call.execute().also { calls[it] = call }
        } catch (e: IOException) {
            ZLog.w(TAG, "open ${Redactor.linkShape(target.url)} failed: $e")
            null
        }
    }

    /** Copies the body to the player; a player that goes away cancels the upstream request. */
    private fun copy(r: Response, ex: HttpExchange): Long {
        val call: Call? = calls.remove(r)
        val body = r.body ?: return 0
        val buf = ByteArray(BUFFER)
        var total = 0L
        body.byteStream().use { input ->
            while (true) {
                val n = try {
                    input.read(buf)
                } catch (e: IOException) {
                    // Upstream broke off; the caller resumes if the range is not complete.
                    ZLog.d(TAG, "upstream read failed after $total bytes: $e")
                    return total
                }
                if (n < 0) return total
                try {
                    ex.output.write(buf, 0, n)
                } catch (e: IOException) {
                    call?.cancel()
                    throw e
                }
                total += n
            }
        }
    }

    companion object {
        private const val TAG = "Relay"
        private const val EARLY_SECONDS = 30L
        private const val MIN_TTL_SECONDS = 5L
        private const val MAX_SESSIONS = 16
        private const val MAX_RESUMES = 3
        private const val BUFFER = 64 * 1024
        private val RETRYABLE = setOf(401, 403, 410)
        private val PASS_THROUGH = listOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "Last-Modified", "ETag")

        /** `bytes=a-b` / `bytes=a-` as (a, b?); suffix ranges and multi-ranges are passed as "whole file". */
        fun parseRange(header: String?): Pair<Long, Long?>? {
            val spec = header?.trim()?.removePrefix("bytes=")?.substringBefore(',') ?: return null
            val a = spec.substringBefore('-').trim().toLongOrNull() ?: return null
            val b = spec.substringAfter('-', "").trim().toLongOrNull()
            return a to b
        }

        private fun contentRangeEnd(header: String?): Long? =
            header?.substringAfter(' ')?.substringBefore('/')?.substringAfter('-')?.toLongOrNull()
    }
}
