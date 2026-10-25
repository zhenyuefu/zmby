package app.zmby.search

import app.zmby.emby.BaseItemDto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinyinMatchTest {
    private fun matches(title: String, input: String) = PinyinTitle(title).matchStart(input) >= 0

    @Test
    fun initialsFullAndMixedPinyinUseOneRule() {
        for (q in listOf("xjcy", "xingjichuanyue", "xingjcy", "xjchuany", "xingji")) assertTrue(q, matches("星际穿越", q))
        assertFalse(matches("星际穿越", "xjcz"))
        assertFalse(matches("星际穿越", "xjcyy"))
    }

    @Test
    fun polyphonesMatchEveryReading() {
        assertTrue(matches("重庆森林", "cqsl"))
        assertTrue(matches("重庆森林", "chongqing"))
        assertTrue(matches("长安", "ca"))
        assertTrue(matches("长安", "changan"))
        assertTrue(matches("长津湖", "cjh"))
        assertTrue(matches("长津湖", "zjh")) // the other reading is still accepted
    }

    @Test
    fun punctuationBracketsAndSpacesAreIgnored() {
        assertTrue(matches("哈利·波特与魔法石", "hlbt"))
        assertTrue(matches("沙丘：第二部", "sqdeb"))
        assertTrue(matches("[某字幕组] 漫长的季节 (2023)", "mcdjj"))
        assertEquals("hlbtymfs", Pinyin.initials("哈利·波特与魔法石"))
    }

    @Test
    fun digitsAndLatinWordsStayWhole() {
        assertTrue(matches("2012", "2012"))
        assertTrue(matches("X战警", "xzj"))
        assertTrue(matches("X战警", "xzhanjing"))
        assertTrue(matches("银翼杀手 2049", "yyss2049"))
        assertEquals("yyss2049", Pinyin.initials("银翼杀手 2049"))
    }

    @Test
    fun matchFromTheMiddleReportsWhereItStarts() {
        assertEquals(0, PinyinTitle("星际穿越").matchStart("xjcy"))
        assertEquals(2, PinyinTitle("星际穿越").matchStart("cy"))
    }

    @Test
    fun polyphoneCombinationsAreCapped() {
        // 8 polyphones would be 2^8 = 256 combinations; ordinary ones collapse first.
        val units = Pinyin.units("长长长长长长长长")
        val combos = units.fold(1) { acc, u -> acc * u.size }
        assertTrue("$combos", combos <= Pinyin.MAX_COMBINATIONS)
        val mixed = Pinyin.units("长的地得了着和长")
        assertTrue(mixed.first().size == 2 || mixed.last().size == 2)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PinyinCandidatesTest {
    private class FakeServer(override val key: String, private val titles: List<String>) : CandidateServer {
        val calls = mutableListOf<String>()
        override suspend fun search(term: String, limit: Int): List<BaseItemDto> {
            calls += term
            return titles.filter { PinyinTitle(it).matchStart(term) >= 0 }.take(limit)
                .map { BaseItemDto(id = "$key:$it", name = it, type = "Movie", productionYear = 2014) }
        }
    }

    @Test
    fun onlyTheFirstThreeServersAreAskedAfterTheDebounce() = runTest {
        val servers = (1..5).map { FakeServer("s$it", listOf("星际穿越")) }
        val c = PinyinCandidates(this, { servers }, { emptyList() })
        c.onInput("xjcy")
        advanceTimeBy(399)
        runCurrent()
        assertTrue(servers.all { it.calls.isEmpty() })
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(1, 1, 1, 0, 0), servers.map { it.calls.size })
        assertEquals(listOf("星际穿越"), c.candidates.value.map { it.title })
        assertEquals("s1", c.candidates.value.first().serverKey)
    }

    @Test
    fun typingCancelsTheRequestThatHasNotGoneOutAndCachesAnswers() = runTest {
        val s = FakeServer("s", listOf("星际穿越", "星际特工"))
        val c = PinyinCandidates(this, { listOf(s) }, { emptyList() })
        c.onInput("x")
        advanceTimeBy(200)
        c.onInput("xj")
        advanceTimeBy(200)
        c.onInput("xjc")
        advanceTimeBy(500)
        runCurrent()
        assertEquals(listOf("xjc"), s.calls)
        c.onInput("xjcy")
        advanceTimeBy(500)
        c.onInput("xjc") // cached
        advanceTimeBy(500)
        runCurrent()
        assertEquals(listOf("xjc", "xjcy"), s.calls)
        assertEquals(listOf("星际穿越"), c.candidates.value.map { it.title })
    }

    @Test
    fun localTitlesNeedNoServerAndHanInputGivesNothing() = runTest {
        val c = PinyinCandidates(this, { emptyList() }, { listOf(LocalTitle("漫长的季节", 2023), LocalTitle("Dune", 2021)) })
        c.onInput("mcd")
        assertEquals(listOf("漫长的季节"), c.candidates.value.map { it.title })
        c.onInput("漫长")
        assertTrue(c.candidates.value.isEmpty())
    }

    @Test
    fun rankingPrefersStartMatchesExactInitialsAndShortTitles() {
        val ranked = PinyinCandidates.rank(
            "xjcy",
            listOf(
                Candidate("流浪地球之星际穿越", null),
                Candidate("星际穿越之后", null),
                Candidate("星际穿越", 2014, serverOrder = 1),
                Candidate("星际穿越", 2014, serverOrder = 0),
            ),
        )
        assertEquals(listOf("星际穿越", "星际穿越之后", "流浪地球之星际穿越"), ranked.map { it.title })
    }

    @Test
    fun probeSearchesTheInitialsOfAChineseTitle() = runTest {
        val latest = listOf(
            BaseItemDto(id = "a", name = "Dune", type = "Movie"),
            BaseItemDto(id = "b", name = "沙丘：第二部", type = "Movie"),
        )
        var asked: String? = null
        val yes = PinyinProbe.probe(latest) { term -> asked = term; listOf(latest[1]) }
        assertEquals("sqdeb", asked)
        assertEquals(true, yes)
        assertEquals(false, PinyinProbe.probe(latest) { emptyList() })
        assertNull(PinyinProbe.probe(listOf(latest[0])) { emptyList() })
    }
}

class CandidateDedupeTest {
    @org.junit.Test fun `a history title folds into its dated twin, different years stay`() {
        val out = PinyinCandidates.rank("sq", listOf(Candidate("沙丘：第二部", null), Candidate("沙丘：第二部", 2024), Candidate("沙丘", 1984), Candidate("沙丘", 2021)))
        org.junit.Assert.assertEquals(listOf("沙丘" to 1984, "沙丘" to 2021, "沙丘：第二部" to 2024).sortedBy { it.second }, out.map { it.title to it.year }.sortedBy { it.second })
    }
}
