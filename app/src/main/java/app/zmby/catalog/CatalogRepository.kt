package app.zmby.catalog

import app.zmby.emby.BaseItemDto
import app.zmby.aggregate.MetadataIdentity
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

class CatalogRepository(private val api: CatalogHttp, private val cache: CatalogStorage,
    private val textLanguage: () -> String, private val posterLanguage: () -> String, private val logoLanguage: () -> String,
    private val animeEnabled: () -> Boolean) {
    val configured get() = api.configured
    /** Null when TMDB accepts [token] (用手机输入 checks it before saving), else why not. */
    suspend fun verifyToken(token: String): String? = api.verifyToken(token)
    private fun viewKey(ref: CatalogId) = "view:${ref.key}:${textLanguage()}:${posterLanguage()}:${logoLanguage()}"
    suspend fun cached(ref: CatalogId): CatalogWork? = withContext(Dispatchers.IO) {
        cache.get(viewKey(ref))?.let { runCatching { api.json.decodeFromString(CatalogWork.serializer(), it.json) }.getOrNull() }
    }
    suspend fun work(ref: CatalogId): CatalogWork = withContext(Dispatchers.IO) {
        val result = if (ref.source == "tmdb") tmdb(ref) else bangumi(ref)
        cache.put(CatalogCache(viewKey(ref), api.json.encodeToString(CatalogWork.serializer(), result), System.currentTimeMillis()))
        result
    }
    /** No title guesses: use the server's explicit external ID, or Bangumi-data's unique alias. */
    suspend fun resolve(item: BaseItemDto): CatalogId? = withContext(Dispatchers.IO) {
        val kind = if (item.type == "Movie") "movie" else "tv"
        val ids = MetadataIdentity.ids(item)
        ids["tmdb"]?.takeIf { it.toLongOrNull()?.let { n -> n > 0 } == true }?.let { return@withContext CatalogId("tmdb", kind, it) }
        for (key in listOf("imdb", "tvdb")) {
            val id = ids[key] ?: continue
            if (!configured || (key == "tvdb" && kind == "movie")) continue
            val results = api.tmdb("find/$id", mapOf("external_source" to if (key == "imdb") "imdb_id" else "tvdb_id"))
                .rows(if (kind == "movie") "movie_results" else "tv_results")
            results.singleOrNull()?.str("id")?.let { return@withContext CatalogId("tmdb", kind, it) }
        }
        ids["bangumi"]?.takeIf { it.toLongOrNull()?.let { n -> n > 0 } == true }?.let { return@withContext CatalogId("bangumi", kind, it) }
        if (animeEnabled()) alias(item)?.let { subject -> return@withContext CatalogId("bangumi", kind, subject) }
        null
    }
    /** The Bangumi subject bangumi-data's aliases give [item] unambiguously, if any. */
    suspend fun alias(item: BaseItemDto): String? = withContext(Dispatchers.IO) {
        api.animeIndex().find(listOfNotNull(item.name, item.originalTitle), item.productionYear)
    }
    suspend fun tmdbCandidates(work: CatalogWork): List<CatalogWork> = withContext(Dispatchers.IO) {
        if (!configured) return@withContext emptyList()
        val terms = listOf(work.originalTitle, work.title).filter { it.isNotBlank() }.distinct()
        terms.flatMap { search(it, work.ref.kind) }.filter { it.year == work.year || it.year == null || work.year == null }.distinctBy { it.ref.key }
    }
    suspend fun search(term: String, kind: String? = null): List<CatalogWork> = withContext(Dispatchers.IO) {
        if (!configured || term.isBlank()) return@withContext emptyList()
        api.tmdb("search/${kind ?: "multi"}", mapOf("query" to term.trim(), "language" to textLanguage(), "include_adult" to "false"), 15 * 60_000)
            .rows("results").mapNotNull { brief(it, kind) }
    }
    suspend fun chart(chart: CatalogChart): List<CatalogWork> = withContext(Dispatchers.IO) {
        if (!configured) return@withContext emptyList()
        api.tmdb(chart.path, mapOf("language" to textLanguage(), "region" to CatalogRegion.of(textLanguage())), 60 * 60_000)
            .rows("results").mapNotNull { brief(it, if (chart.path.startsWith("movie/")) "movie" else if (chart.path.startsWith("tv/")) "tv" else null) }
    }
    /** TMDB's `recommendations` for [ref] (为你推荐); one page, cached like the other TMDB answers. */
    suspend fun recommendations(ref: CatalogId): List<CatalogWork> = withContext(Dispatchers.IO) {
        if (!configured || ref.source != "tmdb") return@withContext emptyList()
        api.tmdb("${ref.kind}/${ref.id}/recommendations", mapOf("language" to textLanguage()))
            .rows("results").mapNotNull { brief(it, ref.kind) }
    }

    /** The stored 为你推荐 list of a work (local first, ui-spec §2.14). */
    suspend fun storedRecommendations(workKey: String): app.zmby.aggregate.StoredRecommendations? = withContext(Dispatchers.IO) {
        cache.get(recsKey(workKey))?.let { runCatching { api.json.decodeFromString(app.zmby.aggregate.StoredRecommendations.serializer(), it.json) }.getOrNull() }
    }

    suspend fun storeRecommendations(workKey: String, recs: app.zmby.aggregate.StoredRecommendations) = withContext(Dispatchers.IO) {
        cache.put(CatalogCache(recsKey(workKey), api.json.encodeToString(app.zmby.aggregate.StoredRecommendations.serializer(), recs), recs.fetchedAt))
    }

    private fun recsKey(workKey: String) = "recs:v1:$workKey:${textLanguage()}"

    private fun brief(row: JsonObject, hint: String? = null): CatalogWork? {
        val kind = row.str("media_type") ?: hint ?: return null
        val id = row.str("id") ?: return null
        if (kind !in setOf("movie", "tv")) return null
        // Search/list poster_path has undocumented fallback choices: show a title until verified.
        return CatalogWork(CatalogId("tmdb", kind, id), row.str("title") ?: row.str("name").orEmpty(),
            row.str("original_title") ?: row.str("original_name").orEmpty(), row.str("overview").orEmpty(),
            (row.str("release_date") ?: row.str("first_air_date"))?.take(4)?.toIntOrNull(), row.dec("vote_average"),
            backdrop = art(row.str("backdrop_path")), ids = mapOf("Tmdb" to id))
    }
    private suspend fun tmdb(ref: CatalogId): CatalogWork = coroutineScope {
        val lang = textLanguage()
        val creditsKey = if (ref.kind == "tv") "aggregate_credits" else "credits"
        val body = api.tmdb("${ref.kind}/${ref.id}", mapOf("language" to lang, "append_to_response" to "translations,external_ids,$creditsKey"))
        val original = body.str("original_language") ?: "en"
        val images = api.tmdb("${ref.kind}/${ref.id}/images", mapOf("include_image_language" to listOf(posterLanguage(), logoLanguage(), original, "null").distinct().joinToString(",")))
        val translations = body.child("translations").rows("translations")
        fun translated(language: String): JsonObject? {
            val parts = language.split('-')
            return translations.firstOrNull { it.str("iso_639_1") == parts[0] && (parts.size == 1 || it.str("iso_3166_1") == parts[1]) }?.child("data")
        }
        val preferred = translated(lang)
        val native = translated(original)
        fun text(field: String) = preferred?.str(field) ?: native?.str(field)
        val titleField = if (ref.kind == "movie") "title" else "name"
        val originalTitle = body.str("original_$titleField").orEmpty()
        val castBody = body.child(creditsKey)
        val cast = castBody.rows("cast").mapNotNull { p ->
            val id = p.str("id") ?: return@mapNotNull null
            CatalogPerson("tmdb", id, p.str("name").orEmpty(), p.str("character") ?: p.rows("roles").mapNotNull { it.str("character") }.distinct().joinToString(" · "), art(p.str("profile_path"), "w185"))
        } + castBody.rows("crew").filter { p -> p.str("job") in setOf("Director", "Writer", "Screenplay") || p.rows("jobs").any { it.str("job") in setOf("Director", "Writer", "Screenplay") } }
            .mapNotNull { p -> p.str("id")?.let { CatalogPerson("tmdb", it, p.str("name").orEmpty(), p.str("job") ?: p.rows("jobs").mapNotNull { it.str("job") }.distinct().joinToString(" · "), art(p.str("profile_path"), "w185")) } }
        val ids = mapOf("Tmdb" to ref.id) + body.child("external_ids").let { ext -> listOfNotNull(ext.str("imdb_id")?.let { "Imdb" to it }, ext.str("tvdb_id")?.let { "Tvdb" to it }).toMap() }
        CatalogWork(ref, text(titleField) ?: originalTitle, originalTitle, text("overview").orEmpty(),
            (body.str("release_date") ?: body.str("first_air_date"))?.take(4)?.toIntOrNull(), body.dec("vote_average"),
            body.rows("genres").mapNotNull { it.str("name") },
            art(ArtworkLanguage.choose(images.rows("posters"), posterLanguage(), original)),
            art(ArtworkLanguage.choose(images.rows("backdrops"), posterLanguage(), original, neutral = true), "w1280"),
            art(ArtworkLanguage.choose(images.rows("logos"), logoLanguage(), original), "w500"),
            body.rows("seasons").mapNotNull { s -> s.num("season_number")?.let { CatalogSeason(it, s.str("name") ?: "第 $it 季", s.num("episode_count") ?: 0, s.str("air_date")) } },
            cast.groupBy { it.key }.values.map { people -> people.first().copy(role = people.map { it.role }.filter { it.isNotBlank() }.distinct().joinToString(" · ")) }, ids)
    }
    suspend fun episodes(work: CatalogWork, season: Int): List<CatalogEpisode> = withContext(Dispatchers.IO) {
        if (work.ref.source == "tmdb") {
            val body = api.tmdb("tv/${work.ref.id}/season/$season", mapOf("language" to textLanguage()))
            body.rows("episodes").mapNotNull { ep -> ep.str("id")?.let { CatalogEpisode(it, season, ep.num("episode_number"), ep.str("name") ?: "第 ${ep.num("episode_number")} 集", ep.str("overview").orEmpty(), art(ep.str("still_path")), ep.str("air_date")) } }
        } else {
            val all = mutableListOf<CatalogEpisode>()
            var offset = 0
            val seen = mutableSetOf<String>()
            while (true) {
                val body = api.bgm("episodes", mapOf("subject_id" to work.ref.id, "limit" to "100", "offset" to "$offset")).obj()
                val rows = body.rows("data")
                val prior = seen.size
                rows.forEach { row -> row.str("id")?.let { seen.add(it) } }
                if (rows.isNotEmpty() && seen.size == prior) throw CatalogException("Bangumi 分页未前进")
                rows.forEach { ep -> ep.str("id")?.let { id ->
                    // A Bangumi special is not a TMDB season-0 episode: keep its subject identity.
                    val type = ep.num("type") ?: 0
                    if ((season == 1 && type == 0) || (season == 0 && type != 0)) all += CatalogEpisode(id, season,
                        ep.dec("sort")?.takeIf { it % 1 == 0.0 }?.toInt(), bgmName(ep), ep.str("desc").orEmpty(), date = ep.str("airdate"))
                } }
                offset += rows.size
                if (rows.isEmpty() || offset >= (body.num("total") ?: offset)) break
                if (offset > 20_000) throw CatalogException("Bangumi 分页结果异常")
            }
            all
        }
    }
    private fun bgmName(body: JsonObject) = if (textLanguage() in setOf("zh-CN", "zh-Hans")) body.str("name_cn") ?: body.str("name").orEmpty() else body.str("name").orEmpty()
    private suspend fun bangumi(ref: CatalogId): CatalogWork {
        val body = api.bgm("subjects/${ref.id}").obj()
        val cast = api.bgm("subjects/${ref.id}/characters").let { it as? JsonArray }.orEmpty().flatMap { entry ->
            val character = entry.obj()
            character.rows("actors").mapNotNull { actor -> actor.str("id")?.let { CatalogPerson("bangumi", it, bgmName(actor), character.str("name").orEmpty(), actor.child("images").str("medium")) } }
        }
        return CatalogWork(ref, bgmName(body), body.str("name").orEmpty(), body.str("summary").orEmpty(), body.str("date")?.take(4)?.toIntOrNull(),
            body.child("rating").dec("score"), body.rows("tags").mapNotNull { it.str("name") }.take(5),
            // Bangumi artwork has no script tags, so do not substitute it for a strict poster preference.
            seasons = if (ref.kind == "tv") listOf(CatalogSeason(1, "本篇 · Bangumi 条目", body.num("eps") ?: body.num("total_episodes") ?: 0), CatalogSeason(0, "SP · 特别篇", 0)) else emptyList(),
            cast = cast.groupBy { it.key }.values.map { it.first().copy(role = it.map { p -> p.role }.distinct().joinToString(" · ")) }, ids = mapOf("Bangumi" to ref.id))
    }
    private fun personKey(source: String, id: String) = "person-view:$source:$id:${textLanguage()}"
    suspend fun cachedPerson(source: String, id: String): CatalogBiography? = withContext(Dispatchers.IO) {
        cache.get(personKey(source, id))?.let { runCatching { api.json.decodeFromString(CatalogBiography.serializer(), it.json) }.getOrNull() }
    }
    suspend fun person(source: String, id: String): CatalogBiography = withContext(Dispatchers.IO) {
        require(source in setOf("tmdb", "bangumi") && id.toLongOrNull()?.let { it > 0 } == true)
        val result = if (source == "tmdb") {
            val body = api.tmdb("person/$id", mapOf("language" to textLanguage(), "append_to_response" to "combined_credits"))
            val credits = body.child("combined_credits")
            val works = (credits.rows("cast") + credits.rows("crew")).mapNotNull { brief(it) }.distinctBy { it.ref.key }.sortedByDescending { it.year ?: 0 }
            CatalogBiography(source, id, body.str("name").orEmpty(), body.str("biography").orEmpty(), art(body.str("profile_path"), "w185"), body.str("birthday"), body.str("place_of_birth"), works)
        } else {
            val body = api.bgm("persons/$id").obj()
            val works = (api.bgm("persons/$id/subjects") as? JsonArray).orEmpty().mapNotNull { row ->
                val p = row.obj()
                if (p.num("type") !in setOf(2, 6)) null else p.str("id")?.let { CatalogWork(CatalogId("bangumi", "tv", it), bgmName(p)) }
            }.distinctBy { it.ref.key }
            CatalogBiography(source, id, bgmName(body), body.str("summary").orEmpty(), body.child("images").str("medium"), works = works)
        }
        cache.put(CatalogCache(personKey(source, id), api.json.encodeToString(CatalogBiography.serializer(), result), System.currentTimeMillis()))
        result
    }
}
