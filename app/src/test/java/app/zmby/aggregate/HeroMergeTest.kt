package app.zmby.aggregate

import app.zmby.data.ServerItem
import app.zmby.emby.BaseItemDto
import org.junit.Assert.assertEquals
import org.junit.Test

class HeroMergeTest {
    private fun m(id: String, tmdb: String? = null, type: String = "Movie") = BaseItemDto(id, name = id, type = type, providerIds = tmdb?.let { mapOf("Tmdb" to it) }.orEmpty())

    @Test fun `suggestions come first, then recommendations, then the latest additions fill up`() {
        val picks = HeroMerge.pick(
            servers = listOf("a"),
            suggestions = mapOf("a" to listOf(m("s1"), m("s2"))),
            recommendations = mapOf("a" to listOf(m("r1"))),
            latest = listOf(ServerItem("a", m("l1")), ServerItem("a", m("l2"))),
            limit = 4,
        )
        assertEquals(listOf("s1", "s2", "r1", "l1"), picks.map { it.ref.item.id })
        assertEquals(listOf(HeroTier.Suggested, HeroTier.Suggested, HeroTier.Recommended, HeroTier.Latest), picks.map { it.tier })
    }

    @Test fun `a server without the endpoints falls back to the latest additions`() {
        val picks = HeroMerge.pick(listOf("a"), emptyMap(), emptyMap(), listOf(ServerItem("a", m("l1"))))
        assertEquals(listOf(HeroTier.Latest), picks.map { it.tier })
    }

    @Test fun `servers take turns in priority order and the same work appears once`() {
        val picks = HeroMerge.pick(
            servers = listOf("a", "b"),
            suggestions = mapOf("a" to listOf(m("a1", "1"), m("a2", "2")), "b" to listOf(m("b1", "1"), m("b3", "3"))),
            recommendations = emptyMap(),
            latest = listOf(ServerItem("b", m("b3x", "3"))),
        )
        assertEquals(listOf("a:a1", "a:a2", "b:b3"), picks.map { "${it.ref.server}:${it.ref.item.id}" })
    }

    @Test fun `recommendation categories interleave and drop repeats`() {
        val flat = HeroMerge.flatten(listOf(listOf(m("x"), m("y")), listOf(m("z"), m("x"), m("w"))))
        assertEquals(listOf("x", "z", "y", "w"), flat.map { it.id })
    }

    @Test fun `folders and people are never hero titles`() {
        val picks = HeroMerge.pick(listOf("a"), mapOf("a" to listOf(m("f", type = "Folder"), m("p", type = "Person"))), emptyMap(), emptyList())
        assertEquals(0, picks.size)
    }
}
