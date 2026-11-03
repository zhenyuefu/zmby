package app.zmby.aggregate

import app.zmby.data.BitrateOrder
import app.zmby.data.CodecAvoid
import app.zmby.data.HdrFormat
import app.zmby.data.ImaxMode
import app.zmby.data.QualityPreference
import app.zmby.data.RankOrder
import app.zmby.emby.MediaSourceInfo
import app.zmby.media.QualityTier
import app.zmby.media.audioFormat
import app.zmby.media.defaultAudio
import app.zmby.media.hdrBadge
import app.zmby.media.isDiscImage
import app.zmby.media.isProbed
import app.zmby.media.isImaxName
import app.zmby.media.qualityTier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** What decides the default among a work's versions. */
data class VersionFacts(
    val server: String,
    /** Server priority, 0 = first. */
    val order: Int,
    val healthy: Boolean,
    val latencyMs: Long?,
    val source: MediaSourceInfo,
)

data class VersionPrefs(
    /** The version picked by hand for this work last time (server, media source id). */
    val manual: Pair<String, String>? = null,
    /** 版本偏好正则 (settings): marks the preferred version by name or path; never filters. */
    val pattern: Regex? = null,
    val quality: QualityPreference = QualityPreference.Auto,
    val preferNonDisc: Boolean = true,
    /** 画质偏好 (ui-spec §2.22). */
    val acceptLower: Boolean = true,
    val hdrOrder: List<HdrFormat> = HdrFormat.entries,
    val imax: ImaxMode = ImaxMode.Neutral,
    /** 码率上限 in Mbps, 0 = none. */
    val bitrateCapMbps: Int = 0,
    val bitrateOrder: BitrateOrder = BitrateOrder.High,
    val codecAvoid: CodecAvoid = CodecAvoid.None,
    val preferLossless: Boolean = true,
    val order: RankOrder = RankOrder.Route,
) {
    companion object {
        /** A user-typed pattern, case-insensitive; an invalid one is ignored rather than failing the page. */
        fun pattern(raw: String): Regex? = raw.trim().takeIf { it.isNotEmpty() }?.let { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() }
    }
}

/** What a version card says about a version under the current preferences (ui-spec §2.22). */
data class VersionMarks(val imax: Boolean = false, val overCap: Boolean = false, val belowResolution: Boolean = false)

/**
 * 默认版本 (ui-spec §2.22): picked by hand > matches the preference pattern > within the bitrate cap >
 * (with 「没有时接受更低分辨率」 off) at least the wanted resolution > then either picture before server
 * (画质优先: resolution, HDR order, IMAX, codec, lossless audio, not a disc image, bitrate, then server
 * priority, health and latency) or server before picture (线路优先). Home and the detail page share it.
 */
object VersionRanker {
    private val LOSSLESS = listOf("TrueHD", "DTS-HD MA", "DTS:X", "FLAC", "LPCM")

    /** [imaxOf] says whether a version is IMAX; by default only by name (see [rank] for the aspect rule). */
    fun comparator(prefs: VersionPrefs, imaxOf: (VersionFacts) -> Boolean = { imaxByName(it.source) }): Comparator<VersionFacts> {
        val wanted = wantedTier(prefs.quality)
        val head = compareBy<VersionFacts> { if (prefs.manual != null && prefs.manual == (it.server to it.source.id)) 0 else 1 }
            .thenBy { if (prefs.pattern != null && matches(prefs.pattern, it.source)) 0 else 1 }
            .thenBy { if (overCap(it.source, prefs.bitrateCapMbps)) 1 else 0 }
            .thenBy { if (!prefs.acceptLower && below(it.source, wanted)) 1 else 0 }
        val route = compareBy<VersionFacts> { it.order }
            .thenBy { if (it.healthy) 0 else 1 }
            .thenBy { it.latencyMs ?: Long.MAX_VALUE }
        val picture = compareBy<VersionFacts> { resolutionKey(it.source, wanted) }
            .thenBy { hdrKey(it.source, prefs.hdrOrder) }
            .thenBy { imaxKey(imaxOf(it), prefs.imax) }
            .thenBy { if (avoided(it.source, prefs.codecAvoid)) 1 else 0 }
            .thenBy { if (prefs.preferLossless && lossless(it.source)) 0 else 1 }
            .thenBy { if (prefs.preferNonDisc && isDiscImage(it.source)) 1 else 0 }
            .then(bitrateComparator(prefs.bitrateOrder))
        return when (prefs.order) {
            RankOrder.Quality -> head.then(picture).then(route)
            RankOrder.Route -> head.then(route).then(picture)
        }
    }

    /** [items] in default-version order; IMAX also counts the aspect rule across all of them. */
    fun <T> rank(items: List<T>, prefs: VersionPrefs, facts: (T) -> VersionFacts): List<T> {
        val all = items.map { it to facts(it) }
        val imax = imaxRule(all.map { it.second.source })
        val comparator = comparator(prefs) { imax(it.source) }
        return all.sortedWith { a, b -> comparator.compare(a.second, b.second) }.map { it.first }
    }

    fun best(versions: List<VersionFacts>, prefs: VersionPrefs): VersionFacts? = rank(versions, prefs) { it }.firstOrNull()

    /** Rank the known sources of one server, using the detail page's preferences (e.g. home badges). */
    fun bestSource(server: String, sources: List<MediaSourceInfo>, prefs: VersionPrefs): MediaSourceInfo? =
        best(sources.map { VersionFacts(server, 0, true, null, it) }, prefs)?.source

    /** The card notes for every version in [facts], keyed (server, source id). */
    fun marks(facts: List<VersionFacts>, prefs: VersionPrefs): Map<Pair<String, String>, VersionMarks> {
        val imax = imaxRule(facts.map { it.source })
        val wanted = wantedTier(prefs.quality)
        return facts.associate { f ->
            (f.server to f.source.id) to VersionMarks(
                imax = imax(f.source),
                overCap = overCap(f.source, prefs.bitrateCapMbps),
                belowResolution = !prefs.acceptLower && below(f.source, wanted),
            )
        }
    }

    fun matches(pattern: Regex, source: MediaSourceInfo): Boolean =
        listOfNotNull(source.name, source.fileName, source.path).any { pattern.containsMatchIn(it) }

    /** `IMAX` (and `IMAX Enhanced`) as a word in the version name, file name or path. */
    fun imaxByName(source: MediaSourceInfo): Boolean = isImaxName(source)

    /**
     * IMAX by name, or by picture shape: at most 1.95 : 1 while the same work also has a version at
     * 2.2 : 1 or wider (the expanded IMAX frame next to the scope release).
     */
    fun imaxRule(sources: List<MediaSourceInfo>): (MediaSourceInfo) -> Boolean {
        val wide = sources.any { s -> aspect(s)?.let { it >= 2.2 } == true }
        return { s -> imaxByName(s) || wide && aspect(s)?.let { it <= 1.95 } == true }
    }

    private fun aspect(s: MediaSourceInfo): Double? {
        val v = s.videoStream ?: return null
        val w = v.width ?: return null
        val h = v.height?.takeIf { it > 0 } ?: return null
        return w.toDouble() / h
    }

    /** The bitrate used for the cap and for 同档码率: the source's, else the video stream's. */
    fun bitrateOf(s: MediaSourceInfo): Long? = s.bitrate?.takeIf { it > 0 } ?: s.videoStream?.bitRate?.takeIf { it > 0 }

    fun overCap(s: MediaSourceInfo, capMbps: Int): Boolean {
        if (capMbps <= 0) return false
        val bps = bitrateOf(s) ?: return false
        return bps > capMbps * 1_000_000L
    }

    /** The HDR format of a probed version; null when the file has not been probed yet. */
    fun hdrFormat(s: MediaSourceInfo): HdrFormat? {
        if (!isProbed(s)) return null
        val badge = hdrBadge(s.videoStream) ?: return HdrFormat.SDR
        return when {
            badge.startsWith("DV") -> HdrFormat.DolbyVision
            badge == "HDR10+" -> HdrFormat.HDR10Plus
            badge == "HLG" -> HdrFormat.HLG
            else -> HdrFormat.HDR10
        }
    }

    /** The video codec as the codec filter sees it. */
    fun codecOf(s: MediaSourceInfo): CodecAvoid? = when (s.videoStream?.codec?.lowercase()) {
        "av1" -> CodecAvoid.AV1
        "h264", "avc", "avc1" -> CodecAvoid.H264
        else -> null
    }

    fun lossless(s: MediaSourceInfo): Boolean {
        val a = defaultAudio(s)?.let(::audioFormat) ?: return false
        return LOSSLESS.any { a.startsWith(it) }
    }

    fun wantedTier(q: QualityPreference): QualityTier? = when (q) {
        QualityPreference.UHD -> QualityTier.UHD
        QualityPreference.FHD -> QualityTier.FHD
        QualityPreference.Auto -> null
    }

    private fun below(s: MediaSourceInfo, wanted: QualityTier?): Boolean = wanted != null && qualityTier(s).ordinal > wanted.ordinal

    /** The wanted tier first, then higher before lower; 自动 is simply higher first. */
    private fun resolutionKey(s: MediaSourceInfo, wanted: QualityTier?): Int {
        val tier = qualityTier(s)
        return if (wanted == null) tier.ordinal else if (tier == wanted) 0 else 1 + tier.ordinal
    }

    /** Position in the user's HDR order; files not probed yet come after SDR. */
    private fun hdrKey(s: MediaSourceInfo, order: List<HdrFormat>): Int =
        hdrFormat(s)?.let { f -> order.indexOf(f).takeIf { it >= 0 } } ?: order.size

    private fun imaxKey(imax: Boolean, mode: ImaxMode): Int = when (mode) {
        ImaxMode.Prefer -> if (imax) 0 else 1
        ImaxMode.Avoid -> if (imax) 1 else 0
        ImaxMode.Neutral -> 0
    }

    private fun avoided(s: MediaSourceInfo, avoid: CodecAvoid): Boolean = avoid != CodecAvoid.None && codecOf(s) == avoid

    /** Known bitrates first (high or low first as set), unknown last. */
    private fun bitrateComparator(order: BitrateOrder): Comparator<VersionFacts> = Comparator { a, b ->
        val x = bitrateOf(a.source)
        val y = bitrateOf(b.source)
        when {
            x == null && y == null -> 0
            x == null -> 1
            y == null -> -1
            order == BitrateOrder.High -> y.compareTo(x)
            else -> x.compareTo(y)
        }
    }
}

/** The ranking preferences as currently set (no hand pick); home and the detail page use the same. */
fun app.zmby.data.Settings.versionPrefs(manual: Pair<String, String>? = null): VersionPrefs = VersionPrefs(
    manual = manual,
    pattern = VersionPrefs.pattern(versionPattern.value),
    quality = quality.value,
    preferNonDisc = preferNonDisc.value,
    acceptLower = acceptLowerResolution.value,
    hdrOrder = HdrFormat.parse(hdrOrder.value),
    imax = imaxMode.value,
    bitrateCapMbps = bitrateCapMbps.value,
    bitrateOrder = bitrateOrder.value,
    codecAvoid = codecAvoid.value,
    preferLossless = preferLossless.value,
    order = rankOrder.value,
)

/** Changes whenever any setting that [versionPrefs] reads changes. */
fun app.zmby.data.Settings.versionPrefsFlow(): Flow<VersionPrefs> = combine(
    listOf<Flow<Any>>(
        versionPattern.flow, quality.flow, preferNonDisc.flow, acceptLowerResolution.flow, hdrOrder.flow, imaxMode.flow,
        bitrateCapMbps.flow, bitrateOrder.flow, codecAvoid.flow, preferLossless.flow, rankOrder.flow,
    ),
) { versionPrefs() }
