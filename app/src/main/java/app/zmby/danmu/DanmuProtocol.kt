package app.zmby.danmu

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.IOException

@Serializable
data class DanmuTarget(
    val episodeId: Long,
    val animeId: String = "",
    val animeTitle: String = "",
    val episodeTitle: String = "",
    val type: String = "",
    val url: String = "",
    /** Retained as metadata. LogVar currently returns zero and applies its offsets server-side. */
    val shift: Double = 0.0,
) {
    val sourceKey: String get() = url.takeIf { it.isNotBlank() } ?: "$animeId:$episodeId:$animeTitle:$episodeTitle"
    val label: String get() = listOf(animeTitle, episodeTitle).filter { it.isNotBlank() }.joinToString(" · ")
}

@Serializable
data class DanmuSelection(val target: DanmuTarget, val searchTitle: String, val manual: Boolean = false)

/** Only structural and clear media-type checks for a server-confirmed match; no local title,
 * year or season/episode equality checks can undo LogVar's alias and cross-season mapping. */
object DanmuProtocol {
    val json = Json { ignoreUnknownKeys = true }

    fun root(body: String): JsonObject {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: throw IOException("弹幕响应格式无效")
        if (root["success"]?.jsonPrimitive?.booleanOrNull == false ||
            root["errorCode"]?.jsonPrimitive?.intOrNull?.let { it != 0 } == true) throw IOException("弹幕服务未能完成请求")
        return root
    }

    fun matched(body: String, movie: Boolean): List<DanmuTarget> {
        val root = root(body)
        if (root["isMatched"]?.jsonPrimitive?.booleanOrNull != true) return emptyList()
        return (root["matches"] as? JsonArray).orEmpty().mapNotNull { target(it as? JsonObject) }
            .filter { compatible(it.type, movie) }.distinctBy { it.sourceKey }
    }

    fun searched(body: String): List<DanmuTarget> {
        val root = root(body)
        return (root["animes"] as? JsonArray).orEmpty().flatMap { value ->
            val anime = value as? JsonObject ?: return@flatMap emptyList()
            (anime["episodes"] as? JsonArray).orEmpty().mapNotNull { ep ->
                val e = ep as? JsonObject ?: return@mapNotNull null
                target(JsonObject(anime + e))
            }
        }.distinctBy { it.sourceKey }
    }

    fun comments(body: String): List<Danmaku> {
        val root = root(body)
        if (root["comments"] !is JsonArray) throw IOException("弹幕列表格式无效")
        return DanmuText.comments(root)
    }

    private fun target(row: JsonObject?): DanmuTarget? {
        row ?: return null
        val id = row.str("episodeId").toLongOrNull()?.takeIf { it > 0 } ?: return null
        val shift = row.str("shift").toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0
        return DanmuTarget(id, row.str("animeId"), row.str("animeTitle"), row.str("episodeTitle"),
            listOf(row.str("type"), row.str("typeDescription")).filter { it.isNotBlank() }.joinToString(" "), row.str("url"), shift)
    }

    private fun JsonObject.str(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun compatible(type: String, movie: Boolean): Boolean {
        val isMovie = type.contains("电影") || type.contains("剧场版") || type.contains("movie", true)
        val isSeries = type.contains("电视剧") || type.contains("tv series", true)
        return if (movie) !isSeries else !isMovie
    }
}
