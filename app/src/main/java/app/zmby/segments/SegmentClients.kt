package app.zmby.segments

import app.zmby.data.SegmentProvider
import app.zmby.log.ZLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.math.roundToLong

/** One external segment database. Read-only: zmby never submits or registers anything. */
interface SegmentClient {
    val provider: SegmentProvider
    suspend fun fetch(query: SegmentQuery, key: String?): ProviderAnswer
}

/** An HTTP answer reduced to what the clients need. */
data class HttpAnswer(val code: Int, val body: String?, val retryAfterS: Long? = null)

/** Shared GET with the app's client: JSON, a short timeout, no cookies; the key only in a header. */
class SegmentHttp(private val http: OkHttpClient, private val userAgent: String) {
    suspend fun get(url: HttpUrl, bearer: String? = null): HttpAnswer? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", userAgent)
            .apply { if (!bearer.isNullOrBlank()) header("Authorization", "Bearer $bearer") }
            .build()
        runCatching {
            http.newCall(request).execute().use { r ->
                HttpAnswer(r.code, r.body?.string(), r.header("Retry-After")?.trim()?.toLongOrNull())
            }
        }.onFailure { ZLog.w(TAG, "${url.host} unreachable: $it") }.getOrNull()
    }

    private companion object { const val TAG = "Segments" }
}

private val JSON = Json { ignoreUnknownKeys = true }

private fun JsonElement?.num(): Long? = (this as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is JsonNull }?.let { p ->
    p.longOrNull ?: p.doubleOrNull?.roundToLong()
}

private fun failed(provider: SegmentProvider, a: HttpAnswer?): ProviderAnswer = when {
    a == null -> ProviderAnswer.Failed("unreachable")
    a.code == 429 -> ProviderAnswer.Failed("rate limited", (a.retryAfterS ?: 300).coerceIn(1, 86_400) * 1000)
    a.code == 401 || a.code == 403 -> ProviderAnswer.Failed("key refused (${a.code})", 3600_000)
    else -> ProviderAnswer.Failed("HTTP ${a.code}")
}.also { ZLog.w("Segments", "${provider.label}: ${(it as ProviderAnswer.Failed).reason}") }

/**
 * TheIntroDB, API v4 (`GET /v4/media`). The video is named by one ID in the API's own priority
 * (TVDB, then TMDB, then IMDb); a 404 under one ID may still be found under the next. Segments are
 * in milliseconds; intro and recap may lack a start (from 0), credits and preview an end (to the end).
 */
class TheIntroDbClient(private val http: SegmentHttp, private val base: String = "https://api.theintrodb.org/v4") : SegmentClient {
    override val provider = SegmentProvider.TheIntroDB

    fun urls(q: SegmentQuery): List<HttpUrl> {
        if (!q.episodeOk) return emptyList()
        return listOfNotNull(q.tvdb?.let { "tvdb_id" to it }, q.tmdb?.let { "tmdb_id" to it }, q.imdb?.let { "imdb_id" to it }).map { (name, id) ->
            "$base/media".toHttpUrl().newBuilder().apply {
                addQueryParameter(name, id)
                if (!q.movie) {
                    addQueryParameter("season", q.season.toString())
                    addQueryParameter("episode", q.episode.toString())
                }
                q.durationMs?.takeIf { it > 0 }?.let { addQueryParameter("duration_ms", it.toString()) }
            }.build()
        }
    }

    override suspend fun fetch(query: SegmentQuery, key: String?): ProviderAnswer {
        val urls = urls(query)
        if (urls.isEmpty()) return ProviderAnswer.NotAsked
        for (url in urls) {
            val a = http.get(url, key)
            when (a?.code) {
                200 -> return parse(a.body.orEmpty())?.let { if (it.segments.isEmpty()) ProviderAnswer.Empty else ProviderAnswer.Found(it) } ?: ProviderAnswer.Empty
                404 -> continue
                else -> return failed(provider, a)
            }
        }
        return ProviderAnswer.Empty
    }

    companion object {
        /** The best-rated span of each kind (confidence, then submissions); null when the body is not an answer. */
        fun parse(body: String): ProviderSegments? {
            val root = runCatching { JSON.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
            if (root.containsKey("error")) return ProviderSegments(emptyList())
            val out = SegmentKind.entries.mapNotNull { kind ->
                val spans = (root[kind.key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                spans.mapNotNull { o ->
                    var start = o["start_ms"].num()
                    var end = o["end_ms"].num()
                    if (kind.atStart) { if (end == null) return@mapNotNull null; if (start == null) start = 0 }
                    else if (start == null) return@mapNotNull null
                    if (end != null && end <= start) return@mapNotNull null
                    val confidence = (o["confidence"] as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull ?: 0.5
                    val score = confidence + (o["submission_count"].num() ?: 1) * 0.001
                    Triple(start, end, score)
                }.maxByOrNull { it.third }?.let { Segment(kind, it.first, it.second) }
            }
            return ProviderSegments(out, root["duration_ms"].num())
        }
    }
}

/**
 * IntroDB (`GET https://api.introdb.app/segments`): IMDb IDs only — the series' with season and
 * episode, or a film with `is_movie=true`. Its `outro` is the credits; `post_credits` is not a kind
 * zmby skips.
 */
class IntroDbClient(private val http: SegmentHttp, private val base: String = "https://api.introdb.app") : SegmentClient {
    override val provider = SegmentProvider.IntroDB

    fun url(q: SegmentQuery): HttpUrl? {
        val imdb = q.imdb ?: return null
        if (!q.episodeOk) return null
        return "$base/segments".toHttpUrl().newBuilder().apply {
            addQueryParameter("imdb_id", imdb)
            if (q.movie) addQueryParameter("is_movie", "true")
            else {
                addQueryParameter("season", q.season.toString())
                addQueryParameter("episode", q.episode.toString())
            }
        }.build()
    }

    override suspend fun fetch(query: SegmentQuery, key: String?): ProviderAnswer {
        val url = url(query) ?: return ProviderAnswer.NotAsked
        val a = http.get(url)
        return when (a?.code) {
            200 -> parse(a.body.orEmpty())?.let { if (it.segments.isEmpty()) ProviderAnswer.Empty else ProviderAnswer.Found(it) } ?: ProviderAnswer.Empty
            404 -> ProviderAnswer.Empty
            else -> failed(provider, a)
        }
    }

    companion object {
        private val FIELDS = listOf(SegmentKind.Intro to "intro", SegmentKind.Recap to "recap", SegmentKind.Credits to "outro")

        fun parse(body: String): ProviderSegments? {
            val root = runCatching { JSON.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
            val out = FIELDS.mapNotNull { (kind, field) ->
                val o = root[field] as? JsonObject ?: return@mapNotNull null
                val start = o["start_ms"].num() ?: o["start_sec"]?.jsonPrimitive?.doubleOrNull?.let { (it * 1000).roundToLong() } ?: return@mapNotNull null
                val end = o["end_ms"].num() ?: o["end_sec"]?.jsonPrimitive?.doubleOrNull?.let { (it * 1000).roundToLong() }
                if (end != null && end <= start) null else Segment(kind, start, end)
            }
            return ProviderSegments(out)
        }
    }
}

/**
 * PublicMetaDB (`GET https://publicmetadb.com/api/external/skips`, `Authorization: Bearer <key>`):
 * TMDB IDs only. It lists one record per contributor and release; records are never mixed
 * (different releases do not line up). Preferred: a streaming release, then the record with more
 * segments, then the newest, then the smallest id (stable whatever the order of the answer).
 */
class PublicMetaDbClient(private val http: SegmentHttp, private val base: String = "https://publicmetadb.com") : SegmentClient {
    override val provider = SegmentProvider.PublicMetaDB

    fun url(q: SegmentQuery): HttpUrl? {
        val tmdb = q.tmdb ?: return null
        if (!q.episodeOk) return null
        return "$base/api/external/skips".toHttpUrl().newBuilder().apply {
            addQueryParameter("tmdb_id", tmdb)
            addQueryParameter("media_type", if (q.movie) "movie" else "tv")
            if (!q.movie) {
                addQueryParameter("season", q.season.toString())
                addQueryParameter("episode", q.episode.toString())
            }
            addQueryParameter("perPage", "20")
        }.build()
    }

    override suspend fun fetch(query: SegmentQuery, key: String?): ProviderAnswer {
        if (key.isNullOrBlank()) return ProviderAnswer.NotAsked
        val url = url(query) ?: return ProviderAnswer.NotAsked
        val a = http.get(url, key)
        return when (a?.code) {
            200 -> parse(a.body.orEmpty())?.let { if (it.segments.isEmpty()) ProviderAnswer.Empty else ProviderAnswer.Found(it) } ?: ProviderAnswer.Empty
            404 -> ProviderAnswer.Empty
            else -> failed(provider, a)
        }
    }

    companion object {
        private val FIELDS = listOf(SegmentKind.Intro to "intro", SegmentKind.Recap to "recap", SegmentKind.Credits to "credits", SegmentKind.Preview to "preview")

        fun parse(body: String): ProviderSegments? {
            val root = runCatching { JSON.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
            data class Record(val segments: List<Segment>, val streaming: Boolean, val created: String, val id: String)
            val records = (root["items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { o ->
                val segs = FIELDS.mapNotNull { (kind, f) ->
                    val start = o["${f}_start_ms"].num() ?: return@mapNotNull null
                    val end = o["${f}_end_ms"].num() ?: return@mapNotNull null
                    if (start < 0 || end <= start) null else Segment(kind, start, end)
                }
                Record(
                    segs,
                    o["source"]?.jsonPrimitive?.contentOrNull == "streaming",
                    o["created"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    o["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }.filter { it.segments.isNotEmpty() }
            // ISO-8601 timestamps of one format compare as text.
            val best = records.sortedWith(
                compareByDescending<Record> { it.streaming }.thenByDescending { it.segments.size }.thenByDescending { it.created }.thenBy { it.id },
            ).firstOrNull()
            return ProviderSegments(best?.segments.orEmpty())
        }
    }
}
