package app.zmby.danmu

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The overlay uses Android's fallback chain. Give ASS explicit symbol fonts from that same device. */
object DanmuAssFonts {
    private data class Font(val file: File, val family: String, val bytes: ByteArray)

    fun build(items: List<DanmuAss.Item>, config: DanmuAss.Config, fontDirectory: File = File("/system/fonts")): String {
        val symbols = LinkedHashSet<Int>()
        for (item in items) for (cp in item.content.codePoints().toArray()) if (isSymbol(cp)) symbols += cp
        if (symbols.isEmpty()) return DanmuAss.build(items, config)
        val fonts = candidates.mapNotNull { name -> runCatching {
            val file = File(fontDirectory, name)
            if (!file.isFile || file.length() !in 1..MAX_FONT_BYTES) return@runCatching null
            val bytes = file.readBytes()
            val family = family(bytes) ?: return@runCatching null
            Font(file, family, bytes)
        }.getOrNull() }
        val chosen = symbols.mapNotNull { cp ->
            fonts.firstOrNull { supports(it.bytes, cp) }?.let { cp to it }
        }.toMap()
        if (chosen.isEmpty()) return DanmuAss.build(items, config)
        val families = chosen.mapValues { it.value.family }
        val rendered = HashMap<String, String>()
        val ass = DanmuAss.build(items, config) { text -> rendered.getOrPut(text) {
            render(text, config.font, families)
        } }
        return ass + chosen.values.distinctBy { it.file.name }.joinToString("", prefix = "\n[Fonts]\n") {
            "fontname: ${it.file.name}\n${encode(it.bytes)}\n"
        }
    }

    /** Font switches affect glyph selection only; the converter's timing and lanes stay unchanged. */
    internal fun render(text: String, base: String, families: Map<Int, String>): String = buildString {
        var active = base
        for (cp in text.trim().codePoints().toArray()) {
            val family = if (cp == 0xFE0E || cp == 0xFE0F || cp == 0x200D) active else families[cp] ?: base
            if (family != active) { append("{\\fn$family}"); active = family }
            if (cp == 10) append("\\N") else appendCodePoint(cp)
        }
        if (active != base) append("{\\fn$base}")
    }

    /** SSA/ASS uses six-bit values plus 33, without Base64 padding or uuencode length prefixes. */
    internal fun encode(bytes: ByteArray): String = buildString {
        var column = 0
        fun emit(v: Int) {
            if (column == 80) { append('\n'); column = 0 }
            append((v + 33).toChar()); column++
        }
        var i = 0
        while (i < bytes.size) {
            val a = bytes[i++].toInt() and 255
            val b = if (i < bytes.size) bytes[i++].toInt() and 255 else -1
            val c = if (i < bytes.size) bytes[i++].toInt() and 255 else -1
            emit(a shr 2)
            emit(((a and 3) shl 4) or (if (b >= 0) b shr 4 else 0))
            if (b >= 0) emit(((b and 15) shl 2) or (if (c >= 0) c shr 6 else 0))
            if (c >= 0) emit(c and 63)
        }
    }

    /** Read the font's actual family; filenames are not font names in ASS. Only standalone SFNT. */
    internal fun family(bytes: ByteArray): String? = runCatching {
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        fun u16(at: Int) = data.getShort(at).toInt() and 65535
        if (bytes.size < 12) return@runCatching null
        val count = u16(4)
        val entry = (0 until count).firstOrNull { data.getInt(12 + it * 16) == 0x6E616D65 } ?: return@runCatching null
        val start = data.getInt(12 + entry * 16 + 8)
        val strings = start + u16(start + 4)
        val names = (0 until u16(start + 2)).mapNotNull { index ->
            val at = start + 6 + index * 12
            val platform = u16(at)
            val id = u16(at + 6)
            if (id !in listOf(1, 16) || platform !in listOf(0, 3)) return@mapNotNull null
            val offset = strings + u16(at + 10)
            val length = u16(at + 8)
            val name = String(bytes, offset, length, Charsets.UTF_16BE)
            if (name.isBlank() || name.any { it.isISOControl() || it in ",{}\\" }) null else id to name
        }
        names.firstOrNull { it.first == 16 }?.second ?: names.firstOrNull()?.second
    }.getOrNull()

    /** Inspect the font itself: Android Paint.hasGlyph would also count unrelated fallback fonts. */
    internal fun supports(bytes: ByteArray, cp: Int): Boolean = runCatching {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        fun u16(at: Int) = b.getShort(at).toInt() and 65535
        val entry = (0 until u16(4)).firstOrNull { b.getInt(12 + it * 16) == 0x636D6170 } ?: return@runCatching false
        val cmap = b.getInt(12 + entry * 16 + 8)
        for (table in 0 until u16(cmap + 2)) {
            val record = cmap + 4 + table * 8
            val platform = u16(record)
            if (platform != 0 && !(platform == 3 && u16(record + 2) in listOf(1, 10))) continue
            val start = cmap + b.getInt(record + 4)
            when (u16(start)) {
                12 -> {
                    var lo = 0
                    var hi = b.getInt(start + 12)
                    while (lo < hi) {
                        val mid = (lo + hi) ushr 1
                        val group = start + 16 + mid * 12
                        val first = b.getInt(group)
                        val last = b.getInt(group + 4)
                        when {
                            cp < first -> hi = mid
                            cp > last -> lo = mid + 1
                            else -> if (b.getInt(group + 8) + cp - first != 0) return@runCatching true else break
                        }
                    }
                }
                4 -> if (cp <= 0xFFFF) {
                    val count = u16(start + 6) / 2
                    val ends = start + 14
                    val starts = ends + count * 2 + 2
                    val deltas = starts + count * 2
                    val offsets = deltas + count * 2
                    for (index in 0 until count) {
                        if (cp > u16(ends + index * 2)) continue
                        if (cp < u16(starts + index * 2)) break
                        val range = u16(offsets + index * 2)
                        val glyph = if (range == 0) cp else u16(offsets + index * 2 + range + (cp - u16(starts + index * 2)) * 2)
                        if ((range == 0 || glyph != 0) && (glyph + u16(deltas + index * 2)) and 65535 != 0) return@runCatching true
                        break
                    }
                }
            }
        }
        false
    }.getOrDefault(false)

    private fun isSymbol(cp: Int): Boolean = cp in 0x2000..0x2BFF || cp in 0x1D000..0x1FAFF
    private const val MAX_FONT_BYTES = 4L * 1024 * 1024
    private val candidates = listOf("NotoSansSymbols-Regular-Subsetted.ttf", "NotoSansSymbols-Regular-Subsetted2.ttf",
        "NotoSansSymbols-Regular.ttf", "NotoSansSymbols2-Regular.ttf")
}
