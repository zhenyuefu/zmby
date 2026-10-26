package app.zmby.zidoo

/**
 * The Zidoo system player's launch and result contract. Verified against the Z9X 8K firmware's
 * own Emby client (com.zidoo.audioplayer, 2026-10): current firmware ships com.zidoo.player;
 * older boxes (and jellyfin-androidtv-zidoo / JellyfinToZidoo) use the gallery3d MovieActivity.
 * Pure Kotlin so it can be unit tested; [ZidooIntents] turns it into an Android Intent.
 */
object ZidooPlayer {
    /** How a player gets an external (Emby-side) subtitle. */
    enum class SubtitleDelivery {
        /** MX Player `subs` extras with HTTP URLs. The gallery3d player reads them (jellyfin-androidtv-zidoo relies on it). */
        IntentExtras,

        /**
         * com.zidoo.player 1.3.60 has no code for `subs` (decompiled and tested 2026-10-24); it only
         * finds subtitles next to a local file. The video goes out as a local .strm whose first line
         * is the HTTP URL, with the downloaded subtitle beside it, chosen by `subtitlePid` (the MD5 of
         * the subtitle file). Only text formats: SUP/VobSub get other ids in the player.
         */
        LocalStrm,
    }

    data class Component(val pkg: String, val activity: String, val subtitles: SubtitleDelivery) {
        /** Whether this player can show an external subtitle in [codec] (Emby's codec name). */
        fun supportsExternal(codec: String?): Boolean =
            subtitles == SubtitleDelivery.IntentExtras || externalFormat(codec) in LOCAL_SUBTITLE_FORMATS
    }

    /** In order of preference; the first one installed is used. */
    val COMPONENTS = listOf(
        Component("com.zidoo.player", "com.zidoo.player.activity.PlayerActivity", SubtitleDelivery.LocalStrm),
        Component("com.android.gallery3d", "com.android.gallery3d.app.MovieActivity", SubtitleDelivery.IntentExtras),
    )

    /** External subtitle formats com.zidoo.player selects by file MD5. */
    val LOCAL_SUBTITLE_FORMATS = setOf("srt", "ass", "ssa")

    /** The file extension for an external subtitle in Emby's [codec]. */
    fun externalFormat(codec: String?): String = when (codec?.lowercase()) {
        "subrip", "srt" -> "srt"
        "ass" -> "ass"
        "ssa" -> "ssa"
        "webvtt", "vtt" -> "vtt"
        "pgssub", "pgs" -> "sup"
        null, "" -> "srt"
        else -> codec.lowercase()
    }

    const val EXTRA_TITLE = "title"
    const val EXTRA_FROM_START = "from_start"
    const val EXTRA_POSITION = "position"
    const val EXTRA_RETURN_RESULT = "return_result"
    const val EXTRA_LOADING_IMAGE = "loading_image"
    const val EXTRA_CLEARLOGO_IMAGE = "clearlogo_image"
    // Misspelled in the Zidoo player itself.
    const val EXTRA_SUBTITLE_SEARCH_TITLE = "subtitle_search_tilte"
    const val EXTRA_FILENAME = "filename"
    const val EXTRA_AUDIO_IDX = "audio_idx"
    const val EXTRA_SUBTITLE_IDX = "subtitle_idx"
    // Only honoured in plexlist mode (verified 2026-10-23); kept for players that do read it.
    const val EXTRA_USER_AGENT = "User-Agent"
    // External subtitles, MX Player protocol: Uri[] plus parallel String[] of names and file names.
    const val EXTRA_SUBS = "subs"
    const val EXTRA_SUBS_NAME = "subs.name"
    const val EXTRA_SUBS_FILENAME = "subs.filename"
    // com.zidoo.player: the external subtitle to start with, as the lowercase MD5 of its file.
    const val EXTRA_SUBTITLE_PID = "subtitlePid"

    const val RESULT_POSITION = "position"
    const val RESULT_DURATION = "duration"
    const val RESULT_END_BY = "end_by"
    const val END_BY_COMPLETION = "playback_completion"
}

data class ZidooLaunchSpec(
    val uri: String,
    val mimeType: String,
    val extras: Map<String, Any>,
    /**
     * False for a local file: the intent then goes out with an explicit component and no action,
     * because Android 11 only applies the file:// exposure check (FileUriExposedException) to
     * intents that have an action. It grants nothing; the player reads the file with its own
     * storage access.
     */
    val viewAction: Boolean = true,
)

/** An extra that goes into the intent as `Uri[]` (Strings stay pure Kotlin until then). */
data class UriArray(val uris: List<String>)

/** An external subtitle the player should load over HTTP. */
data class ExternalSubtitle(val url: String, val name: String, val fileName: String)

data class ZidooLaunchRequest(
    val url: String,
    val container: String?,
    val title: String?,
    val startPositionMs: Long,
    val loadingImageUrl: String? = null,
    /** Only when the item really has a Logo image: the player hides its text title for a logo URL. */
    val clearLogoUrl: String? = null,
    val subtitleSearchTitle: String? = null,
    val fileName: String? = null,
    val userAgent: String? = null,
    /** Zidoo track numbers (see [ZidooTracks]); null leaves the choice to the player. */
    val audioIdx: Int? = null,
    val subtitleIdx: Int? = null,
    val externalSubtitles: List<ExternalSubtitle> = emptyList(),
    /** com.zidoo.player: MD5 of the local subtitle file to start with (see [ZidooPlayer.SubtitleDelivery.LocalStrm]). */
    val subtitlePid: String? = null,
)

fun buildZidooLaunch(request: ZidooLaunchRequest): ZidooLaunchSpec {
    val extras = linkedMapOf<String, Any>(ZidooPlayer.EXTRA_RETURN_RESULT to true)
    // Always state the start explicitly, otherwise the player applies its own bookmark.
    if (request.startPositionMs > 0) {
        extras[ZidooPlayer.EXTRA_FROM_START] = false
        extras[ZidooPlayer.EXTRA_POSITION] = request.startPositionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    } else {
        extras[ZidooPlayer.EXTRA_FROM_START] = true
    }
    request.title?.takeIf { it.isNotBlank() }?.let { extras[ZidooPlayer.EXTRA_TITLE] = it }
    request.loadingImageUrl?.let { extras[ZidooPlayer.EXTRA_LOADING_IMAGE] = it }
    request.clearLogoUrl?.let { extras[ZidooPlayer.EXTRA_CLEARLOGO_IMAGE] = it }
    request.subtitleSearchTitle?.takeIf { it.isNotBlank() }?.let { extras[ZidooPlayer.EXTRA_SUBTITLE_SEARCH_TITLE] = it }
    request.fileName?.takeIf { it.isNotBlank() }?.let { extras[ZidooPlayer.EXTRA_FILENAME] = it }
    request.userAgent?.takeIf { it.isNotBlank() }?.let { extras[ZidooPlayer.EXTRA_USER_AGENT] = it }
    request.audioIdx?.let { extras[ZidooPlayer.EXTRA_AUDIO_IDX] = it }
    request.subtitleIdx?.let { extras[ZidooPlayer.EXTRA_SUBTITLE_IDX] = it }
    if (request.externalSubtitles.isNotEmpty()) {
        extras[ZidooPlayer.EXTRA_SUBS] = UriArray(request.externalSubtitles.map { it.url })
        extras[ZidooPlayer.EXTRA_SUBS_NAME] = request.externalSubtitles.map { it.name }
        extras[ZidooPlayer.EXTRA_SUBS_FILENAME] = request.externalSubtitles.map { it.fileName }
    }
    request.subtitlePid?.let { extras[ZidooPlayer.EXTRA_SUBTITLE_PID] = it }
    val local = request.url.startsWith("file:", ignoreCase = true)
    // A local .strm says nothing about the container it points to; let the player sniff it.
    val mime = if (local) "video/*" else mimeTypeFor(request.container)
    return ZidooLaunchSpec(request.url, mime, extras, viewAction = !local)
}

/** Unknown containers, disc images and .strm go out as a wildcard video type so the player sniffs them. */
fun mimeTypeFor(container: String?): String {
    val c = container?.substringBefore(',')?.trim()?.lowercase()
    return if (c.isNullOrEmpty() || c in SNIFFED_CONTAINERS) "video/*" else "video/$c"
}

private val SNIFFED_CONTAINERS = setOf("strm", "iso", "bluray", "dvd", "bdmv")

/** What the player hands back through onActivityResult (MX Player result protocol). */
data class ZidooResult(
    val positionMs: Long?,
    val durationMs: Long?,
    val endBy: String?,
) {
    val completed: Boolean get() = endBy == ZidooPlayer.END_BY_COMPLETION
    /** MX result error; never infer failure from RESULT_CANCELED or a missing result. */
    val failed: Boolean get() = endBy == "playback_error"

    companion object {
        fun parse(extra: (String) -> Any?): ZidooResult = ZidooResult(
            positionMs = (extra(ZidooPlayer.RESULT_POSITION) as? Number)?.toLong()?.takeIf { it >= 0 },
            durationMs = (extra(ZidooPlayer.RESULT_DURATION) as? Number)?.toLong()?.takeIf { it > 0 },
            endBy = extra(ZidooPlayer.RESULT_END_BY) as? String,
        )
    }
}
