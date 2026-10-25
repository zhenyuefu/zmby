package app.zmby.search

import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType

/**
 * Local pinyin conversion and matching (plan §3.2「拼音转换与匹配规则」), used for candidates
 * from titles the app already has and for the server capability probe. Nothing here builds an
 * index of a server's library.
 */
object Pinyin {
    private val format = HanyuPinyinOutputFormat().apply {
        caseType = HanyuPinyinCaseType.LOWERCASE
        toneType = HanyuPinyinToneType.WITHOUT_TONE
        vCharType = HanyuPinyinVCharType.WITH_V
    }

    /** Readings kept in both forms even when a title has too many polyphones (common in titles). */
    private val TITLE_POLYPHONES: Map<Char, List<String>> = mapOf(
        '长' to listOf("chang", "zhang"),
        '重' to listOf("chong", "zhong"),
        '行' to listOf("xing", "hang"),
        '乐' to listOf("le", "yue"),
        '传' to listOf("chuan", "zhuan"),
        '藏' to listOf("cang", "zang"),
    )

    /** Most titles stay far below this; past it only the most common reading of each character is kept. */
    const val MAX_COMBINATIONS = 16

    fun isHan(c: Char): Boolean = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN

    fun hasHan(s: String): Boolean = s.any(::isHan)

    /** All readings of [c], most common first; empty for non-Han characters. */
    fun readings(c: Char): List<String> {
        TITLE_POLYPHONES[c]?.let { return it }
        val raw = runCatching { PinyinHelper.toHanyuPinyinStringArray(c, format) }.getOrNull() ?: return emptyList()
        return raw.map { it.replace("u:", "v") }.distinct()
    }

    private val BRACKETS = Regex("[(（\\[【][^)）\\]】]*[)）\\]】]")

    /** "哈利·波特 (2001)" → "哈利波特"; keeps Han, letters and digits, drops bracketed parts and punctuation. */
    fun normalize(title: String): String =
        BRACKETS.replace(title, " ").filter { it.isLetterOrDigit() || it.isWhitespace() }.trim()

    /**
     * The title as matchable units: each Han character with its readings, each Latin word, each
     * run of digits. Polyphones are capped at [MAX_COMBINATIONS] reading combinations.
     */
    fun units(title: String): List<List<String>> {
        val out = mutableListOf<MutableList<String>>()
        val hanChars = mutableListOf<Char?>()
        var word = StringBuilder()
        fun flush() {
            if (word.isNotEmpty()) {
                out += mutableListOf(word.toString().lowercase())
                hanChars += null
                word = StringBuilder()
            }
        }
        for (c in normalize(title)) {
            when {
                isHan(c) -> {
                    flush()
                    out += readings(c).ifEmpty { listOf(c.toString()) }.toMutableList()
                    hanChars += c
                }
                c.isWhitespace() -> flush()
                word.isNotEmpty() && (c.isDigit() != word.last().isDigit()) -> { flush(); word.append(c) }
                else -> word.append(c)
            }
        }
        flush()
        capCombinations(out, hanChars)
        return out
    }

    private fun combinations(units: List<List<String>>) = units.fold(1L) { acc, u -> (acc * u.size).coerceAtMost(1L shl 40) }

    private fun capCombinations(units: MutableList<MutableList<String>>, chars: List<Char?>) {
        if (combinations(units) <= MAX_COMBINATIONS) return
        // Collapse ordinary polyphones first, then the title ones, from the end of the title.
        for (keepTitleOnes in listOf(true, false)) {
            for (i in units.indices.reversed()) {
                if (combinations(units) <= MAX_COMBINATIONS) return
                if (units[i].size > 1 && (!keepTitleOnes || chars[i] !in TITLE_POLYPHONES)) {
                    units[i] = mutableListOf(units[i].first())
                }
            }
        }
    }

    /** Initials with the most common readings: 星际穿越 → xjcy, X战警 → xzj, 2012 → 2012. */
    fun initials(title: String): String =
        units(title).joinToString("") { u -> val r = u.first(); if (r.all(Char::isDigit)) r else r.take(1) }

    /** What the user typed, reduced to what can match: lowercase letters and digits. */
    fun cleanInput(input: String): String = input.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }
}

/** A title prepared for matching typed pinyin (initials, full pinyin or a mix). */
class PinyinTitle(val title: String) {
    val units: List<List<String>> = Pinyin.units(title)
    val initials: String by lazy { Pinyin.initials(title) }

    /**
     * The index of the first unit from which [input] matches, or -1. Every unit may be matched by
     * any non-empty prefix of any of its readings, consecutively from that unit on, so `xjcy`,
     * `xingjichuanyue` and `xingjcy` all match 星际穿越 from 0, and `jcy` from 1.
     */
    fun matchStart(input: String): Int {
        val q = Pinyin.cleanInput(input)
        if (q.isEmpty()) return -1
        for (start in units.indices) if (matchesFrom(start, q)) return start
        return -1
    }

    private fun matchesFrom(start: Int, q: String): Boolean {
        val failed = HashSet<Long>()
        fun go(u: Int, pos: Int): Boolean {
            if (pos == q.length) return true
            if (u == units.size) return false
            val key = u.toLong() shl 32 or pos.toLong()
            if (key in failed) return false
            for (r in units[u]) {
                var k = 0
                while (k < r.length && pos + k < q.length && q[pos + k] == r[k]) {
                    k++
                    if (go(u + 1, pos + k)) return true
                }
            }
            failed += key
            return false
        }
        return go(start, 0)
    }
}
