package app.zmby.ratings

import app.zmby.aggregate.ResultStore
import app.zmby.log.ZLog
import app.zmby.net.RequestCache
import app.zmby.trailer.ImdbTitleRepository
import app.zmby.trailer.ImdbTrailers
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/*
 * Ratings beside the catalogue's own on the detail pages (ratings design): IMDb's user rating, and
 * Rotten Tomatoes' Tomatometer (critics) and Popcornmeter (audience). Rotten Tomatoes is found
 * through Wikidata (IMDb ID → its Rotten Tomatoes ID, P1258), never by title; its scores come from
 * the title page's scorecard. Only the IMDb ID and that public page path leave the box.
 */

/** One Rotten Tomatoes meter: percent, fresh (positive) or rotten, and Certified Fresh / Verified Hot. */
@Serializable
data class RtScore(val percent: Int, val fresh: Boolean, val certified: Boolean = false)

@Serializable
data class RtScores(val tomatometer: RtScore? = null, val popcornmeter: RtScore? = null) {
    val isEmpty: Boolean get() = tomatometer == null && popcornmeter == null
}

/** What the detail header shows beyond the catalogue's rating; each part null when unknown. */
@androidx.compose.runtime.Immutable
data class ExternalRatings(
    val imdb: Double? = null,
    val imdbVotes: Int? = null,
    val tomatometer: RtScore? = null,
    val popcornmeter: RtScore? = null,
) {
    val isEmpty: Boolean get() = imdb == null && tomatometer == null && popcornmeter == null
}

object RottenTomatoesParse {
    private val PATH = Regex("(m|tv)/[a-z0-9_\\-]+")

    /** The SPARQL query for [imdbId]'s Rotten Tomatoes ID (`m/dune_part_two`, `tv/breaking_bad`). */
    fun sparql(imdbId: String): String =
        "SELECT ?rt WHERE { ?item wdt:P345 \"$imdbId\" ; wdt:P1258 ?rt . } LIMIT 3"

    /** The first usable Rotten Tomatoes path in a SPARQL JSON answer; null when Wikidata knows none. */
    fun path(json: Json, body: String): String? {
        val bindings = ((json.parseToJsonElement(body) as? JsonObject)?.get("results") as? JsonObject)?.get("bindings") as? JsonArray ?: return null
        return bindings.firstNotNullOfOrNull { b ->
            (((b as? JsonObject)?.get("rt") as? JsonObject)?.get("value") as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
                ?.takeIf { PATH.matches(it) }
        }
    }

    private val SCORECARD = Regex("<script[^>]*\\bid=\"media-scorecard-json\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)

    /** The two meters of a title page (`media-scorecard-json`); empty when the page has none yet. */
    fun scorecard(json: Json, html: String): RtScores {
        val card = SCORECARD.find(html)?.groupValues?.get(1)?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            ?: return RtScores()
        fun meter(key: String): RtScore? {
            val m = card[key] as? JsonObject ?: return null
            val percent = (m["scorePercent"] as? JsonPrimitive)?.contentOrNull?.trim()?.removeSuffix("%")?.toIntOrNull()?.takeIf { it in 0..100 } ?: return null
            val sentiment = (m["sentiment"] as? JsonPrimitive)?.contentOrNull?.uppercase()
            val fresh = when (sentiment) {
                "POSITIVE" -> true
                "NEGATIVE" -> false
                // Older pages: fresh from 60 % (the Tomatometer's own threshold).
                else -> percent >= 60
            }
            val certified = (m["certified"] as? JsonPrimitive)?.contentOrNull == "true"
            return RtScore(percent, fresh, certified && fresh)
        }
        val hideAudience = (card["hideAudienceScore"] as? JsonPrimitive)?.contentOrNull == "true"
        return RtScores(meter("criticsScore"), if (hideAudience) null else meter("audienceScore"))
    }
}

/** A trailer listed on a Rotten Tomatoes title's videos page: thePlatform's media link without its query. */
data class RtVideo(val id: String, val file: String, val title: String?, val durationSec: Int?)

object RottenTomatoesTrailers {
    private val VIDEOS = Regex("<script[^>]*\\bid=\"videos\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
    private const val PLATFORM = "https://link.theplatform.com/"

    /**
     * The first trailer of a videos page (the page lists the newest first): `videoType` TRAILER,
     * not red band (restricted). Clips, featurettes and interviews are not trailers. Only
     * thePlatform links, unless [anyHost] (the debug mock server).
     */
    fun first(json: Json, html: String, anyHost: Boolean = false): RtVideo? {
        val list = VIDEOS.find(html)?.groupValues?.get(1)?.let { runCatching { json.parseToJsonElement(it) as? JsonArray }.getOrNull() } ?: return null
        return list.firstNotNullOfOrNull { e ->
            val v = e as? JsonObject ?: return@firstNotNullOfOrNull null
            fun str(k: String) = (v[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            if (str("videoType")?.uppercase() != "TRAILER" || str("isRedBand") == "true") return@firstNotNullOfOrNull null
            val file = str("file")?.substringBefore('?')?.takeIf { it.startsWith(PLATFORM) || anyHost && (it.startsWith("http://") || it.startsWith("https://")) }
                ?: return@firstNotNullOfOrNull null
            RtVideo(str("publicId") ?: file.substringAfterLast('/'), file, str("title"), str("durationInSeconds")?.toDoubleOrNull()?.toInt())
        }
    }

    private val HLS = Regex("<video\\s[^>]*\\bsrc=\"([^\"]+\\.m3u8[^\"]*)\"")

    /** The unencrypted HLS playlist of a thePlatform SMIL answer (the ad references beside it are ignored). */
    fun hls(smil: String, anyHost: Boolean = false): String? =
        HLS.find(smil)?.groupValues?.get(1)?.replace("&amp;", "&")?.takeIf { it.startsWith("https://") || anyHost && it.startsWith("http://") }
}

/** Wikidata's SPARQL endpoint and Rotten Tomatoes pages; the bases are replaceable for the mock server. */
class RottenTomatoesApi(
    private val http: OkHttpClient,
    private val wikidata: () -> String,
    private val site: () -> String,
    private val userAgent: String,
) {
    suspend fun path(imdbId: String): String = get(
        wikidata().toHttpUrl().newBuilder().addQueryParameter("query", RottenTomatoesParse.sparql(imdbId)).addQueryParameter("format", "json").build().toString(),
        "application/sparql-results+json", MAX_JSON,
    )

    suspend fun page(path: String): String = get(site().trimEnd('/') + "/" + path, "text/html", MAX_PAGE)

    /** The title's videos page. */
    suspend fun videos(path: String): String = page("$path/videos")

    /** thePlatform's SMIL for [file]: only the unencrypted HLS rendition is asked for. */
    suspend fun smil(file: String): String = get(
        file.toHttpUrl().newBuilder().addQueryParameter("format", "SMIL").addQueryParameter("formats", "M3U+none").build().toString(),
        "application/smil+xml", MAX_JSON,
    )

    private suspend fun get(url: String, accept: String, max: Long): String = suspendCancellableCoroutine { continuation ->
        // Wikidata asks every client to name itself in the User-Agent.
        val call = http.newCall(Request.Builder().url(url).header("User-Agent", userAgent).header("Accept", accept).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (!continuation.isCancelled) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (it.code == 404) throw NotFound()
                        if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                        val source = it.body?.source() ?: throw IOException("empty answer")
                        source.request(max + 1)
                        if (source.buffer.size > max) throw IOException("answer too large")
                        continuation.resume(source.readUtf8())
                    }
                } catch (e: Exception) { if (!continuation.isCancelled) continuation.resumeWithException(e) }
            }
        })
    }

    /** The page is gone (a moved or removed title): an answer, not a failure. */
    class NotFound : IOException("not found")

    private companion object {
        const val MAX_JSON = 256 * 1024L
        const val MAX_PAGE = 3 * 1024 * 1024L
    }
}

/**
 * Rotten Tomatoes scores of a title by IMDb ID, local first (统一数据层): the Wikidata mapping (or
 * that there is none) is kept [PATH_FRESH_MS], the scores [SCORES_FRESH_MS], both in
 * `repo_cache`; concurrent asks share one load, failures are not stored and not retried for
 * [RETRY_MS]. Only the scorecard is kept, never the page.
 */
class RottenTomatoesRepository(
    private val path: suspend (imdbId: String) -> String,
    private val page: suspend (path: String) -> String,
    private val store: ResultStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val json: Json = Json { ignoreUnknownKeys = true },
    /** The videos page of a path, and thePlatform's SMIL of a media link (trailers). */
    private val videos: suspend (path: String) -> String = { throw IOException("no videos") },
    private val smil: suspend (file: String) -> String = { throw IOException("no smil") },
    /** The debug mock server: any host, http allowed. */
    private val anyHost: Boolean = false,
) {
    @Serializable
    internal data class StoredTrailer(val trailer: app.zmby.trailer.TrailerInfo? = null, val at: Long)

    private val trailerMemory = RequestCache<String, app.zmby.trailer.TrailerInfo?>(64, { MEMORY_MS }, clock = clock)

    /**
     * The newest trailer on the title's Rotten Tomatoes page as an HLS playlist (trailer design: the
     * fallback when IMDb has none); the Wikidata mapping is the one the ratings use. Kept
     * [SCORES_FRESH_MS] in `repo_cache` (`rt_trailer`, also 「none」); failures are not stored.
     */
    suspend fun trailer(imdbId: String): app.zmby.trailer.TrailerInfo? {
        val id = ImdbTrailers.normalize(imdbId) ?: return null
        return try { trailerMemory.get(id) { withContext(Dispatchers.IO) { loadTrailer(id) } } }
        catch (e: CancellationException) { throw e } catch (e: Exception) { null }
    }

    private suspend fun loadTrailer(id: String): app.zmby.trailer.TrailerInfo? {
        val key = "trailer:$id"
        if (clock() - (failedAt[key] ?: Long.MIN_VALUE / 2) in 0 until RETRY_MS) throw IOException("retry later")
        return try {
            val p = rtPath(id) ?: return null
            read(KIND_TRAILER, p, StoredTrailer.serializer())?.takeIf { clock() - it.at in 0 until SCORES_FRESH_MS }?.let { return it.trailer }
            val video = try { RottenTomatoesTrailers.first(json, videos(p), anyHost) } catch (e: RottenTomatoesApi.NotFound) { null }
            val hls = video?.let { v -> try { RottenTomatoesTrailers.hls(smil(v.file), anyHost) } catch (e: RottenTomatoesApi.NotFound) { null } }
            val info = if (video == null || hls == null) null else app.zmby.trailer.TrailerInfo(
                videoId = video.id, url = hls, height = 0, runtimeSec = video.durationSec,
                expiresAt = clock() + SCORES_FRESH_MS, format = app.zmby.trailer.TrailerFormat.Hls, source = SOURCE,
            )
            write(KIND_TRAILER, p, json.encodeToString(StoredTrailer.serializer(), StoredTrailer(info, clock())))
            ZLog.d(TAG, "$id: $p ${info?.let { "trailer ${it.videoId}" } ?: "no trailer"}")
            info
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failedAt[key] = clock()
            ZLog.i(TAG, "$id: Rotten Tomatoes trailer unavailable (${e.javaClass.simpleName})")
            throw e
        }
    }

    @Serializable
    internal data class StoredPath(val path: String? = null, val at: Long)

    @Serializable
    internal data class StoredScores(val scores: RtScores = RtScores(), val at: Long)

    private val memory = RequestCache<String, RtScores?>(64, { MEMORY_MS }, clock = clock)
    private val failedAt = ConcurrentHashMap<String, Long>()

    /** Null when the title has no Rotten Tomatoes page or it cannot be reached now. */
    suspend fun scores(imdbId: String): RtScores? {
        val id = ImdbTrailers.normalize(imdbId) ?: return null
        // A failure throws out of the shared load, so the memory never keeps it.
        return try { memory.get(id) { withContext(Dispatchers.IO) { load(id) } } }
        catch (e: CancellationException) { throw e } catch (e: Exception) { null }
    }

    private suspend fun load(id: String): RtScores? {
        if (clock() - (failedAt[id] ?: Long.MIN_VALUE / 2) in 0 until RETRY_MS) throw IOException("retry later")
        return try {
            val p = rtPath(id) ?: return null
            val stored = read(KIND_SCORES, p, StoredScores.serializer())?.takeIf { clock() - it.at in 0 until SCORES_FRESH_MS }
            stored?.let { return it.scores.takeUnless { s -> s.isEmpty } }
            val scores = try { RottenTomatoesParse.scorecard(json, page(p)) } catch (e: RottenTomatoesApi.NotFound) { RtScores() }
            write(KIND_SCORES, p, json.encodeToString(StoredScores.serializer(), StoredScores(scores, clock())))
            ZLog.d(TAG, "$id: $p ${scores.tomatometer?.percent ?: "-"} / ${scores.popcornmeter?.percent ?: "-"}")
            scores.takeUnless { it.isEmpty }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failedAt[id] = clock()
            ZLog.i(TAG, "$id: Rotten Tomatoes unavailable (${e.javaClass.simpleName})")
            throw e
        }
    }

    /** The title's Rotten Tomatoes path, from the store while fresh, else Wikidata. */
    private suspend fun rtPath(id: String): String? {
        read(KIND_PATH, id, StoredPath.serializer())?.takeIf { s ->
            clock() - s.at in 0 until PATH_FRESH_MS
        }?.let { return it.path }
        val found = RottenTomatoesParse.path(json, path(id))
        write(KIND_PATH, id, json.encodeToString(StoredPath.serializer(), StoredPath(found, clock())))
        return found
    }

    private suspend fun <T> read(kind: String, key: String, serializer: kotlinx.serialization.KSerializer<T>): T? = try {
        store.read(kind, key, SOURCE, PATH_FRESH_MS)?.let { json.decodeFromString(serializer, it) }
    } catch (e: CancellationException) { throw e } catch (e: Exception) { null }

    private suspend fun write(kind: String, key: String, value: String) {
        try { store.write(kind, key, SOURCE, value) }
        catch (e: CancellationException) { throw e } catch (e: Exception) { ZLog.w(TAG, "rating not stored: ${e.javaClass.simpleName}") }
    }

    companion object {
        const val KIND_PATH = "rt_path"
        const val KIND_SCORES = "rt_scores"
        const val KIND_TRAILER = "rt_trailer"
        const val SOURCE = "rottentomatoes"
        private const val TAG = "Ratings"
        /** As long as `repo_cache` keeps any row (the startup prune drops older ones). */
        const val PATH_FRESH_MS = 7 * 24 * 60 * 60_000L
        const val SCORES_FRESH_MS = 3 * 24 * 60 * 60_000L
        const val RETRY_MS = 10 * 60_000L
        private const val MEMORY_MS = 60 * 60_000L
    }
}

/** The detail pages' external ratings: IMDb (shared with the trailer request) and Rotten Tomatoes, asked together. */
class Ratings(private val imdb: ImdbTitleRepository, private val rt: RottenTomatoesRepository) {
    suspend fun of(imdbId: String): ExternalRatings = coroutineScope {
        val tomatoes = async { rt.scores(imdbId) }
        val title = imdb.title(imdbId)
        val scores = tomatoes.await()
        ExternalRatings(title?.rating, title?.votes, scores?.tomatometer, scores?.popcornmeter)
    }
}
