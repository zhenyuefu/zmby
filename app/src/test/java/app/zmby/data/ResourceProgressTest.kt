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

    @Test fun `loading-only sources re-read until playable and never return placeholders`() = kotlinx.coroutines.test.runTest {
        val pending = app.zmby.emby.MediaSourceInfo("wait", "正在获取中")
        assertTrue(pending.copy(name = "资源加载中…").loadingResource)
        assertTrue(pending.copy(name = "视频", path = "/movie/正在获取中.mp4").loadingResource)
        assertFalse(pending.copy(name = "视频", path = "/正在获取中/movie.mkv").loadingResource)
        val ready = app.zmby.emby.MediaSourceInfo("real")
        var calls = 0
        val result = resolveDisplaySources(listOf(pending)) {
            when (++calls) {
                1 -> emptyList()
                2 -> listOf(pending, ready)
                else -> displaySourceAnswer(listOf(pending), listOf(ready))
            }
        }
        assertEquals(listOf(ready), result)
        assertEquals(3, calls)
        assertEquals(listOf(ready), resolveDisplaySources(listOf(ready)) { error("unexpected polling") })
        val timeout = runCatching { resolveDisplaySources(listOf(pending)) { listOf(pending) } }.exceptionOrNull()
        assertTrue(timeout is java.util.concurrent.TimeoutException)
    }
}
