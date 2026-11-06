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
    enum class Script { Hans, Hant, Both, Unknown }

    private val chineseCodes = setOf("chi", "zho", "zh", "cmn", "chs", "cht", "yue")
    private val hansWords = listOf("简", "簡", "chs", "chseng", "sc", "gb", "gb2312", "gb18030", "hans", "zh-cn", "zh-sg", "simplified")
    private val hantWords = listOf("繁", "cht", "chteng", "tc", "big5", "hant", "zh-tw", "zh-hk", "zh-mo", "traditional")
    private val bilingualWords = listOf("双语", "雙語", "中英", "简英", "繁英", "簡英", "中日", "简日", "繁日", "chs&eng", "cht&eng", "chs_eng", "cht_eng", "chs.eng", "cht.eng", "chseng", "chteng", "bilingual")
    private val effectsWords = listOf("特效", "effects", "effect")

    private val chineseWords = listOf("中文", "中字", "简体", "簡體", "繁体", "繁體", "简中", "繁中", "简繁", "簡繁", "chinese")
    private val chineseBilingualWords = bilingualWords - setOf("双语", "雙語", "bilingual")
    private val wordPatterns = (hansWords + hantWords + bilingualWords + effectsWords + chineseWords + chineseCodes + listOf("cantonese", "mandarin"))
        .filter { word -> word.any { it in 'a'..'z' } }
        .associateWith { Regex("(?<![a-z0-9])${Regex.escape(it)}(?![a-z0-9])") }
    private val filenameSeparators = Regex("[. _\\[\\](){}]+")
    private val chineseLanguageTag = Regex("(?:zh|cmn|yue)(?:-(?:hans|hant))?(?:-(?:cn|tw|hk|sg|mo))?")
    private val chineseFilenameTags = Regex("(?:中文|中字|简体中文|簡體中文|繁体中文|繁體中文|简体|簡體|繁体|繁體|简中|繁中|简繁|簡繁|简英|簡英|繁英|中英|简日|繁日|中日|双语|雙語|特效)+")
    private val latinFilenameTags = (hansWords + hantWords + bilingualWords + effectsWords + listOf("chinese", "zh", "chi", "zho", "cmn", "yue")).filter { it.first() in 'a'..'z' }.toSet()

    private fun code(s: MediaStream): String = s.language.orEmpty().trim().lowercase(Locale.ROOT).replace('_', '-')
    private fun chineseCode(code: String): Boolean = code.substringBefore('-') in chineseCodes
    private fun title(s: MediaStream): String = listOfNotNull(s.title, s.displayTitle).joinToString(" ").lowercase(Locale.ROOT)

    /** Only explicit metadata tokens from the file name; a film title is not a language tag. */
    private fun filenameTags(s: MediaStream): String {
        val file = s.path.orEmpty().replace('\\', '/').substringAfterLast('/').lowercase(Locale.ROOT)
        val tags = file.split(filenameSeparators).filter { it in latinFilenameTags || chineseLanguageTag.matches(it) || chineseFilenameTags.matches(it) }
        // Preserve combined tags that themselves contain separators, such as chs.eng and chs_eng.
        return (tags + chineseBilingualWords.filter { it.any { c -> c in 'a'..'z' } && hasWord(file, it) }).joinToString(" ")
    }

    private fun subtitleText(s: MediaStream): String {
        val language = code(s)
        // A known foreign language beats weak file-name guesses. Track titles may explicitly say bilingual.
        val file = if (language in setOf("", "und", "unknown") || chineseCode(language)) filenameTags(s) else ""
        return title(s) + " " + file
    }

    /** Whole-word match for short Latin tags (「sc」 must not match 「score」). */
    private fun hasWord(text: String, word: String): Boolean =
        wordPatterns[word]?.containsMatchIn(text) ?: text.contains(word)

    fun isChinese(s: MediaStream): Boolean {
        if (chineseCode(code(s))) return true
        val t = subtitleText(s)
        return chineseWords.any { hasWord(t, it) } || chineseCodes.any { hasWord(t, it) } || hansWords.any { hasWord(t, it) } ||
            hantWords.any { hasWord(t, it) } || chineseBilingualWords.any { hasWord(t, it) }
    }

    fun script(s: MediaStream): Script {
        val t = subtitleText(s)
        val code = code(s)
        val parts = code.split('-')
        val hans = hansWords.any { hasWord(t, it) }
        val hant = hantWords.any { hasWord(t, it) }
        return when {
            hans && hant -> Script.Both
            hans -> Script.Hans
            hant -> Script.Hant
            chineseCode(code) && (code == "chs" || "hans" in parts || "hant" !in parts && parts.any { it in setOf("cn", "sg") }) -> Script.Hans
            chineseCode(code) && (code == "cht" || "hant" in parts || parts.any { it in setOf("tw", "hk", "mo") }) -> Script.Hant
            else -> Script.Unknown
        }
    }

    fun bilingual(s: MediaStream): Boolean = subtitleText(s).let { t -> bilingualWords.any { hasWord(t, it) } }

    fun effects(s: MediaStream): Boolean = (title(s) + " " + filenameTags(s)).let { t -> effectsWords.any { hasWord(t, it) } }

    /** Mandarin and Cantonese audio as Chinese viewers name them. */
    fun audioLanguage(s: MediaStream): String? {
        val code = code(s)
        val t = title(s)
        return when {
            code.substringBefore('-') == "yue" || t.contains("粤语") || t.contains("粵語") || hasWord(t, "cantonese") -> "粤语"
            code.substringBefore('-') == "cmn" || t.contains("国语") || t.contains("國語") || t.contains("普通话") || t.contains("普通話") || hasWord(t, "mandarin") -> "国语"
            chineseCode(code) || hasWord(t, "chinese") || t.contains("中文") -> "中文"
            else -> null
        }
    }
}

/**
 * Badges under a version row (2026-11-05 user request: fewer, merged badges): HDR format; the audio
 * formats of the best tier only, each with its channel layout (「DDP 5.1」 hides 「AAC 2.0」; formats of
 * one tier all show, default track first, at most three plus an overflow count); one Chinese audio
 * badge (国语, or 中文 when no track says Mandarin) and 粤语; then the Chinese subtitle script (简中,
 * 繁中, 简繁 or 中字) and the better one of 特效 and 双语.
 * An unprobed file shows its container only.
 */
fun versionBadges(source: MediaSourceInfo): List<String> {
    if (!isProbed(source)) return listOfNotNull(source.container?.substringBefore(',')?.uppercase(Locale.ROOT))
    val out = mutableListOf<String>()
    hdrBadge(source.videoStream)?.let { out += it }
    val default = defaultAudio(source)
    val audio = (listOfNotNull(default) + source.audioStreams.filter { it !== default })
    val formats = audioBadges(audio)
    out += formats.take(MAX_AUDIO_FORMATS)
    if (formats.size > MAX_AUDIO_FORMATS) out += "+${formats.size - MAX_AUDIO_FORMATS}"
    out += audioLanguageBadges(audio)
    out += subtitleBadges(source.subtitleStreams)
    return out
}

private const val MAX_AUDIO_FORMATS = 3

/**
 * Audio format tiers for the version badges, best first: object-based lossless, lossless, lossy
 * Atmos, DTS-HD HRA, DDP and DTS, AC3, the common stereo codecs, anything else.
 */
internal fun audioTier(format: String): Int = when (format) {
    "TrueHD Atmos", "DTS:X" -> 0
    "TrueHD", "DTS-HD MA", "LPCM", "FLAC" -> 1
    "DDP Atmos" -> 2
    "DTS-HD HRA" -> 3
    "DDP", "DTS" -> 4
    "AC3" -> 5
    "AAC", "Opus", "MP3" -> 6
    else -> 7
}

/** 「7.1」 → 8, 「5.1.2」 → 8, 「10ch」 → 10: for keeping the widest layout of one format. */
private fun channelWeight(label: String): Int =
    label.removeSuffix("ch").split('.').sumOf { it.toIntOrNull() ?: 0 }

/** The best tier's formats in track order ([audio] has the default first), each with its widest layout. */
internal fun audioBadges(audio: List<MediaStream>): List<String> {
    val tracks = audio.mapNotNull { a -> audioFormat(a)?.let { it to channelLabel(a) } }
    val best = tracks.minOfOrNull { audioTier(it.first) } ?: return emptyList()
    return tracks.filter { audioTier(it.first) == best }
        .groupBy({ it.first }, { it.second })
        .map { (format, layouts) -> listOfNotNull(format, layouts.filterNotNull().maxByOrNull(::channelWeight)).joinToString(" ") }
}

/** 国语 and generic 中文 read the same on a badge: one of them (国语 when a track says so), then 粤语. */
internal fun audioLanguageBadges(audio: List<MediaStream>): List<String> {
    val languages = audio.mapNotNull(TrackFacts::audioLanguage).toSet()
    return listOfNotNull(
        when { "国语" in languages -> "国语"; "中文" in languages -> "中文"; else -> null },
        "粤语".takeIf { it in languages },
    )
}

/** The Chinese script across all subtitles, then 特效 or else 双语 (never both). */
internal fun subtitleBadges(subs: List<MediaStream>): List<String> {
    val chinese = subs.filter(TrackFacts::isChinese)
    val out = mutableListOf<String>()
    if (chinese.isNotEmpty()) {
        val scripts = chinese.map(TrackFacts::script).toSet()
        out += when {
            TrackFacts.Script.Both in scripts || TrackFacts.Script.Hans in scripts && TrackFacts.Script.Hant in scripts -> "简繁"
            TrackFacts.Script.Hans in scripts -> "简中"
            TrackFacts.Script.Hant in scripts -> "繁中"
            else -> "中字"
        }
    }
    when {
        subs.any(TrackFacts::effects) -> out += "特效"
        subs.any(TrackFacts::bilingual) -> out += "双语"
    }
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
        listOfNotNull(r.format.ifEmpty { null }, r.tag).joinToString(" · "),
        r.embyIndex == current,
        r.embyIndex,
    )
    val preferred = preferredLanguage?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }
    fun rank(r: TrackRow): Int {
        val s = streams[r.embyIndex] ?: return 4
        val code = s.language?.lowercase(Locale.ROOT)
        return when {
            preferred != null && (code == preferred || (preferred.startsWith("zh") && TrackFacts.isChinese(s) && TrackFacts.script(s) in setOf(TrackFacts.Script.Both, if (preferred.contains("tw") || preferred.contains("hk") || preferred.contains("hant")) TrackFacts.Script.Hant else TrackFacts.Script.Hans))) -> 0
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
    val on = rows.firstOrNull { it.embyIndex == current } ?: rows.firstOrNull { it.embyIndex == defaultAudio(source)?.index } ?: return null to null
    val stream = source.audioStreams.firstOrNull { it.index == on.embyIndex }
    val first = listOfNotNull(on.language, stream?.let { listOfNotNull(audioFormat(it), channelLabel(it)).joinToString(" ").ifBlank { null } }).joinToString(" · ")
    val others = rows.filter { it !== on }
    val more = if (others.isEmpty()) null else "另有 ${others.size} 条音轨：" + others.map { it.language }.distinct().take(3).joinToString(" · ")
    return first to more
}
