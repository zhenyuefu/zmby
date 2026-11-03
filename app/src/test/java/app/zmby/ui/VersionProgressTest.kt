package app.zmby.ui

import app.zmby.data.ServerConfig
import app.zmby.data.ServerLine
import app.zmby.data.ServerStatus
import app.zmby.data.newestProgress
import app.zmby.emby.BaseItemDto
import app.zmby.emby.MediaSourceInfo
import app.zmby.emby.UserItemData
import app.zmby.emby.msToTicks
import app.zmby.playback.PlaybackSelection
import app.zmby.playback.SavedPlayback
import app.zmby.playback.TrackChoice
import app.zmby.ui.screens.Version
import app.zmby.ui.screens.playbackDefault
import org.junit.Assert.assertEquals
import org.junit.Test

class VersionProgressTest {
    private fun server(id: String, order: Int) = ServerStatus(ServerConfig(id, id, listOf(ServerLine("l", "主线路", "http://$id")), "l", "u", "t"), order)
    private val runtime = msToTicks(120 * 60_000L)

    private fun version(server: String, item: String, source: String = "$item-s", minutes: Long = 0, played: Boolean = false, at: String? = null) =
        Version(server(server, 0), BaseItemDto(id = item, name = "沙丘2", type = "Movie", runTimeTicks = runtime,
            userData = UserItemData(playbackPositionTicks = msToTicks(minutes * 60_000), played = played, lastPlayedDate = at)), MediaSourceInfo(source))

    private fun memory(v: Version, at: String) =
        SavedPlayback(v.server.id, v.item.id, PlaybackSelection.capture(v.source, TrackChoice())!!, java.time.Instant.parse(at).toEpochMilli())

    @Test fun `the copy holding the newest resume point plays, not the ranked first`() {
        val best = version("a", "a1")
        val resumed = version("b", "b1", minutes = 40, at = "2026-11-01T20:00:00Z")
        assertEquals(resumed, playbackDefault(listOf(best, resumed), null, 90))
        // Another source of the same copy ranks first: that one, not just any.
        val remux = version("b", "b1", source = "b1-remux", minutes = 40, at = "2026-11-01T20:00:00Z")
        assertEquals(remux, playbackDefault(listOf(best, remux, resumed), null, 90))
    }

    @Test fun `a newer finish elsewhere means nothing to resume`() {
        val resumed = version("a", "a1", minutes = 40, at = "2026-11-01T20:00:00Z")
        val finished = version("b", "b1", played = true, at = "2026-11-02T20:00:00Z")
        assertEquals(resumed, playbackDefault(listOf(resumed, finished), null, 90))
        val best = version("c", "c1")
        assertEquals(best, playbackDefault(listOf(best, resumed, finished), null, 90))
    }

    @Test fun `the last start here wins when it is that copy or came later`() {
        val best = version("a", "a1")
        val resumed = version("b", "b1", minutes = 40, at = "2026-11-01T20:00:00Z")
        val other = version("c", "c1")
        assertEquals(other, playbackDefault(listOf(best, resumed, other), memory(other, "2026-11-02T08:00:00Z"), 90))
        assertEquals(resumed, playbackDefault(listOf(best, resumed, other), memory(other, "2026-10-30T08:00:00Z"), 90))
        // The remembered source of the resumed copy, not the ranked one.
        val remux = version("b", "b1", source = "b1-remux", minutes = 40, at = "2026-11-01T20:00:00Z")
        assertEquals(resumed, playbackDefault(listOf(best, remux, resumed), memory(resumed, "2026-10-30T08:00:00Z"), 90))
    }

    @Test fun `a resume point past the watch threshold is not one`() {
        val best = version("a", "a1")
        val almost = version("b", "b1", minutes = 115, at = "2026-11-01T20:00:00Z")
        assertEquals(best, playbackDefault(listOf(best, almost), null, 90))
        assertEquals(null, playbackDefault(emptyList(), null, 90))
    }

    @Test fun `stored and reported progress merge by last played time`() {
        val old = UserItemData(playbackPositionTicks = 10, lastPlayedDate = "2026-11-01T08:00:00Z")
        val new = UserItemData(playbackPositionTicks = 20, lastPlayedDate = "2026-11-02T08:00:00Z")
        val untimed = UserItemData(playbackPositionTicks = 30)
        assertEquals(mapOf("a:1" to new, "b:2" to old), newestProgress(mapOf("a:1" to old, "b:2" to old), mapOf("a:1" to new)))
        assertEquals(mapOf("a:1" to new), newestProgress(mapOf("a:1" to new), mapOf("a:1" to old)))
        assertEquals(mapOf("a:1" to old), newestProgress(mapOf("a:1" to untimed), mapOf("a:1" to old)))
        assertEquals(mapOf("a:1" to old), newestProgress(mapOf("a:1" to old), mapOf("a:1" to untimed)))
    }
}
