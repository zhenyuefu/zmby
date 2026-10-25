package app.zmby.favorites

import app.zmby.data.FavoriteSync
import app.zmby.data.ServerItem
import app.zmby.emby.BaseItemDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteLogicTest {
    private val dune = BaseItemDto(id = "d1", name = "沙丘：第二部", type = "Movie", providerIds = mapOf("Tmdb" to "693134"))
    private val duneB = BaseItemDto(id = "x9", name = "沙丘2", type = "Movie", providerIds = mapOf("TMDB" to "693134"))
    private val policies = mapOf("a" to FavoriteSync.TwoWay, "b" to FavoriteSync.PushOnly, "c" to FavoriteSync.Off)
    private fun policy(s: String) = policies[s] ?: FavoriteSync.Off

    @Test fun `favouriting is local first and queues writes per policy`() {
        val s = FavoriteLogic.toggle(FavoritesState(), ServerItem("a", dune), listOf(ServerItem("a", dune), ServerItem("b", duneB), ServerItem("c", BaseItemDto(id = "c1", type = "Movie"))), 100, ::policy)
        assertTrue(s.favorites.single().active)
        assertEquals("movie:tmdb:693134", s.favorites.single().workKey)
        assertEquals(setOf("a", "b"), s.queue.map { it.server }.toSet())
    }

    @Test fun `removal leaves a tombstone and replaces queued writes`() {
        val on = FavoriteLogic.toggle(FavoritesState(), ServerItem("a", dune), listOf(ServerItem("a", dune)), 100, ::policy)
        val off = FavoriteLogic.toggle(on, ServerItem("a", dune), emptyList(), 200, ::policy)
        assertEquals(200L, off.favorites.single().removedAt)
        assertEquals(listOf(false), off.queue.map { it.favorite })
    }

    @Test fun `pull adds server favourites and does not revive a newer local removal`() {
        val pulled = FavoriteLogic.pull(FavoritesState(), "a", listOf(duneB), 100, ::policy)
        assertTrue(pulled.favorites.single().active)
        val removed = FavoriteLogic.toggle(pulled, ServerItem("a", duneB), emptyList(), 150, ::policy).copy(queue = emptyList())
        val again = FavoriteLogic.pull(removed, "a", listOf(duneB), 200, ::policy)
        assertFalse(again.favorites.single().active)
        // The local removal (150) is newer than the last pull (100): written back.
        assertEquals(listOf("a" to false), again.queue.map { it.server to it.favorite })
    }

    @Test fun `a synced favourite the server no longer has was removed elsewhere`() {
        val first = FavoriteLogic.pull(FavoritesState(), "a", listOf(duneB), 100, ::policy)
        val next = FavoriteLogic.pull(first, "a", emptyList(), 300, ::policy)
        assertFalse(next.favorites.single().active)
    }

    @Test fun `a refusing server drops its queue, errors retry`() {
        val s = FavoriteLogic.toggle(FavoritesState(), ServerItem("a", dune), listOf(ServerItem("a", dune), ServerItem("b", duneB)), 100, ::policy)
        val wa = s.queue.first { it.server == "a" }
        val refused = FavoriteLogic.written(s, wa, WriteResult.Refused(403))
        assertTrue(refused.queue.none { it.server == "a" })
        assertTrue(FavoriteLogic.refusal(403) && FavoriteLogic.refusal(405) && !FavoriteLogic.refusal(500))
        val wb = s.queue.first { it.server == "b" }
        val retry = FavoriteLogic.written(s, wb, WriteResult.Error("timeout"))
        assertEquals(1, retry.queue.first { it.server == "b" }.attempts)
        val ok = FavoriteLogic.written(s, wb, WriteResult.Ok)
        assertTrue(ok.favorites.single().instances.single { it.server == "b" }.synced)
    }
}
