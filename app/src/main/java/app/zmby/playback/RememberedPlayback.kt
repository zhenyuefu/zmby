package app.zmby.playback

import app.zmby.data.ServerItem
import app.zmby.emby.MediaSourceInfo
import kotlinx.coroutines.CancellationException

data class RememberedPlayback(val ref: ServerItem, val sourceId: String, val tracks: TrackChoice)

/** A missing or stale choice sends the user to details, without guessing another source or tracks. */
suspend fun resolveRememberedPlayback(
    saved: SavedPlayback?,
    candidates: List<ServerItem>,
    usable: (ServerItem) -> Boolean,
    sources: suspend (ServerItem) -> List<MediaSourceInfo>,
): RememberedPlayback? {
    saved ?: return null
    val ref = candidates.firstOrNull { it.server == saved.server && it.item.id == saved.itemId } ?: return null
    if (!usable(ref)) return null
    val source = try { sources(ref).firstOrNull { it.id == saved.selection.sourceId } }
    catch (e: CancellationException) { throw e }
    catch (_: Exception) { null } ?: return null
    val tracks = saved.selection.restore(source) ?: return null
    return RememberedPlayback(ref, source.id, tracks)
}
