package app.zmby.playback

import app.zmby.zidoo.PlayerStatusSource
import app.zmby.zidoo.PlayerSubtitleControl
import app.zmby.zidoo.ZidooStatus
import app.zmby.zidoo.ZidooPlayerStrings
import app.zmby.zidoo.ZidooSubtitleList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleSelectionVerifierTest {
    private val ours = "/storage/emulated/0/Download/zmby-playback/s1/zmby_k.strm"

    private class FakePlayer : PlayerStatusSource, PlayerSubtitleControl {
        var path: String? = null
        var list: ZidooSubtitleList? = null
        val sets = mutableListOf<Int>()
        var setWorks = true
        override suspend fun status() = path?.let { ZidooStatus(true, 0, 1000, it, null) }
        override suspend fun seekTo(positionMs: Long) = true
        override suspend fun subtitleList() = list
        override suspend fun setSubtitle(index: Int): Boolean {
            sets += index
            if (setWorks) list = list?.copy(currentIndex = index)
            return setWorks
        }
    }

    private fun item(i: Int, external: Boolean, close: Boolean = false, title: String? = null) = ZidooSubtitleList.Item(i, title, null, "SRT", external, close)
    private fun TestScope.verifier(p: FakePlayer, strings: ZidooPlayerStrings = ZidooPlayerStrings()) =
        SubtitleSelectionVerifier(p, p, strings, now = { testScheduler.currentTime })

    @Test
    fun leavesAPidSelectionAlone() = runTest {
        val p = FakePlayer().apply { path = ours; list = ZidooSubtitleList(2, listOf(item(0, false, true), item(1, false), item(2, true))) }
        assertEquals(SubtitleSelectionVerifier.Result.AlreadySelected, verifier(p).verify("s1/zmby_k.strm"))
        assertEquals(emptyList<Int>(), p.sets)
    }

    @Test
    fun correctsOnceAfterTheGraceTime() = runTest {
        val p = FakePlayer().apply { path = ours; list = ZidooSubtitleList(1, listOf(item(0, false, true), item(1, false), item(2, true))) }
        assertEquals(SubtitleSelectionVerifier.Result.Corrected, verifier(p).verify("s1/zmby_k.strm"))
        assertEquals(listOf(2), p.sets)
    }

    @Test
    fun doesNotTouchAnotherFileOrGuessBetweenSeveral() = runTest {
        val other = FakePlayer().apply { path = "/storage/emulated/0/Movies/x.mkv"; list = ZidooSubtitleList(0, listOf(item(1, true))) }
        assertEquals(SubtitleSelectionVerifier.Result.NeverOpened, verifier(other).verify("s1/zmby_k.strm"))
        assertEquals(emptyList<Int>(), other.sets)
        val two = FakePlayer().apply { path = ours; list = ZidooSubtitleList(0, listOf(item(1, true), item(2, true))) }
        assertEquals(SubtitleSelectionVerifier.Result.Ambiguous, verifier(two).verify("s1/zmby_k.strm"))
        assertEquals(emptyList<Int>(), two.sets)
    }

    @Test
    fun internalMkvTextTracksAreNotMistakenForTheFile() = runTest {
        // As seen on the Z9X 8K (2026-10-24): embedded SRT tracks come back with isExternal=true.
        val p = FakePlayer().apply {
            path = ours
            list = ZidooSubtitleList(1, listOf(
                item(0, false, true, "关闭"),
                item(1, true, title = "中文(内嵌中文)  内置 SRT"),
                item(2, true, title = "英语(Embedded English)  内置 SRT"),
                item(3, true, title = "中文  外挂 SRT"),
            ))
        }
        assertEquals(SubtitleSelectionVerifier.Result.Corrected, verifier(p, ZidooPlayerStrings("内置", "外挂")).verify("s1/zmby_k.strm"))
        assertEquals(listOf(3), p.sets)
        assertEquals(false, ZidooPlayerStrings("内置", "外挂").isScannedFile("外挂字幕(内置)  内置 SRT"))
    }

    @Test
    fun reportsWhenTheSubtitleNeverShowsUp() = runTest {
        val p = FakePlayer().apply { path = ours; list = ZidooSubtitleList(0, listOf(item(0, false, true))) }
        assertEquals(SubtitleSelectionVerifier.Result.NotListed, verifier(p).verify("s1/zmby_k.strm"))
    }
}
