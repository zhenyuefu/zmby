package app.zmby.data

import app.zmby.emby.BaseItemDto
import app.zmby.emby.UserItemData
import org.junit.Assert.*
import org.junit.Test

class ResourceProgressTest {
    @Test fun `cached episodes use fresh playback reports without changing identity or favorites`() {
        val ref = ServerItem("a", BaseItemDto("ep", userData = UserItemData(isFavorite = true, playbackPositionTicks = 100)))
        val played = UserItemData(played = true, playbackPositionTicks = 0, lastPlayedDate = "2026-10-28T08:00:00Z")
        val updated = ref.withPlaybackProgress(mapOf(ref.key to played))
        assertEquals(ref.key, updated.key)
        assertTrue(updated.item.userData!!.played)
        assertTrue(updated.item.userData!!.isFavorite)
        assertEquals(0L, updated.item.userData!!.playbackPositionTicks)
        assertEquals(ref, ref.withPlaybackProgress(mapOf("b:ep" to played)))
        val newer = ref.copy(item = ref.item.copy(userData = played.copy(played = false, lastPlayedDate = "2026-10-28T09:00:00Z")))
        assertEquals(newer, newer.withPlaybackProgress(mapOf(ref.key to played)))
    }
}
