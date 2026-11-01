package app.zmby.playback

import android.content.SharedPreferences
import androidx.core.content.edit
import app.zmby.data.Disk
import app.zmby.data.ServerItem
import app.zmby.emby.MediaSourceInfo
import app.zmby.emby.MediaStream
import app.zmby.zidoo.ZidooTracks
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** No URLs or credentials: indexes are accepted only with the same track identity in the same source. */
@Serializable
data class SavedTrack(val index: Int, val type: String?, val codec: String?, val language: String?, val title: String?, val displayTitle: String?, val external: Boolean) {
    companion object {
        fun of(s: MediaStream) = SavedTrack(s.index, s.type, s.codec, s.language, s.title, s.displayTitle, s.isExternal)
    }
}

@Serializable
data class PlaybackSelection(val sourceId: String, val audio: SavedTrack?, val subtitle: SavedTrack?) {
    /** Null subtitle means an explicit off choice, including a server default with no subtitle. */
    fun restore(source: MediaSourceInfo): TrackChoice? {
        if (source.id != sourceId) return null
        if (audio == null && source.audioStreams.isNotEmpty()) return null
        if (audio != null && source.audioStreams.none { SavedTrack.of(it) == audio }) return null
        if (subtitle != null && source.subtitleStreams.none { SavedTrack.of(it) == subtitle }) return null
        return TrackChoice(audio?.index, subtitle?.index ?: ZidooTracks.SUBTITLE_OFF)
    }

    companion object {
        fun capture(source: MediaSourceInfo, tracks: TrackChoice): PlaybackSelection? {
            if (source.id.isBlank()) return null
            val audioIndex = tracks.audio ?: source.defaultAudioStreamIndex
                ?: source.audioStreams.firstOrNull { it.isDefault }?.index ?: source.audioStreams.firstOrNull()?.index
            val audio = audioIndex?.let { index -> source.audioStreams.firstOrNull { it.index == index } ?: return null }
            val subtitleIndex = tracks.subtitle ?: source.defaultSubtitleStreamIndex ?: ZidooTracks.SUBTITLE_OFF
            val subtitle = if (subtitleIndex == ZidooTracks.SUBTITLE_OFF) null
                else source.subtitleStreams.firstOrNull { it.index == subtitleIndex } ?: return null
            return PlaybackSelection(source.id, audio?.let(SavedTrack::of), subtitle?.let(SavedTrack::of))
        }
    }
}

@Serializable
data class SavedPlayback(val server: String, val itemId: String, val selection: PlaybackSelection, val playedAt: Long) {
    val key: String get() = "$server\n$itemId"
}

/** Construct on IO with the other preferences. Reads afterwards use only the in-memory snapshot. */
class PlaybackMemory(private val prefs: SharedPreferences, private val persist: (() -> Unit) -> Unit = Disk::write) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(SavedPlayback.serializer())
    @Volatile private var entries = runCatching {
        json.decodeFromString(serializer, prefs.getString("selections", "[]")!!).associateBy { it.key }
    }.getOrDefault(emptyMap())

    fun latest(refs: List<ServerItem>): SavedPlayback? = refs.mapNotNull { entries["${it.server}\n${it.item.id}"] }.maxByOrNull { it.playedAt }

    fun tracksFor(ref: ServerItem, source: MediaSourceInfo): TrackChoice? = entries["${ref.server}\n${ref.item.id}"]?.selection?.restore(source)

    @Synchronized
    fun remember(value: SavedPlayback) {
        if ((entries[value.key]?.playedAt ?: Long.MIN_VALUE) > value.playedAt) return
        val snapshot = (entries + (value.key to value)).values.sortedByDescending { it.playedAt }.take(200).associateBy { it.key }
        entries = snapshot
        persist { prefs.edit { putString("selections", json.encodeToString(serializer, snapshot.values.toList())) } }
    }
}
