package app.zmby.catalog

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CatalogException(message: String, val retryAfterMs: Long? = null) : IOException(message)

/** Credentials are scoped to TMDB requests and never enter keys, URLs, exceptions or logs. */
class CatalogHttp(private val http: OkHttpClient, private val dao: CatalogStorage, private val token: () -> String,
    private val tmdbBase: String = "https://api.themoviedb.org/3/", private val bgmBase: String = "https://api.bgm.tv/v0/",
    private val clock: () -> Long = System::currentTimeMillis) {
    val json = Json { ignoreUnknownKeys = true }
    private val blockedUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val requests = Semaphore(4)
    private val locks = Array(32) { Mutex() }
    val configured get() = token().isNotBlank()
    suspend fun tmdb(path: String, params: Map<String, String> = emptyMap(), ttl: Long = DAY): JsonObject {
        if (!configured) throw CatalogException("请在设置中填写 TMDB API Read Access Token")
        return get(tmdbBase + path, params, ttl, true).obj()
    }
    suspend fun bgm(path: String, params: Map<String, String> = emptyMap()): JsonElement = get(bgmBase + path, params, DAY, false)
    private val aliasesLock = Mutex()
    private var aliasesMemory: Pair<Long, JsonArray>? = null
    suspend fun aliases(): JsonArray = aliasesLock.withLock {
        aliasesMemory?.takeIf { clock() - it.first in 0 until 7 * DAY }?.let { return@withLock it.second }
        var data: JsonArray? = null
        for (endpoint in listOf("https://cdn.jsdelivr.net/npm/bangumi-data@0.3/dist/data.json", "https://unpkg.com/bangumi-data@0.3/dist/data.json")) {
            try { data = AnimeAliases.dataset(get(endpoint, emptyMap(), 7 * DAY, false)); if (data != null) break }
            catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
        val loaded = data ?: throw CatalogException("bangumi-data 暂时无法读取")
        aliasesMemory = clock() to loaded
        loaded
    }
    private suspend fun get(base: String, params: Map<String, String>, ttl: Long, auth: Boolean): JsonElement = withContext(Dispatchers.IO) {
        val url = base.toHttpUrl().newBuilder().apply { params.toSortedMap().forEach { (k,v) -> addQueryParameter(k,v) } }.build()
        val key = url.toString()
        locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            val cached = dao.get(key)
            fun parsed() = cached?.let { runCatching { json.parseToJsonElement(it.json) }.getOrNull() }
            if (cached != null && clock() - cached.fetchedAt in 0 until ttl) parsed()?.let { return@withLock it }
            try {
                if ((blockedUntil[url.host] ?: 0) > clock()) return@withLock parsed() ?: throw CatalogException("影视数据库暂时限制请求，请稍后重试")
                val request = Request.Builder().url(url).header("User-Agent", "zmby/0.1 (https://github.com/zhenyuefu/zmby)")
                    .header("Accept", "application/json").apply { if (auth) header("Authorization", "Bearer ${token().trim()}") }.build()
                val body = requests.withPermit { fetch(request) }
                val result = json.parseToJsonElement(body)
                dao.put(CatalogCache(key, body, clock()))
                result
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is CatalogException && e.retryAfterMs != null) blockedUntil[url.host] = clock() + e.retryAfterMs
                parsed() ?: throw CatalogException(if (e is CatalogException) e.message.orEmpty() else "影视数据库暂时无法连接")
            }
        }
    }
    private suspend fun fetch(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (!continuation.isCancelled) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (!it.isSuccessful) throw CatalogException("影视数据库请求失败（HTTP ${it.code}）", if (it.code == 429) (it.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 3600) ?: 60) * 1000 else null)
                        val body = it.body ?: throw CatalogException("影视数据库返回空内容")
                        val source = body.source()
                        source.request(MAX_BYTES + 1)
                        if (source.buffer.size > MAX_BYTES) throw CatalogException("影视数据库响应过大")
                        continuation.resume(source.readUtf8())
                    }
                } catch (e: Exception) { if (!continuation.isCancelled) continuation.resumeWithException(e) }
            }
        })
    }
    companion object { const val DAY = 86_400_000L; private const val MAX_BYTES = 24 * 1024 * 1024L }
}
