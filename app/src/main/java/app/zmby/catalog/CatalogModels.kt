package app.zmby.catalog

import app.zmby.emby.BaseItemDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class CatalogId(val source: String, val kind: String, val id: String) {
    init { require(source in setOf("tmdb", "bangumi") && kind in setOf("movie", "tv") && id.toLongOrNull()?.let { it > 0 } == true) }
    val key: String get() = "$source:$kind:$id"
}
@Serializable
data class CatalogPerson(val source: String, val id: String, val name: String, val role: String = "", val image: String? = null) {
    val key: String get() = "$source:$id"
}
@Serializable
data class CatalogSeason(val number: Int, val title: String, val episodes: Int, val date: String? = null)
@Serializable
data class CatalogEpisode(val id: String, val season: Int, val number: Int?, val title: String, val overview: String = "", val image: String? = null, val date: String? = null)
@Serializable
data class CatalogWork(
    val ref: CatalogId, val title: String, val originalTitle: String = title, val overview: String = "",
    val year: Int? = null, val rating: Double? = null, val genres: List<String> = emptyList(),
    val poster: String? = null, val backdrop: String? = null, val logo: String? = null,
    val seasons: List<CatalogSeason> = emptyList(), val cast: List<CatalogPerson> = emptyList(),
    val ids: Map<String, String> = emptyMap(),
) {
    fun item() = BaseItemDto(id = "catalog:${ref.key}", name = title, originalTitle = originalTitle,
        type = if (ref.kind == "movie") "Movie" else "Series", productionYear = year, providerIds = ids)
    fun item(ep: CatalogEpisode) = BaseItemDto(id = "catalog:${ref.key}:${ep.id}", type = "Episode", name = ep.title,
        seriesId = item().id, seriesName = title, parentIndexNumber = ep.season, indexNumber = ep.number,
        providerIds = if (ref.source == "tmdb") mapOf("Tmdb" to ep.id) else mapOf("Bangumi" to ep.id))
}

enum class CatalogChart(val label: String, val path: String) {
    Trending("本周趋势", "trending/all/week"), NowPlaying("电影 · 正在热映", "movie/now_playing"),
    PopularMovies("电影 · 备受欢迎", "movie/popular"), PopularTv("剧集 · 备受欢迎", "tv/popular"),
    TopMovies("电影 · 高分内容", "movie/top_rated"), TopTv("剧集 · 高分内容", "tv/top_rated"),
    Airing("剧集 · 今日播出", "tv/airing_today"), OnAir("剧集 · 正在播出", "tv/on_the_air");
    companion object { fun of(value: String) = entries.firstOrNull { it.name == value } ?: Trending }
}

internal fun JsonElement.obj() = this as? JsonObject ?: JsonObject(emptyMap())
internal fun JsonObject.str(k: String) = (get(k) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
internal fun JsonObject.num(k: String) = (get(k) as? JsonPrimitive)?.intOrNull
internal fun JsonObject.dec(k: String) = (get(k) as? JsonPrimitive)?.doubleOrNull
internal fun JsonObject.rows(k: String) = (get(k) as? JsonArray).orEmpty().map { it.obj() }
internal fun JsonObject.child(k: String) = get(k)?.obj() ?: JsonObject(emptyMap())
internal fun art(path: String?, size: String = "w780") = path?.takeIf { it.startsWith('/') }?.let { "https://image.tmdb.org/t/p/$size$it" }

/** Never treat a generic Chinese image tag as proof of a particular script. */
object ArtworkLanguage {
    fun tag(raw: String): String = when (val language = raw.replace('_', '-').lowercase()) {
        "zh-hans" -> "zh-Hans"
        "zh-hant" -> "zh-Hant"
        else -> language
    }
    private fun script(language: String) = when (language) {
        "zh-cn", "zh-sg" -> "zh-Hans"
        "zh-tw", "zh-hk", "zh-mo" -> "zh-Hant"
        else -> language
    }
    fun choose(images: List<JsonObject>, preferred: String, original: String, neutral: Boolean = false): String? {
        val wanted = tag(preferred)
        val native = tag(original)
        val ranks = (if (neutral) listOf("", wanted, script(wanted), native) else listOf(wanted, script(wanted), native)).distinct()
        for (rank in ranks) {
            // Original 'zh' also cannot establish which Chinese script an image contains.
            if (rank == "zh") continue
            val eligible = images.filter { image ->
                val language = image.str("iso_639_1")?.let(::tag).orEmpty()
                val region = image.str("iso_3166_1")
                val exact = if (language.isNotEmpty() && !language.contains('-') && region != null) tag("$language-$region") else language
                (exact == rank || (rank == native && !rank.contains('-') && rank != "zh" && language.substringBefore('-') == rank)) && image.str("file_path") != null
            }
            eligible.sortedWith(compareByDescending<JsonObject> { it.dec("vote_average") ?: 0.0 }
                .thenByDescending { it.num("width") ?: 0 }.thenBy { it.str("file_path") }).firstOrNull()?.let { return it.str("file_path") }
        }
        return null
    }
}

/** Bangumi-data supplies aliases and subject IDs; it does not prove season/episode offsets. */
/**
 * TMDB's `region` takes an ISO 3166-1 country, not a script: zh-Hans charts come from mainland
 * China, zh-Hant from Taiwan, and a bare language uses its main country.
 */
object CatalogRegion {
    private val defaults = mapOf("zh" to "CN", "ja" to "JP", "ko" to "KR", "en" to "US", "fr" to "FR", "de" to "DE",
        "es" to "ES", "it" to "IT", "pt" to "BR", "ru" to "RU", "th" to "TH", "vi" to "VN")

    fun of(language: String): String {
        val parts = language.trim().replace('_', '-').split('-').filter { it.isNotEmpty() }
        parts.drop(1).firstOrNull { it.length == 2 && it.all(Char::isLetter) }?.let { return it.uppercase() }
        when (parts.drop(1).firstOrNull { it.length == 4 }?.lowercase()) {
            "hans" -> return "CN"
            "hant" -> return "TW"
        }
        return defaults[parts.firstOrNull()?.lowercase()] ?: "US"
    }
}

object AnimeAliases {
    /** Titles compare by letters and digits only, case-insensitively. */
    fun normalize(value: String) = value.lowercase().filter { it.isLetterOrDigit() }
}

/** Presentation-only card: navigation intercepts it before any server or playback operation. */
fun CatalogWork.cardRef() = app.zmby.data.ServerItem("catalog", item().copy(overview = overview,
    genres = genres, communityRating = rating, imageTags = listOfNotNull(poster?.let { "Primary" to it }, logo?.let { "Logo" to it }).toMap(),
    backdropImageTags = listOfNotNull(backdrop)))

@Serializable
data class CatalogBiography(val source: String, val id: String, val name: String, val overview: String = "", val image: String? = null,
    val birthDate: String? = null, val birthPlace: String? = null, val works: List<CatalogWork> = emptyList())
