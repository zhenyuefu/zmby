package app.zmby.playback

import app.zmby.emby.MediaSourceInfo
import app.zmby.emby.MediaStream
import app.zmby.zidoo.ZidooTracks

/**
 * Default audio and subtitle tracks from the language preference (settings › 音频与字幕), used when
 * the user has not picked a track for this version. A pick always wins; "follow the server" keeps
 * the server's default for this user; a language nobody provides falls back to the server too.
 */
object TrackPreference {
    /** Follow the server's default. */
    const val SERVER = ""
    /** Subtitles off. */
    const val OFF = "off"

    /** Offered languages: stored code to label. */
    val LANGUAGES = listOf("chi" to "中文", "eng" to "英语", "jpn" to "日语")

    fun choose(source: MediaSourceInfo, picked: TrackChoice, audioLanguage: String, subtitleLanguage: String): TrackChoice {
        val audio = picked.audio ?: audioLanguage.takeIf { it != SERVER }?.let { pick(source.audioStreams, it) }?.index
        val subtitle = picked.subtitle ?: when (subtitleLanguage) {
            SERVER -> null
            OFF -> ZidooTracks.SUBTITLE_OFF
            // Forced tracks are for foreign dialogue only; external ones only if the player can load them.
            else -> pick(source.subtitleStreams.filter { !it.isForced && (!it.isExternal || LocalStrm.supports(it)) }, subtitleLanguage)?.index
        }
        return TrackChoice(audio, subtitle)
    }

    private fun pick(streams: List<MediaStream>, language: String): MediaStream? {
        val matching = streams.filter { matches(it, language) }
        return matching.firstOrNull { it.isDefault } ?: matching.firstOrNull()
    }

    fun matches(stream: MediaStream, language: String): Boolean {
        val code = stream.language?.lowercase()?.trim().orEmpty()
        val title = listOfNotNull(stream.title, stream.displayTitle).joinToString(" ")
        return when (language) {
            "chi" -> code in setOf("chi", "zho", "chs", "cht", "zh", "zh-cn", "zh-tw", "cmn", "yue") ||
                listOf("中文", "国语", "國語", "简体", "繁體", "粤语", "Chinese").any { title.contains(it, ignoreCase = true) }
            "eng" -> code in setOf("eng", "en", "en-us", "en-gb") || title.contains("English", ignoreCase = true)
            "jpn" -> code in setOf("jpn", "ja", "jp") || title.contains("日本語") || title.contains("Japanese", ignoreCase = true)
            else -> code == language
        }
    }
}
