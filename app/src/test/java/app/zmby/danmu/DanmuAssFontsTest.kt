package app.zmby.danmu

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DanmuAssFontsTest {
    private fun decode(encoded: String): ByteArray {
        val values = encoded.filter { it != '\n' }.map { it.code - 33 }
        val out = ByteArrayOutputStream()
        for (chunk in values.chunked(4)) {
            require(chunk.size >= 2)
            out.write((chunk[0] shl 2) or (chunk[1] shr 4))
            if (chunk.size >= 3) out.write(((chunk[1] and 15) shl 4) or (chunk[2] shr 2))
            if (chunk.size == 4) out.write(((chunk[2] and 3) shl 6) or chunk[3])
        }
        return out.toByteArray()
    }

    @Test fun `font attachment round trips arbitrary bytes and final partial groups`() {
        for (size in listOf(1, 2, 3, 59, 60, 61, 255, 256, 257, 1024)) {
            val input = ByteArray(size) { (it % 256).toByte() }
            val encoded = DanmuAssFonts.encode(input)
            assertTrue(encoded.lines().all { it.length <= 80 && it.all { c -> c.code in 33..96 } })
            assertArrayEquals(input, decode(encoded))
        }
    }

    @Test fun `symbol font overrides restore the main font and leave ASS line breaks and emoji intact`() {
        val rendered = DanmuAssFonts.render("点赞♡8\n😀", "Noto Sans CJK SC", mapOf(0x2661 to "Noto Sans Symbols2"))
        assertEquals("点赞{\\fnNoto Sans Symbols2}♡{\\fnNoto Sans CJK SC}8\\N😀", rendered)
    }

    @Test fun `fontless plain comments retain the upstream converter output`() {
        val config = DanmuAss.Config()
        val items = listOf(DanmuAss.Item(1.0, "普通中文 ASCII", 0xFFFFFF))
        assertEquals(DanmuAss.build(items, config), DanmuAssFonts.build(items, config))
        assertNull(DanmuAssFonts.family(byteArrayOf(0, 1, 2)))
    }

    @Test fun `symbol coverage comes from the embedded font cmap rather than the Android fallback chain`() {
        val bytes = ByteArray(80)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        b.putShort(4, 1) // one SFNT table
        b.putInt(12, 0x636D6170); b.putInt(20, 28) // cmap
        b.putShort(30, 1); b.putShort(32, 3); b.putShort(34, 10); b.putInt(36, 12)
        b.putShort(40, 12); b.putInt(44, 28); b.putInt(52, 1)
        b.putInt(56, 0x2661); b.putInt(60, 0x2661); b.putInt(64, 1)
        assertTrue(DanmuAssFonts.supports(bytes, 0x2661))
        assertFalse(DanmuAssFonts.supports(bytes, 0x2660))
        assertFalse(DanmuAssFonts.supports(bytes, 0x1F600))
        assertFalse(DanmuAssFonts.supports(byteArrayOf(1, 2), 0x2661))
        assertEquals("A B{\\fnSymbols}♡{\\fnBase}", DanmuAssFonts.render("A B♡", "Base", mapOf(0x2661 to "Symbols")))
    }
}
