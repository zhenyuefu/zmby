package app.zmby.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CastRulesTest {
    private fun e(key: String, name: String, kind: String, ord: Int, role: String? = null) = CastEntry(kind, role, ord, Person(key, name))

    @Test fun `actors first, then crew, one unit for director and writer`() {
        val units = CastRules.units(listOf(
            e("dv", "丹尼斯·维伦纽瓦", "Writer", 4),
            e("tc", "提莫西·查拉梅", "Actor", 0, "保罗"),
            e("dv", "丹尼斯·维伦纽瓦", "Director", 3),
            e("zd", "赞达亚", "Actor", 1, "契妮"),
            e("w2", "另一位编剧", "Writer", 5),
        ))
        assertEquals(listOf("tc", "zd", "dv", "w2"), units.map { it.personKey })
        assertEquals("保罗", units[0].role)
        assertNull(units[0].badge)
        assertEquals("导演 · 编剧", units[2].badge)
        assertEquals("编剧", units[3].badge)
    }

    @Test fun `at most twenty actors`() {
        val units = CastRules.units((0 until 30).map { e("a$it", "演员$it", "Actor", it) })
        assertEquals(20, units.size)
        assertEquals("a19", units.last().personKey)
    }

    @Test fun `person meta is birth year, the most frequent role and the birthplace`() {
        assertEquals("1995 年生 · 演员 · 美国纽约", CastRules.personMeta("1995-12-27T00:00:00Z", "美国纽约", listOf("Actor", "Actor", "Director")))
        assertEquals("导演", CastRules.personMeta(null, null, listOf("Director")))
        assertEquals("", CastRules.personMeta(null, " ", emptyList()))
    }

    @Test fun `the fallback letter is the first character`() {
        assertEquals("提", CastRules.initial(" 提莫西"))
        assertEquals("Z", CastRules.initial("Zendaya"))
    }
}
