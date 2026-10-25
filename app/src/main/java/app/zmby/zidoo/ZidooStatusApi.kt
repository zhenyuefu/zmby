package app.zmby.zidoo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** A snapshot of `GET /ZidooVideoPlay/getPlayStatus`. */
data class ZidooStatus(
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val path: String?,
    val title: String?,
) {
    /** Identifies which file the player has open; changes when it auto-advances. */
    val fileKey: String? get() = path?.takeIf { it.isNotBlank() } ?: title?.takeIf { it.isNotBlank() }

    companion object {
        private const val VIDEO_PLAYING = 1
        private const val VIDEO_PAUSED = 0

        /** Returns null unless a video is actually open (playing or paused with sane dimensions). */
        fun parse(body: String): ZidooStatus? {
            val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
            if (root["status"]?.jsonPrimitive?.intOrNull != 200) return null
            val video = root["video"] as? JsonObject ?: return null
            val state = video["status"]?.jsonPrimitive?.intOrNull ?: return null
            if (state != VIDEO_PLAYING && state != VIDEO_PAUSED) return null
            val width = video["width"]?.jsonPrimitive?.intOrNull ?: 0
            if (width <= 0) return null
            val duration = video["duration"]?.jsonPrimitive?.longOrNull ?: 0
            val position = video["currentPosition"]?.jsonPrimitive?.longOrNull ?: 0
            return ZidooStatus(
                playing = state == VIDEO_PLAYING,
                // Broken HLS streams report a position without a duration; don't trust it.
                positionMs = if (duration > 0) position.coerceAtLeast(0) else 0,
                durationMs = duration,
                path = video["path"]?.jsonPrimitive?.contentOrNull,
                title = video["title"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }
}

/** A snapshot of `GET /ZidooVideoPlay/getSubtitleList`; [index] is what `setSubtitle?index=` takes. */
data class ZidooSubtitleList(val currentIndex: Int, val items: List<Item>) {
    data class Item(val index: Int, val title: String?, val language: String?, val format: String?, val isExternal: Boolean, val isClose: Boolean)

    val externals: List<Item> get() = items.filter { it.isExternal && !it.isClose }

    companion object {
        fun parse(body: String): ZidooSubtitleList? {
            val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
            if (root["status"]?.jsonPrimitive?.intOrNull != 200) return null
            val list = root["subtitles"] as? kotlinx.serialization.json.JsonArray ?: return null
            val items = list.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                fun str(k: String) = o[k]?.jsonPrimitive?.contentOrNull
                fun bool(k: String) = o[k]?.jsonPrimitive?.booleanOrNull ?: false
                Item(
                    index = o["index"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                    title = str("title"),
                    // Misspelled in the player.
                    language = str("laungaue"),
                    format = str("subtitleFormat"),
                    isExternal = bool("isExternal"),
                    isClose = bool("isClose"),
                )
            }
            return ZidooSubtitleList(root["currentIndex"]?.jsonPrimitive?.intOrNull ?: -1, items)
        }
    }
}

/** com.zidoo.player's localized title suffixes for subtitles (`in_text_subtitle` / `ex_subtitle`). */
data class ZidooPlayerStrings(val internalText: String? = null, val externalFile: String? = null) {
    /** Whether a subtitle menu title names a file found next to the video rather than a track inside it. */
    fun isScannedFile(title: String?): Boolean {
        val t = title.orEmpty()
        if (!internalText.isNullOrBlank() && internalText in t) return false
        return externalFile.isNullOrBlank() || externalFile in t
    }
}

/** The player's subtitle menu, as far as the HTTP API exposes it. */
interface PlayerSubtitleControl {
    suspend fun subtitleList(): ZidooSubtitleList?
    suspend fun setSubtitle(index: Int): Boolean
}

interface PlayerStatusSource {
    /** Current status, or null when no video is open or the API is unreachable. */
    suspend fun status(): ZidooStatus?
    suspend fun seekTo(positionMs: Long): Boolean
}

/** The Zidoo HTTP control API; on the box itself it listens on 127.0.0.1:9529. */
class ZidooStatusApi(
    private val http: OkHttpClient,
    private val baseUrl: String = "http://127.0.0.1:9529",
) : PlayerStatusSource, PlayerSubtitleControl {

    override suspend fun subtitleList(): ZidooSubtitleList? = get("ZidooVideoPlay/getSubtitleList")?.let(ZidooSubtitleList::parse)

    override suspend fun setSubtitle(index: Int): Boolean =
        get("ZidooVideoPlay/setSubtitle?index=${index.coerceAtLeast(0)}")?.let(::isOk) ?: false

    override suspend fun status(): ZidooStatus? = get("ZidooVideoPlay/getPlayStatus")?.let(ZidooStatus::parse)

    // The parameter really is spelled "positon".
    override suspend fun seekTo(positionMs: Long): Boolean =
        get("ZidooVideoPlay/seekTo?positon=${positionMs.coerceAtLeast(0)}")?.let(::isOk) ?: false

    private fun isOk(body: String): Boolean = runCatching {
        Json.parseToJsonElement(body).jsonObject["status"]?.jsonPrimitive?.int == 200
    }.getOrDefault(false)

    private suspend fun get(path: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url("$baseUrl/$path").build()).execute().use { r ->
                if (r.isSuccessful) r.body?.string() else null
            }
        }.getOrNull()
    }
}
