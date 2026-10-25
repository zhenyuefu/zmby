package app.zmby.zidoo

import app.zmby.emby.MediaSourceInfo
import app.zmby.emby.MediaStream

/**
 * Converts Emby stream indexes (global across the file) to the Zidoo player's track numbers
 * (plan §2.1), following jellyfin-androidtv-zidoo's `convertToZidooIndex`:
 * - `audio_idx` is the 0-based position among the file's embedded audio tracks;
 * - `subtitle_idx` is 0 for "off", otherwise the 1-based position among embedded subtitle tracks;
 *   external subtitles handed over in `subs` come after the embedded ones, in the order passed.
 */
object ZidooTracks {
    /** Emby's "no subtitle" index. */
    const val SUBTITLE_OFF = -1

    private fun embeddedAudio(source: MediaSourceInfo) = source.mediaStreams.filter { it.type == "Audio" && !it.isExternal }
    private fun embeddedSubs(source: MediaSourceInfo) = source.mediaStreams.filter { it.type == "Subtitle" && !it.isExternal }

    /** External subtitle streams in the order they are passed to the player. */
    fun externalSubtitles(source: MediaSourceInfo): List<MediaStream> =
        source.mediaStreams.filter { it.type == "Subtitle" && it.isExternal }

    fun audioIdx(source: MediaSourceInfo, embyIndex: Int): Int? =
        embeddedAudio(source).indexOfFirst { it.index == embyIndex }.takeIf { it >= 0 }

    fun subtitleIdx(source: MediaSourceInfo, embyIndex: Int): Int? {
        if (embyIndex == SUBTITLE_OFF) return 0
        val embedded = embeddedSubs(source)
        embedded.indexOfFirst { it.index == embyIndex }.takeIf { it >= 0 }?.let { return it + 1 }
        val external = externalSubtitles(source).indexOfFirst { it.index == embyIndex }
        return if (external >= 0) embedded.size + external + 1 else null
    }

    /** The Emby subtitle index that will play: the user's pick, else the server's default; -1 = off, null = no preference. */
    fun wantedSubtitle(source: MediaSourceInfo, subtitle: Int?): Int? = subtitle ?: source.defaultSubtitleStreamIndex?.takeIf { it >= 0 }

    /**
     * The tracks to request: the user's pick, else the server's default for this user. Audio is
     * always sent (falling back to the file's default track): without it the Zidoo player reuses
     * its own bookmark from an earlier playback (seen 2026-10-24). A null subtitle means "let the
     * player decide"; so does an external subtitle the player cannot load from the intent
     * ([externalAllowed] false: com.zidoo.player gets it as a local file chosen by `subtitlePid`).
     */
    fun choose(source: MediaSourceInfo, audio: Int?, subtitle: Int?, externalAllowed: Boolean = true): Pair<Int?, Int?> {
        val fileDefault = embeddedAudio(source).let { list -> list.firstOrNull { it.isDefault } ?: list.firstOrNull() }?.index
        val a = (audio ?: source.defaultAudioStreamIndex ?: fileDefault)?.let { audioIdx(source, it) }
        val wanted = wantedSubtitle(source, subtitle)
        val unsupported = !externalAllowed && wanted != null && externalSubtitles(source).any { it.index == wanted }
        val s = if (unsupported) null else wanted?.let { subtitleIdx(source, it) }
        return a to s
    }
}
