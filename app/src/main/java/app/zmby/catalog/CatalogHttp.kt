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

/**
 * Credentials are scoped to TMDB requests and never enter keys, URLs, exceptions or logs. The
 * bangumi-data alias index is kept in [animeIndexFile] (app-private), not in the Room cache.
 */
class CatalogHttp(private val http: OkHttpClient, private val dao: CatalogStorage, private val token: () -> String,
    private val tmdbBase: String = TmdbEndpoint.DEFAULT, private val bgmBase: String = "https://api.bgm.tv/v0/",
    private val clock: () -> Long = System::currentTimeMillis,
    private val animeIndexFile: java.io.File? = null,
    private val animeEndpoints: List<String> = ANIME_ENDPOINTS,
    private val userAgent: String = "zmby (https://github.com/zhenyuefu/zmby)",
    private val tmdbBaseProvider: () -> String = { tmdbBase }) {
    val json = Json { ignoreUnknownKeys = true }
    private val blockedUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val requests = Semaphore(4)
    private val locks = Array(32) { Mutex() }
    val configured get() = token().isNotBlank()
    suspend fun tmdb(path: String, params: Map<String, String> = emptyMap(), ttl: Long = DAY): JsonObject {
        if (!configured) throw CatalogException("请在设置中填写 TMDB API Read Access Token")
        return get(tmdbBaseProvider() + path, params, ttl, true).obj()
    }
    suspend fun bgm(path: String, params: Map<String, String> = emptyMap()): JsonElement = get(bgmBase + path, params, DAY, false)
    private val animeLock = Mutex()
    /** The compact index only; the downloaded dataset itself is never kept. */
    private var animeMemory: Pair<Long, AnimeIndex>? = null
    private var animeRetryAt = 0L
    private fun fresh(at: Long) = clock() - at in 0 until ANIME_TTL

    /**
     * The bangumi-data alias index: memory, then the app-private file, then a download (at most one
     * attempt per [ANIME_RETRY_MS] after a failure). A stale index beats none when offline.
     */
    suspend fun animeIndex(): AnimeIndex = animeLock.withLock {
        withContext(Dispatchers.IO) {
            animeMemory?.takeIf { fresh(it.first) }?.let { return@withContext it.second }
            val stored = animeMemory ?: animeIndexFile?.let { file ->
                try { AnimeIndex.read(file) } catch (e: Exception) { app.zmby.log.ZLog.w(TAG, "anime index unreadable, fetching again", e); null }
            }
            if (stored != null && fresh(stored.first)) { animeMemory = stored; return@withContext stored.second }
            if (clock() >= animeRetryAt) {
                for (endpoint in animeEndpoints) {
                    try {
                        val index = download(endpoint)
                        val at = clock()
                        animeIndexFile?.let { file -> runCatching { index.write(file, at) }.onFailure { app.zmby.log.ZLog.w(TAG, "anime index not saved", it) } }
                        animeMemory = at to index
                        app.zmby.log.ZLog.i(TAG, "bangumi-data index: ${index.size} items")
                        return@withContext index
                    } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        app.zmby.log.ZLog.d(TAG, "bangumi-data from ${endpoint.toHttpUrl().host}: ${e.message}")
                    }
                }
                animeRetryAt = clock() + ANIME_RETRY_MS
            }
            stored?.let { animeMemory = it; return@withContext it.second }
            throw CatalogException("bangumi-data 暂时无法读取")
        }
    }

    /** Streams the dataset into a temporary file next to the index, then decodes it from there. */
    private suspend fun download(endpoint: String): AnimeIndex {
        val request = Request.Builder().url(endpoint).header("User-Agent", userAgent).header("Accept", "application/json").build()
        val dir = animeIndexFile?.parentFile
        val call = http.newCall(request)
        return requests.withPermit {
            val response = suspendCancellableCoroutine<Response> { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { if (!continuation.isCancelled) continuation.resumeWithException(e) }
                    override fun onResponse(call: Call, response: Response) { continuation.resume(response) { _, _, _ -> response.close() } }
                })
            }
            withContext(Dispatchers.IO) {
                response.use {
                    if (!it.isSuccessful) throw CatalogException("bangumi-data 请求失败（HTTP ${it.code}）")
                    val body = it.body ?: throw CatalogException("bangumi-data 返回空内容")
                    if (dir == null) {
                        val source = body.source(); source.request(MAX_BYTES + 1)
                        if (source.buffer.size > MAX_BYTES) throw CatalogException("bangumi-data 响应过大")
                        source.buffer.inputStream().use(AnimeIndex::decode)
                    } else {
                        dir.mkdirs()
                        val tmp = java.io.File.createTempFile("bangumi-data", ".json", dir)
                        try {
                            tmp.outputStream().use { out ->
                                val input = body.byteStream(); val buffer = ByteArray(64 * 1024); var total = 0L
                                while (true) {
                                    ensureActive()
                                    val n = input.read(buffer); if (n < 0) break
                                    total += n; if (total > MAX_BYTES) throw CatalogException("bangumi-data 响应过大")
                                    out.write(buffer, 0, n)
                                }
                            }
                            tmp.inputStream().use(AnimeIndex::decode)
                        } finally { tmp.delete() }
                    }
                }
            }
        }
    }
    private suspend fun get(base: String, params: Map<String, String>, ttl: Long, auth: Boolean): JsonElement = withContext(Dispatchers.IO) {
        val url = base.toHttpUrl().newBuilder().apply { params.toSortedMap().forEach { (k,v) -> addQueryParameter(k,v) } }.build()
        val key = url.toString()
        locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            // An unreadable row (too large for the cursor window, corrupt) is a miss, not a failure.
            val cached = try { dao.get(key) } catch (e: CancellationException) { throw e }
                catch (e: Exception) { app.zmby.log.ZLog.w(TAG, "cache row unreadable: ${e.javaClass.simpleName}"); null }
            fun parsed() = cached?.let { runCatching { json.parseToJsonElement(it.json) }.getOrNull() }
            if (cached != null && clock() - cached.fetchedAt in 0 until ttl) parsed()?.let { return@withLock it }
            try {
                if ((blockedUntil[url.host] ?: 0) > clock()) return@withLock parsed() ?: throw CatalogException("影视数据库暂时限制请求，请稍后重试")
                val request = Request.Builder().url(url).header("User-Agent", userAgent)
                    .header("Accept", "application/json").apply { if (auth) header("Authorization", "Bearer ${token().trim()}") }.build()
                val body = requests.withPermit { fetch(request) }
                val result = json.parseToJsonElement(body)
                try { dao.put(CatalogCache(key, body, clock())) } catch (e: CancellationException) { throw e }
                catch (e: Exception) { app.zmby.log.ZLog.w(TAG, "cache write failed: ${e.javaClass.simpleName}") }
                result
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is CatalogException && e.retryAfterMs != null) blockedUntil[url.host] = clock() + e.retryAfterMs
                parsed() ?: throw CatalogException(if (e is CatalogException) e.message.orEmpty() else "影视数据库暂时无法连接")
            }
        }
    }
    /**
     * Checks a TMDB token before it is saved (用手机输入): `GET /3/configuration` with it, nothing
     * cached. Null when TMDB accepts it, else the reason in plain words; the token itself never
     * appears in the reason, the URL or the log.
     */
    suspend fun verifyToken(candidate: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(tmdbBaseProvider() + "configuration").header("User-Agent", userAgent)
            .header("Accept", "application/json").header("Authorization", "Bearer ${candidate.trim()}").build()
        try {
            requests.withPermit { fetch(request) }
            null
        } catch (e: CancellationException) { throw e }
        catch (e: CatalogException) {
            if (e.message.orEmpty().contains("401")) "TMDB 不认这个令牌，请确认复制的是「API 读取访问令牌」" else "TMDB 暂时无法验证，请稍后重试"
        } catch (e: Exception) { "连不上 TMDB，请检查电视的网络" }
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
    companion object {
        const val DAY = 86_400_000L
        private const val TAG = "Catalog"
        private const val MAX_BYTES = 24 * 1024 * 1024L
        const val ANIME_TTL = 7 * DAY
        const val ANIME_RETRY_MS = 60 * 60_000L
        val ANIME_ENDPOINTS = listOf("https://cdn.jsdelivr.net/npm/bangumi-data@0.3/dist/data.json", "https://unpkg.com/bangumi-data@0.3/dist/data.json")
    }
}
