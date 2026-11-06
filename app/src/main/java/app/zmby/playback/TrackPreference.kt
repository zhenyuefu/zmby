package app.zmby.playback

import app.zmby.emby.MediaSourceInfo
import app.zmby.emby.MediaStream
import app.zmby.media.TrackFacts
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
    const val ORIGINAL = "original"
    const val OFF = "off"

    /** Offered languages: stored code to label. */
    val LANGUAGES = listOf("chi" to "中文", "eng" to "英语", "jpn" to "日语")

    /**
     * [preferStyled] (特效字幕优先) puts the wanted language's effects subtitles first, recognised
     * as the 「特效」 badge is ([TrackFacts.effects]: the track title or an external file's name
     * tags, never the codec); the server's default still wins among them. It only applies when a
     * language is chosen, never to 跟随服务器 or 关闭.
     */
    fun choose(
        source: MediaSourceInfo,
        picked: TrackChoice,
        audioLanguage: String,
        subtitleLanguage: String,
        originalLanguage: String? = null,
        preferStyled: Boolean = false,
    ): TrackChoice {
        val origin = originalLanguage?.takeIf { it.isNotBlank() && it != "und" }
            ?: source.videoStream?.language?.takeUnless { it == "und" }
        fun resolve(pref: String) = if (pref == ORIGINAL) origin.orEmpty() else pref
        val audio = picked.audio ?: resolve(audioLanguage).takeIf { it != SERVER }?.let { pick(source.audioStreams.filterNot { track -> track.isExternal }, it) }?.index
        val subtitle = picked.subtitle ?: when (val language = resolve(subtitleLanguage)) {
            SERVER -> null
            OFF -> ZidooTracks.SUBTITLE_OFF
            // Forced tracks are for foreign dialogue only; external ones only if the player can load them.
            else -> pick(source.subtitleStreams.filter { !it.isForced && (!it.isExternal || LocalStrm.supports(it)) }, language,
                rank = if (preferStyled) { track -> if (TrackFacts.effects(track)) 0 else 1 } else { _ -> 0 })?.index
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

    private fun pick(streams: List<MediaStream>, language: String, rank: (MediaStream) -> Int = { 0 }): MediaStream? {
        val matching = streams.filter { matches(it, language) }
        val best = matching.minOfOrNull(rank) ?: return null
        val top = matching.filter { rank(it) == best }
        return top.firstOrNull { it.isDefault } ?: top.first()
    }

    private fun normalized(language: String): String {
        val code = language.trim().lowercase().replace('_', '-').substringBefore('-')
        return when (code) {
            "zh", "zho", "chs", "cht", "cmn", "yue" -> "chi"
            "jp" -> "jpn"
            "fre" -> "fra"
            "ger" -> "deu"
            "dut" -> "nld"
            else -> if (code.length == 2) when (val iso = runCatching { java.util.Locale.forLanguageTag(code).isO3Language }.getOrNull()) {
                "zho" -> "chi"
                null, "" -> code
                else -> iso
            } else code
        }
    }

    fun matches(stream: MediaStream, language: String): Boolean {
        val code = normalized(stream.language.orEmpty())
        val title = listOfNotNull(stream.title, stream.displayTitle, stream.displayLanguage).joinToString(" ")
        val wanted = normalized(language)
        if (code.isNotBlank() && code != "und") return code == wanted
        return when (wanted) {
            "chi" -> listOf("中文", "国语", "國語", "简体", "繁體", "粤语", "Chinese").any { title.contains(it, ignoreCase = true) }
            "eng" -> title.contains("English", ignoreCase = true)
            "jpn" -> title.contains("日本語") || title.contains("Japanese", ignoreCase = true)
            else -> false
        }
    }
}
