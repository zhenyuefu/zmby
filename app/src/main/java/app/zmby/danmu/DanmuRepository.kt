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
import app.zmby.net.RequestCache

/** A LogVar session: server-confirmed matching, persistent explicit choices, bounded raw cache. */
class DanmuRepository(
    private val dir: File,
    private val api: DandanplayApi,
    private val clock: () -> Long = System::currentTimeMillis,
    private val choicesDir: File = File(dir, "choices"),
    private val aliasContext: () -> String = { "" },
) {
    @Serializable private data class MatchCache(val selection: DanmuSelection?, val at: Long, val candidates: List<DanmuTarget> = emptyList())
    private val unsupportedUntil = ConcurrentHashMap<String, Long>()
    private val nameSearch = DanmuNameSearch()
    private val json get() = DanmuProtocol.json
    private data class SearchKey(val endpoint: DanmuEndpoint, val term: String, val episode: Int?, val movie: Boolean)
    private val searches = RequestCache<SearchKey, String>(64, { body ->
        if (DanmuProtocol.searched(body).isEmpty()) NO_SEARCH_TTL_MS else SEARCH_TTL_MS
    }, clock, maxWeight = 8L * 1024 * 1024, weight = { it.length.toLong() * 2 })
    private val matches = RequestCache<String, DanmuSelection?>(128, { FRESH_MATCH_MS }, clock)
    private val loads = RequestCache<String, List<Danmaku>?>(32, { 0L }, clock)
    private val bodies = RequestCache<String, List<Danmaku>>(32, { 0L }, clock)

    suspend fun load(
        endpoint: DanmuEndpoint, key: String, item: BaseItemDto, rows: Int,
        query: DanmuQuery? = queryOf(item),
        aliases: suspend (DanmuQuery) -> DanmuQuery = { it },
    ): List<Danmaku>? = withContext(Dispatchers.IO) {
        if (!endpoint.enabled || query == null) return@withContext null
        loads.get(hash(endpoint.cacheKey + "|" + fingerprint(key, query) + "|$rows")) { try {
            prune()
            val manual = manualChoice(endpoint, key, query)
            val snapshot = if (manual == null) cached(endpoint, key, query) else null
            val saved = manual ?: snapshot?.selection
            // A stable source URL plus work/episode identity owns the body, never a bare cache ID.
            saved?.let { selection -> cachedBody(endpoint, key, query, selection)?.let { return@get DanmuText.filter(it, rows) } }
            if (saved == null && snapshot != null) return@get null
            // Without a valid body, refresh IDs before requesting comments. LogVar's IDs may have
            // been evicted or reused since the last playback, even within our metadata TTL.
            var selection = if (manual != null) refreshChoice(endpoint, key, query, manual)
                else automatic(endpoint, key, query, aliases, freshIds = true)
            selection ?: return@get null
            var comments = try { loadComments(endpoint, key, query, selection) }
            catch (e: DanmuHttpException) {
                if (e.code !in setOf(404, 410)) throw e
                matchFile(endpoint, key, query).delete()
                matches.invalidate(matchKey(endpoint, key, query))
                selection = if (manual != null) refreshChoice(endpoint, key, query, manual)
                    else automatic(endpoint, key, query, aliases, freshIds = true)
                selection ?: return@get null
                try { loadComments(endpoint, key, query, selection) } catch (retry: DanmuHttpException) {
                    if (manual != null || retry.code !in setOf(404, 410)) throw retry
                    emptyList()
                }
            }
            catch (e: IOException) {
                if (manual != null) throw e
                emptyList()
            }
            if (comments.isEmpty() && manual == null) {
                val matched = selection
                val options = search(endpoint, matched.searchTitle, refresh = true).map { it.target }
                val alternative = options.firstOrNull { matched.target.url.isNotBlank() && it.url == matched.target.url }
                    ?: options.singleOrNull { matched.target.animeId.isNotBlank() && matched.target.episodeTitle.isNotBlank() &&
                        it.animeId == matched.target.animeId && it.episodeTitle == matched.target.episodeTitle }
                    ?: DanmuMatch.chooseTarget(query.copy(names = query.names + matched.searchTitle), options)
                if (alternative != null) {
                    val recovered = DanmuSelection(alternative, matched.searchTitle)
                    comments = loadComments(endpoint, key, query, recovered)
                    if (comments.isNotEmpty()) {
                        write(matchFile(endpoint, key, query), json.encodeToString(MatchCache(recovered, clock(), options)))
                        matches.invalidate(matchKey(endpoint, key, query))
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            DanmuText.filter(comments, rows).also { ZLog.i(TAG, "danmu for $key: ${comments.size} comments, ${it.size} prepared") }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { ZLog.w(TAG, "danmu unavailable for $key: ${e.message}"); null } }
    }

    /** Current selection for the explicit matching panel. No comments are downloaded here. */
    suspend fun current(endpoint: DanmuEndpoint, key: String, query: DanmuQuery,
        aliases: suspend (DanmuQuery) -> DanmuQuery = { it }): DanmuSelection? = withContext(Dispatchers.IO) {
        manualChoice(endpoint, key, query)?.let { return@withContext it }
        cached(endpoint, key, query)?.let { return@withContext it.selection }
        automatic(endpoint, key, query, aliases)
    }

    suspend fun candidates(endpoint: DanmuEndpoint, key: String, query: DanmuQuery, selection: DanmuSelection): List<DanmuSelection> =
        withContext(Dispatchers.IO) {
            val snapshot = cached(endpoint, key, query)
            val targets = snapshot?.takeIf { it.selection?.searchTitle == selection.searchTitle }?.candidates.orEmpty()
            val options = targets.ifEmpty { search(endpoint, selection.searchTitle).map { it.target } }
            (options + selection.target).distinctBy { it.sourceKey }.map { DanmuSelection(it, selection.searchTitle, manual = true) }
        }

    private fun titleFile(endpoint: DanmuEndpoint, key: String, query: DanmuQuery) =
        File(choicesDir, "title-" + hash(endpoint.cacheKey + "|" + (query.workId ?: key)))

    suspend fun preferredTitle(endpoint: DanmuEndpoint, key: String, query: DanmuQuery) =
        withContext(Dispatchers.IO) { read(titleFile(endpoint, key, query)) }

    suspend fun preferTitle(endpoint: DanmuEndpoint, key: String, query: DanmuQuery, title: String) =
        withContext(Dispatchers.IO) { write(titleFile(endpoint, key, query), title.trim()) }

    /** Manual search lists all episodes so the user can choose a different numbering explicitly. */
    suspend fun search(endpoint: DanmuEndpoint, title: String, refresh: Boolean = false): List<DanmuSelection> = withContext(Dispatchers.IO) {
        val term = title.trim().takeIf { it.isNotEmpty() } ?: return@withContext emptyList()
        if (refresh) searches.invalidate(SearchKey(endpoint, term, null, false))
        DanmuProtocol.searched(searchBody(endpoint, term, null)).map { DanmuSelection(it, term, manual = true) }
    }

    suspend fun saveChoice(endpoint: DanmuEndpoint, key: String, query: DanmuQuery, selection: DanmuSelection,
        followSeason: Boolean = false): DanmuSelection = withContext(Dispatchers.IO) {
        require(selection.target.episodeId > 0)
        val rule = if (followSeason) DanmuSeasonChoice.of(query, selection) else null
        val saved = selection.copy(manual = true, season = rule != null)
        if (rule != null) {
            write(seasonFile(endpoint, query)!!, json.encodeToString(rule))
            choiceFile(endpoint, key, query).delete()
        } else {
            if (followSeason) seasonFile(endpoint, query)?.delete()
            write(choiceFile(endpoint, key, query), json.encodeToString(saved))
        }
        matchFile(endpoint, key, query).delete()
        matches.invalidate(matchKey(endpoint, key, query))
        saved
    }

    /** Explicitly leave manual mode and bypass both positive and negative matching caches. */
    suspend fun reset(endpoint: DanmuEndpoint, key: String, query: DanmuQuery) = withContext(Dispatchers.IO) {
        val previous = try { manualChoice(endpoint, key, query) } catch (_: IOException) { null }
            ?: cached(endpoint, key, query)?.selection
        previous?.let {
            val file = bodyFile(endpoint, key, query, it)
            file.delete()
            bodies.invalidate(file.name)
        }
        choiceFile(endpoint, key, query).delete()
        seasonFile(endpoint, query)?.delete()
        matchFile(endpoint, key, query).delete()
        matches.invalidate(matchKey(endpoint, key, query))
        searches.clear()
        unsupportedUntil.remove(endpoint.cacheKey)
        Unit
    }

    private suspend fun automatic(endpoint: DanmuEndpoint, key: String, query: DanmuQuery,
        aliases: suspend (DanmuQuery) -> DanmuQuery, freshIds: Boolean = false): DanmuSelection? = matches.get(matchKey(endpoint, key, query)) {
        automaticRequest(endpoint, key, query, aliases, freshIds)
    }

    private suspend fun searchBody(endpoint: DanmuEndpoint, term: String, episode: Int?, movie: Boolean = false): String =
        searches.get(SearchKey(endpoint, term, episode, movie)) {
            api.searchEpisodes(endpoint, term, episode, movie).also {
                if (DanmuProtocol.root(it)["animes"] !is kotlinx.serialization.json.JsonArray) throw IOException("弹幕搜索响应格式无效")
            }
        }

    private suspend fun automaticRequest(endpoint: DanmuEndpoint, key: String, query: DanmuQuery,
        aliases: suspend (DanmuQuery) -> DanmuQuery, freshIds: Boolean): DanmuSelection? {
        val expanded = aliases(query)
        val names = (query.names + expanded.names).map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val attempted = (names.take(1) + names.filter { name -> name.any { it.code in 0x3400..0x9fff } } + names).distinct()
        suspend fun attempt(names: List<String>): MatchCache? {
            return nameSearch.first(names) { name ->
                var supported = (unsupportedUntil[endpoint.cacheKey] ?: 0L) <= clock()
                if (supported) {
                    try {
                        fun accepted(body: String): MatchCache? {
                            val options = DanmuProtocol.matched(body, query.movie)
                            val target = options.firstOrNull { name == names.firstOrNull() || DanmuMatch.knownTitle(expanded, it) }
                            return target?.let { MatchCache(DanmuSelection(it, name), clock(), options) }
                        }
                        var target = accepted(api.match(endpoint, fileName(query, name)))
                        if (target == null && !query.movie && query.year != null && (query.season ?: 1) <= 1) {
                            target = accepted(api.match(endpoint, fileName(query.copy(year = null), name)))
                        }
                        if (target != null) return@first target
                    } catch (e: DanmuHttpException) {
                        if (e.code !in setOf(404, 405, 501)) throw e
                        unsupportedUntil[endpoint.cacheKey] = clock() + CAPABILITY_TTL_MS
                        supported = false
                    }
                }
                if (!supported) {
                    if (freshIds) searches.invalidate(SearchKey(endpoint, name, query.episode, query.movie))
                    val body = searchBody(endpoint, name, query.episode, query.movie)
                    val id = DanmuMatch.choose(query.copy(names = names), DanmuText.searchResult(body))
                    val options = DanmuProtocol.searched(body)
                    if (id != null) options.firstOrNull { it.episodeId == id }?.let { return@first MatchCache(DanmuSelection(it, name), clock(), options) }
                }
                null
            }
        }
        var result = attempt(attempted)
        // A working match endpoint can miss although its search has exact candidates.
        if (result == null && (unsupportedUntil[endpoint.cacheKey] ?: 0L) <= clock()) {
            result = nameSearch.first(attempted.toList()) { name ->
                if (freshIds) searches.invalidate(SearchKey(endpoint, name, null, false))
                val options = DanmuProtocol.searched(searchBody(endpoint, name, null))
                DanmuMatch.chooseTarget(expanded, options)?.let { MatchCache(DanmuSelection(it, name), clock(), options) }
            }
        }
        write(matchFile(endpoint, key, query), json.encodeToString(result ?: MatchCache(null, clock())))
        return result?.selection
    }

    private suspend fun refreshChoice(endpoint: DanmuEndpoint, key: String, query: DanmuQuery, saved: DanmuSelection): DanmuSelection {
        // IDs may be recycled even while the full search is cached. A body miss requires fresh IDs.
        searches.invalidate(SearchKey(endpoint, saved.searchTitle.trim(), null, false))
        val candidates = search(endpoint, saved.searchTitle)
        val target = candidates.firstOrNull { candidate ->
            if (saved.target.url.isNotBlank()) candidate.target.url == saved.target.url
            else saved.target.animeId.isNotBlank() && saved.target.episodeTitle.isNotBlank() &&
                candidate.target.animeId == saved.target.animeId && candidate.target.episodeTitle == saved.target.episodeTitle
        } ?: throw IOException("已选弹幕来源失效，请在详情页重新选择")
        val refreshed = target.copy(manual = true, season = saved.season)
        if (!saved.season) saveChoice(endpoint, key, query, refreshed)
        return refreshed
    }

    /** The body is shared across row counts and aliases; each caller applies its own density. */
    private suspend fun loadComments(endpoint: DanmuEndpoint, key: String, query: DanmuQuery, selection: DanmuSelection): List<Danmaku> =
        bodies.get(bodyFile(endpoint, key, query, selection).name) {
            cachedBody(endpoint, key, query, selection)?.let { return@get it }
            val body = api.comments(endpoint, selection.target.episodeId)
            val comments = DanmuProtocol.comments(body)
            currentCoroutineContext().ensureActive()
            if (comments.isNotEmpty()) write(bodyFile(endpoint, key, query, selection), body)
            comments
        }

    private fun readChoice(endpoint: DanmuEndpoint, key: String, query: DanmuQuery): DanmuSelection? =
        read(choiceFile(endpoint, key, query))?.let { runCatching { json.decodeFromString<DanmuSelection>(it) }.getOrNull() }

    private suspend fun manualChoice(endpoint: DanmuEndpoint, key: String, query: DanmuQuery): DanmuSelection? {
        val rule = seasonFile(endpoint, query)?.let(::read)?.let {
            runCatching { json.decodeFromString<DanmuSeasonChoice>(it) }.getOrNull()
        } ?: return readChoice(endpoint, key, query)
        if (query.episode == rule.episode) return rule.anchor
        return rule.select(query.episode ?: return null, search(endpoint, rule.anchor.searchTitle).map { it.target })
    }

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
    private fun fingerprint(key: String, query: DanmuQuery) = listOf("v5", binding(key, query), query.names, query.year, aliasContext()).joinToString("|")
    private fun seasonFile(endpoint: DanmuEndpoint, query: DanmuQuery): File? =
        if (query.movie || query.workId.isNullOrBlank() || query.season == null) null
        else File(choicesDir, "s1-" + hash(endpoint.cacheKey + "|" + query.workId + "|" + query.season))
    private fun matchFile(endpoint: DanmuEndpoint, key: String, query: DanmuQuery) = File(dir, "m3-" + hash(endpoint.cacheKey + "|" + fingerprint(key, query)))
    private fun matchKey(endpoint: DanmuEndpoint, key: String, query: DanmuQuery) = matchFile(endpoint, key, query).name + "|" + hash(endpoint.appId + "|" + endpoint.appSecret)
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
        const val MATCH_TTL_MS = 7L * 24 * 3600_000
        const val NO_MATCH_TTL_MS = 5L * 60_000
        const val COMMENTS_TTL_MS = 12L * 3600_000
        const val SEARCH_TTL_MS = 5L * 60_000
        const val NO_SEARCH_TTL_MS = 30_000L
        private const val FRESH_MATCH_MS = 5_000L
        private const val CAPABILITY_TTL_MS = 6L * 3600_000

        /** Synthetic, minimal name: never a file path or a playback URL. */
        fun fileName(query: DanmuQuery, name: String): String = buildString {
            append(name.replace(Regex("[\\r\\n\\t]+"), " ").trim())
            query.year?.takeIf { query.movie || (query.season ?: 1) <= 1 }?.let { append(" ($it)") }
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
