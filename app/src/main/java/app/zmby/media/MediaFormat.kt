package app.zmby.media

import app.zmby.emby.BaseItemDto
import app.zmby.emby.MediaSourceInfo
import app.zmby.emby.MediaStream
import app.zmby.emby.ticksToMs
import java.util.Locale

/** Quality tabs on the detail screen (ui-spec §3). */
enum class QualityTier(val label: String) { UHD("4K"), FHD("1080p"), OTHER("其他") }

private val UHD_NAME = Regex("(?i)(2160p|\\b4k\\b|\\buhd\\b)")
private val FHD_NAME = Regex("(?i)1080[pi]")

fun qualityTier(source: MediaSourceInfo): QualityTier {
    val v = source.videoStream
    val w = v?.width ?: 0
    val h = v?.height ?: 0
    if (w >= 3200 || h >= 2000) return QualityTier.UHD
    if (w >= 1800 || h >= 1000) return QualityTier.FHD
    // ISO/BDMV/unprobed .strm carry no video stream: fall back to the file name.
    val name = listOfNotNull(source.fileName, source.name).joinToString(" ")
    return when {
        UHD_NAME.containsMatchIn(name) -> QualityTier.UHD
        FHD_NAME.containsMatchIn(name) -> QualityTier.FHD
        else -> QualityTier.OTHER
    }
}

fun isDiscImage(source: MediaSourceInfo): Boolean {
    val c = source.container?.lowercase()
    val t = source.videoType?.lowercase()
    return c == "iso" || c == "bluray" || c == "dvd" || t == "iso" || t == "bluray" || t == "dvd" ||
        source.path?.lowercase()?.endsWith(".iso") == true
}

private val RELEASE_TAGS = listOf(
    "REMUX" to Regex("(?i)remux"),
    "WEB-DL" to Regex("(?i)web-?dl"),
    "WEBRip" to Regex("(?i)webrip"),
    "BluRay" to Regex("(?i)blu-?ray|bdrip"),
    "HDTV" to Regex("(?i)hdtv"),
)

fun resolutionLabel(source: MediaSourceInfo): String? {
    val v = source.videoStream
    val w = v?.width
    val h = v?.height
    if (w != null && h != null) {
        val p = if (v.title?.contains("interlaced", true) == true) "i" else "p"
        return when {
            w >= 7600 || h >= 4300 -> "4320$p"
            w >= 3200 || h >= 2000 -> "2160$p"
            w >= 2500 || h >= 1400 -> "1440$p"
            w >= 1800 || h >= 1000 -> "1080$p"
            w >= 1200 || h >= 700 -> "720$p"
            else -> "${h}$p"
        }
    }
    return when (qualityTier(source)) {
        QualityTier.UHD -> "2160p"
        QualityTier.FHD -> "1080p"
        QualityTier.OTHER -> null
    }
}

/**
 * Whether the server has probed this file yet. Items whose scraping has not finished come with no
 * media streams; their version rows show only the file name and container.
 */
fun isProbed(source: MediaSourceInfo): Boolean = source.mediaStreams.isNotEmpty()

/** "2160p REMUX", "1080p WEB-DL", falling back to the file or source name. */
fun versionTitle(source: MediaSourceInfo): String {
    if (!isProbed(source) && !isDiscImage(source)) {
        source.fileName?.let { return it.substringBeforeLast('.') }
    }
    val res = resolutionLabel(source)
    val name = listOfNotNull(source.fileName, source.name).joinToString(" ")
    val tag = RELEASE_TAGS.firstOrNull { it.second.containsMatchIn(name) }?.first
    return listOfNotNull(res, tag).joinToString(" ").ifBlank { source.name ?: source.container?.uppercase() ?: "版本" }
}

fun hdrBadge(video: MediaStream?): String? {
    if (video == null) return null
    val ext = video.extendedVideoType.orEmpty()
    val sub = video.extendedVideoSubType.orEmpty()
    val range = (video.videoRangeType ?: video.videoRange).orEmpty()
    if (ext.contains("DolbyVision", true) || range.startsWith("DOVI", true)) {
        // Emby writes e.g. DoviProfile76 (7.6), DoviProfile81, DoviProfile5, DoviProfile10.
        val digits = Regex("Profile(\\d+)", RegexOption.IGNORE_CASE).find(sub)?.groupValues?.get(1)
        val major = digits?.let { if (it.startsWith("10")) "10" else it.take(1) }
        return if (major != null) "DV P$major" else "DV"
    }
    return when {
        ext.contains("Hdr10Plus", true) || range.contains("HDR10Plus", true) || range.contains("HDR10+", true) -> "HDR10+"
        ext.contains("Hdr10", true) || range.equals("HDR10", true) -> "HDR10"
        ext.contains("Hlg", true) || range.equals("HLG", true) -> "HLG"
        range.equals("HDR", true) -> "HDR"
        else -> null
    }
}

/** "TrueHD Atmos", "DTS-HD MA", "DDP Atmos", "AAC". */
fun audioFormat(stream: MediaStream): String {
    val codec = stream.codec.orEmpty().lowercase()
    val profile = stream.profile.orEmpty()
    val title = (stream.displayTitle.orEmpty() + " " + stream.title.orEmpty())
    val atmos = profile.contains("Atmos", true) || title.contains("Atmos", true)
    val base = when (codec) {
        "truehd" -> "TrueHD"
        "dts" -> when {
            profile.contains("MA", false) || profile.contains("HD MA", true) -> "DTS-HD MA"
            profile.contains("X", false) -> "DTS:X"
            profile.contains("HRA", true) || profile.contains("HD HRA", true) -> "DTS-HD HRA"
            else -> "DTS"
        }
        "eac3" -> "DDP"
        "ac3" -> "AC3"
        "aac" -> "AAC"
        "flac" -> "FLAC"
        "opus" -> "Opus"
        "mp3" -> "MP3"
        "pcm_s16le", "pcm_s24le", "pcm_bluray", "lpcm" -> "LPCM"
        else -> codec.uppercase(Locale.ROOT).ifBlank { "?" }
    }
    return if (atmos && base != "DTS:X") "$base Atmos" else base
}

fun channelLabel(stream: MediaStream): String? = when (val c = stream.channels) {
    null -> stream.channelLayout
    1 -> "1.0"
    2 -> "2.0"
    3 -> "2.1"
    6 -> "5.1"
    7 -> "6.1"
    8 -> "7.1"
    else -> "${c}ch"
}

fun defaultAudio(source: MediaSourceInfo): MediaStream? {
    val audio = source.audioStreams
    return audio.firstOrNull { it.index == source.defaultAudioStreamIndex } ?: audio.firstOrNull { it.isDefault } ?: audio.firstOrNull()
}


/** Badges next to the title on the hero (4K / DOLBY VISION / ATMOS). */
fun heroBadges(source: MediaSourceInfo?): List<String> {
    if (source == null) return emptyList()
    val out = mutableListOf<String>()
    if (qualityTier(source) == QualityTier.UHD) out += "4K"
    hdrBadge(source.videoStream)?.let { out += if (it.startsWith("DV")) "DOLBY VISION" else it }
    val audio = defaultAudio(source)?.let(::audioFormat).orEmpty()
    if (audio.contains("Atmos")) out += "ATMOS" else if (audio.startsWith("DTS:X")) out += "DTS:X"
    return out
}

fun formatSize(bytes: Long?): String? {
    if (bytes == null || bytes <= 0) return null
    val gb = bytes / 1_073_741_824.0
    return if (gb >= 1) "%.1f GB".format(Locale.ROOT, gb) else "%.0f MB".format(Locale.ROOT, bytes / 1_048_576.0)
}

fun formatBitrate(bps: Long?): String? {
    if (bps == null || bps <= 0) return null
    return if (bps >= 1_000_000) "%.1f Mbps".format(Locale.ROOT, bps / 1_000_000.0) else "${bps / 1000} kbps"
}

/** "1:12:08" / "48:03". */
fun formatClock(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** "2 小时 46 分" / "49 分钟". */
fun formatDuration(ms: Long?): String? {
    if (ms == null || ms <= 0) return null
    val minutes = (ms + 30_000) / 60_000
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0L -> "$m 分钟"
        m == 0L -> "$h 小时"
        else -> "$h 小时 $m 分"
    }
}

fun remainingLabel(item: BaseItemDto): String? {
    val runtime = item.runtimeMs ?: return null
    val left = runtime - item.resumeMs
    return formatDuration(left)?.let { "剩余 $it" }
}

fun episodeCode(item: BaseItemDto): String? {
    val s = item.parentIndexNumber
    val e = item.indexNumber
    return if (s != null && e != null) "S$s E$e" else null
}

fun progressFraction(item: BaseItemDto): Float {
    val runtime = item.runtimeMs
    if (runtime == null || runtime <= 0) {
        // Unscraped items have no runtime, but the server still keeps a played percentage.
        return if (item.resumeMs > 0) ((item.userData?.playedPercentage ?: 0.0) / 100).toFloat().coerceIn(0f, 1f) else 0f
    }
    return (item.resumeMs.toFloat() / runtime).coerceIn(0f, 1f)
}

/** Sort inside a server card: quality, then non-ISO, then bitrate (ui-spec §2.2). */
val versionOrder: Comparator<MediaSourceInfo> = compareBy<MediaSourceInfo> { qualityTier(it).ordinal }
    .thenBy { isDiscImage(it) }
    .thenByDescending { it.bitrate ?: 0 }

data class KeyValue(val key: String, val value: String)

/** The "文件与视频" column of the media info panel; rows without data are left out. */
fun fileRows(source: MediaSourceInfo, chapters: Int, runtimeTicks: Long?): List<KeyValue> {
    val v = source.videoStream
    val rows = mutableListOf<KeyValue>()
    val container = listOfNotNull(
        source.container?.uppercase(Locale.ROOT),
        formatSize(source.size),
        (source.runTimeTicks ?: runtimeTicks)?.let { formatClock(ticksToMs(it)) },
    ).joinToString(" · ")
    if (container.isNotEmpty()) rows += KeyValue("容器", container)
    formatBitrate(source.bitrate)?.let { rows += KeyValue("总码率", it) }
    if (v != null) {
        val fps = (v.realFrameRate ?: v.averageFrameRate)?.let { "%.3f".format(Locale.ROOT, it).trimEnd('0').trimEnd('.') + " fps" }
        val codec = listOfNotNull(v.codec?.uppercase(Locale.ROOT), v.profile).joinToString(" ")
        val dims = if (v.width != null && v.height != null) "${v.width}×${v.height}" else null
        listOfNotNull(codec.ifBlank { null }, dims, fps).joinToString(" · ").takeIf { it.isNotEmpty() }?.let { rows += KeyValue("视频", it) }
        val hdr = listOfNotNull(v.extendedVideoSubTypeDescription, hdrBadge(v)).distinct().joinToString(" · ")
        if (hdr.isNotEmpty()) rows += KeyValue("HDR", hdr)
        val color = listOfNotNull(
            v.colorPrimaries?.uppercase(Locale.ROOT)?.replace("BT2020", "BT.2020")?.replace("BT709", "BT.709"),
            v.colorTransfer?.let { if (it.equals("smpte2084", true)) "PQ" else if (it.equals("arib-std-b67", true)) "HLG" else it.uppercase(Locale.ROOT) },
            v.bitDepth?.let { "$it bit" },
        ).joinToString(" · ")
        if (color.isNotEmpty()) rows += KeyValue("色彩", color)
        formatBitrate(v.bitRate)?.let { rows += KeyValue("视频码率", it) }
    }
    if (chapters > 0) rows += KeyValue("章节", chapters.toString())
    folderLabel(source.path)?.let { rows += KeyValue("路径", it) }
    return rows
}

/**
 * The folder of a file for display: "movies › 沙丘2 (2024)". The UI shows no slashes, so path
 * separators (either kind) become chevrons; the drive or share prefix is kept as the first part.
 */
fun folderLabel(path: String?): String? {
    val parts = path?.replace('\\', '/')?.split('/')?.filter { it.isNotBlank() } ?: return null
    return parts.dropLast(1).joinToString(" › ").ifEmpty { null }
}

/** One row of the audio or subtitle list; [embyIndex] is the stream's index in the file. */
data class TrackRow(val number: Int, val language: String, val format: String, val tag: String?, val embyIndex: Int = -1, val isExternal: Boolean = false)

fun audioRows(source: MediaSourceInfo): List<TrackRow> {
    val default = defaultAudio(source)
    return source.audioStreams.mapIndexed { i, a ->
        val fmt = listOfNotNull(
            listOfNotNull(audioFormat(a), channelLabel(a)).joinToString(" "),
            a.sampleRate?.let { "%s kHz".format(Locale.ROOT, (it / 1000.0).toString().removeSuffix(".0")) },
            a.bitDepth?.let { "$it bit" } ?: formatBitrate(a.bitRate),
            a.title?.takeIf { it.isNotBlank() && !it.equals(a.displayTitle, true) },
        ).joinToString(" · ")
        TrackRow(i + 1, languageName(a), fmt, if (a === default) "默认" else null, a.index)
    }
}

fun subtitleRows(source: MediaSourceInfo): List<TrackRow> = source.subtitleStreams.mapIndexed { i, s ->
    val codec = when (s.codec?.lowercase()) {
        "pgssub", "pgs", "hdmv_pgs_subtitle" -> "PGS"
        "ass", "ssa" -> "ASS"
        "subrip", "srt" -> "SRT"
        "dvdsub", "dvd_subtitle", "vobsub" -> "VobSub"
        "mov_text" -> "TX3G"
        "webvtt", "vtt" -> "VTT"
        else -> s.codec?.uppercase(Locale.ROOT) ?: "?"
    }
    val fmt = if (s.isForced) "$codec · 强制" else codec
    val tag = when {
        s.index == source.defaultSubtitleStreamIndex || s.isDefault -> "默认"
        s.isExternal -> "外挂"
        else -> null
    }
    TrackRow(i + 1, s.title?.takeIf { it.isNotBlank() } ?: languageName(s), fmt, tag, s.index, s.isExternal)
}

private fun languageName(s: MediaStream): String {
    s.displayLanguage?.takeIf { it.isNotBlank() }?.let { return it }
    val code = s.language?.lowercase() ?: return "未知"
    return LANGUAGES[code] ?: runCatching { Locale.forLanguageTag(code).getDisplayLanguage(Locale.SIMPLIFIED_CHINESE) }
        .getOrNull()?.takeIf { it.isNotBlank() && it != code } ?: code
}

private val LANGUAGES = mapOf(
    "chi" to "中文", "zho" to "中文", "chs" to "简体中文", "cht" to "繁體中文",
    "eng" to "English", "jpn" to "日本語", "kor" to "한국어", "fre" to "Français", "fra" to "Français",
    "ger" to "Deutsch", "deu" to "Deutsch", "spa" to "Español", "ita" to "Italiano", "rus" to "Русский",
)
