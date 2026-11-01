package app.zmby.danmu

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One comment: when it appears in the video, its text and its colour (0xRRGGBB). */
data class Danmaku(val timeMs: Long, val text: String, val color: Int = WHITE) {
    companion object { const val WHITE = 0xFFFFFF }
}

/** Parsing and drawing limits. LogVar owns semantic deduplication and count suffixes. */
object DanmuText {
    const val MAX_CHARS = 60
    const val MAX_TOTAL = 12_000

    // Preserve joiners and variation selectors used by emoji, as well as LogVar's hair spaces.
    private val INVISIBLE = Regex("[\\u200B\\u200E\\u200F\\u2060\\uFEFF]")
    private const val LIKE_SUFFIX = "\u200A[\uFE0E\uFE0F]*(?:♡|🔥)\\d+(?:\\.\\d+)?[kw]?"
    private val COUNTS = Regex("(?:\u200Ax\u200A\\d+(?:$LIKE_SUFFIX)?|$LIKE_SUFFIX)$")

    /**
     * Keeps the service's repetition/like suffix intact, even when truncating a long message.
     * The length limit counts code points so supplementary characters are not split in half.
     */
    fun clean(raw: String?): String? {
        val text = raw?.replace(INVISIBLE, "")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val suffix = COUNTS.find(text)
        val message = if (suffix == null) text else text.substring(0, suffix.range.first)
        if (message.codePointCount(0, message.length) <= MAX_CHARS) return text
        val end = message.offsetByCodePoints(0, MAX_CHARS - 1)
        return message.substring(0, end) + "…" + suffix?.value.orEmpty()
    }

    /** `p` is "seconds,mode,colour,user…". */
    fun parse(p: String?, m: String?): Danmaku? {
        val parts = p?.split(',') ?: return null
        val seconds = parts.getOrNull(0)?.trim()?.toDoubleOrNull()?.takeIf { it >= 0 } ?: return null
        val text = clean(m) ?: return null
        val color = parts.getOrNull(2)?.trim()?.toLongOrNull()?.toInt()?.and(0xFFFFFF) ?: Danmaku.WHITE
        return Danmaku((seconds * 1000).toLong(), text, color)
    }

    /** The comments of a dandanplay `/api/v2/comment` answer. */
    fun comments(body: String, json: Json = Json): List<Danmaku> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        return comments(root)
    }

    internal fun comments(root: JsonObject): List<Danmaku> {
        val list = root["comments"] as? JsonArray ?: return emptyList()
        return list.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            parse(o["p"]?.jsonPrimitive?.contentOrNull, o["m"]?.jsonPrimitive?.contentOrNull)
        }
    }

    /**
     * A bounded, time-ordered view of the entire film. Keep both ends and sample across the
     * complete timeline instead of truncating the first N comments. Row capacity is enforced by
     * the stage, not a second semantic filter. Small datasets pass through unchanged.
     */
    fun filter(list: List<Danmaku>, rows: Int): List<Danmaku> {
        val sorted = list.sortedBy { it.timeMs }
        val limit = (rows.coerceIn(1, 5) * 4_000).coerceAtMost(MAX_TOTAL)
        if (sorted.size <= limit) return sorted
        return List(limit) { i -> sorted[(i.toLong() * (sorted.size - 1) / (limit - 1)).toInt()] }
    }

    /** Episode ids of a `/api/v2/search/episodes` answer, by anime (for [DanmuMatch]). */
    fun searchResult(body: String, json: Json = Json): List<DanmuCandidate> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val animes = root["animes"] as? JsonArray ?: return emptyList()
        return animes.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val title = o["animeTitle"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val type = listOfNotNull(o["type"]?.jsonPrimitive?.contentOrNull, o["typeDescription"]?.jsonPrimitive?.contentOrNull).joinToString(" ")
            val episodes = (o["episodes"] as? JsonArray)?.mapNotNull { ep ->
                (ep as? JsonObject)?.get("episodeId")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            }.orEmpty()
            DanmuCandidate(title, type, episodes)
        }
    }
}
