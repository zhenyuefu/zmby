package app.zmby.danmu

import app.zmby.emby.BaseItemDto
import app.zmby.log.ZLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** A LogVar session: server-confirmed matching, persistent explicit choices, bounded raw cache. */
class DanmuRepository(
    private val dir: File,
    private val api: DandanplayApi,
    private val clock: () -> Long = System::currentTimeMillis,
    private val choicesDir: File = File(dir, "choices"),
) {
    @Serializable private data class MatchCache(val selection: DanmuSelection?, val at: Long)
    private val unsupportedUntil = ConcurrentHashMap<String, Long>()
    private val json get() = DanmuProtocol.json

    suspend fun load(
        endpoint: DanmuEndpoint, key: String, item: BaseItemDto, rows: Int,
        query: DanmuQuery? = queryOf(item),
        aliases: suspend (DanmuQuery) -> DanmuQuery = { it },
    ): List<Danmaku>? = withContext(Dispatchers.IO) {
        if (!endpoint.enabled || query == null) return@withContext null
        try {
            prune()
            val manual = readChoice(endpoint, key, query)
            val saved = manual ?: cached(endpoint, key, query)?.selection
            // A stable source URL plus work/episode identity owns the body, never a bare cache ID.
            saved?.let { selection -> cachedBody(endpoint, key, query, selection)?.let { return@withContext DanmuText.filter(it, rows) } }
            if (manual == null && saved == null && cached(endpoint, key, query) != null) return@withContext null
            // Without a valid body, refresh IDs before requesting comments. LogVar's IDs may have
            // been evicted or reused since the last playback, even within our metadata TTL.
            var selection = if (manual != null) refreshChoice(endpoint, key, query, manual)
                else automatic(endpoint, key, query, aliases)
            selection ?: return@withContext null
            val body = try { api.comments(endpoint, selection.target.episodeId) }
            catch (e: DanmuHttpException) {
                if (e.code !in setOf(404, 410)) throw e
                matchFile(endpoint, key, query).delete()
                selection = if (manual != null) refreshChoice(endpoint, key, query, manual)
                    else automatic(endpoint, key, query, aliases)
                selection ?: return@withContext null
                api.comments(endpoint, selection.target.episodeId) // Exactly one recovery attempt.
            }
            val comments = DanmuProtocol.comments(body)
            currentCoroutineContext().ensureActive()
            // Empty/error results are not a twelve-hour negative comment cache.
            if (comments.isNotEmpty()) write(bodyFile(endpoint, key, query, selection), body)
            DanmuText.filter(comments, rows).also { ZLog.i(TAG, "danmu for $key: ${comments.size} comments, ${it.size} prepared") }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { ZLog.w(TAG, "danmu unavailable for $key: ${e.message}"); null }
    }

    /** Current selection for the explicit matching panel. No comments are downloaded here. */
    suspend fun current(endpoint: DanmuEndpoint, key: String, query: DanmuQuery,
        aliases: suspend (DanmuQuery) -> DanmuQuery = { it }): DanmuSelection? = withContext(Dispatchers.IO) {
        readChoice(endpoint, key, query) ?: cached(endpoint, key, query)?.selection ?: automatic(endpoint, key, query, aliases)
    }

    /** Manual search lists all episodes so the user can choose a different numbering explicitly. */
    suspend fun search(endpoint: DanmuEndpoint, title: String): List<DanmuSelection> = withContext(Dispatchers.IO) {
        val term = title.trim().takeIf { it.isNotEmpty() } ?: return@withContext emptyList()
        DanmuProtocol.searched(api.searchEpisodes(endpoint, term, null)).map { DanmuSelection(it, term, manual = true) }
    }

    suspend fun saveChoice(endpoint: DanmuEndpoint, key: String, query: DanmuQuery, selection: DanmuSelection) = withContext(Dispatchers.IO) {
        require(selection.target.episodeId > 0)
        write(choiceFile(endpoint, key, query), json.encodeToString(selection.copy(manual = true)))
        matchFile(endpoint, key, query).delete()
    }

    /** Explicitly leave manual mode and bypass both positive and negative matching caches. */
    suspend fun reset(endpoint: DanmuEndpoint, key: String, query: DanmuQuery) = withContext(Dispatchers.IO) {
        choiceFile(endpoint, key, query).delete()
        matchFile(endpoint, key, query).delete()
        unsupportedUntil.remove(endpoint.cacheKey)
        Unit
    }

    private suspend fun automatic(endpoint: DanmuEndpoint, key: String, query: DanmuQuery,
        aliases: suspend (DanmuQuery) -> DanmuQuery): DanmuSelection? {
        val attempted = LinkedHashSet<String>()
        var supported = (unsupportedUntil[endpoint.cacheKey] ?: 0L) <= clock()
        suspend fun attempt(names: List<String>): DanmuSelection? {
            for (name in names.map { it.trim() }.filter { it.isNotBlank() }.distinct()) {
                if (attempted.size >= MAX_NAMES) break
                if (!attempted.add(name)) continue
                if (supported) {
                    try {
                        val target = DanmuProtocol.matched(api.match(endpoint, fileName(query, name)), query.movie).firstOrNull()
                        if (target != null) return DanmuSelection(target, name)
                    } catch (e: DanmuHttpException) {
                        if (e.code !in setOf(404, 405, 501)) throw e
                        unsupportedUntil[endpoint.cacheKey] = clock() + CAPABILITY_TTL_MS
                        supported = false
                    }
                }
                if (!supported) {
                    val body = api.searchEpisodes(endpoint, name, query.episode, query.movie)
                    val id = DanmuMatch.choose(query.copy(names = names), DanmuText.searchResult(body))
                    if (id != null) DanmuProtocol.searched(body).firstOrNull { it.episodeId == id }?.let { return DanmuSelection(it, name) }
                }
            }
            return null
        }
        var result = attempt(query.names)
        if (result == null && attempted.size < MAX_NAMES) result = attempt(aliases(query).names)
        write(matchFile(endpoint, key, query), json.encodeToString(MatchCache(result, clock())))
        return result
    }

    private suspend fun refreshChoice(endpoint: DanmuEndpoint, key: String, query: DanmuQuery, saved: DanmuSelection): DanmuSelection {
        val candidates = search(endpoint, saved.searchTitle)
        val target = candidates.firstOrNull { candidate ->
            if (saved.target.url.isNotBlank()) candidate.target.url == saved.target.url
            else saved.target.animeId.isNotBlank() && saved.target.episodeTitle.isNotBlank() &&
                candidate.target.animeId == saved.target.animeId && candidate.target.episodeTitle == saved.target.episodeTitle
        } ?: throw IOException("已选弹幕来源失效，请在详情页重新选择")
        saveChoice(endpoint, key, query, target)
        return target
    }

    private fun readChoice(endpoint: DanmuEndpoint, key: String, query: DanmuQuery): DanmuSelection? =
        read(choiceFile(endpoint, key, query))?.let { runCatching { json.decodeFromString<DanmuSelection>(it) }.getOrNull() }

    private fun cached(endpoint: DanmuEndpoint, key: String, query: DanmuQuery): MatchCache? {
        val saved = read(matchFile(endpoint, key, query))?.let { runCatching { json.decodeFromString<MatchCache>(it) }.getOrNull() } ?: return null
        val ttl = if (saved.selection == null) NO_MATCH_TTL_MS else MATCH_TTL_MS
        return saved.takeIf { clock() - saved.at in 0 until ttl }
    }

    private fun cachedBody(endpoint: DanmuEndpoint, key: String, query: DanmuQuery, selection: DanmuSelection): List<Danmaku>? {
        val file = bodyFile(endpoint, key, query, selection)
        if (!file.isFile || clock() - file.lastModified() !in 0 until COMMENTS_TTL_MS) return null
        return read(file)?.let { runCatching { DanmuProtocol.comments(it) }.getOrNull() }?.takeIf { it.isNotEmpty() }
    }

    private fun binding(key: String, query: DanmuQuery) = listOf(query.workId ?: key, query.movie, query.season, query.episode).joinToString("|")
    private fun fingerprint(key: String, query: DanmuQuery) = listOf("v3", binding(key, query), query.names, query.year).joinToString("|")
    private fun matchFile(endpoint: DanmuEndpoint, key: String, query: DanmuQuery) = File(dir, "m3-" + hash(endpoint.cacheKey + "|" + fingerprint(key, query)))
    private fun choiceFile(endpoint: DanmuEndpoint, key: String, query: DanmuQuery) = File(choicesDir, hash(endpoint.cacheKey + "|" + binding(key, query)))
    private fun bodyFile(endpoint: DanmuEndpoint, key: String, query: DanmuQuery, selection: DanmuSelection) =
        File(dir, "c3-" + hash(endpoint.cacheKey + "|" + binding(key, query) + "|" + selection.target.sourceKey))

    private fun read(file: File): String? = if (file.isFile) runCatching { file.readText() }.getOrNull() else null
    private fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File.createTempFile("danmu-", ".tmp", file.parentFile)
        try {
            tmp.writeText(text)
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            file.setLastModified(clock())
        } finally { tmp.delete() }
    }
    private fun prune() {
        val cutoff = clock() - MATCH_TTL_MS
        dir.listFiles()?.filter { it.isFile && it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    companion object {
        private const val TAG = "Danmu"
        private const val MAX_NAMES = 4
        const val MATCH_TTL_MS = 7L * 24 * 3600_000
        const val NO_MATCH_TTL_MS = 5L * 60_000
        const val COMMENTS_TTL_MS = 12L * 3600_000
        private const val CAPABILITY_TTL_MS = 6L * 3600_000

        /** Synthetic, minimal name: never a file path or a playback URL. */
        fun fileName(query: DanmuQuery, name: String): String = buildString {
            append(name.replace(Regex("[\\r\\n\\t]+"), " ").trim())
            query.year?.let { append(" ($it)") }
            if (!query.movie) {
                query.season?.let { append(" S${it.toString().padStart(2, '0')}") }
                query.episode?.let { append("${if (query.season == null) " " else ""}E${it.toString().padStart(2, '0')}") }
            }
        }

        /** Basic fallback when a parent/catalog resolver is not available. No episode-year guess. */
        fun queryOf(item: BaseItemDto): DanmuQuery? = when (item.type) {
            "Episode" -> item.seriesName?.takeIf { it.isNotBlank() }?.let { name ->
                item.indexNumber?.takeIf { it > 0 }?.let { ep -> DanmuQuery(listOf(name), false, item.parentIndexNumber, ep) }
            }
            "Movie" -> listOfNotNull(item.name, item.originalTitle).filter { it.isNotBlank() }.distinct()
                .takeIf { it.isNotEmpty() }?.let { DanmuQuery(it, true, year = item.productionYear) }
            else -> null
        }

        private fun hash(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
    }
}
