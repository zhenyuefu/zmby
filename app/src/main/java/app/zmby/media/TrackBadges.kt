package app.zmby.media

import app.zmby.emby.MediaSourceInfo
import app.zmby.emby.MediaStream
import java.util.Locale

/**
 * What a version's tracks offer, read from every audio and subtitle stream (2026-10-26 user request:
 * the badges under a version follow all of its tracks, and an effects subtitle gets 「特效」).
 *
 * Subtitle facts come from the language code and from the words servers and release groups put in
 * track titles and external file names (「简体」「繁體」「chs&eng」「特效」); nothing is guessed from the
 * codec alone: an ASS track is not an effects subtitle unless it says so.
 */
object TrackFacts {
    enum class Script { Hans, Hant, Unknown }

    private val chineseCodes = setOf("chi", "zho", "zh", "cmn", "chs", "cht", "zh-cn", "zh-tw", "zh-hk", "zh-hans", "zh-hant", "yue")
    private val hansWords = listOf("简", "簡", "chs", "sc", "gb", "hans", "zh-cn", "simplified")
    private val hantWords = listOf("繁", "cht", "tc", "big5", "hant", "zh-tw", "zh-hk", "traditional")
    private val bilingualWords = listOf("双语", "雙語", "中英", "简英", "繁英", "簡英", "中日", "简日", "繁日", "chs&eng", "cht&eng", "chs_eng", "cht_eng", "chs.eng", "cht.eng", "chseng", "chteng", "bilingual")
    private val effectsWords = listOf("特效", "effects", "effect")

    /** Title, display title and the external file's name, lower-cased: where the words are. */
    private fun text(s: MediaStream): String =
        listOfNotNull(s.title, s.displayTitle, s.path?.replace('\\', '/')?.substringAfterLast('/')).joinToString(" ").lowercase(Locale.ROOT)

    /** Whole-word match for short Latin tags (「sc」 must not match 「score」). */
    private fun hasWord(text: String, word: String): Boolean =
        if (word.all { it in 'a'..'z' } && word.length <= 4) Regex("(^|[^a-z])${Regex.escape(word)}([^a-z]|$)").containsMatchIn(text) else text.contains(word)

    fun isChinese(s: MediaStream): Boolean {
        val code = s.language?.lowercase(Locale.ROOT)
        if (code != null && code in chineseCodes) return true
        val t = text(s)
        return t.contains("中") || t.contains("chinese") || listOf("chs", "cht").any { hasWord(t, it) } || bilingual(s)
    }

    fun script(s: MediaStream): Script {
        val t = text(s)
        val code = s.language?.lowercase(Locale.ROOT).orEmpty()
        val hans = hansWords.any { hasWord(t, it) } || code in setOf("chs", "zh-cn", "zh-hans")
        val hant = hantWords.any { hasWord(t, it) } || code in setOf("cht", "zh-tw", "zh-hk", "zh-hant")
        return when {
            hans && !hant -> Script.Hans
            hant && !hans -> Script.Hant
            else -> Script.Unknown
        }
    }

    fun bilingual(s: MediaStream): Boolean = text(s).let { t -> bilingualWords.any { t.contains(it) } }

    fun effects(s: MediaStream): Boolean = text(s).let { t -> effectsWords.any { hasWord(t, it) } }

    /** Mandarin and Cantonese audio as Chinese viewers name them. */
    fun audioLanguage(s: MediaStream): String? {
        val code = s.language?.lowercase(Locale.ROOT)
        val t = text(s)
        return when {
            code == "yue" || t.contains("粤") || t.contains("粵") || t.contains("cantonese") -> "粤语"
            code in setOf("chi", "zho", "zh", "cmn") || t.contains("国语") || t.contains("國語") || t.contains("普通话") || t.contains("mandarin") -> "国语"
            else -> null
        }
    }
}

/**
 * Badges under a version row: HDR format, every distinct audio format (default track first, at most
 * three), 国语 · 粤语 audio, then the Chinese subtitles (简中, 繁中, 简繁 or 中字), 双语 and 特效.
 * An unprobed file shows its container only.
 */
fun versionBadges(source: MediaSourceInfo): List<String> {
    if (!isProbed(source)) return listOfNotNull(source.container?.substringBefore(',')?.uppercase(Locale.ROOT))
    val out = mutableListOf<String>()
    hdrBadge(source.videoStream)?.let { out += it }
    val default = defaultAudio(source)
    val audio = (listOfNotNull(default) + source.audioStreams.filter { it !== default })
    out += audio.map(::audioFormat).distinct().take(MAX_AUDIO_FORMATS)
    out += audio.mapNotNull(TrackFacts::audioLanguage).distinct().sortedBy { if (it == "国语") 0 else 1 }
    out += subtitleBadges(source.subtitleStreams)
    return out
}

private const val MAX_AUDIO_FORMATS = 3

internal fun subtitleBadges(subs: List<MediaStream>): List<String> {
    val chinese = subs.filter(TrackFacts::isChinese)
    val out = mutableListOf<String>()
    if (chinese.isNotEmpty()) {
        val scripts = chinese.map(TrackFacts::script).toSet()
        out += when {
            TrackFacts.Script.Hans in scripts && TrackFacts.Script.Hant in scripts -> "简繁"
            TrackFacts.Script.Hans in scripts -> "简中"
            TrackFacts.Script.Hant in scripts -> "繁中"
            else -> "中字"
        }
        if (chinese.any(TrackFacts::bilingual)) out += "双语"
    }
    if (subs.any(TrackFacts::effects)) out += "特效"
    return out
}

/** One subtitle chip in the 音轨与字幕 summary (ui-spec §2.14): 「简体中文」 with 「PGS · 外挂」 after it. */
data class SubtitleChip(val label: String, val detail: String, val current: Boolean, val embyIndex: Int)

/**
 * The subtitle chips of the collapsed 音轨与字幕 bar: the current subtitle first (「字幕关闭」 when off),
 * then the others: the preferred language from the settings, other Chinese (简, 繁, 双语), English,
 * then the rest; several tracks of one language all show.
 */
fun subtitleChips(source: MediaSourceInfo, current: Int?, preferredLanguage: String?): List<SubtitleChip> {
    val rows = subtitleRows(source)
    val streams = source.subtitleStreams.associateBy { it.index }
    fun chip(r: TrackRow) = SubtitleChip(
        r.language,
        listOfNotNull(r.format.ifEmpty { null }, r.tag?.takeIf { it == "外挂" }, "特效".takeIf { streams[r.embyIndex]?.let(TrackFacts::effects) == true }).joinToString(" · "),
        r.embyIndex == current,
        r.embyIndex,
    )
    val preferred = preferredLanguage?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }
    fun rank(r: TrackRow): Int {
        val s = streams[r.embyIndex] ?: return 4
        val code = s.language?.lowercase(Locale.ROOT)
        return when {
            preferred != null && (code == preferred || (preferred.startsWith("zh") && TrackFacts.isChinese(s) && TrackFacts.script(s) == if (preferred.contains("tw") || preferred.contains("hant")) TrackFacts.Script.Hant else TrackFacts.Script.Hans)) -> 0
            TrackFacts.isChinese(s) -> 1
            code in setOf("eng", "en") -> 2
            else -> 3
        }
    }
    val currentRow = rows.firstOrNull { it.embyIndex == current }
    val head = currentRow?.let(::chip) ?: SubtitleChip("字幕关闭", "", current = true, embyIndex = -1)
    val rest = rows.filter { it !== currentRow }.withIndex().sortedWith(compareBy({ rank(it.value) }, { it.index })).map { chip(it.value) }
    return listOf(head) + rest
}

/** 「English · TrueHD Atmos 7.1」 and the other audio languages, deduplicated, at most three. */
fun audioSummary(source: MediaSourceInfo, current: Int?): Pair<String?, String?> {
    val rows = audioRows(source)
    val on = rows.firstOrNull { it.embyIndex == current } ?: rows.firstOrNull() ?: return null to null
    val stream = source.audioStreams.firstOrNull { it.index == on.embyIndex }
    val first = listOfNotNull(on.language, stream?.let { listOfNotNull(audioFormat(it), channelLabel(it)).joinToString(" ") }).joinToString(" · ")
    val others = rows.filter { it !== on }
    val more = if (others.isEmpty()) null else "另有 ${others.size} 条音轨：" + others.map { it.language }.distinct().take(3).joinToString(" · ")
    return first to more
}
