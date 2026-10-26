package app.zmby.segments

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.zmby.data.ProviderState
import app.zmby.data.SegmentProvider
import app.zmby.emby.BaseItemDto
import app.zmby.emby.ChapterInfo
import app.zmby.emby.msToTicks
import app.zmby.store.ZmbyDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SegmentRepositoryTest {
    private lateinit var db: ZmbyDatabase
    private var now = 1_000_000L

    private class FakeClient(override val provider: SegmentProvider, var answer: ProviderAnswer) : SegmentClient {
        var calls = 0
        override suspend fun fetch(query: SegmentQuery, key: String?): ProviderAnswer { calls++; return answer }
    }

    private val tidb = FakeClient(SegmentProvider.TheIntroDB, ProviderAnswer.Found(ProviderSegments(listOf(Segment(SegmentKind.Intro, 60_000, 150_000), Segment(SegmentKind.Credits, 2_500_000, null)))))
    private val idb = FakeClient(SegmentProvider.IntroDB, ProviderAnswer.Empty)
    private var order = listOf(SegmentProvider.Local, SegmentProvider.Emby, SegmentProvider.TheIntroDB, SegmentProvider.IntroDB)

    private val repo by lazy {
        SegmentRepository(db.sources(), listOf(tidb, idb), { order.mapIndexed { i, p -> ProviderState(p, true, i) } }, { null }) { now }
    }
    private val series = BaseItemDto(id = "s", type = "Series", providerIds = mapOf("Tmdb" to "1"))
    private val ep = BaseItemDto(id = "e", type = "Episode", seriesId = "s", parentIndexNumber = 1, indexNumber = 2)
    private val key = "series:tmdb:1:s01:e02"

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ZmbyDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun close() = db.close()

    @Test fun `providers answer once and are cached, nothing-there for a day`() = runBlocking {
        val plan = repo.plan(ep, series, key, 2_700_000) { emptyList() }
        assertEquals(SegmentProvider.TheIntroDB, plan[SegmentKind.Intro]?.first)
        repo.plan(ep, series, key, 2_700_000) { emptyList() }
        assertEquals(1, tidb.calls); assertEquals(1, idb.calls)
        now += SegmentRules.EMPTY_TTL_MS
        repo.plan(ep, series, key, 2_700_000) { emptyList() }
        assertEquals(1, tidb.calls); assertEquals(2, idb.calls)
    }

    @Test fun `local marks for the season apply, the episode's own win, and come first`() = runBlocking {
        repo.saveManual(key, ManualMarks(introEndMs = 80_000, season = true))
        assertEquals(ManualMarks(introEndMs = 80_000, season = true), repo.manual(key))
        assertEquals(ManualMarks(introEndMs = 80_000, season = true), repo.manual("series:tmdb:1:s01:e03"))
        var plan = repo.plan(ep, series, key, 2_700_000) { emptyList() }
        assertEquals(SegmentProvider.Local to Segment(SegmentKind.Intro, 0, 80_000), plan[SegmentKind.Intro])
        assertEquals(SegmentProvider.TheIntroDB, plan[SegmentKind.Credits]?.first)
        repo.saveManual(key, ManualMarks(introEndMs = 70_000, creditsStartMs = 2_400_000))
        assertEquals(ManualMarks(70_000, 2_400_000, season = false), repo.manual(key))
        plan = repo.plan(ep, series, key, 2_700_000) { emptyList() }
        assertEquals(Segment(SegmentKind.Credits, 2_400_000, null), plan[SegmentKind.Credits]?.second)
    }

    @Test fun `Emby markers, borrowed within tolerance, and unkeyed items never go out`() = runBlocking {
        order = listOf(SegmentProvider.Emby, SegmentProvider.TheIntroDB)
        val other = BaseItemDto(id = "o", runTimeTicks = msToTicks(2_701_000), chapters = listOf(ChapterInfo(null, msToTicks(95_000), "IntroEnd")))
        val plan = repo.plan(ep, series, key, 2_700_000) { listOf(other) }
        assertEquals(SegmentProvider.Emby to Segment(SegmentKind.Intro, 0, 95_000), plan[SegmentKind.Intro])
        val unkeyed = repo.plan(ep, null, "item:a:e", 2_700_000) { emptyList() }
        assertTrue(unkeyed.isEmpty())
        assertEquals(1, tidb.calls)
    }

    @Test fun `a failing provider is paused and its old answer still used`() = runBlocking {
        repo.plan(ep, series, key, 2_700_000) { emptyList() }
        now += SegmentRules.FOUND_TTL_MS
        tidb.answer = ProviderAnswer.Failed("rate limited", 60_000)
        assertEquals(SegmentProvider.TheIntroDB, repo.plan(ep, series, key, 2_700_000) { emptyList() }[SegmentKind.Intro]?.first)
        repo.plan(ep, series, key, 2_700_000) { emptyList() }
        assertEquals(2, tidb.calls)
    }

    @Test fun `the last stop is kept for the marks editor`() = runBlocking {
        repo.recordStop(key, 1_234_000)
        assertEquals(1_234_000L, repo.lastStop(key))
        assertTrue(repo.plan(ep, series, key, 2_700_000) { emptyList() }.values.none { it.first == SegmentProvider.Local })
    }
}
