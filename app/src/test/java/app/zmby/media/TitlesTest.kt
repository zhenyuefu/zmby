package app.zmby.media

import app.zmby.emby.BaseItemDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ui-spec §1.3: every item shows at least a text title. */
class TitlesTest {

    private fun item(name: String? = null, original: String? = null, series: String? = null, path: String? = null, type: String = "Movie") =
        BaseItemDto(id = "1", name = name, originalTitle = original, seriesName = series, path = path, type = type)

    @Test
    fun invisibleTextCountsAsMissing() {
        assertNull(visibleText(null))
        assertNull(visibleText(""))
        assertNull(visibleText("   \t\n"))
        assertNull(visibleText("\u200B\u200D\uFEFF"))
        assertNull(visibleText("\u3164\u3000\u00A0"))
        assertEquals("血谜拼图", visibleText("\uFEFF 血谜拼图\u200B "))
        assertEquals("A B", visibleText(" A B "))
    }

    @Test
    fun nameFirstThenOriginalSeriesAndFile() {
        assertEquals("血谜拼图", titleOf(item(name = "血谜拼图", original = "나인 퍼즐")))
        assertEquals("나인 퍼즐", titleOf(item(name = " \u200B", original = "나인 퍼즐", series = "S")))
        assertEquals("血谜拼图", titleOf(item(name = "", series = "血谜拼图", path = "/m/x.mkv", type = "Episode")))
        assertEquals("Nine.Puzzle.S01E01", titleOf(item(name = null, path = "/media/tv/Nine Puzzle/Nine.Puzzle.S01E01.mkv")))
        assertEquals("Nine Puzzle", titleOf(item(path = "/media/tv/Nine Puzzle/")))
        assertEquals("Movie", titleOf(item(path = "D:\\films\\Movie.strm")))
        assertEquals(UNTITLED, titleOf(item()))
    }

    @Test
    fun theWorkOfAnEpisodeIsItsSeries() {
        assertEquals("血谜拼图", workTitleOf(item(name = "第 5 集", series = "血谜拼图", type = "Episode")))
        assertEquals("第 5 集", workTitleOf(item(name = "第 5 集", series = "\u200B", type = "Episode")))
        assertEquals("血谜拼图", workTitleOf(item(name = "血谜拼图", type = "Series")))
    }

    @Test
    fun episodeDisplayTitleSkipsAnEmptyName() {
        val ep = BaseItemDto(id = "2", name = "", seriesName = "血谜拼图", type = "Episode", parentIndexNumber = 1, indexNumber = 5)
        assertEquals("血谜拼图 S01E05", ep.displayTitle)
        assertEquals("血谜拼图 S01E05 - 第 5 集", ep.copy(name = "第 5 集").displayTitle)
        assertEquals("血谜拼图", item(name = "\u200B", series = "血谜拼图", type = "Series").displayTitle)
    }

    @Test
    fun fileTitleKeepsDotsThatAreNotExtensions() {
        assertEquals("Mr. Robot", fileTitle("/tv/Mr. Robot"))
        assertEquals("Dune.Part.Two.2024.2160p", fileTitle("/m/Dune.Part.Two.2024.2160p.mkv"))
        assertNull(fileTitle(null))
        assertNull(fileTitle("/"))
    }

    @Test
    fun episodeNamesThatOnlyRepeatTheNumberAreDropped() {
        fun ep(name: String?, n: Int?) = BaseItemDto(id = "1", name = name, type = "Episode", indexNumber = n)
        // 血谜拼图 on a real server: every episode is named 「第 n 集」.
        assertNull(episodeName(ep("第 5 集", 5)))
        assertNull(episodeName(ep("第05话", 5)))
        assertNull(episodeName(ep("Episode 5", 5)))
        assertNull(episodeName(ep("E05", 5)))
        assertNull(episodeName(ep("5", 5)))
        assertNull(episodeName(ep("  ", 5)))
        assertEquals("在意外收到拼图后", episodeName(ep("在意外收到拼图后", 5)))
        // A different number is real information (a recap numbered apart, a misnumbered file).
        assertEquals("第 6 集", episodeName(ep("第 6 集", 5)))
        assertEquals("Episode 5", episodeName(ep("Episode 5", null)))
        assertEquals("Episode 5 Part 2", episodeName(ep("Episode 5 Part 2", 5)))
    }
}
