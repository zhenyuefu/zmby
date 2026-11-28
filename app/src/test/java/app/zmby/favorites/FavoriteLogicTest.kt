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

    @Test fun `a catalog work can be saved before resources arrive and only real copies receive writes`() {
        val catalog = ServerItem("catalog", dune.copy(id = "catalog:tmdb:movie:693134"))
        val local = FavoriteLogic.toggle(FavoritesState(), catalog, listOf(catalog), 1) { FavoriteSync.TwoWay }
        assertTrue(local.favorites.single().active)
        assertTrue(local.queue.isEmpty())
        val removed = FavoriteLogic.toggle(local, catalog, listOf(catalog, ServerItem("a", dune), ServerItem("b", duneB)), 2, ::policy)
        assertEquals(listOf(PendingWrite("a", "d1", false), PendingWrite("b", "x9", false)), removed.queue)
    }

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

    @Test fun `known instance gains TMDB before matching new versions regardless of snapshot order`() {
        val fallback = dune.copy(providerIds = emptyMap(), productionYear = 2024)
        val first = FavoriteLogic.pull(FavoritesState(), "a", listOf(fallback), 100, ::policy)
        val otherVersion = dune.copy(id = "second")
        for (snapshot in listOf(listOf(otherVersion, dune), listOf(dune, otherVersion))) {
            val refreshed = FavoriteLogic.pull(first, "a", snapshot, 200, ::policy)
            val peer = FavoriteLogic.pull(refreshed, "b", listOf(duneB), 300, ::policy)
            val favorite = peer.favorites.single()
            assertEquals("movie:tmdb:693134", favorite.workKey)
            assertEquals(100L, favorite.addedAt)
            assertEquals(setOf(FavoriteInstance("a", "d1", true), FavoriteInstance("a", "second", true),
                FavoriteInstance("b", "x9", true)), favorite.instances.toSet())
            assertTrue(favorite.active)
            assertTrue(peer.queue.isEmpty())
        }
    }

    @Test fun `local removal after metadata refresh still wins the next pull`() {
        val first = FavoriteLogic.pull(FavoritesState(), "a", listOf(dune.copy(providerIds = emptyMap())), 100, ::policy)
        val removed = FavoriteLogic.toggle(first, ServerItem("a", dune), emptyList(), 150, ::policy)
        assertEquals(150L, removed.favorites.single().removedAt)
        val again = FavoriteLogic.pull(removed, "a", listOf(dune), 200, ::policy)
        assertEquals(150L, again.favorites.single().removedAt)
        assertEquals(listOf(PendingWrite("a", "d1", false)), again.queue)
    }

    @Test fun `same Emby item ID on another server cannot claim a favourite`() {
        val first = FavoriteLogic.pull(FavoritesState(), "a", listOf(dune.copy(providerIds = emptyMap())), 100, ::policy)
        val next = FavoriteLogic.pull(first, "b", listOf(dune), 200, ::policy)
        assertEquals(2, next.favorites.count { it.active })
        assertTrue(next.favorites.all { it.instances.size == 1 })
        assertTrue(next.queue.isEmpty())
    }

    @Test fun `a TMDB key already owned by another work cannot displace the known instance`() {
        val first = FavoriteLogic.pull(FavoritesState(), "a", listOf(dune.copy(providerIds = emptyMap())), 100, ::policy)
        val both = FavoriteLogic.pull(first, "b", listOf(duneB), 150, ::policy)
        val next = FavoriteLogic.pull(both, "a", listOf(dune), 200, ::policy)
        assertEquals(both.favorites, next.favorites)
        assertTrue(next.queue.isEmpty())
    }

    @Test fun `a legacy tombstone sharing the item does not revive on a later pull`() {
        val first = FavoriteLogic.pull(FavoritesState(), "a", listOf(dune), 100, ::policy)
        val active = first.favorites.single()
        val stale = active.copy(workKey = "item:a:d1", removedAt = 100)
        for (rows in listOf(listOf(stale, active), listOf(active, stale))) {
            val persisted = first.copy(favorites = rows)
            val next = FavoriteLogic.pull(persisted, "a", listOf(dune), 200, ::policy)
            assertEquals(rows, next.favorites)
            assertTrue(next.queue.isEmpty())
            val off = FavoriteLogic.toggle(next, ServerItem("a", dune), emptyList(), 250, ::policy)
            assertTrue(off.favorites.none { it.active })
            val pulled = FavoriteLogic.pull(off, "a", listOf(dune), 300, ::policy)
            assertTrue(pulled.favorites.none { it.active })
            assertEquals(listOf(PendingWrite("a", "d1", false)), pulled.queue)
        }
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
