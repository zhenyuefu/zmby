package app.zmby.trailer

import app.zmby.emby.BaseItemDto
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/*
 * IMDb trailers (trailer design): IMDb's own GraphQL endpoint gives a title's newest trailer as
 * signed MP4 links (CloudFront, valid for about a day). Only the title's IMDb ID is sent; nothing
 * about the servers, the user or the files.
 */

/** How a trailer is fetched: one MP4 file, an HLS playlist, or a media server's stored file. */
@Serializable
enum class TrailerFormat { Mp4, Hls, Server }

/**
 * A title's trailer as played (trailer design): IMDb's progressive MP4 with its signed link, Rotten
 * Tomatoes' HLS playlist, or a trailer stored on a media server. Server trailers carry the login
 * token in [url]: they are built when needed and never stored or logged.
 */
@Serializable
data class TrailerInfo(
    val videoId: String,
    val url: String,
    /** Picture height of [url] (1080, 720, 480 …; 0 when the player decides); part of the cache key. */
    val height: Int,
    val runtimeSec: Int? = null,
    /** Epoch ms after which [url] no longer answers (its `Expires`). */
    val expiresAt: Long,
    val format: TrailerFormat = TrailerFormat.Mp4,
    /** Where it came from: `imdb`, `rottentomatoes`, or the media server's ID. */
    val source: String = "imdb",
    /** The User-Agent the server wants (server trailers behind a proxy); null for the default. */
    val userAgent: String? = null,
) {
    /** The bytes of this video at this height, whatever the signature of the link that fetched them. */
    val cacheKey: String get() = "$source:$videoId:$height"

    /** Kept in the playback cache: the internet sources; a server's own trailer is on the LAN and can be large. */
    val cached: Boolean get() = format != TrailerFormat.Server
}

object ImdbTrailers {
    private val IMDB_ID = Regex("tt\\d{5,10}")

    /** `tt1234567` from a provider ID value (some servers send `https://www.imdb.com/title/tt…/` or upper case). */
    fun normalize(raw: String?): String? = raw?.lowercase()?.let { IMDB_ID.find(it)?.value }

    /**
     * The IMDb ID a film or series is known by: its own `Imdb` provider ID. An episode's own ID is the
     * episode's (IMDb has no trailer for it), so episodes give none; callers use their series.
     */
    fun idOf(item: BaseItemDto?): String? {
        if (item == null || item.type !in setOf("Movie", "Series")) return null
        return normalize(item.providerIds.entries.firstOrNull { it.key.equals("imdb", ignoreCase = true) }?.value)
    }

    /** The first IMDb ID among [items] (in the caller's order of trust), see [idOf]. */
    fun firstId(items: List<BaseItemDto?>): String? = items.firstNotNullOfOrNull(::idOf)

    /**
     * The GraphQL query: the newest trailer, the title's first videos for when that has no MP4, and
     * the user rating (one request for both features).
     */
    internal const val QUERY = "query Title(\$id: ID!) { title(id: \$id) { ratingsSummary { aggregateRating voteCount } latestTrailer { ...V } primaryVideos(first: 5) { edges { node { ...V } } } } } " +
        "fragment V on Video { id contentType { id } runtime { value } playbackURLs { url videoMimeType videoDefinition } }"

    fun requestBody(imdbId: String): String = buildJsonObject {
        put("query", QUERY)
        putJsonObject("variables") { put("id", imdbId) }
    }.toString()

    /** Definition names to picture height; DEF_SD is IMDb's 360-line copy. */
    internal fun heightOf(definition: String?): Int? = when (definition?.uppercase()) {
        "DEF_2160P" -> 2160
        "DEF_1440P" -> 1440
        "DEF_1080P" -> 1080
        "DEF_720P" -> 720
        "DEF_480P" -> 480
        "DEF_SD" -> 360
        "DEF_240P" -> 240
        else -> null
    }

    /** The link's `Expires` (epoch seconds) in ms; [fallback] when the link has none. */
    internal fun expiresOf(url: String, fallback: Long): Long =
        Regex("[?&]Expires=(\\d{9,12})(?:&|$)").find(url)?.groupValues?.get(1)?.toLongOrNull()?.times(1000) ?: fallback

    /**
     * The trailer to play from a GraphQL answer: the newest trailer when it has an MP4, else the first
     * of the title's videos that is a trailer (clips and featurettes are not played). Of its MP4s the
     * tallest at most [maxHeight], else the shortest one. Null when the title has none. Links must
     * be https unless [allowHttp] (the debug mock server).
     */
    fun parse(json: Json, body: String, maxHeight: Int, now: Long, allowHttp: Boolean = false): TrailerInfo? {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return null
        val title = root.obj("data")?.obj("title") ?: return null
        val candidates = buildList {
            title.obj("latestTrailer")?.let(::add)
            (title.obj("primaryVideos")?.get("edges") as? JsonArray)?.forEach { e -> (e as? JsonObject)?.obj("node")?.let(::add) }
        }
        for (video in candidates) {
            val id = video.str("id") ?: continue
            if (video.obj("contentType")?.str("id")?.endsWith(".trailer") != true) continue
            val mp4s = (video["playbackURLs"] as? JsonArray).orEmpty().mapNotNull { p ->
                val o = p as? JsonObject ?: return@mapNotNull null
                if (o.str("videoMimeType")?.equals("MP4", ignoreCase = true) != true) return@mapNotNull null
                val url = o.str("url")?.takeIf { it.startsWith("https://") || allowHttp && it.startsWith("http://") } ?: return@mapNotNull null
                val height = heightOf(o.str("videoDefinition")) ?: return@mapNotNull null
                height to url
            }
            val pick = mp4s.filter { it.first <= maxHeight }.maxByOrNull { it.first } ?: mp4s.minByOrNull { it.first } ?: continue
            val runtime = (video.obj("runtime")?.get("value") as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            return TrailerInfo(id, pick.second, pick.first, runtime, expiresOf(pick.second, now + DEFAULT_LINK_MS))
        }
        return null
    }

    /** IMDb's user rating (1–10) and vote count from the same answer; null when the title has none yet. */
    fun rating(json: Json, body: String): Pair<Double, Int>? {
        val summary = ((json.parseToJsonElement(body) as? JsonObject)?.obj("data")?.obj("title")?.obj("ratingsSummary")) ?: return null
        val rating = (summary["aggregateRating"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.takeIf { it in 1.0..10.0 } ?: return null
        val votes = (summary["voteCount"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
        return rating to votes
    }

    /** How long a link without `Expires` is used. */
    private const val DEFAULT_LINK_MS = 6 * 60 * 60_000L

    private fun JsonObject.obj(k: String) = get(k) as? JsonObject
    private fun JsonObject.str(k: String) = (get(k) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}

/** Asks IMDb's GraphQL endpoint; [endpoint] is replaceable for the mock server (debug.zmby.imdb_base). */
class ImdbTrailerApi(private val http: OkHttpClient, private val endpoint: () -> String, private val userAgent: String) {
    /** The answer's body; HTTP and network failures throw. */
    suspend fun query(imdbId: String): String {
        val request = Request.Builder().url(endpoint())
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            // IMDb answers 403 to clients that do not name themselves.
            .header("x-imdb-client-name", "zmby")
            .post(ImdbTrailers.requestBody(imdbId).toRequestBody(JSON))
            .build()
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (!continuation.isCancelled) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (!it.isSuccessful) throw IOException("IMDb HTTP ${it.code}")
                            val source = it.body?.source() ?: throw IOException("IMDb empty answer")
                            source.request(MAX_BYTES + 1)
                            if (source.buffer.size > MAX_BYTES) throw IOException("IMDb answer too large")
                            continuation.resume(source.readUtf8())
                        }
                    } catch (e: Exception) { if (!continuation.isCancelled) continuation.resumeWithException(e) }
                }
            })
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        const val MAX_BYTES = 512 * 1024L
    }
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
