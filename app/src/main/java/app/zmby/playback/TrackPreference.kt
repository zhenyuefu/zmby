package app.zmby.playback

import app.zmby.emby.MediaSourceInfo
import app.zmby.emby.MediaStream
import app.zmby.zidoo.ZidooTracks

/**
 * Default audio and subtitle tracks from the language preference (settings › 音频与字幕), used when
 * the user has not picked a track for this version. A pick always wins; "follow the server" keeps
 * the server's default for this user; a language nobody provides falls back to the server too.
 */
/** Audio and subtitle languages as [TrackPreference] codes ([TrackPreference.OFF] = subtitles off). */
data class TrackLanguages(val audio: String, val subtitle: String)

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

    /**
     * The languages a playback ended up with, to carry over to the next episode or another version
     * (Up Next, 播放失败自动换源): stream indexes mean nothing in another file, languages do. Tracks
     * left to the server count as the server's default ones; unknown languages keep [audioDefault] /
     * [subtitleDefault].
     */
    fun carried(source: MediaSourceInfo, chosen: TrackChoice, audioDefault: String, subtitleDefault: String): TrackLanguages {
        fun code(stream: MediaStream?): String? {
            stream ?: return null
            return LANGUAGES.firstOrNull { matches(stream, it.first) }?.first ?: stream.language?.lowercase()?.trim()?.takeIf { it.isNotEmpty() && it != "und" }
        }
        val audioIndex = chosen.audio ?: source.defaultAudioStreamIndex
        val audio = code(source.audioStreams.firstOrNull { it.index == audioIndex }) ?: audioDefault
        val subtitle = when (val i = chosen.subtitle ?: source.defaultSubtitleStreamIndex) {
            null -> subtitleDefault
            ZidooTracks.SUBTITLE_OFF -> OFF
            else -> code(source.subtitleStreams.firstOrNull { it.index == i }) ?: subtitleDefault
        }
        return TrackLanguages(audio, subtitle)
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
