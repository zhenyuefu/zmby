package app.zmby.playback

import app.zmby.log.Redactor
import app.zmby.log.ZLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** What one `Range: bytes=0-0` request to a stream URL answered (redirects not followed). */
data class ProbeResult(val status: Int, val location: String?, val maxAgeSeconds: Long?) {
    val isRedirect: Boolean get() = status in 300..399 && location != null
    val honorsRange: Boolean get() = status == 206
}

/** The outcome of probing a server's stream URL once. */
data class StreamCheck(
    /** "" or "/emby": the path prefix under which Range requests work. */
    val rangePrefix: String,
    /** Set when the stream URL answers with a redirect (302 cloud-drive servers). */
    val redirect: ProbeResult?,
)

/**
 * Checks how a server serves streams before the first playback (plan §2.4, §3.3): some reverse
 * proxies only handle Range under `/emby` (at the root they answer 200 with the whole file, so the
 * player cannot seek), and 302 servers redirect the stream to signed cloud-drive links.
 */
class StreamProbe(http: OkHttpClient, private val userAgent: String) {
    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false).build()

    suspend fun probe(url: String, userAgent: String = this.userAgent): ProbeResult? = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        runCatching {
            val request = Request.Builder().url(url).header("Range", "bytes=0-0").header("User-Agent", userAgent).get().build()
            http.newCall(request).execute().use { r ->
                ProbeResult(r.code, r.header("Location")?.let { r.request.url.resolve(it)?.toString() ?: it }, maxAge(r.header("Cache-Control")))
            }
        }.onSuccess {
            ZLog.d(TAG, "probe $url -> ${it.status}${it.location?.let { l -> " -> ${Redactor.linkShape(l)}" } ?: ""} max-age=${it.maxAgeSeconds} ${(System.nanoTime() - started) / 1_000_000}ms")
        }.onFailure {
            ZLog.w(TAG, "probe $url failed: $it")
        }.getOrNull()
    }

    /** Null when the server could not be reached; the caller then plays the URL unchanged. */
    suspend fun check(url: String, userAgent: String = this.userAgent): StreamCheck? {
        val current = StreamUrls.prefixOf(url)
        val first = probe(url, userAgent) ?: return null
        if (first.isRedirect || first.honorsRange) return StreamCheck(current, first.takeIf { it.isRedirect })
        // 200 (Range ignored) or an error under this prefix: try the other one.
        val other = StreamUrls.otherPrefix(current)
        val second = probe(StreamUrls.withPrefix(url, other), userAgent)
        return when {
            second != null && (second.honorsRange || second.isRedirect) -> StreamCheck(other, second.takeIf { it.isRedirect })
            first.status in 200..299 -> StreamCheck(current, null)
            second != null && second.status in 200..299 -> StreamCheck(other, null)
            else -> StreamCheck(current, null)
        }
    }

    companion object {
        private const val TAG = "Probe"

        fun maxAge(cacheControl: String?): Long? =
            cacheControl?.let { Regex("max-age\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toLongOrNull() }
    }
}
