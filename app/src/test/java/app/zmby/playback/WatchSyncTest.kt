package app.zmby.playback

import app.zmby.data.ServerItem
import app.zmby.emby.BaseItemDto
import app.zmby.emby.EmbyException
import app.zmby.emby.MediaSourceInfo
import app.zmby.emby.UserItemData
import app.zmby.emby.msToTicks
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class WatchSyncTest {
    private val minute = 60_000L
    private val startedAt = java.time.Instant.parse("2026-11-03T20:00:00Z").toEpochMilli()

    private fun film(id: String, runtimeMs: Long? = 120 * minute, ids: Map<String, String> = mapOf("Tmdb" to "693134"),
        sources: List<Long> = emptyList(), data: UserItemData? = null, type: String = "Movie") =
        BaseItemDto(id, name = id, type = type, providerIds = ids, runTimeTicks = runtimeMs?.let(::msToTicks),
            mediaSources = sources.mapIndexed { i, ms -> MediaSourceInfo("$id-$i", runTimeTicks = msToTicks(ms)) }, userData = data)

    private fun episode(id: String, ids: Map<String, String> = emptyMap()) =
        BaseItemDto(id, name = id, type = "Episode", seriesId = "s-$id", parentIndexNumber = 1, indexNumber = 3, providerIds = ids, runTimeTicks = msToTicks(45 * minute))

    // ---- which copies

    @Test fun `copies on other servers in the source are written, the played one and catalogue entries never`() {
        val played = ServerItem("a", film("a1"))
        val copies = listOf(played, ServerItem("a", film("a2")), ServerItem("b", film("b1")), ServerItem("c", film("c1")),
            ServerItem("catalog", film("tmdb-693134")), ServerItem("b", film("b1")))
        val targets = WatchSyncRules.targets(played, copies) { it != "c" }
        assertEquals(listOf("b:b1"), targets.map { it.key })
    }

    @Test fun `a conflicting external ID or another type keeps a copy out`() {
        val played = ServerItem("a", film("a1", ids = mapOf("Tmdb" to "1", "Imdb" to "tt1")))
        val copies = listOf(
            ServerItem("b", film("b1", ids = mapOf("Tmdb" to "2"))),
            ServerItem("c", film("c1", ids = mapOf("tmdb" to "1", "IMDB" to "TT1"))),
            ServerItem("d", film("d1", ids = emptyMap())),
            ServerItem("e", film("e1", type = "Series")),
        )
        assertEquals(listOf("c", "d"), WatchSyncRules.targets(played, copies) { true }.map { it.server })
    }

    @Test fun `episodes with different own IDs are kept apart, missing-value IDs do not count`() {
        val played = ServerItem("a", episode("a1", mapOf("Tvdb" to "100")))
        val copies = listOf(ServerItem("b", episode("b1", mapOf("Tvdb" to "101"))), ServerItem("c", episode("c1", mapOf("Tvdb" to "0"))),
            ServerItem("d", episode("d1", mapOf("Tvdb" to "100"))))
        assertEquals(listOf("c", "d"), WatchSyncRules.targets(played, copies) { true }.map { it.server })
    }

    // ---- what to write

    @Test fun `watched writes played, part-way from 5 percent writes the position, less writes nothing`() {
        assertEquals(WatchWrite(0, true), WatchSyncRules.intent(PlaybackOutcome(null, watched = true), null))
        assertEquals(WatchWrite(msToTicks(30 * minute), null), WatchSyncRules.intent(PlaybackOutcome(30 * minute, false), 120 * minute))
        assertEquals(WatchWrite(msToTicks(6 * minute), null), WatchSyncRules.intent(PlaybackOutcome(6 * minute, false), 120 * minute))
        assertNull(WatchSyncRules.intent(PlaybackOutcome(5 * minute, false), 120 * minute))
        assertNull("no runtime, no position", WatchSyncRules.intent(PlaybackOutcome(30 * minute, false), null))
        assertNull("not started", WatchSyncRules.intent(PlaybackOutcome(null, false), 120 * minute))
    }

    @Test fun `a position goes only to a copy whose every runtime is within 15 seconds`() {
        val at = WatchWrite(msToTicks(30 * minute), null)
        val runtime = 120 * minute
        assertEquals(at, WatchSyncRules.writeFor(at, runtime, film("b", runtimeMs = runtime + 14_000), startedAt))
        assertNull("another cut", WatchSyncRules.writeFor(at, runtime, film("b", runtimeMs = runtime + 16_000), startedAt))
        assertNull("one source is the extended cut", WatchSyncRules.writeFor(at, runtime, film("b", sources = listOf(runtime, runtime + 20 * minute)), startedAt))
        assertEquals(at, WatchSyncRules.writeFor(at, runtime, film("b", runtimeMs = null, sources = listOf(runtime - 3_000, runtime + 2_000)), startedAt))
        assertNull("runtime unknown there", WatchSyncRules.writeFor(at, runtime, film("b", runtimeMs = null), startedAt))
        assertNull("runtime unknown here", WatchSyncRules.writeFor(at, null, film("b"), startedAt))
    }

    @Test fun `played goes to any cut, and is not sent again`() {
        val played = WatchWrite(0, true)
        assertEquals(played, WatchSyncRules.writeFor(played, 120 * minute, film("b", runtimeMs = 150 * minute), startedAt))
        assertNull(WatchSyncRules.writeFor(played, 120 * minute, film("b", data = UserItemData(played = true)), startedAt))
        assertEquals("a stale resume point is cleared", played,
            WatchSyncRules.writeFor(played, 120 * minute, film("b", data = UserItemData(played = true, playbackPositionTicks = 5)), startedAt))
    }

    @Test fun `a copy played since the playback began, or already at the position, is left alone`() {
        val at = WatchWrite(msToTicks(30 * minute), null)
        fun on(date: String?, position: Long = 0) = film("b", data = UserItemData(playbackPositionTicks = position, lastPlayedDate = date))
        assertNull(WatchSyncRules.writeFor(at, 120 * minute, on("2026-11-03T20:30:00Z"), startedAt))
        assertNull("within the clock tolerance", WatchSyncRules.writeFor(at, 120 * minute, on("2026-11-03T19:59:30Z"), startedAt))
        assertEquals(at, WatchSyncRules.writeFor(at, 120 * minute, on("2026-11-03T19:58:00Z"), startedAt))
        assertEquals(at, WatchSyncRules.writeFor(at, 120 * minute, on(null), startedAt))
        assertNull(WatchSyncRules.writeFor(at, 120 * minute, on(null, msToTicks(30 * minute - 9_000)), startedAt))
        assertNull(WatchSyncRules.writeFor(WatchWrite(0, true), 120 * minute, on("2026-11-03T21:00:00Z"), startedAt))
    }

    // ---- the run

    private class Fake(val remote: MutableMap<String, BaseItemDto>, val failing: Map<String, Exception> = emptyMap()) {
        val sent = mutableListOf<Pair<String, WatchWrite>>()
        val recorded = mutableListOf<String>()
        val refused = mutableListOf<Pair<String, Int>>()
        fun sync(allowed: (String) -> Boolean = { true }) = WatchSync(
            allowed = allowed,
            fetch = { s, id -> remote["$s:$id"] ?: throw EmbyException("HTTP 404", 404) },
            send = { s, id, w -> failing[s]?.let { throw it }; synchronized(sent) { sent += "$s:$id" to w } },
            recorded = { s, id, _ -> synchronized(recorded) { recorded += "$s:$id" } },
            refused = { s, code -> synchronized(refused) { refused += s to code } },
        )
    }

    @Test fun `a stop writes each matched copy once and records it locally`() = runBlocking {
        val fake = Fake(mutableMapOf("b:b1" to film("b1"), "c:c1" to film("c1", runtimeMs = 150 * minute)))
        val played = ServerItem("a", film("a1"))
        val result = fake.sync().afterPlayback(played, 120 * minute, PlaybackOutcome(40 * minute, false),
            listOf(played, ServerItem("b", film("b1")), ServerItem("c", film("c1"))), startedAt)
        assertEquals(mapOf("b:b1" to SyncResult.Written, "c:c1" to SyncResult.Skipped), result)
        assertEquals(listOf("b:b1" to WatchWrite(msToTicks(40 * minute), null)), fake.sent)
        assertEquals(listOf("b:b1"), fake.recorded)
    }

    @Test fun `nothing is asked when no server is in the source or nothing is resumable`() = runBlocking {
        val fake = Fake(mutableMapOf())
        val played = ServerItem("a", film("a1"))
        val copies = listOf(played, ServerItem("b", film("b1")))
        assertTrue(fake.sync { false }.afterPlayback(played, 120 * minute, PlaybackOutcome(40 * minute, false), copies, startedAt).isEmpty())
        assertTrue(fake.sync().afterPlayback(played, 120 * minute, PlaybackOutcome(2 * minute, false), copies, startedAt).isEmpty())
        assertTrue(fake.sent.isEmpty())
    }

    @Test fun `a refusal turns the server off, other errors are only reported, none is retried`() = runBlocking {
        val fake = Fake(mutableMapOf("b:b1" to film("b1"), "c:c1" to film("c1"), "d:d1" to film("d1")),
            failing = mapOf("b" to EmbyException("HTTP 403", 403), "c" to IOException("timeout")))
        val played = ServerItem("a", film("a1"))
        val result = fake.sync().afterPlayback(played, 120 * minute, PlaybackOutcome(null, true),
            listOf(played, ServerItem("b", film("b1")), ServerItem("c", film("c1")), ServerItem("d", film("d1")), ServerItem("e", film("e1"))), startedAt)
        assertEquals(mapOf("b:b1" to SyncResult.Refused, "c:c1" to SyncResult.Failed, "d:d1" to SyncResult.Written, "e:e1" to SyncResult.Failed), result)
        assertEquals(listOf("b" to 403), fake.refused)
        assertEquals(listOf("d:d1" to WatchWrite(0, true)), fake.sent)
        assertFalse(fake.recorded.contains("b:b1"))
    }
}
