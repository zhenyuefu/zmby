package app.zmby.media

import app.zmby.emby.BaseItemDto

/*
 * Titles that always show (ui-spec §1.3): wherever an item appears there is at least a text title,
 * even when a server sends an empty name, one made of spaces, or one of only invisible characters
 * (zero-width spaces, a byte order mark, Hangul fillers).
 */

/** Characters that take no visible ink: whitespace, format and control characters, blank fillers. */
private fun invisible(cp: Int): Boolean =
    Character.isWhitespace(cp) || Character.isSpaceChar(cp) || Character.isISOControl(cp) ||
        Character.getType(cp) == Character.FORMAT.toInt() || cp in BLANK_FILLERS

/** Hangul fillers, the Mongolian vowel separator, the blank Braille pattern: drawn as nothing. */
private val BLANK_FILLERS = setOf(0x115F, 0x1160, 0x180E, 0x2800, 0x3164, 0xFFA0)

/** [text] without invisible characters at its ends; null when nothing visible is left. */
fun visibleText(text: String?): String? {
    if (text == null) return null
    var start = 0
    var end = text.length
    while (start < end) {
        val cp = text.codePointAt(start)
        if (!invisible(cp)) break
        start += Character.charCount(cp)
    }
    while (end > start) {
        val cp = text.codePointBefore(end)
        if (!invisible(cp)) break
        end -= Character.charCount(cp)
    }
    return if (start < end) text.substring(start, end) else null
}

/** The file (or folder) name of an item's path without its extension: `/media/血谜拼图/S01E01.mkv` → `S01E01`. */
fun fileTitle(path: String?): String? {
    val name = path?.trimEnd('/', '\\')?.substringAfterLast('/')?.substringAfterLast('\\') ?: return null
    val dot = name.lastIndexOf('.')
    // A dot in the middle is an extension (`.mkv`, `.strm`); a leading one or a long tail is part of the name.
    val stem = if (dot > 0 && name.length - dot - 1 in 1..5) name.substring(0, dot) else name
    return visibleText(stem)
}

/** Shown when an item has no name, original title, series name or path at all. */
const val UNTITLED = "未命名"

/**
 * An item's text title: its name, else the original title, the series name, the file name
 * (ui-spec §1.3). Never empty.
 */
fun titleOf(item: BaseItemDto): String =
    visibleText(item.name) ?: visibleText(item.originalTitle) ?: visibleText(item.seriesName) ?: fileTitle(item.path) ?: UNTITLED

/** The work an item belongs to: the series for an episode or a season, else the item itself. */
fun workTitleOf(item: BaseItemDto): String =
    if (item.type == "Episode" || item.type == "Season") visibleText(item.seriesName) ?: titleOf(item) else titleOf(item)

/** The first of [candidates] with something visible, else the item's own title. */
fun titleOr(item: BaseItemDto, vararg candidates: String?): String =
    candidates.firstNotNullOfOrNull { visibleText(it) } ?: titleOf(item)
