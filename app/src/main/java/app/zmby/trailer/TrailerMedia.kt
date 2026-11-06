package app.zmby.trailer

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The bytes of the trailers (trailer design): one least-recently-used playback cache of
 * [budgetBytes] in the app's cache directory, filled while a trailer streams. Keys are
 * [TrailerInfo.cacheKey], so a new signed link for the same video reads what is already here and a
 * fully cached trailer plays without any request. The cache opens its index on disk: [open] it off
 * the main thread before a player asks for [dataSourceFactory].
 */
@OptIn(UnstableApi::class)
class TrailerMedia(private val context: Context, private val dir: File, private val userAgent: String, private val budgetBytes: Long = BUDGET_BYTES) {
    @Volatile private var cache: SimpleCache? = null

    private fun cache(): SimpleCache = cache ?: synchronized(this) {
        cache ?: SimpleCache(dir, LeastRecentlyUsedCacheEvictor(budgetBytes), StandaloneDatabaseProvider(context)).also { cache = it }
    }

    /** Opens the cache (disk I/O) on [Dispatchers.IO]. */
    suspend fun open() { withContext(Dispatchers.IO) { cache() } }

    /** Whether every byte of [key] is cached (its length is known once a download has started). */
    fun complete(key: String): Boolean = try {
        val c = cache()
        val length = ContentMetadata.getContentLength(c.getContentMetadata(key))
        length > 0 && c.isCached(key, 0, length)
    } catch (e: Exception) { false }

    /** Bytes cached now. */
    fun usedBytes(): Long = cache?.cacheSpace ?: 0L

    /** A media server's own trailer: straight from the server (LAN, possibly large), never cached; redirects followed. */
    fun serverDataSourceFactory(userAgent: String?): DataSource.Factory = DefaultHttpDataSource.Factory()
        .setUserAgent(userAgent ?: this.userAgent)
        .setConnectTimeoutMs(8_000)
        .setReadTimeoutMs(15_000)
        .setAllowCrossProtocolRedirects(true)

    /** Reads through the cache; a cache error falls back to the network instead of failing the trailer. */
    fun dataSourceFactory(): DataSource.Factory = CacheDataSource.Factory()
        .setCache(cache())
        .setUpstreamDataSourceFactory(
            DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setConnectTimeoutMs(8_000)
                .setReadTimeoutMs(15_000)
                .setAllowCrossProtocolRedirects(false),
        )
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    companion object {
        /** About five two-minute 1080p trailers. */
        const val BUDGET_BYTES = 512L * 1024 * 1024
    }
}

/**
 * What the screens use (trailer design): the first trailer of IMDb, then Rotten Tomatoes (by the same
 * IMDb ID), then the trailers stored on the title's servers ([TrailerQuery.copies] in order, at
 * most [MAX_COPIES]); each source is asked only when the ones before it have none. The playback
 * cache is open when a trailer is returned.
 */
class Trailers(
    val imdb: ImdbTitleRepository,
    val media: TrailerMedia,
    private val rottenTomatoes: (suspend (imdbId: String) -> TrailerInfo?)? = null,
    private val servers: ServerTrailers? = null,
) {
    suspend fun find(query: TrailerQuery): TrailerInfo? {
        val info = firstTrailer(query, imdb::trailer, rottenTomatoes, servers?.let { s -> { copy, unknown -> s.trailer(copy, unknown) } }) ?: return null
        media.open()
        return info
    }
}

/** A title's copies asked for a stored trailer: the page's own and at most two more. */
internal const val MAX_TRAILER_COPIES = 3

/**
 * The source order of [Trailers.find]; a later source is asked only when every earlier one has none.
 * Of the copies, only the first may be asked without knowing it has stored trailers.
 */
internal suspend fun firstTrailer(
    query: TrailerQuery,
    imdb: suspend (String) -> TrailerInfo?,
    rottenTomatoes: (suspend (String) -> TrailerInfo?)?,
    server: (suspend (app.zmby.data.ServerItem, Boolean) -> TrailerInfo?)?,
): TrailerInfo? =
    query.imdbId?.let { id -> imdb(id) ?: rottenTomatoes?.invoke(id) }
        ?: server?.let { s -> query.copies.take(MAX_TRAILER_COPIES).withIndex().firstNotNullOfOrNull { (i, copy) -> s(copy, i == 0) } }
