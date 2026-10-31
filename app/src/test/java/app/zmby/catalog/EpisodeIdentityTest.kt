package app.zmby.catalog

import app.zmby.data.ServerItem
import app.zmby.emby.BaseItemDto
import app.zmby.store.EpisodeMappingRule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EpisodeIdentityTest {
    @Test fun `no external episode result falls back to a verified numbered slot but errors and conflicts do not`() {
        val ref = ref(season = 3, ids = mapOf("Tvdb" to "2"))
        assertEquals(listOf(ref), map(listOf(ref), mapOf(ref.key to EpisodeIdentity(EpisodeIdentityState.NotFound))).matched["301"])
        for (state in listOf(EpisodeIdentityState.Unavailable, EpisodeIdentityState.Conflict)) {
            assertTrue(map(listOf(ref), mapOf(ref.key to EpisodeIdentity(state))).matched.isEmpty())
        }
        assertTrue(map(listOf(ref(season = 4)), mapOf(ref.key to EpisodeIdentity(EpisodeIdentityState.NotFound))).matched.isEmpty())
    }

    @Test fun `copies of the same external identity across servers share one lookup and cached answers`() = runBlocking {
        val copies = (1..6).map { ref(server = "s$it", ids = mapOf("Imdb" to "tt1")) }
        var calls = 0
        val answer = EpisodeIdentity(EpisodeIdentityState.Matched, "301")
        val result = episodeIdentities(work, copies) { calls++; answer }.last()
        assertEquals(1, calls)
        assertEquals(copies.associate { it.key to answer }, result)
        assertEquals(result, episodeIdentities(work, copies, mapOf(copies.first().key to answer)) { error("must reuse identity") }.last())
    }

    @Test fun `a slow first identity does not hold up later results or the next queued identity`() = runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val refs = (1..5).map { ref(id = "$it", ids = mapOf("Imdb" to "tt$it")) }
        val states = episodeIdentities(work, refs) { item ->
            if (item.id == "1") gate.await()
            EpisodeIdentity(EpisodeIdentityState.NotFound)
        }.onEach { state ->
            if (state[refs.last().key]?.state == EpisodeIdentityState.NotFound) gate.complete(Unit)
        }.toList()
        assertTrue(states.any { it[refs.first().key]?.state == EpisodeIdentityState.Pending && it[refs.last().key]?.state == EpisodeIdentityState.NotFound })
    }
    private val work = CatalogWork(CatalogId("tmdb", "tv", "10"), "Test", ids = mapOf("Tmdb" to "10"))
    private val catalog = listOf(CatalogEpisode("301", 3, 1, "One"), CatalogEpisode("302", 3, 2, "Two"))
    private fun ref(server: String = "a", id: String = "file", season: Int = 4, number: Int = 1, ids: Map<String, String> = emptyMap()) =
        ServerItem(server, BaseItemDto(id, type = "Episode", seriesId = "parent", parentIndexNumber = season, indexNumber = number, providerIds = ids))
    private fun rule(first: Int = 1, last: Int = 9, offset: Int = 0) = EpisodeMappingRule("rule", work.ref.key, "a", "parent", 4, first, last, 3, offset)
    private fun map(refs: List<ServerItem>, identities: Map<String, EpisodeIdentity> = emptyMap(), rules: List<EpisodeMappingRule> = emptyList()) =
        ResourceMapping.episodes(work, catalog, listOf(ResourceBatch("a", refs, true)), identities = identities, rules = rules)

    @Test fun `IMDb episode identity takes precedence over an incorrect season and keeps the resource address`() = runBlocking {
        val ref = ref(ids = mapOf("Imdb" to "tt1"))
        val identity = resolveEpisodeIdentity(work, ref.item) { provider, id ->
            assertEquals("imdb", provider); assertEquals("tt1", id)
            listOf(EpisodeIdentityHit("10", "301"))
        }
        val mapped = map(listOf(ref), mapOf(ref.key to identity))
        assertEquals(listOf(ref), mapped.matched["301"])
        assertEquals(4, mapped.matched["301"]!!.single().item.parentIndexNumber)
    }

    @Test fun `different shows ambiguous episodes and conflicting providers cannot be matched by number`() = runBlocking {
        val ref = ref(season = 3, ids = mapOf("Imdb" to "tt1", "Tvdb" to "2"))
        for (foreign in listOf(true, false)) {
            val identity = resolveEpisodeIdentity(work, ref.item) { provider, _ ->
                if (foreign) listOf(EpisodeIdentityHit("99", "301"))
                else listOf(EpisodeIdentityHit("10", if (provider == "imdb") "301" else "302"))
            }
            assertEquals(EpisodeIdentityState.Conflict, identity.state)
            assertEquals("单集外部 ID 冲突", map(listOf(ref), mapOf(ref.key to identity)).reasons[ref.key])
        }
    }

    @Test fun `lookup failures are retryable and cancellation is propagated`() = runBlocking {
        val ref = ref(ids = mapOf("Tvdb" to "2"))
        assertEquals(EpisodeIdentityState.Unavailable, resolveEpisodeIdentity(work, ref.item) { _, _ -> error("offline") }.state)
        assertEquals(EpisodeIdentityState.NotFound, resolveEpisodeIdentity(work, ref.item) { _, _ -> emptyList() }.state)
        try { resolveEpisodeIdentity(work, ref.item) { _, _ -> throw CancellationException() }; fail("Expected cancellation") }
        catch (_: CancellationException) { }
    }

    @Test fun `pending lookups never temporarily borrow the original numbered slot and completed answers are reused`() = runBlocking {
        val ref = ref(season = 3, ids = mapOf("Imdb" to "tt1"))
        assertTrue(map(listOf(ref)).matched.isEmpty())
        assertEquals(listOf(ref), map(listOf(ref)).awaitingIdentity)
        val states = episodeIdentities(work, listOf(ref)) { EpisodeIdentity(EpisodeIdentityState.Matched, "302") }.toList()
        assertEquals(EpisodeIdentityState.Pending, states.first()[ref.key]?.state)
        val cached = episodeIdentities(work, listOf(ref), states.last()) { error("must not refetch") }.last()
        assertEquals(listOf(ref), map(listOf(ref), cached).matched["302"])
        val retried = episodeIdentities(work, listOf(ref), mapOf(ref.key to EpisodeIdentity(EpisodeIdentityState.Unavailable))) {
            EpisodeIdentity(EpisodeIdentityState.Matched, "301")
        }.last()
        assertEquals(EpisodeIdentityState.Matched, retried[ref.key]?.state)
    }

    @Test fun `missing-value IDs and copied show IDs are not treated as episode identities`() {
        val ref = ref(season = 3, ids = mapOf("Tmdb" to "None", "Imdb" to "null", "Tvdb" to "0"))
        assertEquals(listOf(ref), map(listOf(ref)).matched["301"])
        assertTrue(secondaryEpisodeIds(work.copy(ids = mapOf("Imdb" to "tt1", "Tvdb" to "2")), ref(ids = mapOf("Imdb" to "tt1", "Tvdb" to "2")).item).isEmpty())
    }

    @Test fun `season rules are scoped to work server parent and episode range and allow split-season offsets`() {
        val refs = listOf(ref(number = 1), ref(id = "second", number = 6), ref(server = "b"), ref(id = "other").copy(item = ref().item.copy(id = "other", seriesId = "other-parent")))
        val result = map(refs, rules = listOf(rule(first = 1, last = 2), rule(first = 6, last = 7, offset = -5)))
        assertEquals(setOf("file", "second"), result.matched["301"]!!.map { it.item.id }.toSet())
        assertEquals(2, result.unmatched.size)
        assertEquals(4, result.matched["301"]!!.first().item.parentIndexNumber)
        assertTrue(map(refs, rules = listOf(rule().copy(workKey = "tmdb:tv:99"))).matched.isEmpty())
    }

    @Test fun `explicit episode identities beat season rules and one-file overrides beat either`() {
        val ref = ref(ids = mapOf("Tmdb" to "302"))
        assertEquals(listOf(ref), map(listOf(ref), rules = listOf(rule())).matched["302"])
        val override = rule().copy(id = "file-override", itemId = ref.item.id, targetEpisodeId = "301")
        assertEquals(listOf(ref), map(listOf(ref), rules = listOf(rule(), override)).matched["301"])
        assertEquals(listOf(ref), map(listOf(ref), rules = listOf(rule())).matched["302"])
    }

    @Test fun `out-of-range targets and overlapping season rules remain unmatched`() {
        val ref = ref(number = 9)
        assertEquals("本机映射目标不在目录", map(listOf(ref), rules = listOf(rule())).reasons[ref.key])
        val first = ref()
        assertEquals("本机季映射范围重叠", map(listOf(first), rules = listOf(rule(), rule().copy(id = "overlap"))).reasons[first.key])
    }
}
