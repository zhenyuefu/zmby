package app.zmby.danmu

import app.zmby.log.ZLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The 弹幕服务器 the user typed (danmu design: 数据源); [base] empty means 弹幕 is off. */
data class DanmuEndpoint(val base: String, val appId: String = "", val appSecret: String = "") {
    val enabled: Boolean get() = base.isNotBlank()

    /** What the cache files are keyed by: the address only, never the secret. */
    val cacheKey: String get() = base.trimEnd('/')
}

class DanmuHttpException(val code: Int) : IOException("弹幕请求失败（HTTP $code）")

/**
 * A dandanplay v2 compatible API: the official one (signed with AppId and AppSecret) or a
 * self-hosted one (no signature). Only names, numbers and episode ids are sent.
 */
class DandanplayApi(http: OkHttpClient, private val clock: () -> Long = System::currentTimeMillis) {
    private val http = http.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()

    suspend fun match(endpoint: DanmuEndpoint, fileName: String): String =
        request(endpoint, "/api/v2/match", body = buildJsonObject { put("fileName", fileName) }.toString())

    suspend fun searchEpisodes(endpoint: DanmuEndpoint, name: String, episode: Int?, movie: Boolean = false): String =
        request(endpoint, "/api/v2/search/episodes", listOfNotNull("anime" to name,
            (if (movie) "movie" else episode?.toString())?.let { "episode" to it }))

    suspend fun comments(endpoint: DanmuEndpoint, episodeId: Long): String =
        request(endpoint, "/api/v2/comment/$episodeId", listOf("format" to "json", "withRelated" to "true", "chConvert" to "1"))

    private suspend fun request(endpoint: DanmuEndpoint, path: String, query: List<Pair<String, String>> = emptyList(), body: String? = null): String = withContext(Dispatchers.IO) {
        val url = url(endpoint.base, path, query) ?: throw IOException("弹幕服务器地址无效")
        val request = Request.Builder().url(url).header("Accept", "application/json")
        if (body != null) request.post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (endpoint.appId.isNotBlank() && endpoint.appSecret.isNotBlank()) {
            ZLog.secret(endpoint.appSecret)
            val ts = (clock() / 1000).toString()
            request.header("X-AppId", endpoint.appId).header("X-Timestamp", ts)
                .header("X-Signature", signature(endpoint.appId, ts, path, endpoint.appSecret))
        }
        val started = System.nanoTime()
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request.build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use { r ->
                            ZLog.d(TAG, "${if (body == null) "GET" else "POST"} ${url.host}$path -> ${r.code} ${(System.nanoTime() - started) / 1_000_000}ms")
                            if (!r.isSuccessful) throw DanmuHttpException(r.code)
                            val source = r.body?.source() ?: throw IOException("弹幕响应为空")
                            source.request(MAX_BYTES + 1)
                            if (source.buffer.size > MAX_BYTES) throw IOException("弹幕响应过大")
                            continuation.resume(source.readUtf8())
                        }
                    } catch (e: Exception) {
                        if (!continuation.isCancelled) continuation.resumeWithException(e)
                    }
                }
            })
        }
    }

    companion object {
        private const val TAG = "Danmu"
        private const val MAX_BYTES = 32L * 1024 * 1024

        /** `<base>/api/v2/...`; a path in [base] (a self-hosted server's token) is kept and kept out of the log. */
        fun url(base: String, path: String, query: List<Pair<String, String>>): okhttp3.HttpUrl? {
            val root = base.trim().trimEnd('/').toHttpUrlOrNull() ?: return null
            root.encodedPath.trim('/').takeIf { it.isNotEmpty() }?.let { ZLog.secret(it) }
            val b = root.newBuilder()
            path.trim('/').split('/').forEach { b.addPathSegment(it) }
            query.forEach { (k, v) -> b.addQueryParameter(k, v) }
            return b.build()
        }

        /** dandanplay's signature: base64(sha256(AppId + timestamp + path + AppSecret)). */
        fun signature(appId: String, timestamp: String, path: String, secret: String): String =
            Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest((appId + timestamp + path + secret).toByteArray()))
    }
}
